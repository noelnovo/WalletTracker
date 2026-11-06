"""Fail-closed release identity checks; no live Vault, Gradle or signing calls."""
import os
import base64
import hashlib
import io
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch
import build_prod


class ProductionSigningTests(unittest.TestCase):
    def environment(self, **changes):
        return {'CI_PROJECT_ID': '31', 'CI_COMMIT_TAG': 'v3.0.0', 'CI_COMMIT_REF_PROTECTED': 'true',
                'CI_COMMIT_SHA': 'a' * 40, **changes}

    def test_unprotected_or_nonproduction_identity_cannot_sign(self):
        for changes in ({'CI_PROJECT_ID': '1'}, {'CI_COMMIT_TAG': 'v2.2.2'}, {'CI_COMMIT_REF_PROTECTED': 'false'}):
            with self.subTest(changes=changes), patch.object(build_prod, 'command') as command:
                with self.assertRaises(RuntimeError):
                    build_prod.validate_identity(self.environment(**changes))
                command.assert_not_called()

    def test_matching_main_and_tag_are_required(self):
        outputs = ['', 'a' * 40, '', 'a' * 40 + '\trefs/heads/main', 'a' * 40]
        with patch.object(build_prod, 'command', side_effect=outputs):
            self.assertEqual(build_prod.validate_identity(self.environment()), 'v3.0.0')

    def test_divergent_github_main_blocks_production_signing(self):
        outputs = ['', 'a' * 40, '', 'b' * 40 + '\trefs/heads/main', 'a' * 40]
        with patch.object(build_prod, 'command', side_effect=outputs):
            with self.assertRaises(RuntimeError):
                build_prod.validate_identity(self.environment())

    def test_different_apk_certificate_is_rejected(self):
        with patch.object(build_prod, 'command', return_value='Signer #1 certificate SHA-256 digest: ' + 'b' * 64):
            with self.assertRaises(RuntimeError):
                build_prod.signer('apksigner', 'synthetic.apk', {})

    def test_correct_existing_production_certificate_is_accepted(self):
        with patch.object(build_prod, 'command', return_value='Signer #1 certificate SHA-256 digest: ' + build_prod.EXPECTED_SIGNER):
            build_prod.signer('apksigner', 'synthetic.apk', {})

    def test_record_is_production_only(self):
        self.assertEqual(build_prod.PROD_RECORD, 'downops/data/apps/wallettracker/prod/mobile')
        self.assertNotIn('/dev/', build_prod.PROD_RECORD)

    def test_ci_publication_requires_credential_and_protected_release_gates(self):
        pipeline = (build_prod.ROOT / '.gitlab/ci/pipeline.yml').read_text()
        publication = pipeline.split('\npublish-prod:\n', 1)[1]
        rules = publication.split('  rules:\n', 1)[1].split('  environment:', 1)[0]
        self.assertIn('$GITHUB_RELEASE_READY == "true"', rules)
        self.assertIn('$CI_COMMIT_REF_PROTECTED == "true"', rules)
        self.assertIn('when: manual', rules)
        self.assertNotIn('ci-check', rules)
        self.assertIn('GITHUB_RELEASE_READY: "false"', pipeline)

    def test_check_tag_cannot_authorize_a_real_release(self):
        with patch.object(build_prod, 'command') as command:
            with self.assertRaises(RuntimeError):
                build_prod.validate_identity(self.environment(CI_COMMIT_TAG='v3.0.0-ci-check'))
            command.assert_not_called()

    def test_check_requires_exact_protected_tag_project_namespace(self):
        environment = self.environment(CI_COMMIT_TAG='v3.0.0-ci-check', CI_PROJECT_NAMESPACE='downops')
        for changes in ({'CI_PROJECT_ID': '1'}, {'CI_PROJECT_NAMESPACE': 'other'},
                        {'CI_COMMIT_REF_PROTECTED': 'false'}, {'CI_COMMIT_TAG': 'v3.0.0'},
                        {'CI_COMMIT_TAG': 'v2.2.2-ci-check'}, {'CI_COMMIT_TAG': 'v3.0.0-ci-check.0'}):
            with self.subTest(changes=changes), patch.object(build_prod, 'command') as command:
                with self.assertRaises(RuntimeError):
                    build_prod.validate_check_identity(dict(environment, **changes))
                command.assert_not_called()

    def test_check_requires_mirrored_dev_ancestry(self):
        for suffix in ('', '.1'):
            outputs = ['', 'a' * 40, '', 'a' * 40 + '\trefs/heads/dev', 'a' * 40]
            with self.subTest(suffix=suffix), patch.object(build_prod, 'command', side_effect=outputs):
                tag = 'v3.0.0-ci-check' + suffix
                self.assertEqual(build_prod.validate_check_identity(self.environment(
                    CI_COMMIT_TAG=tag, CI_PROJECT_NAMESPACE='downops')), tag)

    def test_validation_retains_only_nonpublishable_receipt(self):
        pathlib.Path('/tmp/opencode').mkdir(parents=True, exist_ok=True)
        previous = b'synthetic-previous-apk'
        values = {'KEYSTORE_BASE64': base64.b64encode(b'synthetic-keystore').decode(),
                  'KEYSTORE_PASSWORD': 'synthetic-password', 'KEY_ALIAS': 'synthetic-alias'}
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as directory:
            root = pathlib.Path(directory)
            def fake_command(args, env):
                if 'sign' in args:
                    pathlib.Path(args[args.index('--out') + 1]).write_bytes(b'synthetic-signed-apk')
                return ''
            with patch.object(build_prod, 'ROOT', root), \
                 patch.dict(os.environ, {'VAULT_ID_TOKEN': 'synthetic-jwt', 'ANDROID_HOME': str(root / 'sdk'), 'CI_COMMIT_SHA': 'a' * 40}, clear=True), \
                 patch.object(build_prod, 'validate_check_identity', return_value='v3.0.0-ci-check'), \
                 patch.object(build_prod, 'validate_identity') as release_identity, \
                 patch.object(build_prod, 'verify_backend'), \
                 patch.object(build_prod, 'vault_request', side_effect=[{'auth': {'client_token': 'synthetic-token'}}, {'data': {'data': values}}]), \
                 patch.object(build_prod.urllib.request, 'urlopen', return_value=io.BytesIO(previous)), \
                 patch.object(build_prod, 'PREVIOUS_APK_SHA256', hashlib.sha256(previous).hexdigest()), \
                 patch.object(build_prod.subprocess, 'run') as gradle, \
                 patch.object(build_prod, 'command', side_effect=fake_command), \
                 patch.object(build_prod, 'signer'), \
                 patch.object(build_prod, 'validate_built_configuration'):
                build_prod.main(validation=True)
            release_identity.assert_not_called()
            self.assertNotIn('VAULT_ID_TOKEN', gradle.call_args.kwargs['env'])
            self.assertNotIn('PROD_KEYSTORE_PASSWORD', gradle.call_args.kwargs['env'])
            self.assertEqual(list(root.glob('.prod-signing-*')), [])
            self.assertEqual(list(root.rglob('*.apk')), [])
            self.assertFalse((root / 'delivery/artifacts').exists())
            receipt = json.loads((root / 'delivery/check-artifacts/production-check-receipt.json').read_text())
            self.assertEqual(receipt['purpose'], 'signing-validation')
            self.assertIs(receipt['publishable'], False)
            self.assertIs(receipt['signed_apk_retained'], False)

    def test_no_github_actions_remain(self):
        workflows = build_prod.ROOT / '.github/workflows'
        self.assertEqual(list(workflows.glob('*.yml')) + list(workflows.glob('*.yaml')), [])


if __name__ == '__main__':
    unittest.main()
