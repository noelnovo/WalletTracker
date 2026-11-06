"""GitHub-only source guards; Vault independently validates the job's OIDC claims."""
import re

REPOSITORY = 'noelnovo/WalletTracker'
REPOSITORY_ID = '909094292'
OWNER_ID = '128699907'
WORKFLOW = '.github/workflows/android.yml'


def require_repository(env):
    expected = {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': REPOSITORY,
                'GITHUB_REPOSITORY_ID': REPOSITORY_ID, 'GITHUB_REPOSITORY_OWNER_ID': OWNER_ID}
    if any(env.get(name) != value for name, value in expected.items()):
        raise RuntimeError('Signing requires the approved GitHub repository identity')
    ref = env.get('GITHUB_REF', '')
    if env.get('GITHUB_WORKFLOW_REF') != REPOSITORY + '/' + WORKFLOW + '@' + ref:
        raise RuntimeError('The reviewed GitHub workflow identity is required')
    if env.get('GITHUB_EVENT_NAME') not in ('push', 'workflow_dispatch'):
        raise RuntimeError('Untrusted pull-request events cannot use signing credentials')
    if not re.fullmatch('[a-f0-9]{40}', env.get('GITHUB_SHA', '')):
        raise RuntimeError('A verified GitHub source revision is required')


def require_dev(env):
    require_repository(env)
    if env.get('GITHUB_REF') != 'refs/heads/dev' or env.get('GITHUB_REF_TYPE') != 'branch':
        raise RuntimeError('Development signing is restricted to the approved dev branch')


def require_production(env):
    require_repository(env)
    if (env.get('GITHUB_REF_TYPE') != 'tag' or not env.get('GITHUB_REF', '').startswith('refs/tags/v')
            or env.get('GITHUB_REF_PROTECTED') != 'true'):
        raise RuntimeError('Production signing requires an owner-protected GitHub version tag')


def build_environment(env):
    """Gradle must not inherit OIDC/Vault/publication or Actions runtime tokens."""
    forbidden = {'VAULT_TOKEN', 'VAULT_ID_TOKEN', 'GH_TOKEN', 'GITHUB_TOKEN',
                 'ACTIONS_ID_TOKEN_REQUEST_TOKEN', 'ACTIONS_ID_TOKEN_REQUEST_URL',
                 'ACTIONS_RUNTIME_TOKEN', 'PROD_KEYSTORE_PASSWORD', 'PROD_KEY_PASSWORD'}
    return {key: value for key, value in env.items() if key not in forbidden}
