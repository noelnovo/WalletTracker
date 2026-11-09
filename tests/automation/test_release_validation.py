"""Synthetic Actions API evidence; never contacts GitHub or grants approval."""
import copy
import unittest
from unittest.mock import patch
from scripts.ci import release_validation as gate
from scripts.shared.github_identity import REPOSITORY_ID, VALIDATION_WORKFLOW
from scripts.shared.validated_apk import artifact_name

SHA = 'a' * 40


def run():
    return {'id': 123, 'run_attempt': 2, 'head_sha': SHA, 'head_branch': 'main', 'event': 'push',
            'path': VALIDATION_WORKFLOW, 'status': 'completed', 'conclusion': 'success',
            'repository': {'id': int(REPOSITORY_ID)}, 'head_repository': {'id': int(REPOSITORY_ID)}}


def artifact():
    return {'id': 456, 'name': artifact_name(SHA, 123, 2), 'expired': False, 'digest': 'sha256:' + 'b' * 64,
            'workflow_run': {'id': 123, 'head_sha': SHA, 'head_branch': 'main',
                             'repository_id': int(REPOSITORY_ID), 'head_repository_id': int(REPOSITORY_ID)}}


def jobs():
    return {'jobs': [{'name': name, 'status': 'completed', 'conclusion': 'success'}
                     for name in ('checks', 'unsigned-build', 'security')]}


class ReleaseEvidenceTests(unittest.TestCase):
    def test_security_gate_is_mandatory_before_signing(self):
        for state in ['missing', 'failure', 'skipped']:
            evidence = jobs()
            if state == 'missing':
                evidence['jobs'] = [job for job in evidence['jobs'] if job['name'] != 'security']
            else:
                evidence['jobs'][-1]['conclusion'] = state
            with self.subTest(state=state), patch.object(gate, 'api', return_value=evidence), self.assertRaises(RuntimeError):
                gate.verify_completed_run(run(), 'synthetic-token')
    def test_pr_dev_wrong_workflow_and_foreign_repository_cannot_supply_evidence(self):
        self.assertTrue(gate.trusted_run(run(), SHA))
        for change in [{'event': 'pull_request'}, {'head_branch': 'dev'}, {'head_sha': 'b' * 40},
                       {'path': '.github/workflows/android.yml'}, {'repository': {'id': 1}},
                       {'head_repository': {'id': 1}}]:
            with self.subTest(change=change):
                self.assertFalse(gate.trusted_run(dict(run(), **change), SHA))

    def test_success_requires_every_job_and_exact_attempt_immutable_artifact(self):
        with patch.object(gate, 'api', side_effect=[jobs(), {'artifacts': [artifact()]}]) as api:
            evidence = gate.verify_completed_run(run(), 'synthetic-token')
        self.assertEqual(evidence['validation_artifact_id'], 456)
        self.assertEqual(evidence['validation_run_attempt'], 2)
        self.assertIn('/attempts/2/jobs', api.call_args_list[0].args[0])

    def test_failed_cancelled_skipped_or_pending_main_run_cannot_authorize_release(self):
        for change in [{'conclusion': 'failure'}, {'conclusion': 'cancelled'}, {'conclusion': 'skipped'},
                       {'status': 'in_progress', 'conclusion': None}]:
            with self.subTest(change=change), patch.object(gate, 'api') as api, self.assertRaises(RuntimeError):
                gate.verify_completed_run(dict(run(), **change), 'synthetic-token')
            api.assert_not_called()

    def test_skipped_missing_duplicate_or_failed_required_job_is_rejected(self):
        for changed in [[], [jobs()['jobs'][0]], jobs()['jobs'] + [jobs()['jobs'][0]],
                        [dict(job, conclusion='skipped') for job in jobs()['jobs']],
                        [dict(job, status='in_progress') for job in jobs()['jobs']]]:
            with self.subTest(jobs=changed), patch.object(gate, 'api', return_value={'jobs': changed}), self.assertRaises(RuntimeError):
                gate.verify_completed_run(run(), 'synthetic-token')

    def test_missing_expired_wrong_attempt_wrong_source_and_digestless_artifacts_fail_closed(self):
        bad = [[], [artifact(), artifact()]]
        for change in [{'expired': True}, {'expired': None}, {'id': 0}, {'digest': ''},
                       {'name': artifact_name(SHA, 123, 1)}, {'workflow_run': {'id': 999}}]:
            bad.append([dict(artifact(), **change)])
        for key in ['head_sha', 'head_branch', 'repository_id', 'head_repository_id']:
            value = copy.deepcopy(artifact()); value['workflow_run'][key] = 'wrong'
            bad.append([value])
        for artifacts in bad:
            with self.subTest(artifacts=artifacts), patch.object(gate, 'api', side_effect=[jobs(), {'artifacts': artifacts}]), self.assertRaises(RuntimeError):
                gate.verify_completed_run(run(), 'synthetic-token')

    def test_tag_waits_for_main_instead_of_starting_a_duplicate_build(self):
        pending = dict(run(), status='in_progress', conclusion=None)
        responses = [{'workflow_runs': [pending]}, {'workflow_runs': [run()]}, jobs(), {'artifacts': [artifact()]}]
        with patch.object(gate, 'api', side_effect=responses), patch.object(gate.time, 'sleep') as sleep:
            self.assertEqual(gate.resolve(SHA, 'synthetic-token')['validation_run_id'], 123)
        sleep.assert_called_once_with(15)

    def test_newer_failed_run_blocks_reuse_of_an_older_green_run(self):
        newer = dict(run(), id=124, conclusion='failure')
        with patch.object(gate, 'api', return_value={'workflow_runs': [run(), newer]}), self.assertRaises(RuntimeError):
            gate.resolve(SHA, 'synthetic-token')

    def test_missing_trusted_validation_times_out_without_fallback_or_approval(self):
        with patch.object(gate, 'api', return_value={'workflow_runs': []}), self.assertRaises(RuntimeError):
            gate.resolve(SHA, 'synthetic-token', timeout=0)

    def test_unsigned_branch_cannot_enter_gate_or_request_actions_evidence(self):
        with patch.object(gate, 'api') as api, self.assertRaises(RuntimeError):
            gate.main({})
        api.assert_not_called()
