"""Publish the verified GitHub Actions APK using its job-scoped GITHUB_TOKEN."""
import hashlib
import json
import os
import pathlib
import re
import urllib.error
import urllib.parse
import urllib.request
from build_dev import NoRedirect
from build_prod import ROOT, validate_identity, verify_backend

REPOSITORY = 'noelnovo/WalletTracker'
API = 'https://api.github.com/repos/' + REPOSITORY


def github(path, token, data=None, method=None, binary=None, content_type=None):
    url = path if path.startswith('https://uploads.github.com/') else API + path
    headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
               'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'WalletTracker-GitHub-Actions-Release'}
    body = binary if binary is not None else (json.dumps(data).encode() if data is not None else None)
    if body is not None:
        headers['Content-Type'] = content_type or 'application/json'
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=120) as response:
            if response.status == 204:
                return None
            return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404 and method is None and data is None and binary is None:
            return None
        try:
            message = str(json.loads(error.read(8192)).get('message', '')).lower()
        except (ValueError, UnicodeError, AttributeError):
            message = ''
        category = ('integration-permission' if 'resource not accessible by integration' in message else
                    'workflow-permission' if 'workflow' in message else
                    'protected-ref' if 'protected' in message or 'ruleset' in message else
                    'rate-limit' if 'rate limit' in message else 'unclassified')
        request_id = error.headers.get('X-GitHub-Request-Id', '') if error.headers else ''
        if not re.fullmatch('[A-Za-z0-9:-]{1,128}', request_id):
            request_id = 'unavailable'
        raise RuntimeError('GitHub release request rejected (HTTP ' + str(error.code)
                           + '; category=' + category + '; request=' + request_id + '); response withheld') from None


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
        raise RuntimeError('APK receipt does not match the protected GitHub release source')
    if receipt.get('package') != 'win.downops.wallettracker' or receipt.get('signer_matches_previous_release') is not True:
        raise RuntimeError('Production APK signing identity is not verified')
    if (receipt.get('api_version') != 2 or receipt.get('registration_ui_available') is not True
            or receipt.get('registration_authority') != 'backend' or 'registration_enabled' in receipt):
        raise RuntimeError('Production APK configuration mismatch')
    apk = directory / ('WalletTracker-' + tag + '.apk')
    if hashlib.sha256(apk.read_bytes()).hexdigest() != receipt.get('apk_sha256'):
        raise RuntimeError('Production APK checksum mismatch')
    checksum = directory / (apk.name + '.sha256')
    if checksum.read_text().strip() != receipt['apk_sha256'] + '  ' + apk.name:
        raise RuntimeError('Production checksum sidecar differs from the verified APK')
    return apk


def publication_token(env):
    from github_identity import require_repository
    require_repository(env)
    token = env.get('GH_TOKEN') or env.get('GITHUB_TOKEN')
    if not token:
        raise RuntimeError('A job-scoped GitHub Actions token is required for publication')
    return token


def main():
    env = os.environ.copy()
    tag = validate_identity(env)
    verify_backend()
    directory = ROOT / 'delivery/artifacts'
    receipt = json.loads((directory / 'release-receipt.json').read_text())
    apk = validate_artifact(receipt, tag, env['GITHUB_SHA'], directory)
    token = publication_token(env)
    if tag_commit(tag, token) != env['GITHUB_SHA']:
        raise RuntimeError('GitHub release tag differs from the verified Actions source')
    release = github('/releases/tags/' + urllib.parse.quote(tag, safe=''), token)
    if release is None:
        notes = (ROOT / ('delivery/RELEASE-NOTES-' + tag.removeprefix('v') + '.md')).read_text()
        # The existing tag was already resolved/verified; never ask GITHUB_TOKEN
        # to create a different target or modify workflow-bearing refs.
        release = github('/releases', token, {'tag_name': tag,
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
