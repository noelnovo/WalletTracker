"""Protected-tag production APK signing and GitLab release; secrets stay out of argv, caches and logs."""
import base64, hashlib, json, os, pathlib, re, subprocess, tempfile, urllib.request
from ci.vault_access import Vault

ROOT = pathlib.Path(os.getcwd())
PROD_RECORD = 'downops/data/apps/wallettracker/prod/mobile'
PREVIOUS_APK = 'https://github.com/noelnovo/WalletTracker/releases/download/v2.2.2/app-release-unsigned-signed.apk'
PREVIOUS_APK_SHA256 = 'a4fe553370782f0d046a3fe181acde210a1db23024e5f92774edfedfd8007b51'
EXPECTED_SIGNER = '60b988457cca109d8f3a1f3ad288f95648f9938990aeedff0dfc5ed479952b61'
GITLAB_API = 'https://gitlab.downops.win/api/v4'


def version():
    text = (ROOT / 'app/build.gradle.kts').read_text()
    return '.'.join(re.search(r'val ' + name + r'Version\s*=\s*(\d+)', text).group(1) for name in ('major', 'minor', 'patch'))


def command(args, env=None):
    process = subprocess.run(args, cwd=ROOT, env=env, capture_output=True, text=True)
    if process.returncode:
        raise RuntimeError('Production delivery command failed: ' + pathlib.Path(args[0]).name + '; sensitive diagnostics withheld')
    return process.stdout.strip()


def validate_identity(env):
    tag = 'v' + version()
    if os.environ.get('CI_COMMIT_TAG') != tag or not os.environ.get('CI_COMMIT_REF_PROTECTED') == 'true':
        raise RuntimeError('Production signing requires the exact protected version tag')
    sha = os.environ.get('CI_COMMIT_SHA', '')
    if not re.fullmatch('[a-f0-9]{40}', sha):
        raise RuntimeError('A verified source revision is required')
    command(['git', 'fetch', 'origin', 'main'], env)
    command(['git', 'merge-base', '--is-ancestor', sha, 'origin/main'], env)
    return tag


def verify_backend():
    request_url = 'https://api.downops.win/api/v1/ready'
    with urllib.request.urlopen(urllib.request.Request(request_url, headers={'User-Agent': 'WalletTracker-Production-Release'}), timeout=30) as response:
        if response.status != 200:
            raise RuntimeError('Verified production API-v2 deployment is required before APK signing')


def signed_release(vault, tag):
    values = vault.read(PROD_RECORD)
    if not all(values.get(key) for key in ('KEYSTORE_BASE64', 'KEYSTORE_PASSWORD', 'KEY_ALIAS')):
        raise RuntimeError('Canonical production keystore record is incomplete')
    env = os.environ.copy()
    env.pop('VAULT_ID_TOKEN', None)
    unsigned = next((ROOT / 'app/build/outputs/apk/release').glob('app-release-unsigned.apk'))
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
        signed = ROOT / 'artifacts' / ('app-release-signed-' + tag + '.apk')
        signed.parent.mkdir(parents=True, exist_ok=True)
        command([str(sdk / 'apksigner'), 'sign',
                 '--ks', str(keyfile),
                 '--ks-key-alias', values['KEY_ALIAS'],
                 '--ks-pass', 'pass:' + values['KEYSTORE_PASSWORD'],
                 '--key-pass', 'pass:' + (values.get('KEY_PASSWORD') or values['KEYSTORE_PASSWORD']),
                 '--out', str(signed), str(unsigned)], env)
        signer(sdk / 'apksigner', signed, env)
        return signed
    finally:
        keyfile.unlink(missing_ok=True)


def signer(apksigner, apk, env):
    output = command([str(apksigner), 'verify', '--print-certs', str(apk)], env)
    found = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})', output)
    if len(found) != 1 or found[0].lower() != EXPECTED_SIGNER:
        raise RuntimeError('APK signing identity differs from the published production app; update would be unsafe')


def publish_gitlab_release(tag, signed, vault):
    api = GITLAB_API + '/projects/' + str(os.environ['CI_PROJECT_ID'])
    headers = {'Job-Token': os.environ['CI_JOB_TOKEN']}
    request(api + '/releases', 'POST', {
        'tag_name': tag, 'name': 'WalletTracker ' + tag[1:],
        'description': (ROOT / 'docs/releases' / (tag[1:] + '.md')).read_text(),
        'assets': {'links': [{'name': 'Signed release APK', 'url':
            GITLAB_API.split('/api/v4')[0] + '/' + os.environ['CI_PROJECT_PATH'] + '/-/releases/' + tag + '/downloads/' + signed.name}]}}, headers)


def main():
    env = os.environ.copy()
    tag = validate_identity(env)
    verify_backend()
    vault = Vault('wallettracker-android')
    signed = signed_release(vault, tag)
    publish_gitlab_release(tag, signed, vault)
    digest = hashlib.sha256(signed.read_bytes()).hexdigest()
    print(json.dumps({'release_published': True, 'tag': tag, 'apk_sha256': digest}))


if __name__ == '__main__':
    try:
        main()
    except RuntimeError as error:
        print(json.dumps({'release_failed': True, 'safe_error': str(error)}))
        raise SystemExit(1)
