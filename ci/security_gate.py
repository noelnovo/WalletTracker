"""Job-time SonarQube and Dependency-Track gates. Never log credentials/payloads."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
SCANNER = 'sonarsource/sonar-scanner-cli@sha256:38207bba3d98c8ce6f6c051ea2b9f05a24480da78c9ee5945ceafc3d9de02b88'
SYFT = 'anchore/syft@sha256:03215d0e98b661d0ecbc8575dbc14d65be1743b5de5df6410aecb7e80f95b060'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def request(url, method='GET', data=None, headers=None):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or parsed.username or parsed.password:
        raise RuntimeError('Verified HTTPS without URL credentials is required')
    req = urllib.request.Request(url, method=method,
        data=None if data is None else json.dumps(data).encode(),
        headers={'Content-Type': 'application/json',
                 'User-Agent': 'Mozilla/5.0 DownOps-Security-CI', **(headers or {})})
    try:
        with urllib.request.build_opener(NoRedirect()).open(req, timeout=60) as response:
            raw = response.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        raise RuntimeError('Security service rejected request: HTTP ' + str(error.code)) from None
    except (urllib.error.URLError, ValueError):
        raise RuntimeError('Security service unavailable or invalid response') from None


def config():
    return json.loads((ROOT / 'ci/security.json').read_text())


def trusted_identity(cfg, env):
    if cfg['provider'] == 'github':
        return (env.get('GITHUB_ACTIONS') == 'true'
            and env.get('GITHUB_REPOSITORY') == cfg['repository']
            and env.get('GITHUB_EVENT_NAME') == 'push'
            and env.get('GITHUB_REF') in ['refs/heads/' + b for b in cfg['branches']]
            and env.get('GITHUB_WORKFLOW_REF') == cfg['repository'] + '/.github/workflows/'
                + cfg['workflow'] + '@' + env.get('GITHUB_REF', ''))
    return (env.get('GITLAB_CI') == 'true'
        and env.get('CI_PROJECT_ID') == cfg['project_id']
        and env.get('CI_PROJECT_PATH') == cfg['repository']
        and env.get('CI_PIPELINE_SOURCE') == 'push'
        and env.get('CI_COMMIT_REF_PROTECTED') == 'true'
        and env.get('CI_COMMIT_BRANCH') in cfg['branches'])


def login(cfg):
    if not trusted_identity(cfg, os.environ):
        raise RuntimeError('Only an exact trusted push job can obtain scanner credentials')
    if cfg['provider'] == 'github':
        url = os.environ['ACTIONS_ID_TOKEN_REQUEST_URL']
        if urllib.parse.urlsplit(url).hostname != 'token.actions.githubusercontent.com':
            # GitHub uses geographically sharded *.actions.githubusercontent.com hosts.
            if not (urllib.parse.urlsplit(url).hostname or '').endswith('.actions.githubusercontent.com'):
                raise RuntimeError('Unexpected GitHub OIDC endpoint')
        url += ('&' if '?' in url else '?') + urllib.parse.urlencode({'audience': cfg['audience']})
        jwt = request(url, headers={'Authorization': 'Bearer '
            + os.environ['ACTIONS_ID_TOKEN_REQUEST_TOKEN']})['value']
    else:
        jwt = os.environ['VAULT_ID_TOKEN']
    return request(cfg['vault_url'] + '/v1/auth/' + cfg['auth_mount'] + '/login',
        'POST', {'role': cfg['role'], 'jwt': jwt})['auth']['client_token']


def prepare(cfg):
    if cfg['sbom'] == 'image':
        # Build before reading credentials; no credential is passed to the build.
        revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        branch = os.environ.get('GITHUB_REF_NAME', os.environ.get('CI_COMMIT_BRANCH', ''))
        args = []
        for key, value in cfg.get('build_args_by_branch', {}).get(branch, {}).items():
            args.extend(['--build-arg', key + '=' + value])
        subprocess.run(['docker', 'build', '--label', 'org.opencontainers.image.revision=' + revision,
            '-t', 'security-target:ci', *args, '.'], check=True, cwd=ROOT)
        archive = ROOT / 'security-image.tar'
        subprocess.run(['docker', 'save', '-o', str(archive), 'security-target:ci'], check=True)
        with (ROOT / 'bom.json').open('wb') as output:
            subprocess.run(['docker', 'run', '--rm', '--volume', str(ROOT) + ':/work:ro',
                SYFT, 'docker-archive:/work/security-image.tar', '-o', 'cyclonedx-json'],
                stdout=output, check=True)
        image_id = subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}',
            'security-target:ci'], text=True).strip()
        receipt = {'revision': revision, 'image_id': image_id, 'sha256': file_hash(archive)}
        (ROOT / 'security-image.json').write_text(json.dumps(receipt) + '\n')
    else:
        # The pinned Gradle CycloneDX plugin resolves actual transitive dependencies.
        source = ROOT / 'build/reports/cyclonedx/bom.json'
        (ROOT / 'bom.json').write_bytes(source.read_bytes())
    bom = json.loads((ROOT / 'bom.json').read_text())
    if bom.get('bomFormat') != 'CycloneDX' or not bom.get('components'):
        raise RuntimeError('A nonempty resolved CycloneDX SBOM is required')
    print(json.dumps({'sbom_generated': True, 'components': len(bom['components'])}))


def file_hash(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def reuse():
    receipt = json.loads((ROOT / 'security-image.json').read_text())
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if receipt['revision'] != revision or receipt['sha256'] != file_hash(ROOT / 'security-image.tar'):
        raise RuntimeError('Scanned image source/checksum does not match this publication')
    subprocess.run(['docker', 'load', '-i', str(ROOT / 'security-image.tar')], check=True,
                   capture_output=True)
    raw = subprocess.check_output(['docker', 'image', 'inspect', 'security-target:ci'])
    image = json.loads(raw)[0]
    if (image['Id'] != receipt['image_id']
        or image['Config']['Labels']['org.opencontainers.image.revision'] != revision):
        raise RuntimeError('Scanned image identity does not match this publication')
    print(json.dumps({'exact_scanned_image_reused': True, 'revision': revision}))


def dependency_track(creds, bom, *, wait=time.sleep):
    url = creds['DT_URL'].rstrip('/')
    project = str(uuid.UUID(creds['DT_PROJECT_UUID']))
    headers = {'X-Api-Key': creds['DT_API_KEY']}
    result = request(url + '/api/v1/bom', 'PUT', {
        'project': project, 'bom': base64.b64encode(bom).decode()}, headers)
    token = str(uuid.UUID(result['token']))
    for _ in range(120):
        state = request(url + '/api/v1/event/token/' + token, headers=headers)
        if state.get('processing') is False:
            break
        if state.get('processing') is not True:
            raise RuntimeError('Dependency-Track returned invalid processing state')
        wait(5)
    else:
        raise RuntimeError('Dependency-Track analysis timed out')
    counts = {}
    offset = 0
    while True:
        findings = request(url + '/api/v1/finding/project/' + project
            + '?suppressed=false&offset=' + str(offset) + '&limit=100', headers=headers)
        if not isinstance(findings, list):
            raise RuntimeError('Dependency-Track returned invalid findings')
        for finding in findings:
            severity = finding['vulnerability']['severity']
            counts[severity] = counts.get(severity, 0) + 1
        if len(findings) < 100:
            break
        offset += len(findings)
    metrics = request(url + '/api/v1/metrics/project/' + project + '/current', headers=headers)
    policy_failures = metrics.get('policyViolationsFail')
    if not isinstance(policy_failures, int):
        raise RuntimeError('Dependency-Track policy metrics are unavailable')
    passed = not (counts.get('CRITICAL', 0) or counts.get('HIGH', 0) or policy_failures)
    print(json.dumps({'dependency_track_completed': True, 'findings': counts,
        'policy_failures': policy_failures, 'dependency_track_gate_passed': passed}))
    return passed


def sonar(creds):
    env = dict(os.environ, SONAR_TOKEN=creds['SONAR_TOKEN'],
        SONAR_HOST_URL=creds['SONAR_HOST_URL'])
    result = subprocess.run(['docker', 'run', '--rm',
        '--user', str(os.getuid()) + ':' + str(os.getgid()),
        '--env', 'SONAR_TOKEN', '--env', 'SONAR_HOST_URL',
        '--env', 'SONAR_USER_HOME=/tmp/sonar',
        '--volume', str(ROOT) + ':/usr/src', SCANNER,
        '-Dsonar.projectKey=' + creds['SONAR_PROJECT_KEY'],
        '-Dsonar.qualitygate.wait=true', '-Dsonar.qualitygate.timeout=300'],
        env=env, capture_output=True)
    # Scanner output may contain sensitive diagnostics. Emit fixed categories only.
    text = (result.stdout + result.stderr).decode(errors='replace').lower()
    print(json.dumps({'sonarqube_quality_gate_passed': result.returncode == 0,
        'quality_gate_rejected': 'quality gate status: failed' in text,
        'authentication_failure': 'not authorized' in text or 'authentication failed' in text,
        'analysis_submitted': 'analysis report uploaded' in text}))
    return result.returncode == 0


def scan(cfg):
    bom = (ROOT / 'bom.json').read_bytes()
    if not json.loads(bom).get('components'):
        raise RuntimeError('Refusing to scan an empty SBOM')
    token = login(cfg)
    headers = {'X-Vault-Token': token}
    try:
        def read(service):
            path = 'downops/data/services/' + service + '/clients/' + cfg['instance']
            return request(cfg['vault_url'] + '/v1/' + path, headers=headers)['data']['data']
        dt = read('dependency-track')
        sq = read('sonarqube')
        results = []
        for action in (lambda: dependency_track(dt, bom), lambda: sonar(sq)):
            try:
                results.append(action())
            except Exception as error:
                print(json.dumps({'security_check_failed': True, 'error_type': type(error).__name__}))
                results.append(False)
        if not all(results):
            raise RuntimeError('One or more security gates failed; publication blocked')
    finally:
        request(cfg['vault_url'] + '/v1/auth/token/revoke-self', 'POST', {}, headers)


def main():
    cfg = config()
    if sys.argv[1:] == ['prepare']:
        prepare(cfg)
    elif sys.argv[1:] == ['scan']:
        scan(cfg)
    elif sys.argv[1:] == ['reuse']:
        reuse()
    else:
        raise RuntimeError('Expected prepare, scan, or reuse')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'security_gate_failed': True, 'error_type': type(error).__name__}))
        raise SystemExit(1)
