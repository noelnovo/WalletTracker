# GitHub Actions owns MAIN-only releases

GitHub is the authoritative source, workflow runner and APK download host.
GitLab CI is disabled; its repositories are passive fast-forward backups.

## Current release policy

`.github/workflows/android.yml` owns checks, compilation and production releases.
DEV runs secret-free validation and uploads test reports only. The automatic
DEV signed-APK job and check-tag draft/signing jobs were retired after verification.

An exact owner-protected version tag must be on GitHub MAIN. The `release-context`
job verifies version, source and MAIN ancestry before any signing-secret access.
Production signing and publication retain protected-environment owner approval.
No release or signing job can run from a DEV branch or a `-ci-check` tag.

## Verified authentication

GitHub OIDC uses `github-actions` and verified TLS at `https://vault.downops.win`.
The owner applied the corrected role bodies after checking existing trust.
GitHub API confirmed immutable subjects with owner/repository IDs; actual DEV
and production login/read/signing tests succeeded on source `b242d77…`.
Roles, policy names, TTL/max900 and no-default restrictions are recorded in
`github-actions-vault-plan.json`; original signing keys remain unchanged.

Builders consume the Vault action's already-read masked outputs via
`SIGNING_INPUT_JSON`. No duplicate HTTP secret read or exported Vault token is
required. Private JSON and all job tokens are removed before Gradle runs;
production passwords are also withheld from Gradle.

Publication uses built-in job-scoped `GITHUB_TOKEN` with `contents: write`.
No GitHub upload PAT or publication Vault secret is needed. It checks the
existing tag, publishable receipt, unchanged signer, checksums and live production
API-v2 before publishing a verified draft. Validation receipts are rejected.

## Application promotion remains separate

CI-only workflow changes on MAIN do not promote the application or database.
Before the actual APK release, preserve fresh encrypted backup/restore,
write-freeze/checkpoint, schema/data safety and backend/API-v2 compatibility.
See `PRODUCTION-RELEASE.md`; never restart a stale writer after new writes.

## Passive backups and cleanup

GitLab CE has no native pull mirror. Existing authorized server-side sync fetches
public GitHub and pushes explicit fast-forward MAIN/DEV/tag backups. No force,
ref deletion, `--mirror` or estate credential export into GitHub CI is permitted.

Owner clarified cleanup includes only our migration temporaries: preserve
financial recovery backups, GitLab mirrors, keys/data and shared operators.
Inventory processes, dependencies and provenance before deleting files.
Published release assets, Git/LFS history and active old Vault/image paths are
not automatically leftovers. Backend Argo/security/delivery and Harbor target
access must be verified before retiring their old sources.
