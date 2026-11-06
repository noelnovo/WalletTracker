"""Publish the verified GitLab APK to GitHub using a separate repo-scoped identity."""
import hashlib
import json
import os
import pathlib
import urllib.error
import urllib.parse
import urllib.request
from build_dev import NoRedirect, request as vault_request
from build_prod import ROOT, validate_identity, verify_backend

REPOSITORY = 'noelnovo/WalletTracker'
API = 'https://api.github.com/repos/' + REPOSITORY
TOKEN_RECORD = 'downops/data/apps/wallettracker/delivery/github-release'


def github(path, token, data=None, method=None, binary=None, content_type=None):
    url = path if path.startswith('https://uploads.github.com/') else API + path
    headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
               'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'WalletTracker-GitLab-Release'}
    body = binary if binary is not None else (json.dumps(data).encode() if data is not None else None)
    if body is not None:
        headers['Content-Type'] = content_type or 'application/json'
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=120) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404 and method is None and data is None and binary is None:
            return None
        raise RuntimeError('GitHub release request rejected (HTTP ' + str(error.code) + '); response withheld') from None


def tag_commit(tag, token):
    reference = github('/git/ref/tags/' + urllib.parse.quote(tag, safe=''), token)
    if not reference:
        raise RuntimeError('The approved release tag must already be mirrored to GitHub')
    obj = reference['object']
    for _ in range(4):
        if obj['type'] == 'commit':
            return obj['sha']
        if obj['type'] != 'tag':
            break
        obj = github('/git/tags/' + obj['sha'], token)['object']
    raise RuntimeError('Unexpected release tag object')


def validate_artifact(receipt, tag, sha, directory):
    if receipt.get('purpose') != 'release' or receipt.get('publishable') is not True:
        raise RuntimeError('A signing-validation artifact can never authorize publication')
    if receipt.get('tag') != tag or receipt.get('source_revision') != sha:
        raise RuntimeError('APK receipt does not match the protected release source')
    if receipt.get('package') != 'win.downops.wallettracker' or receipt.get('signer_matches_previous_release') is not True:
        raise RuntimeError('Production APK signing identity is not verified')
    if receipt.get('api_version') != 2 or receipt.get('registration_enabled') is not False:
        raise RuntimeError('Production APK configuration mismatch')
    apk = directory / ('WalletTracker-' + tag + '.apk')
    if hashlib.sha256(apk.read_bytes()).hexdigest() != receipt.get('apk_sha256'):
        raise RuntimeError('Production APK checksum mismatch')
    checksum = directory / (apk.name + '.sha256')
    if checksum.read_text().strip() != receipt['apk_sha256'] + '  ' + apk.name:
        raise RuntimeError('Production checksum sidecar differs from the verified APK')
    return apk


def main():
    env = os.environ.copy()
    if env.get('GITHUB_RELEASE_READY') != 'true':
        raise RuntimeError('GitLab publication requires the verified repository-scoped credential gate')
    tag = validate_identity(env)
    verify_backend()
    directory = ROOT / 'delivery/artifacts'
    receipt = json.loads((directory / 'release-receipt.json').read_text())
    apk = validate_artifact(receipt, tag, env['CI_COMMIT_SHA'], directory)
    auth = vault_request('auth/gitlab-ci/login', {'role': 'wallettracker-android-publish-prod', 'jwt': env['VAULT_ID_TOKEN']})
    values = vault_request(TOKEN_RECORD, token=auth['auth']['client_token'])['data']['data']
    token = values.get('GITHUB_RELEASE_TOKEN')
    if not token:
        raise RuntimeError('Repository-scoped GitHub publication credential is missing')
    if tag_commit(tag, token) != env['CI_COMMIT_SHA']:
        raise RuntimeError('GitHub release tag differs from the verified GitLab source')
    release = github('/releases/tags/' + urllib.parse.quote(tag, safe=''), token)
    if release is None:
        notes = (ROOT / ('delivery/RELEASE-NOTES-' + tag.removeprefix('v') + '.md')).read_text()
        release = github('/releases', token, {'tag_name': tag, 'target_commitish': env['CI_COMMIT_SHA'],
            'name': 'WalletTracker ' + tag.removeprefix('v'), 'body': notes, 'draft': True, 'prerelease': False})
    files = [apk, directory / (apk.name + '.sha256'), directory / 'release-receipt.json']
    existing = {asset['name']: asset for asset in release.get('assets', [])}
    for file in files:
        payload = file.read_bytes()
        digest = 'sha256:' + hashlib.sha256(payload).hexdigest()
        if file.name in existing:
            if existing[file.name].get('digest') != digest:
                raise RuntimeError('Existing GitHub release asset differs; refusing to overwrite user work')
            continue
        if not release.get('draft'):
            raise RuntimeError('Published release is incomplete; manual review required before adding assets')
        content_type = 'application/vnd.android.package-archive' if file.suffix == '.apk' else 'application/octet-stream'
        url = release['upload_url'].split('{', 1)[0] + '?' + urllib.parse.urlencode({'name': file.name})
        uploaded = github(url, token, method='POST', binary=payload, content_type=content_type)
        if uploaded.get('digest') != digest:
            raise RuntimeError('GitHub uploaded-asset digest is unverified; draft retained')
    if release.get('draft'):
        release = github('/releases/' + str(release['id']), token, {'draft': False, 'prerelease': False, 'make_latest': 'true'}, method='PATCH')
    print(json.dumps({'published': True, 'release_url': release['html_url'], 'tag': tag, 'apk_sha256': receipt['apk_sha256']}))


if __name__ == '__main__':
    main()
