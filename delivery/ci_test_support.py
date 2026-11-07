"""Synthetic GitHub job metadata for offline delivery tests."""
from github_identity import REPOSITORY, REPOSITORY_ID, OWNER_ID, WORKFLOW


def github_environment(ref='refs/tags/v3.0.0', **changes):
    return {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': REPOSITORY,
            'GITHUB_REPOSITORY_ID': REPOSITORY_ID, 'GITHUB_REPOSITORY_OWNER_ID': OWNER_ID,
            'GITHUB_REF': ref, 'GITHUB_REF_NAME': ref.split('/', 2)[2],
            'GITHUB_REF_TYPE': 'branch' if ref.startswith('refs/heads/') else 'tag',
            'GITHUB_REF_PROTECTED': 'true', 'GITHUB_SHA': 'a' * 40,
            'GITHUB_WORKFLOW_REF': REPOSITORY + '/' + WORKFLOW + '@' + ref,
            'GITHUB_EVENT_NAME': 'push', **changes}
