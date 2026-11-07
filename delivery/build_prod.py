"""Protected-tag production APK signing; secrets stay out of argv, caches and logs."""
import base64
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import urllib.request
import urllib.error
from build_dev import request as vault_request, report_failure
from github_identity import require_production, build_environment, PRODUCTION_SIGNER

ROOT = pathlib.Path(__file__).resolve().parents[1]
PROD_RECORD = 'downops/data/apps/wallettracker/prod/mobile'
PREVIOUS_APK = 'https://github.com/noelnovo/WalletTracker/releases/download/v2.2.2/app-release-unsigned-signed.apk'
PREVIOUS_APK_SHA256 = 'a4fe553370782f0d046a3fe181acde210a1db23024e5f92774edfedfd8007b51'
EXPECTED_SIGNER = PRODUCTION_SIGNER


def version():
    text = (ROOT / 'app/build.gradle.kts').read_text()
    return '.'.join(re.search(r'val ' + name + r'Version\s*=\s*(\d+)', text).group(1) for name in ('major', 'minor', 'patch'))


def command(args, env=None):
    process = subprocess.run(args, cwd=ROOT, env=env, capture_output=True, text=True)
    if process.returncode:
        raise RuntimeError('Production delivery command failed: ' + pathlib.Path(args[0]).name + '; sensitive diagnostics withheld')
    return process.stdout.strip()


def validate_identity(env):
    require_production(env)
    tag = 'v' + version()
    if env.get('GITHUB_REF_NAME') != tag or env.get('GITHUB_REF') != 'refs/tags/' + tag:
        raise RuntimeError('Production signing requires the exact GitHub version tag')
    sha = env.get('GITHUB_SHA', '')
    if not re.fullmatch('[a-f0-9]{40}', sha):
        raise RuntimeError('A verified source revision is required')
    command(['git', 'fetch', 'origin', 'main'], env)
    if command(['git', 'rev-parse', 'HEAD'], env) != sha:
        raise RuntimeError('Source revision mismatch')
    command(['git', 'merge-base', '--is-ancestor', sha, 'origin/main'], env)
    return tag


def validate_check_identity(env):
    """A protected dev-source signing exercise, never a release authorization."""
    require_production(env)
    tag = env.get('GITHUB_REF_NAME', '')
    expected = r'v' + re.escape(version()) + r'-ci-check(?:\.[1-9][0-9]*)?'
    if not re.fullmatch(expected, tag) or env.get('GITHUB_REF') != 'refs/tags/' + tag:
        raise RuntimeError('Signing validation requires the exact protected GitHub check tag')
    sha = env.get('GITHUB_SHA', '')
    if not re.fullmatch('[a-f0-9]{40}', sha):
        raise RuntimeError('A verified check source revision is required')
    command(['git', 'fetch', 'origin', 'dev'], env)
    if command(['git', 'rev-parse', 'HEAD'], env) != sha:
        raise RuntimeError('Check source revision mismatch')
    command(['git', 'merge-base', '--is-ancestor', sha, 'origin/dev'], env)
    return tag


def verify_backend(require_v2=True):
    request = urllib.request.Request('https://api.downops.win/api/v1/ready', headers={'User-Agent': 'WalletTracker-Production-Release'})
    with urllib.request.urlopen(request, timeout=30) as response:
        if response.status != 200 or not json.load(response).get('success'):
            raise RuntimeError('Production database readiness is required')
    if not require_v2:
        return
    request = urllib.request.Request('https://api.downops.win/api/v2/sync', headers={'User-Agent': 'WalletTracker-Production-Release'})
    try:
        urllib.request.urlopen(request, timeout=30).close()
    except urllib.error.HTTPError as error:
        if error.code == 401:
            return
    raise RuntimeError('Verified production API-v2 deployment is required before APK signing')


def signer(apksigner, apk, env):
    output = command([str(apksigner), 'verify', '--print-certs', str(apk)], env)
    found = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})', output)
    if len(found) != 1 or found[0].lower() != EXPECTED_SIGNER:
        raise RuntimeError('APK signing identity differs from the published production app; update would be unsafe')


def validate_built_configuration(sdk, apk, env):
    badging = command([str(sdk / 'aapt2'), 'dump', 'badging', str(apk)], env)
    if "package: name='win.downops.wallettracker'" not in badging or 'application-debuggable' in badging:
        raise RuntimeError('Production APK package/debug state is unsafe')
    configs = list((ROOT / 'app/build/generated/source/buildConfig/release').glob('**/BuildConfig.java'))
    if len(configs) != 1:
        raise RuntimeError('Production build configuration could not be verified')
    text = configs[0].read_text()
    required = ('String API_BASE_URL = "https://api.downops.win";', 'String API_VERSION = "2";',
                'boolean ALLOW_REGISTER = false;', 'String SIGN_SECRET = "";')
    if not all(value in text for value in required):
        raise RuntimeError('Production API, registration or embedded-secret configuration is unsafe')


def main(*, validation=False):
    env = os.environ.copy()
    tag = validate_check_identity(env) if validation else validate_identity(env)
    # Build/sign before the write-freeze to minimize downtime; publication gates API-v2.
    verify_backend(require_v2=False)
    token = env.get('VAULT_TOKEN')
    if not token:
        raise RuntimeError('The scoped GitHub OIDC Vault login must succeed before production signing')
    values = vault_request(PROD_RECORD, token=token)['data']['data']
    if not all(values.get(key) for key in ('KEYSTORE_BASE64', 'KEYSTORE_PASSWORD', 'KEY_ALIAS')):
        raise RuntimeError('Canonical production keystore record is incomplete')
    env.pop('VAULT_ID_TOKEN', None)
    env['API_BASE_URL'] = 'https://api.downops.win'
    env['PROD_KEYSTORE_PASSWORD'] = values['KEYSTORE_PASSWORD']
    env['PROD_KEY_PASSWORD'] = values.get('KEY_PASSWORD') or values['KEYSTORE_PASSWORD']
    output = ROOT / ('delivery/check-artifacts' if validation else 'delivery/artifacts')
    output.mkdir(parents=True, exist_ok=True)
    directory = pathlib.Path(tempfile.mkdtemp(prefix='.prod-signing-', dir=ROOT))
    try:
        keyfile = directory / 'prod.jks'
        keyfile.write_bytes(base64.b64decode(''.join(values['KEYSTORE_BASE64'].split()), validate=True))
        keyfile.chmod(0o600)
        previous = directory / 'previous.apk'
        with urllib.request.urlopen(PREVIOUS_APK, timeout=60) as response:
            previous.write_bytes(response.read())
        if hashlib.sha256(previous.read_bytes()).hexdigest() != PREVIOUS_APK_SHA256:
            raise RuntimeError('Previous production release checksum mismatch')
        sdk = pathlib.Path(env['ANDROID_HOME']) / 'build-tools/36.0.0'
        signer(sdk / 'apksigner', previous, env)
        # Gradle must not inherit production signing credentials; signing is a separate step.
        build_env = build_environment(env)
        subprocess.run(['sh', 'gradlew', '--no-daemon', '--max-workers=2',
                        '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8',
                        '-Pkotlin.compiler.execution.strategy=in-process', 'testDebugUnitTest', 'assembleRelease'],
                       cwd=ROOT, env=build_env, check=True)
        unsigned = ROOT / 'app/build/outputs/apk/release/app-release-unsigned.apk'
        aligned = directory / 'aligned.apk'
        target = directory / 'production-check.apk' if validation else output / ('WalletTracker-' + tag + '.apk')
        command([str(sdk / 'zipalign'), '-p', '-f', '4', str(unsigned), str(aligned)], env)
        command([str(sdk / 'apksigner'), 'sign', '--ks', str(keyfile), '--ks-key-alias', values['KEY_ALIAS'],
                 '--ks-pass', 'env:PROD_KEYSTORE_PASSWORD', '--key-pass', 'env:PROD_KEY_PASSWORD',
                 '--out', str(target), str(aligned)], env)
        signer(sdk / 'apksigner', target, env)
        validate_built_configuration(sdk, target, env)
        checksum = hashlib.sha256(target.read_bytes()).hexdigest()
        if not validation:
            (output / (target.name + '.sha256')).write_text(checksum + '  ' + target.name + '\n')
        receipt_name = 'production-check-receipt.json' if validation else 'release-receipt.json'
        (output / receipt_name).write_text(json.dumps({'tag': tag, 'source_revision': env['GITHUB_SHA'],
            'package': 'win.downops.wallettracker', 'signer_matches_previous_release': True,
            'api_version': 2, 'registration_enabled': False, 'apk_sha256': checksum,
            'purpose': 'signing-validation' if validation else 'release',
            'publishable': not validation, 'signed_apk_retained': not validation}, indent=2) + '\n')
        print('Production signing validation passed; test APK is not retained or publishable' if validation
              else 'Production APK built, signed and verified; publication is a separate approved step', flush=True)
    finally:
        shutil.rmtree(directory)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        report_failure(error)
        raise
