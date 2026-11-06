"""Synthetic REST tests: no network requests, real credentials or releases."""
import hashlib
import json
import os
import pathlib
import tempfile
import unittest
from unittest.mock import patch
import check_publication


class PublicationCheckTests(unittest.TestCase):
    def test_scoped_credential_gate_precedes_network_access(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(check_publication, 'publication_token') as token:
            with self.assertRaises(RuntimeError):
                check_publication.main()
            token.assert_not_called()

    def test_owned_draft_upload_verification_and_safe_cleanup(self):
        pathlib.Path('/tmp/opencode').mkdir(parents=True, exist_ok=True)
        for outcome in ('success', 'bad-digest', 'upload-failure', 'user-changed-draft', 'existing-release'):
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory(dir='/tmp/opencode') as directory:
                root = pathlib.Path(directory)
                state = {'created': False, 'uploaded': False, 'deleted': False}
                tag = 'v3.0.0-ci-check.1'
                def github(path, token, data=None, method=None, binary=None, content_type=None):
                    self.assertNotEqual(method, 'PATCH', 'Validation must never publish a release')
                    if path.startswith('/releases/tags/'):
                        return {'draft': False} if outcome == 'existing-release' else None
                    if path == '/releases':
                        self.assertIs(data['draft'], True)
                        self.assertNotIn('target_commitish', data)
                        self.assertIs(data['prerelease'], True)
                        self.assertEqual(data['make_latest'], 'false')
                        state.update(created=True, body=data['body'])
                        return {'id': 42, 'draft': True, 'tag_name': tag,
                                'upload_url': 'https://uploads.github.com/repos/noelnovo/WalletTracker/releases/42/assets{?name}'}
                    if binary is not None:
                        payload = json.loads(binary)
                        self.assertIs(payload['contains_apk'], False)
                        self.assertIs(payload['publishable'], False)
                        state['uploaded'] = True
                        if outcome == 'upload-failure':
                            raise RuntimeError('Synthetic upload rejection')
                        return {'digest': 'sha256:' + ('0' * 64 if outcome == 'bad-digest' else hashlib.sha256(binary).hexdigest())}
                    self.assertEqual(path, '/releases/42')
                    if method == 'DELETE':
                        state['deleted'] = True
                        return None
                    if state['deleted']:
                        return None
                    return {'draft': True, 'tag_name': tag,
                            'body': 'User modified' if outcome == 'user-changed-draft' else state['body']}
                with patch.dict(os.environ, {'GITHUB_RUN_ID': '7', 'GITHUB_SHA': 'a' * 40}, clear=True), \
                     patch.object(check_publication, 'ROOT', root), \
                     patch.object(check_publication, 'validate_check_identity', return_value=tag), \
                     patch.object(check_publication, 'publication_token', return_value='synthetic-token'), \
                     patch.object(check_publication, 'tag_commit', return_value='a' * 40), \
                     patch.object(check_publication, 'github', side_effect=github), \
                     patch('builtins.print'):
                    if outcome == 'success':
                        check_publication.main()
                    else:
                        with self.assertRaises(RuntimeError):
                            check_publication.main()
                receipt_path = root / 'delivery/check-artifacts/publication-check-receipt.json'
                if outcome == 'success':
                    receipt = json.loads(receipt_path.read_text())
                    self.assertIs(receipt['published'], False)
                    self.assertIs(receipt['asset_digest_verified'], True)
                    self.assertIs(receipt['owned_draft_deleted'], True)
                else:
                    self.assertFalse(receipt_path.exists())
                self.assertEqual(state['deleted'], outcome in ('success', 'bad-digest', 'upload-failure'))
                if outcome == 'existing-release':
                    self.assertFalse(state['created'])
                    self.assertFalse(state['uploaded'])


if __name__ == '__main__':
    unittest.main()
