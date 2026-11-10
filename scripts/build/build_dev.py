"""Stable dev signing using GitHub OIDC/Vault. No secrets in APK/argv/logs."""
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
from scripts.shared.github_identity import require_dev, build_environment, PRODUCTION_SIGNER, signing_values

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEV_SIGNING_PATH = 'downops/data/apps/wallettracker/dev/mobile'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def verify_artifact(keyfile, values, env):
    """Check the actual APK against the loaded DEV certificate, not a debug fallback."""
    child = build_environment(env)
    certificate = subprocess.run(['keytool', '-exportcert', '-keystore', str(keyfile),
        '-storepass:env', 'DEV_KEYSTORE_PASSWORD', '-alias', values['KEY_ALIAS']],
        env=child, capture_output=True)
    if certificate.returncode or not certificate.stdout:
        raise RuntimeError('Development signing certificate could not be verified; diagnostics withheld')
    expected = hashlib.sha256(certificate.stdout).hexdigest()
    if expected == PRODUCTION_SIGNER:
        raise RuntimeError('Production signing identity must never be used for a development APK')
    apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    sdk = pathlib.Path(env['ANDROID_HOME']) / 'build-tools/36.0.0'
    verification = subprocess.run([str(sdk / 'apksigner'), 'verify', '--print-certs', str(apk)],
                                  env=child, capture_output=True)
    digests = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})',
                         verification.stdout.decode(errors='replace'))
    if verification.returncode or len(digests) != 1 or digests[0].lower() != expected:
        raise RuntimeError('Built development APK does not match the stable DEV signing certificate')
    receipt = {'source_revision': env['GITHUB_SHA'], 'package': 'win.downops.wallettracker.debug',
               'signing_record_path': DEV_SIGNING_PATH,
               'purpose': 'development', 'publishable': False,
               'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
               'certificate_public_sha256': expected,
               'signer_matches_dev_record': True, 'distinct_from_production': True}
    output = ROOT / 'artifacts'
    output.mkdir(parents=True, exist_ok=True)
    (output / 'dev-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('Development APK signature and stable signer verified; receipt produced', flush=True)


def http_failure_category(error):
    """Bound an HTTP failure to a coarse format and edge-challenge category."""
    headers = error.headers or {}
    content = headers.get('Content-Type', '').lower()
    if 'text/html' in content:
        kind = 'html'
    elif 'json' in content:
        kind = 'json'
    else:
        kind = 'other'
    challenge = headers.get('cf-mitigated', '').lower() == 'challenge'
    error.close()
    return 'public-dependency-http-' + str(error.code) + '; format=' + kind + '; edge-challenge=' + str(challenge).lower()


def missing_input_category(error):
    """Bound a missing tool or required input to a coarse category."""
    if isinstance(error, FileNotFoundError):
        tool = pathlib.Path(error.filename or '').name
        if tool in ('keytool', 'apksigner', 'gradlew'):
            return 'missing-tool-' + tool
        return 'missing-tool-unclassified'
    key = error.args[0] if error.args else ''
    if key in ('ANDROID_HOME', 'GITHUB_SHA', 'KEY_ALIAS', 'KEYSTORE_PASSWORD'):
        return 'missing-input-' + key
    return 'missing-input-unclassified'


def runtime_failure_category(error):
    """Bound a signing or validation failure to a coarse category."""
    fixed = str(error)
    if fixed.startswith('The verified signing outputs') or fixed == 'The verified Vault action signing outputs are required':
        return 'vault-action-output-input'
    if fixed == 'The development signing record is incomplete':
        return 'keystore-record-incomplete'
    if 'GitHub' in fixed or 'workflow identity' in fixed:
        return 'github-job-identity'
    if 'certificate' in fixed or 'signing identity' in fixed:
        return 'dev-signer-verification'
    return 'runtime-validation-failed'


def report_failure(error):
    """A bounded public CI annotation; never expose exception bodies or credentials."""
    if isinstance(error, urllib.error.HTTPError):
        category = http_failure_category(error)
    elif isinstance(error, (FileNotFoundError, KeyError)):
        category = missing_input_category(error)
    elif isinstance(error, subprocess.CalledProcessError):
        category = 'gradle-build-failed'
    elif isinstance(error, RuntimeError):
        category = runtime_failure_category(error)
    else:
        category = 'unexpected-build-error'
    print('::error title=WalletTracker signing diagnostic::' + category, flush=True)


def main():
    env = os.environ.copy()
    directory = None
    try:
        if env.get('DEV_SIGNING_READY', 'false').lower() == 'true':
            require_dev(env)
            values = signing_values(env, 'DEV')
            required = ('KEYSTORE_BASE64', 'KEYSTORE_PASSWORD', 'KEY_ALIAS')
            if not all(values.get(key) for key in required):
                raise RuntimeError('The development signing record is incomplete')
            directory = pathlib.Path(tempfile.mkdtemp(prefix='.dev-signing-', dir=ROOT))
            keyfile = directory / 'dev.jks'
            keyfile.write_bytes(base64.b64decode(''.join(values['KEYSTORE_BASE64'].split()), validate=True))
            keyfile.chmod(0o600)
            env.update(DEV_KEYSTORE_FILE=str(keyfile), DEV_KEYSTORE_PASSWORD=values['KEYSTORE_PASSWORD'],
                       DEV_KEY_ALIAS=values['KEY_ALIAS'], DEV_KEY_PASSWORD=values.get('KEY_PASSWORD') or values['KEYSTORE_PASSWORD'])
            print('Building a stable dev-signed artifact from the scoped Vault record', flush=True)
        else:
            print('DEV_SIGNING_READY=false: disposable debug signing; not an update-stable release', flush=True)
        # Neither signing mode passes the job's Vault OIDC credential to Gradle.
        env = build_environment(env)
        subprocess.run(['sh', 'gradlew', '--no-daemon', '--max-workers=2',
                        '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8',
                        '-Pkotlin.compiler.execution.strategy=in-process', 'testDebugUnitTest', 'assembleDebug'],
                        cwd=ROOT, env=env, check=True)
        if directory is not None:
            verify_artifact(keyfile, values, env)
    finally:
        if directory:
            shutil.rmtree(directory)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        report_failure(error)
        raise
