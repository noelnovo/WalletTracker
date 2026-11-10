"""Android keeps thin callers only; the scanner is maintained in noelnovo/.github."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
PIN = 'noelnovo/.github/.github/workflows/dependency-track-scan.yml@55425668e1d0aaac5a60c7eec35b91a99d483b79'


class SharedSecurityTests(unittest.TestCase):
    def test_pr_and_push_use_one_identical_reviewed_shared_pin(self):
        for name in ['premerge-security.yml', 'validation.yml']:
            workflow = (ROOT / '.github/workflows' / name).read_text()
            refs = re.findall(r'uses:\s*(noelnovo/\.github/\S+)', workflow)
            self.assertEqual(refs, [PIN])
            self.assertNotIn('secrets: inherit', workflow)
            self.assertNotIn('security_gate.py', workflow)
            self.assertNotIn('vault-action', workflow)

    def test_no_local_duplicate_scanner_controller_or_settings(self):
        for name in ['security_gate.py', 'premerge_security.py', 'security.json',
                     'premerge-security.json', 'premerge-sonar.properties', 'test_security_gate.py',
                     'test_premerge_security.py']:
            self.assertFalse((ROOT / 'ci' / name).exists())
        self.assertFalse((ROOT / 'sonar-project.properties').exists())

    def test_push_aggregate_keeps_release_security_gate_fail_closed(self):
        workflow = (ROOT / '.github/workflows/validation.yml').read_text()
        aggregate = workflow.split('\n  security:\n', 1)[1]
        self.assertIn('if: always() && github.event_name == \'push\'', aggregate)
        self.assertIn('needs: security-analysis', aggregate)
        self.assertIn('SECURITY_RESULT: ${{ needs.security-analysis.result }}', aggregate)
        self.assertIn('test "$SECURITY_RESULT" = success', aggregate)
        self.assertNotIn('id-token:', aggregate)
        self.assertNotIn('statuses: write', aggregate)
        self.assertNotIn('continue-on-error', aggregate)

    def test_pr_caller_preserves_required_status_contract_without_executing_pr_code(self):
        workflow = (ROOT / '.github/workflows/premerge-security.yml').read_text()
        self.assertIn('pull_request:', workflow)
        self.assertNotIn('pull_request_target:', workflow)
        self.assertIn('branches: [dev, main]', workflow)
        self.assertIn('statuses: write', workflow)
        self.assertIn('id-token: write', workflow)
        self.assertNotIn('actions/checkout', workflow)
        self.assertNotIn('run:', workflow)


if __name__ == '__main__':unittest.main()
