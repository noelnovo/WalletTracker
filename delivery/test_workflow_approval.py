"""One review before signing; separate least-privilege automatic publication."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


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
        self.assertIn('delivery/publish_prod.py', publish)

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
        self.assertIn('needs: [checks, unsigned-build]', context)
        self.assertIn("startsWith(github.ref, 'refs/tags/v')", context)
        self.assertIn('github.ref_protected', context)
        self.assertIn("!contains(github.ref_name, '-')", context)
        self.assertIn('needs: release-context', self.job('prod-apk'))


if __name__ == '__main__':
    unittest.main()
