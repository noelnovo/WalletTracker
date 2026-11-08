"""Test GitHub Actions' built-in token upload using an owned disposable draft."""
import hashlib
import json
import os
import re
import urllib.parse
from scripts.build.build_prod import ROOT, validate_check_identity
from scripts.release.publish_prod import github, publication_token, tag_commit


def main():
    env = os.environ.copy()
    tag = validate_check_identity(env)
    sha = env['GITHUB_SHA']
    job = env.get('GITHUB_RUN_ID', '')
    if not re.fullmatch('[1-9][0-9]*', job):
        raise RuntimeError('A verified GitHub Actions run identity is required')
    token = publication_token(env)
    if tag_commit(tag, token) != sha:
        raise RuntimeError('GitHub check tag differs from the verified Actions source')
    if github('/releases/tags/' + urllib.parse.quote(tag, safe=''), token) is not None:
        raise RuntimeError('A release already exists for this test tag; refusing to overwrite it')
    marker = 'Nonpublishing WalletTracker CI upload check; job=' + job + '; source=' + sha
    receipt = {'tag': tag, 'source_revision': sha, 'job_id': job, 'purpose': 'publication-validation',
               'published': False, 'contains_apk': False, 'publishable': False,
               'asset_digest_verified': False, 'owned_draft_deleted': False}
    payload = json.dumps(receipt, sort_keys=True).encode()
    digest = 'sha256:' + hashlib.sha256(payload).hexdigest()
    release = github('/releases', token, {'tag_name': tag,
        'name': 'WalletTracker CI upload check ' + tag, 'body': marker,
        'draft': True, 'prerelease': True, 'make_latest': 'false'})
    if release.get('draft') is not True or release.get('tag_name') != tag or not isinstance(release.get('id'), int):
        raise RuntimeError('Unexpected draft identity; inspect GitHub before any retry')
    release_path = '/releases/' + str(release['id'])
    try:
        url = release['upload_url'].split('{', 1)[0] + '?' + urllib.parse.urlencode({'name': 'publication-check.json'})
        uploaded = github(url, token, method='POST', binary=payload, content_type='application/json')
        if uploaded.get('digest') != digest:
            raise RuntimeError('Uploaded validation asset digest differs; no publication is permitted')
        receipt['asset_digest_verified'] = True
    finally:
        current = github(release_path, token)
        if (not current or current.get('draft') is not True or current.get('tag_name') != tag
                or current.get('body') != marker):
            raise RuntimeError('Owned draft changed; refusing destructive cleanup of user work')
        github(release_path, token, method='DELETE')
        if github(release_path, token) is not None:
            raise RuntimeError('Draft cleanup could not be verified')
        receipt['owned_draft_deleted'] = True
    directory = ROOT / 'artifacts/signing-check'
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'publication-check-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('GitHub Actions upload/digest/owned-draft cleanup verified; nothing published')


if __name__ == '__main__':
    main()
