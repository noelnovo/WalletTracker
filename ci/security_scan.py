"""Real Dependency-Track and SonarQube gates for the android client."""
import base64, json, os, pathlib, subprocess, time
from ci.vault_access import Vault, request

PROJECT_KEY = 'noelpatata_WalletTracker_982bd7e1-4fb1-48a0-aebb-c34cdddfe86a'


def dependency_track(dt):
    headers = {'X-Api-Key': dt['DT_API_KEY']}
    url = dt['DT_URL'].rstrip('/')
    result = request(url + '/api/v1/bom', 'PUT', {
        'project': dt['DT_PROJECT_UUID'],
        'bom': base64.b64encode(pathlib.Path('bom.json').read_bytes()).decode()}, headers)
    token = result['token']
    for _ in range(120):
        value = request(url + '/api/v1/bom/token/' + token, headers=headers)
        if value.get('processing') is False:
            break
        time.sleep(5)
    else:
        raise RuntimeError('Dependency-Track analysis timed out')
    print(json.dumps({'dependency_track_analyzed': True}))


def sonar(sonar):
    env = dict(os.environ, SONAR_TOKEN=sonar['SONAR_TOKEN'], SONAR_HOST_URL=sonar['SONAR_HOST_URL'])
    result = subprocess.run(['docker', 'run', '--rm', '--env', 'SONAR_TOKEN', '--env', 'SONAR_HOST_URL',
                             '--volume', os.getcwd() + ':/usr/src',
                             'sonarsource/sonar-scanner-cli:latest',
                             '-Dsonar.projectKey=' + PROJECT_KEY,
                             '-Dsonar.qualitygate.wait=true', '-Dsonar.qualitygate.timeout=300'],
                            env=env, capture_output=True, timeout=600)
    output = (result.stdout + result.stderr).decode(errors='replace').lower()
    if result.returncode:
        hints = {name: needle in output for name, needle in {
            'quality_gate_rejected': 'quality gate status: failed',
            'unauthorized': 'not authorized',
            'authentication_failure': 'authentication failed',
            'network_failure': 'failed to connect',
            'coverage_path_failure': 'cannot resolve',
            'workdir_failure': 'unable to create',
            'oom': 'outofmemoryerror',
        }.items()}
        print(json.dumps({'sonarqube_failure_categories': hints, 'scanner_exit_code': result.returncode}))
        raise RuntimeError('SonarQube analysis/quality gate failed; diagnostics withheld')
    print(json.dumps({'sonarqube_quality_gate_passed': True}))


def main():
    vault = Vault('wallettracker-android')
    dt = vault.read('downops/data/services/dependency-track/clients/instance-005')
    sonar_creds = vault.read('downops/data/services/sonarqube/clients/instance-005')
    dependency_track(dt)
    sonar(sonar_creds)


if __name__ == '__main__':
    try:
        main()
    except RuntimeError as error:
        print(json.dumps({'security_gate_failed': True, 'safe_error': str(error)}))
        raise SystemExit(1)
