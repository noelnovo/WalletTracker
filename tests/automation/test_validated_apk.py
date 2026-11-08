"""Unsigned artifact integrity and signing reuse, using only synthetic files."""
import base64
import hashlib
import io
import json
import os
import pathlib
import tempfile
import unittest
from unittest.mock import patch
from scripts.build import build_prod
from scripts.ci import package_validated_apk as packaging
from scripts.shared.github_identity import REPOSITORY, REPOSITORY_ID, VALIDATION_WORKFLOW
from scripts.shared.validated_apk import validate_files, version_code
from ci_test_support import github_environment

SHA = 'a' * 40
VERSION = '3.1.1'
CONFIG = '\n'.join(('String API_BASE_URL = "https://api.downops.win";', 'String API_VERSION = "2";',
                    'boolean REGISTRATION_UI_AVAILABLE = true;', 'String SIGN_SECRET = "";'))


def fixture(directory):
    directory.mkdir(parents=True)
    apk = b'synthetic-tested-unsigned-apk'
    (directory / 'app-release-unsigned.apk').write_bytes(apk)
    (directory / 'BuildConfig.java').write_text(CONFIG)
    receipt = {'schema': 1, 'purpose': 'unsigned-validation', 'publishable': False,
               'repository': REPOSITORY, 'repository_id': REPOSITORY_ID, 'source_revision': SHA,
               'version': VERSION, 'version_code': version_code(VERSION), 'run_id': 123, 'run_attempt': 2,
               'workflow_ref': REPOSITORY + '/' + VALIDATION_WORKFLOW + '@refs/heads/main',
               'event': 'push', 'ref': 'refs/heads/main', 'unsigned_apk_sha256': hashlib.sha256(apk).hexdigest(),
               'build_config_sha256': hashlib.sha256(CONFIG.encode()).hexdigest()}
    (directory / 'validation-receipt.json').write_text(json.dumps(receipt))
    return receipt


class ValidatedApkTests(unittest.TestCase):
    def validate(self, directory, **changes):
        args = dict(sha=SHA, version=VERSION, run_id=123, attempt=2)
        return validate_files(directory, **dict(args, **changes))

    def test_exact_version_source_run_and_attempt_are_required(self):
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary:
            directory = pathlib.Path(temporary) / 'input'; fixture(directory)
            self.assertEqual(self.validate(directory)['run_id'], 123)
            for change in [{'sha': 'b' * 40}, {'version': '3.1.2'}, {'run_id': 124}, {'attempt': 1}, {'run_id': None}]:
                with self.subTest(change=change), self.assertRaises(RuntimeError):
                    self.validate(directory, **change)

    def test_corruption_extra_files_symlinks_and_self_authorizing_receipts_are_rejected(self):
        for mutation in ['apk', 'config', 'extra', 'symlink', 'receipt-purpose', 'receipt-branch', 'receipt-bool']:
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary:
                directory = pathlib.Path(temporary) / 'input'; receipt = fixture(directory)
                if mutation == 'apk': (directory / 'app-release-unsigned.apk').write_bytes(b'tampered')
                elif mutation == 'config': (directory / 'BuildConfig.java').write_text('tampered')
                elif mutation == 'extra': (directory / 'extra').write_text('unexpected')
                elif mutation == 'symlink':
                    (directory / 'BuildConfig.java').unlink()
                    (directory / 'BuildConfig.java').symlink_to(directory / 'app-release-unsigned.apk')
                else:
                    key, value = {'receipt-purpose': ('purpose', 'release'), 'receipt-branch': ('ref', 'refs/heads/dev'),
                                  'receipt-bool': ('schema', True)}[mutation]
                    receipt[key] = value; (directory / 'validation-receipt.json').write_text(json.dumps(receipt))
                with self.assertRaises(RuntimeError): self.validate(directory)

    def test_only_main_push_can_package_the_actual_tested_build(self):
        env = github_environment(ref='refs/heads/main', GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='2',
                                 VALIDATION_CHECKS_RESULT='success', ANDROID_HOME='/synthetic-sdk')
        env['GITHUB_WORKFLOW_REF'] = REPOSITORY + '/' + VALIDATION_WORKFLOW + '@refs/heads/main'
        for change in [{'GITHUB_EVENT_NAME': 'pull_request'}, {'GITHUB_REF': 'refs/heads/dev'}, {'VALIDATION_CHECKS_RESULT': 'failure'}]:
            with self.subTest(change=change), patch.object(packaging, 'validate_built_configuration') as validation, self.assertRaises(RuntimeError):
                packaging.package(dict(env, **change))
            validation.assert_not_called()
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary:
            root = pathlib.Path(temporary)
            apk = root / 'app/build/outputs/apk/release/app-release-unsigned.apk'
            config = root / 'app/build/generated/source/buildConfig/release/package/BuildConfig.java'
            apk.parent.mkdir(parents=True); config.parent.mkdir(parents=True)
            apk.write_bytes(b'synthetic-tested-unsigned-apk'); config.write_text(CONFIG)
            with patch.object(packaging, 'ROOT', root), patch.object(packaging, 'version', return_value=VERSION), \
                 patch.object(packaging, 'command', return_value=SHA), patch.object(packaging, 'validate_built_configuration'):
                packaging.package(env)
            self.validate(root / 'artifacts/validated-input')

    def test_normal_signing_uses_validated_apk_and_never_runs_gradle(self):
        previous = b'synthetic-previous-apk'
        values = {'KEYSTORE_BASE64': base64.b64encode(b'synthetic-key').decode(), 'KEYSTORE_PASSWORD': 'synthetic-password', 'KEY_ALIAS': 'synthetic-alias'}
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary:
            root = pathlib.Path(temporary); unsigned_receipt = fixture(root / 'validated-input')
            env = github_environment(ref='refs/tags/v3.1.1', ANDROID_HOME=str(root / 'sdk'),
                EXPECTED_VALIDATION_RUN_ID='123', EXPECTED_VALIDATION_RUN_ATTEMPT='2', EXPECTED_VALIDATION_ARTIFACT_ID='456',
                SIGNING_INPUT_JSON=json.dumps({'PROD_' + k: v for k, v in values.items()}))
            seen = []
            def command(args, env):
                if 'zipalign' in str(args[0]):
                    seen.append(pathlib.Path(args[-2]).read_bytes())
                if 'sign' in args:
                    pathlib.Path(args[args.index('--out') + 1]).write_bytes(b'synthetic-signed-apk')
                return ''
            with patch.object(build_prod, 'ROOT', root), patch.object(build_prod, 'version', return_value=VERSION), \
                 patch.dict(os.environ, env, clear=True), patch.object(build_prod, 'validate_identity', return_value='v3.1.1'), \
                 patch.object(build_prod, 'verify_backend'), patch.object(build_prod.urllib.request, 'urlopen', return_value=io.BytesIO(previous)), \
                 patch.object(build_prod, 'PREVIOUS_APK_SHA256', hashlib.sha256(previous).hexdigest()), \
                 patch.object(build_prod, 'command', side_effect=command), patch.object(build_prod, 'signer'), \
                 patch.object(build_prod, 'validate_built_configuration'), patch.object(build_prod.subprocess, 'run') as gradle:
                build_prod.main()
            gradle.assert_not_called()
            self.assertEqual(seen, [b'synthetic-tested-unsigned-apk'])
            receipt = json.loads((root / 'artifacts/release-receipt.json').read_text())
            self.assertEqual(receipt['unsigned_apk_sha256'], unsigned_receipt['unsigned_apk_sha256'])
            self.assertEqual(receipt['validation_artifact_id'], 456)
            self.assertTrue(receipt['publishable'])

    def test_missing_evidence_blocks_signing_before_credentials_are_consumed(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(build_prod, 'validate_identity', return_value='v3.1.1'), \
             patch.object(build_prod, 'signing_values') as secrets, patch.object(build_prod, 'command') as command, self.assertRaises(RuntimeError):
            build_prod.main()
        secrets.assert_not_called(); command.assert_not_called()

    def test_missing_artifact_with_valid_identity_still_blocks_secrets_and_builds(self):
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary:
            env = {'GITHUB_SHA': SHA, 'EXPECTED_VALIDATION_RUN_ID': '123', 'EXPECTED_VALIDATION_RUN_ATTEMPT': '2',
                   'EXPECTED_VALIDATION_ARTIFACT_ID': '456'}
            with patch.dict(os.environ, env, clear=True), patch.object(build_prod, 'ROOT', pathlib.Path(temporary)), \
                 patch.object(build_prod, 'version', return_value=VERSION), \
                 patch.object(build_prod, 'validate_identity', return_value='v3.1.1'), \
                 patch.object(build_prod, 'signing_values') as secrets, patch.object(build_prod.subprocess, 'run') as gradle, self.assertRaises(RuntimeError):
                build_prod.main()
            secrets.assert_not_called(); gradle.assert_not_called()

    def test_downloaded_configuration_is_checked_without_requiring_a_new_build(self):
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary:
            root = pathlib.Path(temporary); fixture(root / 'validated-input')
            badging = "package: name='win.downops.wallettracker' versionCode='30101' versionName='3.1.1'"
            with patch.object(build_prod, 'ROOT', root), patch.object(build_prod, 'version', return_value=VERSION), \
                 patch.object(build_prod, 'command', return_value=badging) as command:
                build_prod.validate_built_configuration(root / 'sdk', root / 'validated-input/app-release-unsigned.apk', {},
                                                        config_path=root / 'validated-input/BuildConfig.java')
                for broken in [badging.replace('30101', '30100'), badging.replace('3.1.1', '3.1.0'), badging + ' application-debuggable']:
                    command.return_value = broken
                    with self.assertRaises(RuntimeError):
                        build_prod.validate_built_configuration(root / 'sdk', root / 'validated-input/app-release-unsigned.apk', {},
                                                               config_path=root / 'validated-input/BuildConfig.java')
