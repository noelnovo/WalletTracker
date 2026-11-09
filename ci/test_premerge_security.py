"""Offline adversarial tests for trusted PR execution and merge evidence."""
import io
import json
import os
from pathlib import Path
import stat
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import premerge_security as control

HEAD = 'a' * 40
BASE = 'b' * 40
CANDIDATE = 'c' * 40
ENV = {'GITHUB_EVENT_NAME': 'pull_request_target', 'GITHUB_REPOSITORY': control.REPOSITORY,
       'GITHUB_SHA': BASE, 'GITHUB_RUN_ID': '12', 'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_TOKEN': 'synthetic'}


def event():
    return {'number': 7, 'repository': {'id': control.REPOSITORY_ID},
        'pull_request': {'head': {'sha': HEAD}, 'base': {'sha': BASE, 'ref': 'dev',
            'repo': {'id': control.REPOSITORY_ID}}}}


def current():
    return {'state': 'open', 'mergeable': True, 'head': {'sha': HEAD},
        'base': {'sha': BASE, 'ref': 'dev', 'repo': {'id': control.REPOSITORY_ID}},
        'merge_commit_sha': CANDIDATE}


def source_tar(name='repository/app/src/main/Example.kt', kind=None):
    data = io.BytesIO()
    with tarfile.open(fileobj=data, mode='w:gz') as archive:
        member = tarfile.TarInfo(name)
        if kind is not None:
            member.type = kind
            member.linkname = '/etc/passwd'
            archive.addfile(member)
        else:
            member.size = 4
            archive.addfile(member, io.BytesIO(b'data'))
    return data.getvalue()


def build_zip(extra=None, link=False):
    data = io.BytesIO()
    with zipfile.ZipFile(data, 'w') as archive:
        archive.writestr('build/reports/cyclonedx/bom.json', '{"components":[{}]}')
        archive.writestr('app/build/tmp/kotlin-classes/debug/Example.class', b'bytecode-not-executed')
        if extra:
            info = zipfile.ZipInfo(extra)
            if link:
                info.create_system = 3
                info.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(info, '/etc/passwd')
    return data.getvalue()


class PremergeTests(unittest.TestCase):
    def test_only_trusted_target_event_is_accepted(self):
        self.assertEqual(control.event_identity(event(), ENV)['target'], 'dev')
        for key, value in [('GITHUB_EVENT_NAME', 'pull_request'), ('GITHUB_REPOSITORY', 'attacker/repo')]:
            with self.assertRaises(RuntimeError):
                control.event_identity(event(), dict(ENV, **{key: value}))

    def test_target_specific_status_prevents_dev_result_reuse_for_main(self):
        first = control.event_identity(event(), ENV)
        other = event()
        other['pull_request']['base']['ref'] = 'main'
        second = control.event_identity(other, ENV)
        self.assertNotEqual(first['context'], second['context'])

    def test_exact_merge_parents_are_required(self):
        identity = control.event_identity(event(), ENV)
        with patch.object(control, 'api', side_effect=[current(), {'parents': [{'sha': BASE}, {'sha': HEAD}]}]):
            self.assertEqual(control.current_candidate(identity), CANDIDATE)
        with patch.object(control, 'api', side_effect=[current(), {'parents': [{'sha': BASE}, {'sha': 'd' * 40}]}]):
            with self.assertRaises(RuntimeError):
                control.current_candidate(identity)

    def test_changed_head_or_base_is_rejected(self):
        identity = control.event_identity(event(), ENV)
        for field in ['head', 'base']:
            changed = current()
            changed[field]['sha'] = 'd' * 40
            with patch.object(control, 'api', return_value=changed), self.assertRaises(RuntimeError):
                control.current_candidate(identity)

    def test_conflicts_cannot_receive_success(self):
        changed = dict(current(), mergeable=False)
        with patch.object(control, 'api', return_value=changed), self.assertRaises(RuntimeError):
            control.current_candidate(control.event_identity(event(), ENV))

    def test_skipped_failed_or_cancelled_build_never_reaches_credentials(self):
        for state in ['skipped', 'failure', 'cancelled', '']:
            with patch.dict(os.environ, dict(ENV, PREMERGE_BUILD_RESULT=state), clear=True), patch.object(control.security_gate, 'scan') as scan:
                with self.assertRaises(RuntimeError):
                    control.evaluate(control.event_identity(event(), ENV))
                scan.assert_not_called()

    def test_invalid_archive_paths_are_rejected(self):
        for name in ['../escape', '/absolute', 'folder/../../escape', 'folder\\escape', 'bad\x00name']:
            with self.assertRaises(RuntimeError):
                control.safe_path(name)

    def test_source_links_and_devices_are_rejected(self):
        for kind in [tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.CHRTYPE]:
            with tempfile.TemporaryDirectory() as directory, self.assertRaises(RuntimeError):
                control.unpack_source(source_tar(kind=kind), Path(directory))

    def test_pr_scanner_config_and_fake_compiled_output_are_ignored(self):
        for name in ['repository/sonar-project.properties', 'repository/.scannerwork/report-task.txt',
                     'repository/app/build/fake.class', 'repository/bom.json']:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                control.unpack_source(source_tar(name), root)
                self.assertFalse(any(p.is_file() for p in root.rglob('*')))

    def test_regular_source_is_only_written_as_nonexecutable_data(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control.unpack_source(source_tar(), root)
            target = root / 'app/src/main/Example.kt'
            self.assertEqual(target.read_bytes(), b'data')
            self.assertEqual(target.stat().st_mode & 0o777, 0o600)

    def test_build_archive_cannot_overwrite_trusted_code_or_configuration(self):
        for name in ['ci/security_gate.py', 'sonar-project.properties', '../outside', '.github/workflows/control.yml']:
            with tempfile.TemporaryDirectory() as directory, self.assertRaises(RuntimeError):
                control.unpack_build(build_zip(name), Path(directory))

    def test_artifact_links_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(RuntimeError):
            control.unpack_build(build_zip('app/build/tmp/kotlin-classes/debug/evil.class', True), Path(directory))

    def test_valid_artifact_is_data_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control.unpack_build(build_zip(), root)
            self.assertEqual((root / 'app/build/tmp/kotlin-classes/debug/Example.class').stat().st_mode & 0o777, 0o600)

    def test_no_skipped_status_can_be_published(self):
        with self.assertRaises(RuntimeError):
            control.publish(control.event_identity(event(), ENV), 'skipped')

    def test_github_token_cannot_be_forwarded_to_other_hosts(self):
        with self.assertRaises(RuntimeError):
            control.http('https://example.test/download', token='synthetic')

    def test_artifact_must_belong_to_same_run(self):
        artifact = {'id': 5, 'name': 'premerge-security-' + CANDIDATE + '-1', 'expired': False,
                    'workflow_run': {'id': 99}, 'digest': 'sha256:' + '0' * 64}
        with patch.dict(os.environ, ENV, clear=True), patch.object(control, 'api', return_value={'artifacts': [artifact]}), self.assertRaises(RuntimeError):
            control.artifact_for_run(CANDIDATE)

    def test_digest_mismatch_is_rejected(self):
        artifact = {'id': 5, 'name': 'premerge-security-' + CANDIDATE + '-1', 'expired': False,
                    'workflow_run': {'id': 12}, 'digest': 'sha256:' + '0' * 64}
        with patch.dict(os.environ, ENV, clear=True), patch.object(control, 'api', return_value={'artifacts': [artifact]}), patch.object(control, 'http', return_value=b'different'), self.assertRaises(RuntimeError):
            control.artifact_for_run(CANDIDATE)

    def test_changed_candidate_after_analysis_cannot_receive_success(self):
        self.evaluate_with_candidate_change('d' * 40, should_pass=False)

    def test_success_is_published_only_after_exact_candidate_verification(self):
        self.evaluate_with_candidate_change(CANDIDATE, should_pass=True)

    def evaluate_with_candidate_change(self, last, should_pass):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'ci').mkdir()
            (root / 'ci/premerge-security.json').write_text('{}')
            (root / 'ci/premerge-sonar.properties').write_text('trusted=settings\n')
            env = dict(ENV, PREMERGE_BUILD_RESULT='success', PREMERGE_CANDIDATE=CANDIDATE)
            identity = control.event_identity(event(), ENV)
            with patch.dict(os.environ, env, clear=True), patch.object(control, 'TRUSTED_ROOT', root), \
                 patch.object(control, 'current_candidate', side_effect=[CANDIDATE, last]), \
                 patch.object(control, 'artifact_for_run', return_value=b'data'), \
                 patch.object(control, 'http', return_value=b'data'), \
                 patch.object(control, 'unpack_source'), patch.object(control, 'unpack_build'), \
                 patch.object(control.security_gate, 'ROOT', root), \
                 patch.object(control.security_gate, 'prepare'), patch.object(control.security_gate, 'scan'), \
                 patch.object(control, 'publish') as publish:
                if should_pass:
                    control.evaluate(identity)
                    publish.assert_called_once_with(identity, 'success')
                else:
                    with self.assertRaises(RuntimeError):
                        control.evaluate(identity)
                    publish.assert_not_called()

    def test_workflow_separates_untrusted_build_from_credentialed_control(self):
        workflow = (control.TRUSTED_ROOT / '.github/workflows/premerge-security.yml').read_text()
        build = workflow.split('\n  build:\n', 1)[1].split('\n  security:\n', 1)[0]
        gate = workflow.split('\n  security:\n', 1)[1]
        self.assertNotIn('id-token:', build)
        self.assertNotIn('statuses: write', build)
        self.assertNotIn('secrets.', build)
        self.assertIn('ref: ${{ github.sha }}', gate)
        self.assertNotIn('ref: ${{ needs.context.outputs.candidate }}', gate)
        self.assertNotIn('gradlew', gate)


if __name__ == '__main__':
    unittest.main()
