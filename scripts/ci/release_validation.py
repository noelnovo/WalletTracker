"""Fail-closed read-only MAIN evidence gate; never approves or invokes a rebuild."""
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from scripts.build.build_dev import NoRedirect
from scripts.build.build_prod import ROOT
from scripts.shared.github_identity import require_production, REPOSITORY, REPOSITORY_ID, VALIDATION_WORKFLOW
from scripts.shared.validated_apk import artifact_name, positive_id

API = 'https://api.github.com/repos/' + REPOSITORY


def api(path, token):
    request = urllib.request.Request(API + path, headers={'Authorization': 'Bearer ' + token,
        'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28',
        'User-Agent': 'WalletTracker-Validation-Evidence'})
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=30) as response:
            return json.load(response)
    except (urllib.error.URLError, ValueError):
        raise RuntimeError('GitHub validation evidence is unavailable; no release authorized') from None


def trusted_run(run, sha):
    return (run.get('head_sha') == sha and run.get('head_branch') == 'main' and run.get('event') == 'push'
            and run.get('path') == VALIDATION_WORKFLOW
            and str(run.get('repository', {}).get('id')) == REPOSITORY_ID
            and str(run.get('head_repository', {}).get('id')) == REPOSITORY_ID)


def verify_completed_run(run, token):
    run_id, attempt = positive_id(run.get('id')), positive_id(run.get('run_attempt'))
    if run.get('status') != 'completed' or run.get('conclusion') != 'success':
        raise RuntimeError('Exact-source MAIN validation did not succeed')
    jobs = api(f'/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100', token)['jobs']
    for name in ('checks', 'unsigned-build', 'security'):
        matches = [job for job in jobs if job.get('name') == name]
        if len(matches) != 1 or matches[0].get('status') != 'completed' or matches[0].get('conclusion') != 'success':
            raise RuntimeError('Required MAIN validation job is absent, skipped, or unsuccessful')
    name = artifact_name(run['head_sha'], run_id, attempt)
    artifacts = api(f'/actions/runs/{run_id}/artifacts?per_page=100', token)['artifacts']
    matches = [artifact for artifact in artifacts if artifact.get('name') == name]
    if len(matches) != 1 or matches[0].get('expired') is not False:
        raise RuntimeError('Exact-attempt unsigned artifact missing/expired; rerun the original MAIN validation')
    artifact = matches[0]
    artifact_id = positive_id(artifact.get('id'))
    origin = artifact.get('workflow_run', {})
    if (origin.get('id') != run_id or origin.get('head_sha') != run['head_sha']
            or origin.get('head_branch') != 'main' or str(origin.get('repository_id')) != REPOSITORY_ID
            or str(origin.get('head_repository_id')) != REPOSITORY_ID):
        raise RuntimeError('Unsigned artifact provenance differs from verified MAIN validation')
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', artifact.get('digest', '')):
        raise RuntimeError('Immutable unsigned artifact digest is unavailable')
    return {'validation_run_id': run_id, 'validation_run_attempt': attempt,
            'validation_artifact_id': artifact_id, 'validation_artifact_name': name}


def resolve(sha, token, *, timeout=900, interval=15):
    query = urllib.parse.urlencode({'branch': 'main', 'event': 'push', 'head_sha': sha, 'per_page': 100})
    deadline = time.monotonic() + timeout
    while True:
        runs = api('/actions/workflows/validation.yml/runs?' + query, token)['workflow_runs']
        matches = [run for run in runs if trusted_run(run, sha)]
        if matches:
            run = max(matches, key=lambda value: positive_id(value.get('id')))
            if run.get('status') == 'completed':
                return verify_completed_run(run, token)
            if run.get('status') not in ('queued', 'in_progress', 'pending', 'requested', 'waiting'):
                raise RuntimeError('MAIN validation requires intervention; release blocked')
        if time.monotonic() >= deadline:
            raise RuntimeError('Successful MAIN validation for the exact release commit is required')
        time.sleep(interval)


def main(env):
    require_production(env)
    ready = json.loads((ROOT / 'config/ci/github-actions-status.json').read_text()).get('vault_signing_ready')
    if ready is not True:
        raise RuntimeError('Existing production signing configuration must be verified')
    token = env.get('GITHUB_TOKEN')
    if not token:
        raise RuntimeError('A read-only Actions token is required for validation evidence')
    evidence = resolve(env['GITHUB_SHA'], token)
    with open(env['GITHUB_OUTPUT'], 'a') as output:
        for key, value in evidence.items():
            output.write(f'{key}={value}\n')
    print(json.dumps({'exact_source_main_validation_verified': True, **evidence}))


if __name__ == '__main__':
    try:
        main(os.environ)
    except Exception as error:
        print('::error::' + (str(error) if isinstance(error, RuntimeError) else 'MAIN evidence could not be verified; release blocked'))
        raise SystemExit(1)
