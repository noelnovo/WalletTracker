"""Retain MAIN's tested unsigned APK; verify downloaded input before Vault access."""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
from scripts.build.build_prod import ROOT, version, validate_built_configuration, command
from scripts.shared.github_identity import require_main_validation, require_production, REPOSITORY, REPOSITORY_ID
from scripts.shared.validated_apk import positive_id, validate_files, version_code


def package(env):
    require_main_validation(env)
    if env.get('VALIDATION_CHECKS_RESULT') != 'success':
        raise RuntimeError('Source and automation checks must pass before artifact packaging')
    if command(['git', 'rev-parse', 'HEAD'], env) != env['GITHUB_SHA']:
        raise RuntimeError('Tested checkout differs from the Actions source revision')
    run_id, attempt = positive_id(env.get('GITHUB_RUN_ID')), positive_id(env.get('GITHUB_RUN_ATTEMPT'))
    apk = ROOT / 'app/build/outputs/apk/release/app-release-unsigned.apk'
    configs = list((ROOT / 'app/build/generated/source/buildConfig/release').glob('**/BuildConfig.java'))
    if len(configs) != 1:
        raise RuntimeError('The actual tested release BuildConfig is required')
    validate_built_configuration(pathlib.Path(env['ANDROID_HOME']) / 'build-tools/36.0.0', apk, env)
    output = ROOT / 'artifacts/validated-input'
    output.mkdir(parents=True, exist_ok=True)
    for source, name in [(apk, 'app-release-unsigned.apk'), (configs[0], 'BuildConfig.java')]:
        shutil.copyfile(source, output / name)
    receipt = {'schema': 1, 'purpose': 'unsigned-validation', 'publishable': False,
               'repository': REPOSITORY, 'repository_id': REPOSITORY_ID,
               'source_revision': env['GITHUB_SHA'], 'version': version(), 'version_code': version_code(version()),
               'workflow_ref': env['GITHUB_WORKFLOW_REF'], 'event': env['GITHUB_EVENT_NAME'],
               'ref': env['GITHUB_REF'], 'run_id': run_id, 'run_attempt': attempt,
               'unsigned_apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
               'build_config_sha256': hashlib.sha256(configs[0].read_bytes()).hexdigest()}
    (output / 'validation-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    validate_files(output, sha=env['GITHUB_SHA'], version=version(), run_id=run_id, attempt=attempt)


def verify(env):
    require_production(env)
    positive_id(env.get('EXPECTED_VALIDATION_ARTIFACT_ID'))
    directory = ROOT / 'validated-input'
    validate_files(directory, sha=env['GITHUB_SHA'], version=version(),
                   run_id=env.get('EXPECTED_VALIDATION_RUN_ID'), attempt=env.get('EXPECTED_VALIDATION_RUN_ATTEMPT'))
    validate_built_configuration(pathlib.Path(env['ANDROID_HOME']) / 'build-tools/36.0.0',
                                directory / 'app-release-unsigned.apk', env, config_path=directory / 'BuildConfig.java')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    try:
        verify(os.environ) if args.verify else package(os.environ)
    except Exception:
        print('::error::Validated APK packaging/verification failed; no signing authorization granted')
        raise SystemExit(1)
