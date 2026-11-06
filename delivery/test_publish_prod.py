import hashlib
import json
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
            receipt = {'tag': 'v3.0.0', 'source_revision': 'a' * 40, 'package': 'win.downops.wallettracker',
                       'signer_matches_previous_release': True, 'api_version': 2, 'registration_enabled': False,
                       'apk_sha256': hashlib.sha256(payload).hexdigest()}
            self.assertTrue(publish_prod.validate_artifact(receipt, 'v3.0.0', 'a' * 40, directory).is_file())
            for change in ({'source_revision': 'b' * 40}, {'signer_matches_previous_release': False},
                           {'registration_enabled': True}, {'package': 'win.downops.wallettracker.debug'}, {'apk_sha256': '0' * 64}):
                with self.subTest(change=change), self.assertRaises(RuntimeError):
                    publish_prod.validate_artifact(dict(receipt, **change), 'v3.0.0', 'a' * 40, directory)

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


if __name__ == '__main__':
    unittest.main()
