# GitHub Actions ownership

Owner corrected the target: GitHub Actions builds, signs and publishes APKs.
GitLab is a passive backup mirror, not a workflow runner. This supersedes the
earlier GitLab-pipeline and server-side publication designs.

## Android

`.github/workflows/android.yml` owns checks, compilation, stable DEV APKs,
nonpublishing production signing/upload tests and protected production releases.
`.gitlab-ci.yml` has `workflow: rules: when: never`; it includes no active jobs.

GitHub OIDC authenticates to `https://vault.downops.win` at `github-actions`.
The exact new role plan is `github-actions-vault-plan.json`. An already-authorized
administrator must inspect existing mount trust and configure those two roles;
the current scoped operators cannot administer that mount. Do not recover root,
self-grant, reuse the old broader role or modify signing keys to avoid this gate.

Until trust/role verification, `delivery/github-actions-status.json` keeps
`vault_signing_ready=false`. This reviewed source gate needs no repository-variable
API permission. Secret-free
checks/unsigned compilation and the built-in-token draft upload test can run;
**signing is skipped, not falsely reported as passed**. Then enable the flag and
test DEV plus a new immutable protected `v3.0.0-ci-check.N` tag. Old GitLab test
results are historical and are not GitHub execution proof.

The production tag/environment controls, exact version/main ancestry, unchanged
signer, recovery checkpoint and live backend/API-v2 gates remain in force.
No production app deployment or database migration is part of this CI cutover.

## GitHub Releases

Publication uses the built-in `GITHUB_TOKEN`, exposed only to a separate job with
`contents: write`. Build/sign jobs have `contents: read` and `id-token: write`.
Checkout never persists credentials. Gradle receives no Vault, OIDC, GitHub
publication or Actions runtime tokens. No separate GitHub upload PAT is needed.

`publication-check` creates one owned draft with a tiny synthetic JSON asset,
verifies its digest and deletes only that unchanged draft. It never uploads an
APK or publishes a release. Actual publication rejects validation receipts.

## Backend and backup coordination

GitLab CE19.5.3 has no native pull mirroring. Existing authorized server-side
backup sync can fetch public GitHub and push only explicit fast-forward refs to
GitLab without exporting estate credentials to Actions. Never use force,
deletion or `git push --mirror`; retain unrelated refs and all old credentials.

Backend GitLab MAIN is an ancestor of GitHub MAIN with an identical tree; any
alignment must be verified fast-forward, not reset. Backend Argo still reads the
GitLab repository. Coordinate its public GitHub HTTPS source/allowlist switch
through reviewed GitOps, preserve manual production sync and existing readers
until verification, and do not weaken required `python-tests`/`sonarqube` checks.

Full cutover must also port backend delivery/scan OIDC roles, source writeback
and network access. No missing permission or successful unsigned build may be
presented as proof that those live delivery gates passed.
