"""Exact-source unsigned inputs; no credentials, networking, or self-asserted CI approval."""
import hashlib
import json
import pathlib
import re
from scripts.shared.github_identity import REPOSITORY, REPOSITORY_ID, VALIDATION_WORKFLOW

FILES = {'app-release-unsigned.apk', 'BuildConfig.java', 'validation-receipt.json'}


def positive_id(value):
    if not re.fullmatch(r'[1-9][0-9]*', str(value)) or isinstance(value, bool):
        raise RuntimeError('A verified positive Actions identity is required')
    return int(value)


def artifact_name(sha, run_id, attempt):
    if not re.fullmatch(r'[a-f0-9]{40}', sha):
        raise RuntimeError('An exact validation source revision is required')
    return f'validated-unsigned-{sha}-{positive_id(run_id)}-{positive_id(attempt)}'


def version_code(version):
    major, minor, patch = map(int, version.split('.'))
    return major * 10000 + minor * 100 + patch


def validate_files(directory, *, sha, version, run_id, attempt):
    directory = pathlib.Path(directory)
    if directory.is_symlink() or not directory.is_dir() or {p.name for p in directory.iterdir()} != FILES:
        raise RuntimeError('Validated unsigned input is absent or contains unexpected files')
    if any(p.is_symlink() or not p.is_file() for p in directory.iterdir()):
        raise RuntimeError('Validated unsigned input must contain only regular files')
    receipt = json.loads((directory / 'validation-receipt.json').read_text())
    expected = {'schema': 1, 'purpose': 'unsigned-validation', 'publishable': False,
                'repository': REPOSITORY, 'repository_id': REPOSITORY_ID,
                'source_revision': sha, 'version': version, 'version_code': version_code(version),
                'workflow_ref': REPOSITORY + '/' + VALIDATION_WORKFLOW + '@refs/heads/main',
                'event': 'push', 'ref': 'refs/heads/main', 'run_id': positive_id(run_id),
                'run_attempt': positive_id(attempt)}
    if not isinstance(receipt, dict) or any(receipt.get(k) != v or type(receipt.get(k)) is not type(v)
                                            for k, v in expected.items()):
        raise RuntimeError('Unsigned validation receipt does not match verified MAIN evidence')
    for filename, key in [('app-release-unsigned.apk', 'unsigned_apk_sha256'), ('BuildConfig.java', 'build_config_sha256')]:
        digest = receipt.get(key, '')
        if not isinstance(digest, str) or not re.fullmatch(r'[a-f0-9]{64}', digest):
            raise RuntimeError('Validation input checksum is missing')
        if hashlib.sha256((directory / filename).read_bytes()).hexdigest() != digest:
            raise RuntimeError('Validated unsigned input checksum mismatch')
    return receipt
