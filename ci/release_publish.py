"""Immutable GitLab release controller. Candidate files are data, never imported."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request

from ci.vault_access import Vault, request

SIGNER = '60b988457cca109d8f3a1f3ad288f95648f9938990aeedff0dfc5ed479952b61'
RECORD = 'downops/data/apps/wallettracker/prod/mobile'
API = 'https://gitlab.int.downops.win/api/v4/projects/31'
PUBLIC_API = 'https://gitlab.downops.win/api/v4/projects/31'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def open_url(req):
    if isinstance(req, urllib.request.Request):
        req.add_header('User-Agent', 'Mozilla/5.0 WalletTracker-Android-Release')
    return urllib.request.build_opener(NoRedirect()).open(req, timeout=120)


def command(args, env):
    result = subprocess.run(args, env=env, capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise RuntimeError('Release tool failed; sensitive diagnostics withheld')
    return result.stdout


def version(root):
    text = (root / 'app/build.gradle.kts').read_text()
    return '.'.join(re.search(r'val ' + name + r'Version\s*=\s*(\d+)', text).group(1)
                    for name in ('major', 'minor', 'patch'))


def identity(root, env):
    tag = env.get('CI_COMMIT_TAG', '')
    revision = env.get('CI_COMMIT_SHA', '')
    if (env.get('CI_PROJECT_ID') != '31' or env.get('CI_PIPELINE_SOURCE') != 'push'
            or env.get('CI_COMMIT_REF_PROTECTED') != 'true'
            or tag != 'v' + version(root) or not re.fullmatch(r'[0-9a-f]{40}', revision)):
        raise RuntimeError('Exact protected version-tag release required')
    remote = command(['git', '-C', str(root), 'ls-remote', 'origin', 'refs/heads/main'], env).split()
    if len(remote) != 2 or remote[0] != revision:
        raise RuntimeError('Release tag is not the current approved MAIN')
    receipt = json.loads((root / 'security-output/receipt.json').read_text())
    if any(receipt.get(key) != value for key, value in {
            'project': '31', 'pipeline': env['CI_PIPELINE_ID'], 'head': revision,
            'base': revision, 'target': 'main', 'event': 'push'}.items()):
        raise RuntimeError('Release artifact identity mismatch')
    return tag


def verify_backend():
    # No account registration or data writes. Readiness plus auth boundary only.
    req = urllib.request.Request('https://api.downops.win/api/v1/ready',
                                 headers={'User-Agent': 'Mozilla/5.0 WalletTracker-Release'})
    with urllib.request.urlopen(req, timeout=30) as response:
        if response.status != 200 or not json.load(response).get('success'):
            raise RuntimeError('Production backend is not ready')
    req = urllib.request.Request('https://api.downops.win/api/v2/sync',
                                 headers={'User-Agent': 'Mozilla/5.0 WalletTracker-Release'})
    try:
        urllib.request.urlopen(req, timeout=30).close()
    except urllib.error.HTTPError as error:
        if error.code == 401:
            return
    raise RuntimeError('API-v2 authentication boundary is unavailable')


def verify_apk(apk, sdk, env, release_version):
    badging = command([str(sdk / 'aapt2'), 'dump', 'badging', str(apk)], env)
    line = next((line for line in badging.splitlines() if line.startswith('package: ')), '')
    fields = {part.partition('=')[0]: part.partition('=')[2]
              for part in shlex.split(line.removeprefix('package: ')) if '=' in part}
    numbers = [int(value) for value in release_version.split('.')]
    code = str(numbers[0] * 10000 + numbers[1] * 100 + numbers[2])
    if (fields.get('name') != 'win.downops.wallettracker' or fields.get('versionCode') != code
            or fields.get('versionName') not in [release_version, release_version + '-alpha']
            or 'application-debuggable' in badging):
        raise RuntimeError('Production APK package/version/debug state mismatch')


def verify_signature(apk, sdk, env):
    output = command([str(sdk / 'apksigner'), 'verify', '--print-certs', str(apk)], env)
    certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})', output)
    if len(certs) != 1 or certs[0].lower() != SIGNER:
        raise RuntimeError('Production APK signer mismatch')


def sign(root, sdk, tag, vault):
    unsigned = root / 'app/build/outputs/apk/release/app-release-unsigned.apk'
    receipt = json.loads((root / 'release-input.json').read_text())
    digest = hashlib.sha256(unsigned.read_bytes()).hexdigest()
    if (receipt != {'sha': os.environ['CI_COMMIT_SHA'], 'pipeline': os.environ['CI_PIPELINE_ID'],
                    'unsigned_sha256': digest}):
        raise RuntimeError('Unsigned APK does not match the same-pipeline producer')
    config = list((root / 'app/build/generated/source/buildConfig/release').rglob('BuildConfig.java'))
    expected = ['String API_BASE_URL = "https://api.downops.win";', 'String API_VERSION = "2";',
                'boolean REGISTRATION_UI_AVAILABLE = true;', 'String SIGN_SECRET = "";']
    if len(config) != 1 or not all(item in config[0].read_text() for item in expected):
        raise RuntimeError('Production API configuration mismatch')
    env = {key: value for key, value in os.environ.items()
           if key in ['PATH', 'JAVA_HOME', 'HOME', 'LANG']}
    verify_apk(unsigned, sdk, env, tag[1:])
    values = vault.read(RECORD)
    env['PROD_STORE_PASSWORD'] = values['KEYSTORE_PASSWORD']
    env['PROD_KEY_PASSWORD'] = values.get('KEY_PASSWORD') or values['KEYSTORE_PASSWORD']
    output = root / 'artifacts'
    output.mkdir()
    target = output / ('WalletTracker-' + tag + '.apk')
    with tempfile.TemporaryDirectory(prefix='wallet-signing-') as directory:
        key = Path(directory) / 'production.p12'
        key.write_bytes(base64.b64decode(''.join(values['KEYSTORE_BASE64'].split()), validate=True))
        key.chmod(0o600)
        aligned = Path(directory) / 'aligned.apk'
        command([str(sdk / 'zipalign'), '-p', '-f', '4', str(unsigned), str(aligned)], env)
        command([str(sdk / 'apksigner'), 'sign', '--ks', str(key), '--ks-key-alias', values['KEY_ALIAS'],
                 '--ks-pass', 'env:PROD_STORE_PASSWORD', '--key-pass', 'env:PROD_KEY_PASSWORD',
                 '--out', str(target), str(aligned)], env)
    verify_signature(target, sdk, env)
    verify_apk(target, sdk, env, tag[1:])
    checksum = hashlib.sha256(target.read_bytes()).hexdigest()
    (output / (target.name + '.sha256')).write_text(checksum + '  ' + target.name + '\n')
    (output / 'release-receipt.json').write_text(json.dumps({
        'tag': tag, 'source_revision': os.environ['CI_COMMIT_SHA'],
        'pipeline': os.environ['CI_PIPELINE_ID'], 'unsigned_sha256': digest,
        'apk_sha256': checksum, 'signer_sha256': SIGNER}) + '\n')
    return output


def upload(tag, path, headers):
    # PUT and subsequent authenticated GET use the SAME real endpoint. A release
    # link is created only after the actual published bytes have been verified.
    url = API + '/packages/generic/wallettracker-android/' + tag[1:] + '/' + path.name
    req = urllib.request.Request(url, data=path.read_bytes(), method='PUT', headers=headers)
    with open_url(req) as response:
        if response.status != 201:
            raise RuntimeError('Package upload did not create an immutable file')
    with open_url(urllib.request.Request(url, headers=headers)) as response:
        if hashlib.sha256(response.read()).digest() != hashlib.sha256(path.read_bytes()).digest():
            raise RuntimeError('Published package checksum mismatch')
    public = PUBLIC_API + '/packages/generic/wallettracker-android/' + tag[1:] + '/' + path.name
    with open_url(urllib.request.Request(public, headers=headers)) as response:
        if hashlib.sha256(response.read()).digest() != hashlib.sha256(path.read_bytes()).digest():
            raise RuntimeError('Public release download checksum mismatch')
    return {'name': path.name, 'url': public, 'direct_asset_path': '/' + path.name}


def resume_package(root, sdk, tag, headers):
    """Owner-authorized metadata retry: no Vault login, key access or re-signing."""
    expected = os.environ['CI_PIPELINE_ID'] + ':' + os.environ['CI_COMMIT_SHA'] + ':' + tag
    if os.environ.get('RELEASE_RESUME_VERIFIED_PACKAGE') != expected:
        raise RuntimeError('Resume approval does not match exact pipeline/source/tag')
    output = root / 'artifacts'
    output.mkdir()
    names = ['WalletTracker-' + tag + '.apk', 'WalletTracker-' + tag + '.apk.sha256', 'release-receipt.json']
    for name in names:
        url = API + '/packages/generic/wallettracker-android/' + tag[1:] + '/' + name
        with open_url(urllib.request.Request(url, headers=headers)) as response:
            raw = response.read(32 * 1024 * 1024 + 1)
        if len(raw) > 32 * 1024 * 1024:
            raise RuntimeError('Existing release file exceeds bounds')
        (output / name).write_bytes(raw)
    receipt = json.loads((output / 'release-receipt.json').read_text())
    target = output / names[0]
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    unsigned = root / 'app/build/outputs/apk/release/app-release-unsigned.apk'
    if (receipt.get('source_revision') != os.environ['CI_COMMIT_SHA']
            or receipt.get('pipeline') != os.environ['CI_PIPELINE_ID'] or receipt.get('tag') != tag
            or receipt.get('signer_sha256') != SIGNER or receipt.get('apk_sha256') != digest
            or receipt.get('unsigned_sha256') != hashlib.sha256(unsigned.read_bytes()).hexdigest()
            or (output / names[1]).read_text().split()[0] != digest):
        raise RuntimeError('Existing signed package source/signature/digest receipt mismatch')
    env = {key: value for key, value in os.environ.items() if key in ['PATH', 'JAVA_HOME', 'HOME', 'LANG']}
    verify_signature(target, sdk, env)
    verify_apk(target, sdk, env, tag[1:])
    print(json.dumps({'existing_signed_package_verified': True, 'apk_sha256': digest, 'signing_repeated': False}))
    return output


def existing_links(tag, output, headers):
    links = []
    for path in sorted(output.iterdir()):
        url = PUBLIC_API + '/packages/generic/wallettracker-android/' + tag[1:] + '/' + path.name
        with open_url(urllib.request.Request(url, headers=headers)) as response:
            if hashlib.sha256(response.read()).digest() != hashlib.sha256(path.read_bytes()).digest():
                raise RuntimeError('Existing public release bytes differ')
        links.append({'name': path.name, 'url': url, 'direct_asset_path': '/' + path.name})
    return links


def ensure_absent(tag, headers):
    names = ['WalletTracker-' + tag + '.apk', 'WalletTracker-' + tag + '.apk.sha256', 'release-receipt.json']
    urls = [API + '/releases/' + tag] + [API + '/packages/generic/wallettracker-android/'
            + tag[1:] + '/' + name for name in names]
    for url in urls:
        try:
            with open_url(urllib.request.Request(url, method='HEAD', headers=headers)):
                raise RuntimeError('Release or package file already exists; overwrite refused')
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise RuntimeError('Cannot prove release destination is unused') from None


def main():
    root = Path(os.environ['CI_PROJECT_DIR'])
    tag = identity(root, os.environ)
    verify_backend()
    headers = {'JOB-TOKEN': os.environ['CI_JOB_TOKEN']}
    sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0'
    if os.environ.get('RELEASE_RESUME_VERIFIED_PACKAGE'):
        output = resume_package(root, sdk, tag, headers)
        links = existing_links(tag, output, headers)
    else:
        ensure_absent(tag, headers)
        vault = Vault('wallettracker-android-gitlab-prod')
        try:
            output = sign(root, sdk, tag, vault)
        finally:
            vault.close()
        links = [upload(tag, path, headers) for path in sorted(output.iterdir())]
    existing = None
    if os.environ.get('RELEASE_RESUME_VERIFIED_PACKAGE'):
        try:
            existing = request(API + '/releases/' + tag, headers=headers)
        except RuntimeError as error:
            if 'HTTP 404' not in str(error):
                raise
    if existing is None:
        request(API + '/releases', 'POST', {'tag_name': tag, 'name': 'WalletTracker ' + tag[1:],
                'description': (root / 'docs/releases' / (tag[1:] + '.md')).read_text(),
                'assets': {'links': links}}, headers)
    else:
        actual = {(link['name'], link['url']) for link in existing['assets']['links']}
        if actual != {(link['name'], link['url']) for link in links} or existing['commit']['id'] != os.environ['CI_COMMIT_SHA']:
            raise RuntimeError('Existing release metadata differs; overwrite refused')
    result = request(API + '/releases/' + tag, headers=headers)
    if sorted(link['name'] for link in result['assets']['links']) != sorted(link['name'] for link in links):
        raise RuntimeError('Published release links differ from verified package files')
    print(json.dumps({'release_published': True, 'tag': tag, 'verified_files': len(links)}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'release_failed': True, 'error_type': type(error).__name__,
                          'reason': str(error) if type(error) is RuntimeError else 'Sensitive diagnostics withheld'}))
        raise SystemExit(1)
