"""Synthetic-only tests: no production signing, credentials or publication."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from ci import release_publish as subject
from ci.vault_access import Vault


class ReleaseTests(unittest.TestCase):
    def env(self):
        return {'CI_PROJECT_ID':'31', 'CI_PIPELINE_SOURCE':'push', 'CI_COMMIT_REF_PROTECTED':'true',
                'CI_COMMIT_TAG':'v3.2.0', 'CI_COMMIT_SHA':'a'*40, 'CI_PIPELINE_ID':'9'}

    def test_exact_tag_current_main_and_same_pipeline_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'app').mkdir(); (root/'security-output').mkdir()
            (root/'app/build.gradle.kts').write_text('val majorVersion = 3\nval minorVersion = 2\nval patchVersion = 0')
            receipt = {'project':'31','pipeline':'9','head':'a'*40,'base':'a'*40,'target':'main','event':'push'}
            (root/'security-output/receipt.json').write_text(json.dumps(receipt))
            with patch.object(subject, 'command', return_value='a'*40+' refs/heads/main'):
                self.assertEqual(subject.identity(root, self.env()), 'v3.2.0')
                for key, value in [('CI_COMMIT_TAG','v3.1.1'),('CI_PROJECT_ID','10'),
                                   ('CI_COMMIT_REF_PROTECTED','false'),('CI_PIPELINE_SOURCE','web'),
                                   ('CI_PIPELINE_ID','10')]:
                    with self.subTest(key=key), self.assertRaises(RuntimeError):
                        subject.identity(root, dict(self.env(), **{key:value}))
            with patch.object(subject, 'command', return_value='b'*40+' refs/heads/main'):
                with self.assertRaises(RuntimeError):subject.identity(root, self.env())

    def test_signing_role_cannot_read_other_paths(self):
        vault = object.__new__(Vault)
        with patch('ci.vault_access.request') as call:
            with self.assertRaises(RuntimeError):vault.read('downops/data/services/sonarqube/clients/instance-005')
            call.assert_not_called()

    def test_original_certificate_required(self):
        with patch.object(subject, 'command', return_value='Signer #1 certificate SHA-256 digest: '+subject.SIGNER):
            subject.verify_signature(Path('synthetic.apk'),Path('/sdk'),{})
        for value in ['', 'Signer #1 certificate SHA-256 digest: '+'0'*64]:
            with patch.object(subject,'command',return_value=value), self.assertRaises(RuntimeError):
                subject.verify_signature(Path('synthetic.apk'),Path('/sdk'),{})

    def test_production_package_and_non_debuggable_version_required(self):
        correct = "package: name='win.downops.wallettracker' versionCode='30200' versionName='3.2.0-alpha'"
        with patch.object(subject,'command',return_value=correct):
            subject.verify_apk(Path('synthetic.apk'),Path('/sdk'),{},'3.2.0')
        for value in [correct+'\napplication-debuggable',correct.replace('30200','30101'),correct.replace('wallettracker','other')]:
            with patch.object(subject,'command',return_value=value), self.assertRaises(RuntimeError):
                subject.verify_apk(Path('synthetic.apk'),Path('/sdk'),{},'3.2.0')

    def test_existing_destination_prevents_signing(self):
        with patch.object(subject,'open_url'):
            with self.assertRaises(RuntimeError):subject.ensure_absent('v3.2.0',{})

    def test_command_error_does_not_disclose_tool_output(self):
        with patch.object(subject.subprocess,'run') as run:
            run.return_value.returncode=1
            run.return_value.stdout='synthetic diagnostic'
            with self.assertRaisesRegex(RuntimeError,'sensitive diagnostics withheld'):
                subject.command(['synthetic-tool'],{})


if __name__ == '__main__':
    unittest.main()
