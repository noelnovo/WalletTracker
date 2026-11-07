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
from github_identity import require_dev, build_environment, PRODUCTION_SIGNER, signing_values

ROOT = pathlib.Path(__file__).resolve().parents[1]
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
    output = ROOT / 'delivery/artifacts'
    output.mkdir(parents=True, exist_ok=True)
    (output / 'dev-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('Development APK signature and stable signer verified; receipt produced', flush=True)


def report_failure(error):
    """A bounded public CI annotation; never expose exception bodies or credentials."""
    if isinstance(error, urllib.error.HTTPError):
        headers = error.headers or {}
        content = headers.get('Content-Type', '').lower()
        kind = 'html' if 'text/html' in content else 'json' if 'json' in content else 'other'
        challenge = headers.get('cf-mitigated', '').lower() == 'challenge'
        category = 'public-dependency-http-' + str(error.code) + '; format=' + kind + '; edge-challenge=' + str(challenge).lower()
        error.close()
    elif isinstance(error, FileNotFoundError):
        tool = pathlib.Path(error.filename or '').name
        category = 'missing-tool-' + (tool if tool in ('keytool', 'apksigner', 'gradlew') else 'unclassified')
    elif isinstance(error, subprocess.CalledProcessError):
        category = 'gradle-build-failed'
    elif isinstance(error, KeyError):
        key = error.args[0] if error.args else ''
        category = 'missing-input-' + (key if key in ('ANDROID_HOME', 'GITHUB_SHA', 'KEY_ALIAS', 'KEYSTORE_PASSWORD') else 'unclassified')
    elif isinstance(error, RuntimeError):
        fixed = str(error)
        category = ('vault-action-output-input' if fixed.startswith('The verified signing outputs') or fixed == 'The verified Vault action signing outputs are required' else
                    'keystore-record-incomplete' if fixed == 'The development signing record is incomplete' else
                    'github-job-identity' if 'GitHub' in fixed or 'workflow identity' in fixed else
                    'dev-signer-verification' if 'certificate' in fixed or 'signing identity' in fixed else
                    'runtime-validation-failed')
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
