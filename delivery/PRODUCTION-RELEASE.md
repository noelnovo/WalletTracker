# WalletTracker production release — developer guide

GitHub is the authoritative Android source, CI runner and APK download host.
GitLab is a passive source/tag backup with CI disabled. The active workflow is
[`android.yml`](../.github/workflows/android.yml).

**One production approval:** approve before signing; publication of the verified
artifact then runs automatically in a separate least-privilege job.

## Who can do what?

| Role | Responsibility | Infrastructure access needed? |
| --- | --- | --- |
| Android developer | Submit code, version and release notes; pass CI; open the release PR | No SSH, Kubernetes, database or Vault access |
| Release maintainer | Review/merge the release and create its protected version tag on MAIN | Repository release/tag permission only |
| Production reviewer | Approve the `production` environment once before signing | GitHub review permission only |
| GitHub Actions | Obtain restricted signing inputs, build/verify, then publish | Existing job identities; no developer-held signing credentials |

Current `wallettracker-release-tags` rules protect creation, update and deletion
of `v*` tags, with the configured repository-admin bypass. Production has a named
required reviewer. An ordinary write-only developer cannot complete those two
restricted steps alone. Reading/forking/submitting contributions does not grant
release rights. Assigning additional release rights requires an explicit owner
decision, **not** sharing a keystore, password or Vault token.

## 1. Prepare on DEV

1. Make the Android changes using the usual PR/integration process.
2. Update `majorVersion`, `minorVersion` and `patchVersion` in
   [`app/build.gradle.kts`](../app/build.gradle.kts). They determine both
   `versionName` and `versionCode`; the version code must increase. For example,
   `3.0.1` produces `30001`, newer than `3.0.0`/`30000`.
3. Add `delivery/RELEASE-NOTES-X.Y.Z.md` for that version. The publisher requires
   this file when creating a new release; see
   [`RELEASE-NOTES-3.0.0.md`](RELEASE-NOTES-3.0.0.md) as an example.
4. Check that the client remains compatible with the deployed production API.
   This app currently targets API v2. Backend changes must be released and
   verified separately before publishing a client that requires them.
5. Pass DEV CI. The workflow runs delivery tests, JVM tests, debug compilation
   and unsigned optimized release compilation on Java 21/Android SDK 36.
   No emulator or production signing credentials are needed for these stages.

DEV, PR and ordinary MAIN branch runs validate only. They do not create releases,
production-signed APKs or automatic release drafts. Historical signing-test/check-
tag receipts are not authorization to publish a real release.

## 2. Merge the reviewed release into MAIN

Open a release PR from DEV to MAIN, review its changes and CI results, and merge
it without discarding production-only changes. Preserve the long-lived DEV branch.
Merging/pushing MAIN does not itself publish an APK.

The release maintainer must confirm that MAIN contains the reviewed application
and that its version matches the intended tag. Repository source/tag backups in
GitLab are synchronized with the existing authorized writer; never enable GitLab
CI, force-push or use `git push --mirror` as part of this procedure.

## 3. Create the exact protected tag on MAIN

Run this as an authorized release maintainer, using a clean clone. `GH_REMOTE`
must name the GitHub remote (commonly `origin`, or `github` in the server clone).
The example version is illustrative: it must match the version actually merged.

```sh
GH_REMOTE=origin
VERSION=3.0.1
git fetch --no-tags "$GH_REMOTE" main
git switch main
git merge --ff-only "$GH_REMOTE/main"
git tag -a "v$VERSION" -m "WalletTracker $VERSION"
git push "$GH_REMOTE" "refs/tags/v$VERSION"
```

Do not tag DEV, replace an existing version tag, create a draft manually as a
shortcut, or change tag protections to get a build running. The workflow checks
the exact version tag, protected-ref status, source revision and membership in
GitHub MAIN **before** reading signing secrets. A branch `workflow_dispatch` run
is not a substitute for a protected MAIN version tag.

## 4. Approve once and let CI finish

On the tag's GitHub Actions run:

1. `checks` and `unsigned-build` run without production secrets.
2. `release-context` verifies the protected exact-version MAIN source.
3. `prod-apk` waits for the `production` review. The named reviewer selects
   **Review deployments → production → Approve and deploy**.
4. Signing then runs using the existing production keystore from Vault.
5. `publish-prod` runs automatically after successful signing. It does **not**
   declare another production environment review.

The signing job has `contents: read` and `id-token: write`; it authenticates via
GitHub OIDC to the existing restricted `github-actions` Vault role and reads only
the approved production signing record. Masked `SIGNING_INPUT_JSON` supplies the
builder. Gradle does not inherit signing JSON/passwords or Vault/OIDC/Actions/
publication tokens. Do not toggle readiness flags to conceal an authentication
or signature failure.

The separate publisher has `contents: write`, no Vault/OIDC access, and uses the
ephemeral built-in `GITHUB_TOKEN`. No external publication PAT is needed.

Production registration remains server-controlled and disabled by default.
The APK retains its registration UI so the owner can enable registration
temporarily on the server without rebuilding the app. Showing that UI does not
authorize account creation.

## 5. Verify the published release

The signing/publishing scripts enforce:

- Production package `win.downops.wallettracker`, non-debuggable build, expected
  API URL/version and empty legacy embedded signing secret.
- The original production signing certificate, not a replacement/dev signer.
- A publishable release receipt bound to the exact tag and source revision.
- APK/checksum equality, protected tag resolution and live production API v2.
- Verified uploaded-asset digests before publishing the automatically created
  draft. An existing published asset is never silently overwritten.

Expected assets on `https://github.com/noelnovo/WalletTracker/releases/tag/vX.Y.Z`:

- `WalletTracker-vX.Y.Z.apk`
- `WalletTracker-vX.Y.Z.apk.sha256`
- `release-receipt.json`

Download the APK and its checksum sidecar, then run:

```sh
sha256sum -c WalletTracker-vX.Y.Z.apk.sha256
apksigner verify --verbose --print-certs WalletTracker-vX.Y.Z.apk
```

Use the actual version in place of `X.Y.Z`. The original **public certificate**
SHA-256 fingerprint is:

```text
60b988457cca109d8f3a1f3ad288f95648f9938990aeedff0dfc5ed479952b61
```

This is a public certificate fingerprint, not a private key or password. CI
success and signature checks do not imply that a real mobile-device smoke test
was performed; test installation/login/sync separately when required.

## Troubleshooting and safe retries

| Symptom | Action |
| --- | --- |
| Only checks/unsigned compilation run | Branch runs do not release; verify the protected version tag, MAIN membership and signing-readiness gate |
| `prod-apk` is waiting | The configured production reviewer must approve in GitHub; do not share credentials or bypass the environment |
| Review API returns 403 | The caller/token may lack `deployments:write`; use the review UI rather than broadening an automation credential |
| Vault authentication/claims fail | Stop and have an authorized administrator compare the existing role/trust with actual job claims; no root recovery, self-grant or signing-key rotation |
| Backend API-v2 readiness/protection check fails | Coordinate the backend owner; an Android release cannot update the backend/database |
| Signing certificate/checksum differs | Stop; never substitute a debug/dev key or overwrite a published asset |
| Draft remains after upload failure | Preserve it for inspection; verify the exact artifact/source/digests before a reviewed retry |
| Retry fails to download an artifact | Old CI artifacts may expire or be cleaned up; do not fabricate a receipt or weaken artifact checks |

A rerun uses the workflow frozen at its original tag. For example, published
`v3.0.0` historically required two reviews; future tags from the updated MAIN
use one. Do not move a published tag to retrofit workflow changes. Rebuilds may
produce different APK bytes: immutable existing-asset checks still apply.

## Backend/database boundary

An Android-only release does **not** deploy the backend, run Alembic, reset/import
database data, create backups or require a backup/restore checkpoint. Production
API v2 is already deployed. Any later backend/schema change belongs to its own
explicitly reviewed release procedure; the old first-cutover backup/restore
instructions are not prerequisites for routine APK releases.
