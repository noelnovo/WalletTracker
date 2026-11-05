"""Optional stable dev signing using scoped GitLab OIDC. No secrets in APK/argv/logs."""
import base64
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
VAULT = 'https://vault.int.downops.win'


def request(path, data=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['X-Vault-Token'] = token
    req = urllib.request.Request(VAULT + '/v1/' + path,
                                 data=json.dumps(data).encode() if data is not None else None,
                                 headers=headers)
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def main():
    env = os.environ.copy()
    directory = None
    try:
        if env.get('DEV_SIGNING_READY', 'false').lower() == 'true':
            if env.get('CI_COMMIT_BRANCH') != 'dev' or env.get('CI_PROJECT_ID') != '31':
                raise RuntimeError('Dev signing is restricted to project31/dev')
            token = request('auth/gitlab-ci/login', {'role': 'wallettracker-android-ci-dev', 'jwt': env['VAULT_ID_TOKEN']})['auth']['client_token']
            values = request('wallettracker/data/dev/android-signing', token=token)['data']['data']
            required = ('KEYSTORE_BASE64', 'KEYSTORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD')
            if not all(values.get(key) for key in required):
                raise RuntimeError('The development signing record is incomplete')
            directory = pathlib.Path(tempfile.mkdtemp(prefix='.dev-signing-', dir=ROOT))
            keyfile = directory / 'dev.jks'
            keyfile.write_bytes(base64.b64decode(''.join(values['KEYSTORE_BASE64'].split()), validate=True))
            keyfile.chmod(0o600)
            env.update(DEV_KEYSTORE_FILE=str(keyfile), DEV_KEYSTORE_PASSWORD=values['KEYSTORE_PASSWORD'],
                       DEV_KEY_ALIAS=values['KEY_ALIAS'], DEV_KEY_PASSWORD=values['KEY_PASSWORD'])
            # Do not let the Gradle subprocess inherit the job OIDC credential.
            env.pop('VAULT_ID_TOKEN', None)
            print('Building a stable dev-signed artifact from the scoped Vault record', flush=True)
        else:
            print('DEV_SIGNING_READY=false: disposable debug signing; not an update-stable release', flush=True)
        subprocess.run(['sh', 'gradlew', '--no-daemon', '--max-workers=1',
                        '-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8',
                        '-Pkotlin.compiler.execution.strategy=in-process', 'testDebugUnitTest', 'assembleDebug'],
                       cwd=ROOT, env=env, check=True)
    finally:
        if directory:
            shutil.rmtree(directory)


if __name__ == '__main__':
    main()
