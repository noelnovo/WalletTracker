import json
import pathlib
import unittest
from ci_test_support import github_environment
from github_identity import require_repository, require_dev, require_production, build_environment, signing_values


class GitHubIdentityTests(unittest.TestCase):
    def test_exact_repository_workflow_and_event_are_required(self):
        require_repository(github_environment())
        for change in ({'GITHUB_ACTIONS': 'false'}, {'GITHUB_REPOSITORY_ID': '1'},
                       {'GITHUB_REPOSITORY_OWNER_ID': '1'}, {'GITHUB_REPOSITORY': 'fork/WalletTracker'},
                       {'GITHUB_WORKFLOW_REF': 'other/workflow'}, {'GITHUB_EVENT_NAME': 'pull_request'},
                       {'GITHUB_SHA': 'not-a-source-revision'}):
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                require_repository(github_environment(**change))

    def test_production_requires_protected_tag_but_dev_does_not_read_production(self):
        require_dev(github_environment(ref='refs/heads/dev'))
        require_production(github_environment())
        with self.assertRaises(RuntimeError):
            require_dev(github_environment())
        with self.assertRaises(RuntimeError):
            require_production(github_environment(ref='refs/heads/dev'))
        with self.assertRaises(RuntimeError):
            require_production(github_environment(GITHUB_REF_PROTECTED='false'))

    def test_gradle_receives_no_job_identity_or_production_signing_secrets(self):
        secrets = ('VAULT_TOKEN', 'VAULT_ID_TOKEN', 'GH_TOKEN', 'GITHUB_TOKEN', 'SIGNING_INPUT_JSON',
                   'ACTIONS_ID_TOKEN_REQUEST_TOKEN', 'ACTIONS_ID_TOKEN_REQUEST_URL',
                   'ACTIONS_RUNTIME_TOKEN', 'PROD_KEYSTORE_PASSWORD', 'PROD_KEY_PASSWORD')
        env = dict.fromkeys(secrets, 'synthetic-secret')
        env['DEV_KEYSTORE_PASSWORD'] = 'synthetic-dev-signing-input'
        child = build_environment(env)
        self.assertFalse(any(name in child for name in secrets))
        self.assertIn('DEV_KEYSTORE_PASSWORD', child)

    def test_role_plan_is_narrow_and_does_not_assume_gitlab_claims(self):
        plan = json.loads((pathlib.Path(__file__).parent / 'github-actions-vault-plan.json').read_text())
        for role, settings in plan['roles'].items():
            self.assertEqual(settings['token_type'], 'batch')
            self.assertEqual(settings['token_ttl'], 900)
            self.assertEqual(settings['token_max_ttl'], 900)
            self.assertIs(settings['token_no_default_policy'], True)
            claims = settings['bound_claims']
            self.assertEqual(claims['repository_id'], '909094292')
            self.assertEqual(claims['repository_owner_id'], '128699907')
            self.assertNotIn('ref_protected', claims)
            self.assertNotIn('project_id', claims)
            self.assertEqual(settings['bound_subject'],
                             'repo:noelnovo@128699907/WalletTracker@909094292:environment:' + claims['environment'])
            if role.endswith('-dev'):
                self.assertEqual(claims['environment'], 'development')
                self.assertEqual(claims['ref'], 'refs/heads/dev')
                self.assertEqual(settings['token_policies'], ['wallettracker-android-ci-dev'])
            else:
                self.assertEqual(claims['environment'], 'production')
                self.assertEqual(claims['ref'], 'refs/tags/v*')
                self.assertEqual(settings['token_policies'], ['wallettracker-android-ci-prod'])

    def test_ui_ready_role_bodies_exactly_match_reviewed_plan(self):
        root = pathlib.Path(__file__).parent
        plan = json.loads((root / 'github-actions-vault-plan.json').read_text())
        for suffix in ('dev', 'prod'):
            body = json.loads((root / ('github-actions/role-' + suffix + '.json')).read_text())
            self.assertEqual(body, plan['roles']['wallettracker-mobile-ci-' + suffix])

    def test_signing_input_prefix_and_required_fields_are_fail_closed(self):
        values = {'KEYSTORE_BASE64': 'synthetic-key', 'KEYSTORE_PASSWORD': 'synthetic-password', 'KEY_ALIAS': 'dev'}
        env = {'SIGNING_INPUT_JSON': json.dumps({'DEV_' + key: value for key, value in values.items()})}
        self.assertEqual(signing_values(env, 'DEV')['KEY_ALIAS'], 'dev')
        with self.assertRaises(RuntimeError):
            signing_values(env, 'PROD')
        for raw in ('not-json', '[]', '{}'):
            with self.subTest(raw=raw), self.assertRaises(RuntimeError):
                signing_values({'SIGNING_INPUT_JSON': raw}, 'DEV')


if __name__ == '__main__':
    unittest.main()
