"""One review before signing; separate least-privilege automatic publication."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ReleaseApprovalTests(unittest.TestCase):
    def setUp(self):
        self.workflow = (ROOT / '.github/workflows/android.yml').read_text()

    def job(self, name):
        match = re.search(r'^  ' + re.escape(name) + r':\n(.*?)(?=^  [\w-]+:\n|\Z)',
                          self.workflow, re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(match)
        return match.group(1)

    def test_single_production_review_before_signing(self):
        self.assertEqual(1, len(re.findall(r'^    environment: production$',
                                          self.workflow, re.MULTILINE)))
        self.assertIn('environment: production', self.job('prod-apk'))
        self.assertNotIn('environment:', self.job('publish-prod'))

    def test_publication_requires_the_successful_signing_job(self):
        publish = self.job('publish-prod')
        self.assertIn('needs: prod-apk', publish)
        self.assertNotIn('always()', publish)
        self.assertNotIn('continue-on-error', publish)
        self.assertIn('actions/download-artifact@v4', publish)
        self.assertIn('wallettracker-production-${{ github.sha }}', publish)
        self.assertIn('scripts.release.publish_prod', publish)

    def test_publication_and_signing_permissions_stay_separate(self):
        publish, signing = self.job('publish-prod'), self.job('prod-apk')
        self.assertIn('contents: write', publish)
        self.assertNotIn('id-token: write', publish)
        self.assertNotIn('vault-action', publish)
        self.assertIn('id-token: write', signing)
        self.assertIn('contents: read', signing)
        self.assertNotIn('contents: write', signing)

    def test_dev_cannot_enter_release_context(self):
        context = self.job('release-context')
        self.assertIn('scripts.ci.release_validation', context)
        self.assertIn('actions: read', context)
        self.assertIn("startsWith(github.ref, 'refs/tags/v')", context)
        self.assertIn('github.ref_protected', context)
        self.assertIn("!contains(github.ref_name, '-')", context)
        self.assertIn('needs: release-context', self.job('prod-apk'))

    def test_tag_workflow_reuses_exact_artifact_and_does_not_repeat_validation(self):
        self.assertNotIn('branches:', self.workflow)
        self.assertNotIn('pull_request:', self.workflow)
        self.assertNotIn('gradlew', self.workflow)
        self.assertNotIn('unsigned-build:', self.workflow)
        signing = self.job('prod-apk')
        self.assertIn('artifact-ids: ${{ needs.release-context.outputs.validation_artifact_id }}', signing)
        self.assertIn('run-id: ${{ needs.release-context.outputs.validation_run_id }}', signing)
        self.assertLess(signing.index('scripts.ci.package_validated_apk --verify'), signing.index('vault-action'))
        self.assertIn('EXPECTED_VALIDATION_RUN_ATTEMPT', signing)

    def test_validation_keeps_all_checks_without_signing_permissions(self):
        validation = (ROOT / '.github/workflows/validation.yml').read_text()
        self.assertIn('branches: [dev, main]', validation)
        self.assertIn('pull_request:', validation)
        self.assertNotIn('tags:', validation)
        self.assertNotIn('vault-action', validation)
        build_jobs, security_job = validation.split('\n  security-analysis:\n', 1)
        self.assertNotIn('id-token:', build_jobs)
        self.assertIn('id-token: write', security_job)
        self.assertIn("if: github.event_name == 'push'", security_job)
        self.assertIn('needs: [checks, unsigned-build]', security_job)
        self.assertNotIn('environment: production', security_job)
        self.assertIn('noelnovo/.github/.github/workflows/dependency-track-scan.yml@', security_job)
        self.assertNotIn('mobile-ci-prod', security_job)
        self.assertIn('\n  security:\n', security_job)
        self.assertIn('test "$SECURITY_RESULT" = success', security_job)
        self.assertNotIn('continue-on-error', validation)
        self.assertIn('python3 -m scripts.ci.verify_contract', validation)
        self.assertIn('discover -s tests/automation', validation)
        self.assertIn('testDebugUnitTest jacocoTestReport assembleDebug assembleRelease', validation)
        self.assertIn("github.event_name == 'push' && github.ref == 'refs/heads/main'", validation)


if __name__ == '__main__':
    unittest.main()
