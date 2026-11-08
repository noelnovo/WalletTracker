import hashlib
import io
import json
import os
import pathlib
import tempfile
import unittest
from unittest.mock import patch
from scripts.release import publish_prod
from ci_test_support import github_environment


class ProductionPublicationTests(unittest.TestCase):
    def test_successful_empty_delete_response_is_not_parsed_as_json(self):
        response = io.BytesIO(b'')
        response.status = 204
        with patch.object(publish_prod.urllib.request, 'build_opener') as opener:
            opener.return_value.open.return_value = response
            self.assertIsNone(publish_prod.github('/releases/42', 'synthetic-token', method='DELETE'))

    def test_artifact_must_match_source_identity_configuration_and_checksum(self):
        pathlib.Path('/tmp/opencode').mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as directory:
            directory = pathlib.Path(directory)
            payload = b'synthetic-signed-apk'
            (directory / 'WalletTracker-v3.0.0.apk').write_bytes(payload)
            (directory / 'WalletTracker-v3.0.0.apk.sha256').write_text(hashlib.sha256(payload).hexdigest() + '  WalletTracker-v3.0.0.apk\n')
            receipt = {'tag': 'v3.0.0', 'source_revision': 'a' * 40, 'package': 'win.downops.wallettracker',
                       'signer_matches_previous_release': True, 'api_version': 2,
                       'registration_ui_available': True, 'registration_authority': 'backend',
                       'apk_sha256': hashlib.sha256(payload).hexdigest(), 'purpose': 'release', 'publishable': True}
            self.assertTrue(publish_prod.validate_artifact(receipt, 'v3.0.0', 'a' * 40, directory).is_file())
            for change in ({'source_revision': 'b' * 40}, {'signer_matches_previous_release': False},
                           {'registration_ui_available': False}, {'registration_authority': 'apk'},
                           {'registration_enabled': False}, {'registration_enabled': True},
                           {'package': 'win.downops.wallettracker.debug'}, {'apk_sha256': '0' * 64},
                           {'purpose': 'signing-validation'}, {'publishable': False}):
                with self.subTest(change=change), self.assertRaises(RuntimeError):
                    publish_prod.validate_artifact(dict(receipt, **change), 'v3.0.0', 'a' * 40, directory)
            (directory / 'WalletTracker-v3.0.0.apk.sha256').write_text('tampered-sidecar')
            with self.assertRaises(RuntimeError):
                publish_prod.validate_artifact(receipt, 'v3.0.0', 'a' * 40, directory)

    def test_publication_cannot_create_unapproved_tags(self):
        with patch.object(publish_prod, 'github', return_value=None):
            with self.assertRaises(RuntimeError):
                publish_prod.tag_commit('v3.0.0', 'synthetic-token')

    def test_annotated_tag_is_resolved_to_the_exact_commit(self):
        with patch.object(publish_prod, 'github', side_effect=[{'object': {'type': 'tag', 'sha': 'b' * 40}}, {'object': {'type': 'commit', 'sha': 'a' * 40}}]):
            self.assertEqual(publish_prod.tag_commit('v3.0.0', 'synthetic-token'), 'a' * 40)

    def test_publication_uses_builtin_token_without_vault_dependency(self):
        token = 'synthetic-actions-token'
        self.assertEqual(publish_prod.publication_token(github_environment(GH_TOKEN=token)), token)
        self.assertFalse(hasattr(publish_prod, 'vault_request'))

    def test_publication_is_inactive_without_verified_credential_gate(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(publish_prod, 'publication_token') as request:
            with self.assertRaises(RuntimeError):
                publish_prod.main()
            request.assert_not_called()

    def test_draft_upload_verification_precedes_publication(self):
        pathlib.Path('/tmp/opencode').mkdir(parents=True, exist_ok=True)
        for bad_digest in (False, True):
            with self.subTest(bad_digest=bad_digest), tempfile.TemporaryDirectory(dir='/tmp/opencode') as directory:
                root = pathlib.Path(directory)
                artifacts = root / 'artifacts'
                artifacts.mkdir(parents=True)
                (root / 'docs/releases').mkdir(parents=True)
                (root / 'docs/releases/3.0.0.md').write_text('Synthetic release notes')
                apk = artifacts / 'WalletTracker-v3.0.0.apk'
                apk.write_bytes(b'synthetic-signed-apk')
                digest = hashlib.sha256(apk.read_bytes()).hexdigest()
                (artifacts / (apk.name + '.sha256')).write_text(digest + '  ' + apk.name + '\n')
                receipt = {'tag': 'v3.0.0', 'source_revision': 'a' * 40,
                           'package': 'win.downops.wallettracker', 'signer_matches_previous_release': True,
                           'api_version': 2, 'registration_ui_available': True,
                           'registration_authority': 'backend', 'apk_sha256': digest,
                           'purpose': 'release', 'publishable': True}
                (artifacts / 'release-receipt.json').write_text(json.dumps(receipt))
                uploads, publications = [], []
                def github(path, token, data=None, method=None, binary=None, content_type=None):
                    if path.startswith('/git/ref/'):
                        return {'object': {'type': 'commit', 'sha': 'a' * 40}}
                    if path.startswith('/releases/tags/'):
                        return None
                    if path == '/releases':
                        self.assertIs(data['draft'], True)
                        self.assertNotIn('target_commitish', data)
                        return {'id': 42, 'draft': True, 'assets': [], 'upload_url': 'https://uploads.github.com/repos/noelnovo/WalletTracker/releases/42/assets{?name}'}
                    if binary is not None:
                        uploads.append(binary)
                        return {'digest': 'sha256:' + ('0' * 64 if bad_digest else hashlib.sha256(binary).hexdigest())}
                    self.assertEqual(len(uploads), 3)
                    self.assertEqual((path, method, data['draft']), ('/releases/42', 'PATCH', False))
                    publications.append(path)
                    return {'html_url': 'https://github.com/noelnovo/WalletTracker/releases/tag/v3.0.0'}
                with patch.object(publish_prod, 'ROOT', root), \
                     patch.dict(os.environ, github_environment(GH_TOKEN='synthetic-actions-token'), clear=True), \
                     patch.object(publish_prod, 'validate_identity', return_value='v3.0.0'), \
                     patch.object(publish_prod, 'verify_backend'), \
                     patch.object(publish_prod, 'github', side_effect=github), \
                     patch('builtins.print'):
                    if bad_digest:
                        with self.assertRaises(RuntimeError):
                            publish_prod.main()
                        self.assertEqual(publications, [])
                    else:
                        publish_prod.main()
                        self.assertEqual(len(publications), 1)


if __name__ == '__main__':
    unittest.main()
