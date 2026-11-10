"""Signing-path and credential-boundary tests; no Vault or Gradle calls."""
import base64
import json
import hashlib
import os
import pathlib
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from scripts.build import build_dev
from ci_test_support import github_environment


class DevSigningTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        pathlib.Path('/tmp/opencode').mkdir(parents=True, exist_ok=True)

    def environment(self, **changes):
        return github_environment(ref='refs/heads/dev', DEV_SIGNING_READY='true',
                                  VAULT_TOKEN='synthetic-vault-token',
                                  ACTIONS_ID_TOKEN_REQUEST_TOKEN='synthetic-oidc-request', **changes)

    def test_scoped_outputs_are_used_without_a_duplicate_vault_http_read(self):
        for key_password in (None, 'synthetic-distinct-key-password'):
            with self.subTest(distinct_key_password=key_password is not None):
                values = {'KEYSTORE_BASE64': base64.b64encode(b'synthetic-keystore').decode(),
                          'KEYSTORE_PASSWORD': 'synthetic-store-password', 'KEY_ALIAS': 'dev'}
                if key_password:
                    values['KEY_PASSWORD'] = key_password
                payload = json.dumps({'DEV_' + key: value for key, value in values.items()})
                with tempfile.TemporaryDirectory(dir='/tmp/opencode') as directory:
                    with patch.dict(os.environ, self.environment(SIGNING_INPUT_JSON=payload), clear=True), \
                          patch.object(build_dev, 'ROOT', pathlib.Path(directory)), \
                          patch.object(build_dev.urllib.request, 'urlopen') as request, \
                          patch.object(build_dev.subprocess, 'run') as run, \
                          patch.object(build_dev, 'verify_artifact') as verify:
                        build_dev.main()
                        request.assert_not_called()
                        env = run.call_args.kwargs['env']
                        self.assertEqual(key_password or values['KEYSTORE_PASSWORD'], env['DEV_KEY_PASSWORD'])
                        self.assertNotIn('VAULT_ID_TOKEN', env)
                        self.assertNotIn('VAULT_TOKEN', env)
                        self.assertNotIn('ACTIONS_ID_TOKEN_REQUEST_TOKEN', env)
                        self.assertNotIn('SIGNING_INPUT_JSON', env)
                        self.assertEqual([], list(pathlib.Path(directory).iterdir()))
                        verify.assert_called_once()

    def test_non_dev_identity_cannot_read_signing_secrets(self):
        for changes in ({'GITHUB_REF': 'refs/heads/main'}, {'GITHUB_REPOSITORY_ID': '1'},
                        {'GITHUB_EVENT_NAME': 'pull_request'}, {'GITHUB_ACTIONS': 'false'}):
            with self.subTest(changes=changes), \
                 patch.dict(os.environ, self.environment(**changes), clear=True), \
                  patch.object(build_dev.urllib.request, 'urlopen') as request, \
                 patch.object(build_dev.subprocess, 'run') as run:
                with self.assertRaises(RuntimeError):
                    build_dev.main()
                request.assert_not_called()
                run.assert_not_called()

    def test_disposable_build_does_not_read_vault_or_forward_identity(self):
        with patch.dict(os.environ, dict(self.environment(), DEV_SIGNING_READY='false'), clear=True), \
             patch.object(build_dev.urllib.request, 'urlopen') as request, \
             patch.object(build_dev.subprocess, 'run') as run:
            build_dev.main()
            request.assert_not_called()
            self.assertNotIn('VAULT_ID_TOKEN', run.call_args.kwargs['env'])

    def test_policy_plan_matches_exact_dev_record(self):
        plan = json.loads((build_dev.ROOT / 'config/signing/dev-signing-vault-plan.json').read_text())
        self.assertEqual(build_dev.DEV_SIGNING_PATH, f"{plan['kv_mount']}/data/{plan['kv_v2_path']}")
        self.assertEqual('path "downops/data/apps/wallettracker/dev/mobile" { capabilities = ["read"] }', plan['policy'])
        self.assertNotIn('KEY_PASSWORD', plan['required_keys'])

    def test_dev_is_validation_only_and_does_not_release_signed_apks(self):
        workflow = (build_dev.ROOT / '.gitlab-ci.yml').read_text()
        self.assertNotIn('role: wallettracker-mobile-ci-dev', workflow)
        self.assertNotIn('prod-signing-check:', workflow)
        self.assertIn('testDebugUnitTest jacocoTestReport', workflow)
        self.assertIn('assembleDebug assembleRelease', workflow)

    def test_actual_apk_must_match_dev_certificate_and_not_production(self):
        certificate = b'synthetic-public-certificate'
        expected = hashlib.sha256(certificate).hexdigest()
        for outcome in ('valid', 'wrong-apk-signer', 'production-key'):
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory(dir='/tmp/opencode') as directory:
                root = pathlib.Path(directory)
                apk = root / 'app/build/outputs/apk/debug/app-debug.apk'
                apk.parent.mkdir(parents=True)
                apk.write_bytes(b'synthetic-signed-apk')
                digest = '0' * 64 if outcome == 'wrong-apk-signer' else expected
                responses = [SimpleNamespace(returncode=0, stdout=certificate),
                             SimpleNamespace(returncode=0, stdout=('Signer #1 certificate SHA-256 digest: ' + digest).encode())]
                with patch.object(build_dev, 'ROOT', root), \
                     patch.object(build_dev.subprocess, 'run', side_effect=responses) as run, \
                     patch.object(build_dev, 'PRODUCTION_SIGNER', expected if outcome == 'production-key' else 'f' * 64), \
                     patch('builtins.print'):
                    env = self.environment(ANDROID_HOME=str(root / 'sdk'))
                    env['DEV_KEYSTORE_PASSWORD'] = 'synthetic-store-password'
                    if outcome == 'valid':
                        build_dev.verify_artifact(root / 'dev.jks', {'KEY_ALIAS': 'dev'}, env)
                    else:
                        with self.assertRaises(RuntimeError):
                            build_dev.verify_artifact(root / 'dev.jks', {'KEY_ALIAS': 'dev'}, env)
                receipt_path = root / 'artifacts/dev-receipt.json'
                if outcome == 'valid':
                    receipt = json.loads(receipt_path.read_text())
                    self.assertEqual(expected, receipt['certificate_public_sha256'])
                    self.assertIs(True, receipt['signer_matches_dev_record'])
                    self.assertIs(True, receipt['distinct_from_production'])
                else:
                    self.assertFalse(receipt_path.exists())
                for call in run.call_args_list:
                    self.assertNotIn('VAULT_TOKEN', call.kwargs['env'])
                    self.assertNotIn('ACTIONS_ID_TOKEN_REQUEST_TOKEN', call.kwargs['env'])

    def test_failure_annotation_contains_categories_not_credentials(self):
        cases = [
            (RuntimeError('The verified Vault action signing outputs are required'), 'vault-action-output-input'),
            (RuntimeError('synthetic-secret-value'), 'runtime-validation-failed'),
            (KeyError('ANDROID_HOME'), 'missing-input-ANDROID_HOME'),
            (build_dev.urllib.error.HTTPError('https://vault.downops.win/hidden', 403, 'synthetic-secret-value',
                {'Content-Type': 'text/html', 'cf-mitigated': 'challenge'}, None),
             'public-dependency-http-403; format=html; edge-challenge=true'),
        ]
        for error, category in cases:
            with self.subTest(category=category), patch('builtins.print') as output:
                build_dev.report_failure(error)
                self.assertIn(category, output.call_args.args[0])
                self.assertNotIn('synthetic-secret-value', output.call_args.args[0])
                self.assertNotIn('/hidden', output.call_args.args[0])


if __name__ == '__main__':
    unittest.main()
