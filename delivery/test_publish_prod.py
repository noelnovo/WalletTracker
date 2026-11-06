import hashlib
import json
import os
import pathlib
import tempfile
import unittest
from unittest.mock import patch
import publish_prod


class ProductionPublicationTests(unittest.TestCase):
    def test_artifact_must_match_source_identity_configuration_and_checksum(self):
        pathlib.Path('/tmp/opencode').mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as directory:
            directory = pathlib.Path(directory)
            payload = b'synthetic-signed-apk'
            (directory / 'WalletTracker-v3.0.0.apk').write_bytes(payload)
            (directory / 'WalletTracker-v3.0.0.apk.sha256').write_text(hashlib.sha256(payload).hexdigest() + '  WalletTracker-v3.0.0.apk\n')
            receipt = {'tag': 'v3.0.0', 'source_revision': 'a' * 40, 'package': 'win.downops.wallettracker',
                       'signer_matches_previous_release': True, 'api_version': 2, 'registration_enabled': False,
                       'apk_sha256': hashlib.sha256(payload).hexdigest(), 'purpose': 'release', 'publishable': True}
            self.assertTrue(publish_prod.validate_artifact(receipt, 'v3.0.0', 'a' * 40, directory).is_file())
            for change in ({'source_revision': 'b' * 40}, {'signer_matches_previous_release': False},
                           {'registration_enabled': True}, {'package': 'win.downops.wallettracker.debug'}, {'apk_sha256': '0' * 64},
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

    def test_publication_credential_is_not_the_signing_record(self):
        self.assertEqual(publish_prod.TOKEN_RECORD, 'downops/data/apps/wallettracker/delivery/github-release')
        self.assertNotIn('/prod/mobile', publish_prod.TOKEN_RECORD)

    def test_publication_is_inactive_without_verified_credential_gate(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(publish_prod, 'vault_request') as request:
            with self.assertRaises(RuntimeError):
                publish_prod.main()
            request.assert_not_called()

    def test_draft_upload_verification_precedes_publication(self):
        pathlib.Path('/tmp/opencode').mkdir(parents=True, exist_ok=True)
        for bad_digest in (False, True):
            with self.subTest(bad_digest=bad_digest), tempfile.TemporaryDirectory(dir='/tmp/opencode') as directory:
                root = pathlib.Path(directory)
                artifacts = root / 'delivery/artifacts'
                artifacts.mkdir(parents=True)
                (root / 'delivery/RELEASE-NOTES-3.0.0.md').write_text('Synthetic release notes')
                apk = artifacts / 'WalletTracker-v3.0.0.apk'
                apk.write_bytes(b'synthetic-signed-apk')
                digest = hashlib.sha256(apk.read_bytes()).hexdigest()
                (artifacts / (apk.name + '.sha256')).write_text(digest + '  ' + apk.name + '\n')
                receipt = {'tag': 'v3.0.0', 'source_revision': 'a' * 40,
                           'package': 'win.downops.wallettracker', 'signer_matches_previous_release': True,
                           'api_version': 2, 'registration_enabled': False, 'apk_sha256': digest,
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
                        return {'id': 42, 'draft': True, 'assets': [], 'upload_url': 'https://uploads.github.com/repos/noelnovo/WalletTracker/releases/42/assets{?name}'}
                    if binary is not None:
                        uploads.append(binary)
                        return {'digest': 'sha256:' + ('0' * 64 if bad_digest else hashlib.sha256(binary).hexdigest())}
                    self.assertEqual(len(uploads), 3)
                    self.assertEqual((path, method, data['draft']), ('/releases/42', 'PATCH', False))
                    publications.append(path)
                    return {'html_url': 'https://github.com/noelnovo/WalletTracker/releases/tag/v3.0.0'}
                with patch.object(publish_prod, 'ROOT', root), \
                     patch.dict(os.environ, {'GITHUB_RELEASE_READY': 'true', 'CI_COMMIT_SHA': 'a' * 40, 'VAULT_ID_TOKEN': 'synthetic-jwt'}, clear=True), \
                     patch.object(publish_prod, 'validate_identity', return_value='v3.0.0'), \
                     patch.object(publish_prod, 'verify_backend'), \
                     patch.object(publish_prod, 'vault_request', side_effect=[{'auth': {'client_token': 'synthetic-token'}}, {'data': {'data': {'GITHUB_RELEASE_TOKEN': 'synthetic-publication-token'}}}]), \
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
