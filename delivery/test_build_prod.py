"""Fail-closed release identity checks; no live Vault, Gradle or signing calls."""
import os
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

    def test_operator_publication_does_not_activate_ci_publication(self):
        pipeline = (build_prod.ROOT / '.gitlab/ci/pipeline.yml').read_text()
        publication = pipeline.split('\npublish-prod:\n', 1)[1]
        rules = publication.split('  rules:\n', 1)[1].split('  environment:', 1)[0]
        self.assertIn('- when: never', rules)
        self.assertNotIn('when: manual', rules)


if __name__ == '__main__':
    unittest.main()
