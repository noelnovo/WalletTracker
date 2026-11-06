# WalletTracker 3 production release

Owner requested production promotion, a dev-to-main merge, GitHub release APK,
and synchronized GitLab/GitHub repositories. The owner also approved **one fresh
encrypted off-cluster MariaDB backup and restore verification**; no application
backup schedule, Kubernetes backup Job or backup PVC may be added.

## Required order — no publication shortcut

1. Recheck current operations receipts instead of repeating completed staging.
   Separate production operator `run-prod` is available. Production records and
   additive reader grants were staged and verified; recovery is closed.
2. Verify canonical production Vault records against their current source values.
   Preserve DB passwords, server RSA identity and the production APK signing key.
   `production-signing-vault-plan.json` scopes the protected-tag APK reader.
   If its public certificate differs from the verified v2.2.2 APK, stop; do not
   sign a replacement, force uninstall, or silently use the dev key.
3. Test the internal legacy phone-data upgrade, including invalid-row review,
   encrypted recovery, account consent and non-destructive v2-cache schema upgrade.
   This is not a CSV/file-import feature. Old plaintext token/client-key fields
   are retired; existing biometric credentials support one authenticated upgrade
   and are then replaced by the new wrapped refresh credential.
4. Reconcile production-only commits into the release source without discarding
   production image/state changes. Open reviewed dev-to-main release MRs on GitLab;
   preserve long-lived dev. Mirror exact approved main/tag history to GitHub.
5. On an exact protected Android version tag descended from synchronized main,
   stage the signed APK with GitLab `prod-apk` before entering maintenance.
   It checks source/mirror equality, previous release checksum and unchanged
   signing identity. It does not publish; live API-v2 is gated at publication.
6. Immediately before production schema changes, establish a fresh, verified
   off-cluster recovery checkpoint and an approved write-freeze/cutover window.
   The old static migration checkpoint is not evidence of a new backup. Do not
   copy production data into dev, restart a stale writer, or automatically stamp
   existing unversioned databases.
7. Deliver the backend API-v2 upgrade through approved protected-tag/manual prod
   GitLab/Argo flow. Keep registration disabled. Prevent divergent v1/v2 writers;
   arrange the app update/cutover together. Verify actual Ready image/generation,
   migration heads, retained data, HTTPS readiness and authenticated API semantics.
8. The staged APK/checksum/receipt must remain unchanged after approval; the dev
   key is never a production input. Shared2GB runner capacity must be sufficient
   and isolated, not competing with heavy backend jobs.
9. GitLab `publish-prod` uses a separate repository-scoped GitHub credential to
   upload the **verified CI APK**, checksum and receipt to a draft GitHub release.
   Gate live API-v2, exact source/tag identity, independent APK signature and all
   uploaded digests before publishing to `noelnovo/WalletTracker`. Keep the estate
    credential server-side; never copy it into CI. Real asset upload remains an
    untested gate until successful. The distinct CI publication credential is
    currently absent: `GITHUB_RELEASE_READY` defaults to false. Enable it only
    after the repository-only credential is staged/verified at the reviewed
    delivery path. Publishing is not accomplished by
   building an unsigned APK or merely creating a tag.

The exact Android role plan is published on both hosts at
`e274a8a89ae332a2530a37a4347ca2cf414ae171`; its protected signing/publication roles
are administrator-configured and verified. Real protected-tag CI login remains
untested. The manual checkpoint SQL is published at backend commit
`ddd0b72d5bdf5bfc3c1f88164f4ffef032df38c9` and is not an approval.
Production staging receipt:
`/var/lib/wallettracker-production-operations/vault-stage-20261106T073611Z.json`.
The existing PKCS12 signer is verified unchanged. No database recovery proof or
release follows from staging alone. See backend `delivery/PRODUCTION-OPS-HANDOFF.md`.
Retain old sources/grants until verified cutover and explicit retirement approval.

## GitLab-only pipeline testing

GitLab project31 `downops/wallet-tracker-android` owns all checks, builds, signing
and publication. `.gitlab-ci.yml` includes `.gitlab/ci/pipeline.yml`. GitHub Actions
workflows have been removed; GitHub remains the mirror and release-download host.
All Android builds share one resource group for the2GiB runner.

- `dev-apk` uses the verified, distinct dev signing record with
  `DEV_SIGNING_READY=true`. It remains manual unless capacity is explicitly ready.
- `prod-signing-check` is manual on protected `v3.0.0-ci-check` tags (optional
  positive numeric suffix for a new immutable test tag). It requires synchronized
  GitLab/GitHub dev and exercises real production JWT login, signing, unchanged
  public certificate and APK configuration. Only a **nonpublishable receipt** is
  retained; the signed test APK is deleted. This is not a release or promotion.
- `prod-apk` still requires an exact protected release version tag whose source
  is on main, main mirror equality and the existing signer. No check tag can pass
  its identity guard.
- `publish-prod` is manual only on protected strict-semver release tags and only
  with `GITHUB_RELEASE_READY=true`. It rejects signing-validation receipts and
  requires live production API-v2. It never uses the server estate credential.
- `publication-check` is a separate manual, credential-gated check-tag job. It
  uploads only a tiny synthetic JSON asset to a new **draft**, verifies the digest,
  and deletes only that unchanged job-owned draft. It never uploads an APK or
  publishes anything, and refuses to overwrite an existing release or user work.

The production tag rule is `v*`, Maintainer-only creation. Do not loosen JWT claims
or tag protections to make a test pass. The distinct fine-grained GitHub credential
must be restricted to `noelnovo/WalletTracker`, Contents read/write and Metadata
read, stored as `GITHUB_RELEASE_TOKEN` at
`downops/apps/wallettracker/delivery/github-release`; never paste it into chat.

## Release notes to preserve

- Update-in-place retains the production package/signing identity.
- Legacy categories/expenses are moved only after choosing the destination wallet.
- Invalid legacy entries remain editable review drafts, not discarded.
- Retired statement/season data is retained in encrypted recovery, not reintroduced
  as file import or silently treated as expenses.
- Fingerprint-protected login upgrades old stored credentials after authentication;
  an unavailable/enrollment-invalidated legacy key falls back to account password.
- Offline changes sync when the wallet is unlocked; locked/restarted apps require
  unlock, rather than persisting an unattended biometric bypass credential.

## Remaining backend review

The current dev backend still has provisioning/root DB authority and plaintext
tenant-credential/legacy-RSA columns in its main database. This is documented in
the backend notes, not silently claimed hardened. Production acceptance must
address least-privilege/envelope-key work or explicitly review those boundaries
before release. An APK download alone does not complete a safe server migration.
