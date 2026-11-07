# WalletTracker production release — GitHub Actions

GitHub is the authoritative source, workflow runner and APK download host.
GitLab CI is disabled; its repositories are passive fast-forward backups.
See `GITHUB-MIGRATION.md` and the exact `github-actions-vault-plan.json`.

## CI authentication

The original production PKCS12 signer and distinct dev signer remain unchanged
in `downops/apps/wallettracker/{prod,dev}/mobile`. The GitHub workflow uses OIDC
through the existing `github-actions` mount and public verified-TLS Vault URL.
The owner applied the corrected GitHub roles; actual GitHub DEV and production
login/read/signing tests passed. Old GitLab results are historical evidence only.

`github-actions-status.json` records the verified signing access. No recovery,
self-grant, legacy broad-role workaround
or key rotation is permitted. Preserve old records/grants until cutover and
explicit retirement approval. Public production certificate SHA256:
`60b988457cca109d8f3a1f3ad288f95648f9938990aeedff0dfc5ed479952b61`.

GitHub Releases uses job-scoped built-in `GITHUB_TOKEN` with `contents: write`.
No external GitHub upload PAT, publication Vault secret or GitLab CI token is
needed. Build/sign jobs cannot access the release job's token; Gradle receives
no Vault/OIDC/Actions runtime/publication credentials.

## Test without publishing

Owner now requires MAIN-only releases: DEV has only secret-free validation and
test reports. Automatic DEV signing/APK artifact and test-tag draft/signing jobs
have been retired from the active workflow after their completed verification.
The protected exact version tag is checked against MAIN before any Vault access.
Historical check scripts/receipts are not production release authorization.

- DEV no longer fetches signing keys or releases signed APK artifacts.
- Completed DEV and production signing tests verified the original identities;
  their signed test APK/draft jobs are no longer in the active workflow.
- The completed built-in-token upload test verified a disposable JSON draft and
  removed it. Historical validation receipts cannot authorize a real release.
- Secret-free checks and unsigned compilation may run before OIDC is ready;
  skipped signing is never reported as a successful signing test.

## Production promotion order — no CI shortcut

1. Recheck signing/Vault equality, readiness and exact source revisions. CI-only
   retirement commits on MAIN do not promote the application or its database.
2. Verify legacy phone migration, invalid-row review, encrypted recovery,
   destination-account consent, idempotency and biometric reauthentication.
   No phone connection, file import or uninstall is required.
3. Before production schema changes, coordinate the approved brief WalletTracker
   write/DDL/account quiescence/read-lock window. Include InnoDB application and
   Aria account/definition state. `--single-transaction` alone is insufficient.
4. Take the approved one-off backup, encrypt before persistent storage and keep
   ciphertext off-cluster. Restore-test in an isolated non-public non-DEV sandbox;
   preserve complete data, accounts/grants and definitions. No schedules, backup
   Kubernetes Jobs/PVCs or production data in DEV. Report paths, timestamps,
   counts and equality/integrity booleans only.
5. Only after fresh restore/write-freeze proof may the authorized operator approve
   `wallettracker-api-v2` using the reviewed backend checkpoint schema. Old id1
   and synthetic sandbox proofs are not fresh release approval. If writes resume,
   revalidate complete private state at cutover and stop on mismatch. CI and
   migrations must never approve themselves.
6. Merge the reviewed application release without losing production-only changes,
   verify GitHub MAIN and passive GitLab backup equality, then perform the backend
   protected release/manual Argo sync. Preserve registration-disabled config and
   prevent divergent v1/v2 writers. Verify image/revision, migration heads, data
   preservation and actual Ready/API-v2 semantics. Never resume a stale writer
   or restore an old snapshot after new writes without current-data transfer.
7. An exact protected Android version tag on MAIN can build/sign the real release.
   `publish-prod` requires the matching publishable receipt, checksums, exact tag,
   unchanged signer and live production API-v2. It creates a draft, verifies all
   asset digests, and only then publishes to GitHub Releases. A check-tag receipt
   can never authorize it.

## Existing backend boundary

The existing backend still has provisioning/root DB authority and plaintext
tenant-credential/legacy RSA columns. Do not claim those boundaries are hardened;
retain their review before production acceptance. Backend GitOps source and
security-role migration must preserve MAIN's required checks and manual PROD sync.
