"""Signing-path and credential-boundary tests; no Vault or Gradle calls."""
import base64
import json
import os
import pathlib
import tempfile
import unittest
from unittest.mock import patch
import build_dev


class DevSigningTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        pathlib.Path('/tmp/opencode').mkdir(parents=True, exist_ok=True)

    def environment(self, **changes):
        return {'DEV_SIGNING_READY': 'true', 'CI_COMMIT_BRANCH': 'dev',
                'CI_PROJECT_ID': '31', 'VAULT_ID_TOKEN': 'synthetic-job-identity', **changes}

    def test_only_canonical_dev_record_is_read(self):
        for key_password in (None, 'synthetic-distinct-key-password'):
            with self.subTest(distinct_key_password=key_password is not None):
                values = {'KEYSTORE_BASE64': base64.b64encode(b'synthetic-keystore').decode(),
                          'KEYSTORE_PASSWORD': 'synthetic-store-password', 'KEY_ALIAS': 'dev'}
                if key_password:
                    values['KEY_PASSWORD'] = key_password
                responses = [{'auth': {'client_token': 'synthetic-vault-token'}}, {'data': {'data': values}}]
                with tempfile.TemporaryDirectory(dir='/tmp/opencode') as directory:
                    with patch.dict(os.environ, self.environment(), clear=True), \
                         patch.object(build_dev, 'ROOT', pathlib.Path(directory)), \
                         patch.object(build_dev, 'request', side_effect=responses) as request, \
                         patch.object(build_dev.subprocess, 'run') as run:
                        build_dev.main()
                        request.assert_any_call('downops/data/apps/wallettracker/dev/mobile', token='synthetic-vault-token')
                        self.assertEqual(len(request.call_args_list), 2)
                        env = run.call_args.kwargs['env']
                        self.assertEqual(env['DEV_KEY_PASSWORD'], key_password or values['KEYSTORE_PASSWORD'])
                        self.assertNotIn('VAULT_ID_TOKEN', env)
                        self.assertEqual(list(pathlib.Path(directory).iterdir()), [])

    def test_non_dev_identity_cannot_read_signing_secrets(self):
        for changes in ({'CI_COMMIT_BRANCH': 'main'}, {'CI_PROJECT_ID': '1'}):
            with self.subTest(changes=changes), \
                 patch.dict(os.environ, self.environment(**changes), clear=True), \
                 patch.object(build_dev, 'request') as request, \
                 patch.object(build_dev.subprocess, 'run') as run:
                with self.assertRaises(RuntimeError):
                    build_dev.main()
                request.assert_not_called()
                run.assert_not_called()

    def test_disposable_build_does_not_read_vault_or_forward_identity(self):
        with patch.dict(os.environ, self.environment(DEV_SIGNING_READY='false'), clear=True), \
             patch.object(build_dev, 'request') as request, \
             patch.object(build_dev.subprocess, 'run') as run:
            build_dev.main()
            request.assert_not_called()
            self.assertNotIn('VAULT_ID_TOKEN', run.call_args.kwargs['env'])

    def test_policy_plan_matches_exact_dev_record(self):
        plan = json.loads((pathlib.Path(__file__).parent / 'dev-signing-vault-plan.json').read_text())
        self.assertEqual(f"{plan['kv_mount']}/data/{plan['kv_v2_path']}", build_dev.DEV_SIGNING_PATH)
        self.assertEqual(plan['policy'], 'path "downops/data/apps/wallettracker/dev/mobile" { capabilities = ["read"] }')
        self.assertNotIn('KEY_PASSWORD', plan['required_keys'])

    def test_ci_dev_job_explicitly_uses_the_verified_stable_signer(self):
        pipeline = (build_dev.ROOT / '.gitlab/ci/pipeline.yml').read_text()
        job = pipeline.split('\ndev-apk:\n', 1)[1].split('\nprod-apk:\n', 1)[0]
        self.assertIn('DEV_SIGNING_READY: "true"', job)


if __name__ == '__main__':
    unittest.main()
