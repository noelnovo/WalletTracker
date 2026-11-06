"""Stable dev signing using GitHub OIDC/Vault. No secrets in APK/argv/logs."""
import base64
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import urllib.request
from github_identity import require_dev, build_environment

ROOT = pathlib.Path(__file__).resolve().parents[1]
VAULT = 'https://vault.downops.win'
DEV_SIGNING_PATH = 'downops/data/apps/wallettracker/dev/mobile'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request(path, data=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['X-Vault-Token'] = token
    req = urllib.request.Request(VAULT + '/v1/' + path,
                                 data=json.dumps(data).encode() if data is not None else None,
                                 headers=headers)
    with urllib.request.build_opener(NoRedirect()).open(req, timeout=30) as response:
        return json.load(response)


def main():
    env = os.environ.copy()
    directory = None
    try:
        if env.get('DEV_SIGNING_READY', 'false').lower() == 'true':
            require_dev(env)
            token = env.get('VAULT_TOKEN')
            if not token:
                raise RuntimeError('The scoped GitHub OIDC Vault login must succeed before signing')
            values = request(DEV_SIGNING_PATH, token=token)['data']['data']
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
    finally:
        if directory:
            shutil.rmtree(directory)


if __name__ == '__main__':
    main()
