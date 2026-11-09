"""Credential-free regression tests for security boundaries and hard failures."""
import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('security_gate', pathlib.Path(__file__).with_name('security_gate.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class SecurityTests(unittest.TestCase):
    def test_https_required(self):
        for url in ['http://example.test', 'https://user:password@example.test']:
            with self.assertRaises(RuntimeError):
                gate.request(url)

    def test_github_identity(self):
        cfg = {'provider': 'github', 'repository': 'owner/repo', 'branches': ['dev'], 'workflow': 'validation.yml'}
        env = {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': 'owner/repo',
            'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/heads/dev',
            'GITHUB_WORKFLOW_REF': 'owner/repo/.github/workflows/validation.yml@refs/heads/dev'}
        self.assertTrue(gate.trusted_identity(cfg, env))
        for field, value in [('GITHUB_EVENT_NAME', 'pull_request'), ('GITHUB_REF', 'refs/heads/feature'),
                             ('GITHUB_REPOSITORY', 'attacker/repo'), ('GITHUB_WORKFLOW_REF', 'unexpected')]:
            self.assertFalse(gate.trusted_identity(cfg, dict(env, **{field: value})))

    def test_gitlab_identity(self):
        cfg = {'provider': 'gitlab', 'repository': 'downops/prompt-gateway', 'project_id': '10', 'branches': ['main']}
        env = {'GITLAB_CI': 'true', 'CI_PROJECT_PATH': cfg['repository'], 'CI_PROJECT_ID': '10',
            'CI_PIPELINE_SOURCE': 'push', 'CI_COMMIT_BRANCH': 'main', 'CI_COMMIT_REF_PROTECTED': 'true'}
        self.assertTrue(gate.trusted_identity(cfg, env))
        for field, value in [('CI_PIPELINE_SOURCE', 'merge_request_event'), ('CI_COMMIT_REF_PROTECTED', 'false'),
                             ('CI_PROJECT_ID', '1'), ('CI_COMMIT_BRANCH', 'feature')]:
            self.assertFalse(gate.trusted_identity(cfg, dict(env, **{field: value})))

    def dt_responses(self, findings=None, policy=0, state=False):
        return [{'token': '00000000-0000-0000-0000-000000000001'}, {'processing': state},
                findings or [], {'policyViolationsFail': policy}]

    def run_dt(self, responses):
        creds = {'DT_URL': 'https://dt.example.test', 'DT_API_KEY': 'synthetic',
                 'DT_PROJECT_UUID': '00000000-0000-0000-0000-000000000002'}
        with patch.object(gate, 'request', side_effect=responses):
            return gate.dependency_track(creds, b'{}', wait=lambda _: None)

    def test_clean_pass(self):
        self.assertTrue(self.run_dt(self.dt_responses()))

    def test_high_and_critical_fail(self):
        for severity in ['HIGH', 'CRITICAL']:
            self.assertFalse(self.run_dt(self.dt_responses([{'vulnerability': {'severity': severity}}])))

    def test_policy_fail(self):
        self.assertFalse(self.run_dt(self.dt_responses(policy=1)))

    def test_invalid_processing_fails(self):
        with self.assertRaises(RuntimeError):
            self.run_dt(self.dt_responses(state=None))

    def test_missing_metrics_fail(self):
        with self.assertRaises(RuntimeError):
            self.run_dt(self.dt_responses(policy=None))

    def test_pagination(self):
        page = [{'vulnerability': {'severity': 'LOW'}}] * 100
        responses = self.dt_responses()[:2] + [page, [{'vulnerability': {'severity': 'HIGH'}}], {'policyViolationsFail': 0}]
        self.assertFalse(self.run_dt(responses))

    def test_redirects_refused(self):
        self.assertIsNone(gate.NoRedirect().redirect_request(None, None, None, None, None, None))

    def test_analysis_timeout_fails(self):
        responses = [{'token': '00000000-0000-0000-0000-000000000001'}] + [{'processing': True}] * 120
        with self.assertRaises(RuntimeError):
            self.run_dt(responses)

    def test_reuse_rejects_wrong_source_or_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / 'security-image.tar').write_bytes(b'synthetic-image')
            for receipt in [{'revision': 'other', 'sha256': gate.file_hash(root / 'security-image.tar')},
                            {'revision': 'current', 'sha256': 'wrong'}]:
                (root / 'security-image.json').write_text(json.dumps(receipt))
                with patch.object(gate, 'ROOT', root), patch.object(gate.subprocess, 'check_output', return_value='current\n'), patch.object(gate.subprocess, 'run') as run:
                    with self.assertRaises(RuntimeError):
                        gate.reuse()
                    run.assert_not_called()

    def test_reuse_checks_loaded_image_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / 'security-image.tar').write_bytes(b'synthetic-image')
            receipt = {'revision': 'current', 'sha256': gate.file_hash(root / 'security-image.tar'), 'image_id': 'expected'}
            (root / 'security-image.json').write_text(json.dumps(receipt))
            image = [{'Id': 'unexpected', 'Config': {'Labels': {'org.opencontainers.image.revision': 'current'}}}]
            with patch.object(gate, 'ROOT', root), patch.object(gate.subprocess, 'check_output', side_effect=['current\n', json.dumps(image).encode()]), patch.object(gate.subprocess, 'run'):
                with self.assertRaises(RuntimeError):
                    gate.reuse()


if __name__ == '__main__':
    unittest.main()
