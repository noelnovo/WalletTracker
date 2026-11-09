"""Trusted-base PR controller. PR files/artifacts are data, never privileged code."""
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

import security_gate

REPOSITORY = 'noelnovo/WalletTracker'
REPOSITORY_ID = 909094292
API = 'https://api.github.com/repos/' + REPOSITORY
TRUSTED_ROOT = Path(__file__).resolve().parents[1]
MAX_DOWNLOAD = 32 * 1024 * 1024
MAX_EXPANDED = 128 * 1024 * 1024
MAX_FILES = 20000
SHA = re.compile(r'[0-9a-f]{40}\Z')


class CandidatePending(RuntimeError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def http(url, *, token=None, data=None, binary=False):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or parsed.username or parsed.password:
        raise RuntimeError('HTTPS is required')
    headers = {'User-Agent': 'DownOps-Trusted-Premerge', 'Accept': 'application/vnd.github+json'}
    if token:
        if parsed.hostname != 'api.github.com':
            raise RuntimeError('Refusing to send GitHub credentials to another host')
        headers['Authorization'] = 'Bearer ' + token
    if data is not None:
        headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(url, headers=headers,
        data=None if data is None else json.dumps(data).encode())
    try:
        with urllib.request.build_opener(NoRedirect()).open(req, timeout=60) as response:
            raw = response.read(MAX_DOWNLOAD + 1)
    except urllib.error.HTTPError as error:
        if binary and error.code == 302:
            location = error.headers['Location']
            host = urllib.parse.urlsplit(location).hostname or ''
            if not (host == 'codeload.github.com' or host.endswith('.blob.core.windows.net')
                    or host.endswith('.actions.githubusercontent.com')):
                raise RuntimeError('Unexpected artifact download host') from None
            # Signed download URL only: never forward the GitHub bearer token.
            return http(location, binary=True)
        raise RuntimeError('GitHub request failed: HTTP ' + str(error.code)) from None
    if len(raw) > MAX_DOWNLOAD:
        raise RuntimeError('Download exceeded the bounded input size')
    return raw if binary else (json.loads(raw) if raw else {})


def api(path, data=None):
    return http(API + path, token=os.environ['GITHUB_TOKEN'], data=data)


def sha(value):
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise RuntimeError('Invalid commit identity')
    return value


def event_identity(event, env):
    if (env.get('GITHUB_EVENT_NAME') != 'pull_request_target'
        or env.get('GITHUB_REPOSITORY') != REPOSITORY
        or event.get('repository', {}).get('id') != REPOSITORY_ID):
        raise RuntimeError('Trusted target-repository PR event required')
    pr = event['pull_request']
    target = pr['base']['ref']
    if target not in ['dev', 'main'] or pr['base']['repo']['id'] != REPOSITORY_ID:
        raise RuntimeError('Unexpected PR target')
    number = event['number']
    if type(number) is not int or number <= 0:
        raise RuntimeError('Invalid PR number')
    return {'number': number, 'head': sha(pr['head']['sha']), 'base': sha(pr['base']['sha']),
        'target': target, 'context': 'security/sonarqube-dtrack-' + target}


def current_candidate(identity):
    pr = api('/pulls/' + str(identity['number']))
    if (pr['state'] != 'open' or pr['head']['sha'] != identity['head']
        or pr['base']['sha'] != identity['base'] or pr['base']['ref'] != identity['target']
        or pr['base']['repo']['id'] != REPOSITORY_ID):
        raise RuntimeError('PR head or base changed; a new run is required')
    if pr.get('mergeable') is False:
        raise RuntimeError('Conflicting PR cannot satisfy pre-merge security')
    if not pr.get('merge_commit_sha'):
        raise CandidatePending('GitHub has not prepared a merge candidate yet')
    candidate = sha(pr['merge_commit_sha'])
    commit = api('/git/commits/' + candidate)
    parents = [p['sha'] for p in commit['parents']]
    if parents != [identity['base'], identity['head']]:
        raise RuntimeError('Merge candidate does not match the exact PR head/base')
    return candidate


def trusted_controller(env):
    # pull_request_target executes the default-main control plane, even when
    # the PR targets DEV. The target base is validated separately above.
    controller = sha(env.get('GITHUB_SHA'))
    workflow_sha = sha(env.get('GITHUB_WORKFLOW_SHA'))
    allowed = {REPOSITORY + '/.github/workflows/premerge-security.yml@refs/heads/' + b
               for b in ['dev', 'main']}
    print(json.dumps({'controller_identity': {'controller_sha': controller,
        'workflow_sha': workflow_sha, 'workflow_ref_expected': env.get('GITHUB_WORKFLOW_REF') in allowed,
        'event_ref_expected': env.get('GITHUB_REF') in ['refs/heads/dev', 'refs/heads/main']}}))
    if (env.get('GITHUB_WORKFLOW_REF') not in allowed
        or env.get('GITHUB_REF') not in ['refs/heads/dev', 'refs/heads/main']
        or workflow_sha != controller):
        raise RuntimeError('Unexpected trusted workflow identity')
    if api('/branches/main')['commit']['sha'] != controller:
        raise RuntimeError('Trusted default controller changed; a new run is required')
    print(json.dumps({'trusted_controller_verified': True, 'controller_sha': controller,
        'workflow_ref': env['GITHUB_WORKFLOW_REF'], 'event_ref': env['GITHUB_REF']}))
    return controller


def publish(identity, state):
    if state not in ['pending', 'success', 'failure']:
        raise RuntimeError('Unsupported status')
    run = int(os.environ['GITHUB_RUN_ID'])
    api('/statuses/' + identity['head'], {
        'state': state, 'context': identity['context'],
        'description': {'pending': 'Waiting for exact-candidate build and security analysis',
            'success': 'Exact candidate passed the SonarQube/Dependency-Track gate',
            'failure': 'Security failed or evidence was stale, missing, or invalid'}[state],
        'target_url': 'https://github.com/' + REPOSITORY + '/actions/runs/' + str(run)})


def safe_path(name):
    path = PurePosixPath(name)
    if (not name or path.is_absolute() or '..' in path.parts or '\\' in name or '\x00' in name):
        raise RuntimeError('Unsafe archive path')
    return path


def unpack_source(data, destination):
    total = 0
    count = 0
    prefix = None
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
        for member in archive:
            path = safe_path(member.name)
            if prefix is None:
                prefix = path.parts[0]
            if path.parts[0] != prefix:
                raise RuntimeError('Unexpected source archive root')
            relative = PurePosixPath(*path.parts[1:])
            if not relative.parts:
                continue
            if not member.isfile() and not member.isdir():
                raise RuntimeError('Links/devices are forbidden in source inputs')
            if (relative.parts[0] in ['.git', '.scannerwork', 'build']
                or relative.parts[:2] == ('app', 'build')
                or str(relative) in ['sonar-project.properties', 'bom.json']):
                continue
            target = destination.joinpath(*relative.parts)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            count += 1
            total += member.size
            if count > MAX_FILES or total > MAX_EXPANDED:
                raise RuntimeError('Expanded source exceeded bounds')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.extractfile(member).read())
            target.chmod(0o600)


def unpack_build(data, destination):
    total = 0
    classes = 0
    sbom = False
    seen = set()
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if len(archive.infolist()) > MAX_FILES:
            raise RuntimeError('Too many artifact entries')
        for member in archive.infolist():
            path = safe_path(member.filename)
            mode = (member.external_attr >> 16) & 0o170000
            if mode not in [0, stat.S_IFREG, stat.S_IFDIR]:
                raise RuntimeError('Artifact links/devices are forbidden')
            if member.is_dir():
                continue
            name = str(path)
            allowed = (name == 'build/reports/cyclonedx/bom.json'
                or (name.startswith(('app/build/intermediates/javac/', 'app/build/tmp/kotlin-classes/',
                                     'app/build/intermediates/built_in_kotlinc/'))
                    and name.endswith(('.class', '.jar', '.kotlin_module'))))
            if not allowed or name in seen:
                raise RuntimeError('Unexpected or duplicate build artifact path')
            seen.add(name)
            total += member.file_size
            if total > MAX_EXPANDED:
                raise RuntimeError('Expanded build artifact exceeded bounds')
            target = destination.joinpath(*path.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(member))
            target.chmod(0o600)
            classes += name.endswith('.class')
            sbom |= name == 'build/reports/cyclonedx/bom.json'
    if not classes or not sbom:
        raise RuntimeError('Compiled classes and a resolved SBOM are required')


def artifact_for_run(candidate):
    run_id = int(os.environ['GITHUB_RUN_ID'])
    attempt = int(os.environ['GITHUB_RUN_ATTEMPT'])
    name = 'premerge-security-' + candidate + '-' + str(attempt)
    artifacts = api('/actions/runs/' + str(run_id) + '/artifacts?per_page=100')['artifacts']
    matches = [a for a in artifacts if a['name'] == name and not a['expired']]
    if len(matches) != 1:
        raise RuntimeError('Exactly one same-run immutable build artifact is required')
    artifact = matches[0]
    if artifact.get('workflow_run', {}).get('id') != run_id:
        raise RuntimeError('Artifact originated in another workflow run')
    digest = artifact.get('digest', '')
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', digest):
        raise RuntimeError('Artifact digest unavailable')
    data = http(API + '/actions/artifacts/' + str(int(artifact['id'])) + '/zip',
        token=os.environ['GITHUB_TOKEN'], binary=True)
    if 'sha256:' + hashlib.sha256(data).hexdigest() != digest:
        raise RuntimeError('Build artifact digest mismatch')
    return data


def prepare(identity):
    trusted_controller(os.environ)
    publish(identity, 'pending')
    candidate = None
    for attempt in range(5):
        try:
            candidate = current_candidate(identity)
            break
        except CandidatePending:
            if attempt == 4:
                raise RuntimeError('Merge candidate unavailable') from None
            time.sleep(2)
    if candidate is None:
        raise RuntimeError('Merge candidate unavailable')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write('candidate=' + candidate + '\n')
    print(json.dumps({'premerge_candidate_verified': True, 'candidate': candidate}))


def evaluate(identity):
    if os.environ.get('PREMERGE_BUILD_RESULT') != 'success':
        raise RuntimeError('Failed, cancelled, or skipped build cannot satisfy security')
    trusted_controller(os.environ)
    candidate = sha(os.environ['PREMERGE_CANDIDATE'])
    if current_candidate(identity) != candidate:
        raise RuntimeError('Merge candidate changed before analysis')
    data = artifact_for_run(candidate)
    source = http('https://codeload.github.com/' + REPOSITORY + '/tar.gz/' + candidate, binary=True)
    cfg = json.loads((TRUSTED_ROOT / 'ci/premerge-security.json').read_text())
    properties = (TRUSTED_ROOT / 'ci/premerge-sonar.properties').read_text()
    with tempfile.TemporaryDirectory(prefix='wallet-premerge-', dir=os.environ.get('RUNNER_TEMP')) as directory:
        work = Path(directory)
        unpack_source(source, work)
        unpack_build(data, work)
        # Ignore PR-controlled scanner settings, helpers, actions and hooks.
        (work / 'sonar-project.properties').write_text(properties + '\nsonar.scm.revision=' + candidate + '\n')
        security_gate.ROOT = work
        security_gate.prepare(cfg)
        security_gate.scan(cfg)
    trusted_controller(os.environ)
    if current_candidate(identity) != candidate:
        raise RuntimeError('PR or base changed during analysis; success refused')
    publish(identity, 'success')
    print(json.dumps({'premerge_security_passed': True, 'candidate': candidate}))


def main():
    identity = None
    try:
        event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
        identity = event_identity(event, os.environ)
        if sys.argv[1:] == ['prepare']:
            prepare(identity)
        elif sys.argv[1:] == ['gate']:
            evaluate(identity)
        else:
            raise RuntimeError('Expected prepare or gate')
    except Exception as error:
        if identity:
            try:
                publish(identity, 'failure')
            except Exception:
                pass  # Missing/pending status remains blocking, never green.
        reasons = {'Unexpected trusted workflow identity': 'workflow_identity_mismatch',
            'Trusted default controller changed; a new run is required': 'stale_controller',
            'PR head or base changed; a new run is required': 'stale_pr',
            'Merge candidate does not match the exact PR head/base': 'candidate_parent_mismatch',
            'Failed, cancelled, or skipped build cannot satisfy security': 'producer_not_successful',
            'SonarQube quality gate failed; publication blocked': 'quality_gate_rejected'}
        reason = reasons.get(str(error), 'details_withheld') if isinstance(error, RuntimeError) else 'details_withheld'
        print(json.dumps({'premerge_security_failed': True, 'error_type': type(error).__name__,
            'failure_reason': reason}))
        raise SystemExit(1)


if __name__ == '__main__':
    main()
