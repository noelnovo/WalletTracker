"""Android delivery runs on GitLab CI; the GitHub repository is a passive backup."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class GitlabDeliveryTests(unittest.TestCase):
    def test_gitlab_pipeline_defines_the_full_delivery(self):
        workflow = (ROOT / '.gitlab-ci.yml').read_text()
        for stage in ('checks', 'build', 'security', 'publish'):
            self.assertIn(stage, workflow)
        self.assertIn('python3 -m scripts.ci.verify_contract', workflow)
        self.assertIn('discover -s tests/automation', workflow)
        self.assertIn('testDebugUnitTest jacocoTestReport', workflow)
        self.assertIn('python3 -m ci.security_scan', workflow)
        self.assertIn('python3 -m ci.release_publish', workflow)
        # Gates and the release run only on protected branches and exact version tags.
        self.assertIn("$CI_COMMIT_REF_PROTECTED == \"true\"", workflow)
        self.assertIn("$CI_COMMIT_TAG =~ /^v[0-9]+\\.[0-9]+\\.[0-9]+$/", workflow)
        self.assertIn('when: manual', workflow)

    def test_scanner_configuration_is_in_repo_and_scoped(self):
        properties = (ROOT / 'sonar-project.properties').read_text()
        self.assertIn('sonar.projectKey=noelpatata_WalletTracker_982bd7e1-4fb1-48a0-aebb-c34cdddfe86a', properties)
        self.assertIn('sonar.coverage.jacoco.xmlReportPaths=app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml', properties)
        self.assertIn('sonar.coverage.exclusions=', properties)

    def test_delivery_identity_is_pinned_to_the_android_gitlab_project(self):
        access = (ROOT / 'ci' / 'vault_access.py').read_text()
        self.assertIn("'CI_PROJECT_ID': '31'", access)
        self.assertIn("'CI_PROJECT_PATH': 'downops/wallet-tracker-android'", access)
        self.assertIn('auth/gitlab-ci/login', access)
        self.assertIn("Merge requests cannot obtain delivery credentials", access)
        release = (ROOT / 'ci' / 'release_publish.py').read_text()
        self.assertIn('downops/data/apps/wallettracker/prod/mobile', release)
        self.assertIn('60b988457cca109d8f3a1f3ad288f95648f9938990aeedff0dfc5ed479952b61', release)

    def test_github_workflows_are_retired(self):
        self.assertFalse((ROOT / '.github/workflows').exists() and any((ROOT / '.github/workflows').iterdir()))


if __name__ == '__main__':
    unittest.main()
