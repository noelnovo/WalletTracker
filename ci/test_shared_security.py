"""GitLab Android delivery retains trusted pre-merge and separate signing boundaries."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class GitlabDeliveryTests(unittest.TestCase):
    def test_scan_uses_the_canonical_generic_adapter(self):
        workflow = (ROOT / '.gitlab-ci.yml').read_text()
        self.assertIn('project: downops/github-workflows', workflow)
        self.assertIn('/gitlab/dependency-track-scan.yml', workflow)
        self.assertIn('coverage xml', workflow)
        self.assertIn('coverage/python.xml', workflow)
        self.assertIn('merge_request_event', workflow)
        self.assertNotIn('python3 -m ci.security_scan', workflow)

    def test_builds_are_bounded_and_signer_does_not_execute_candidate_code(self):
        workflow = (ROOT / '.gitlab-ci.yml').read_text()
        self.assertIn('--max-workers=1', workflow)
        self.assertIn('-Xmx512m', workflow)
        self.assertIn('-XX:MaxMetaspaceSize=384m', workflow)
        self.assertIn('-XX:ReservedCodeCacheSize=96m', workflow)
        release = workflow.split('\nrelease:\n')[1]
        self.assertIn('job: security', release)
        self.assertIn('cache: []', release)
        self.assertIn('cd "$DELIVERY_CONTROL_DIR" && python3 -m ci.release_publish', release)

    def test_signing_uses_env_passwords_and_verified_package_upload(self):
        release = (ROOT / 'ci/release_publish.py').read_text()
        self.assertIn('env:PROD_STORE_PASSWORD', release)
        self.assertNotIn("'pass:'", release)
        self.assertIn('method=\'PUT\'', release)
        self.assertIn('Published package checksum mismatch', release)
        self.assertIn('Production APK signer mismatch', release)

    def test_signing_role_cannot_request_scan_credentials(self):
        access = (ROOT / 'ci/vault_access.py').read_text()
        self.assertIn('wallettracker-android-gitlab-prod', access)
        self.assertIn('Signing role cannot request scanner or unrelated secrets', access)

    def test_github_workflows_remain_retired(self):
        self.assertFalse((ROOT / '.github/workflows').exists() and any((ROOT / '.github/workflows').iterdir()))


if __name__ == '__main__':
    unittest.main()
