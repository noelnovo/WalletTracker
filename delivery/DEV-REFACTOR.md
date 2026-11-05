# WalletTracker 3 development track

## Runtime and UI

The launcher is `v2/WalletActivity`: Material 3 day/night, expense/category
editing, equal restaurant splits with exact integer cents, settings, lock,
logout, confirmed reset and six-second swipe/reset undo. Money is never split
with binary floating point. Supported currencies are EUR, USD and GBP; totals
are kept separate (no invented exchange rates). File import, CSV parsing, share
handlers and the mobile bulk-import API are removed.

UI rule: short labels, one primary action per view, compact rows and icon-led
affordances. No marketing copy or instructional paragraphs. Dates use a picker;
bill payers are selected from entered people; shares have a dedicated detail
view. Conflicts compare readable cards, not JSON. Only destructive decisions
get a brief confirmation; technical details live behind About. Fingerprint is
an icon-only accessible toggle. Date/currency use aligned outlined fields with
a calendar end icon, localized date display and the Material calendar dialog.

`WalletModel` owns authenticated state and synchronization, `FinanceApi` owns
HTTPS transport, `FinanceStore` owns durable local records/outbox/conflicts, and
`DeviceVault` owns encryption. Old v1 screens/repositories are retained as
non-exported migration/reference code, not as the new launch path. Do not route
users back through that legacy authentication or encryption stack.

## Synchronization contract

- Account databases are separated by API origin **and** authenticated user ID.
  The local notebook is explicitly separate and never silently merged.
- Mutations use UUID operation IDs and expected record versions. Server receipts
  make network retries idempotent. Local edits racing an acknowledgement are
  retained and rebased; other-device edits produce a conflict, not silent loss.
- Sync pushes pending operations, then reads bounded cursor pages. Tombstones
  remain available to devices that have been offline; do not prune tombstones or
  receipts without a device-watermark/retention protocol.
- A deletion is durably delayed beyond the undo window. Undo preserves record
  identity instead of inserting a duplicate. Process death does not lose the
  outbox. Unresolved deleted-record conflicts appear in Settings as well.
- Sync runs on network return, mutations, manual request and a 30-second retry
  while unlocked. **It deliberately does not bypass fingerprint authentication
  after lock/process restart to run unattended background sync.** The next
  unlock resumes the durable queue. Full unattended sync would require a weaker
  credential-unlock policy and is not claimed here.
- The backend migration copies legacy categories/expenses into the v2 tenant
  records without removing old tables. Dev disables v1 writes to prevent two
  independently writable data models. Production remains on its existing v1
  deployment until a separately reviewed release/migration.
- Existing legacy **device-only** finance data is not automatically attributed
  to an account. It is not deleted. A consented, account-aware in-app migration
  still needs implementing before a production upgrade; CSV/file import is not
  a substitute.

## Security boundaries

No password, server signing secret, database password or server private key is
needed by the v2 client. Access tokens live in memory. Persisted refresh tokens
are RSA-OAEP wrapped with an Android Keystore private key requiring strong
biometric authentication for decryption. Public-key encryption lets refresh
rotation replace the wrapped token without storing a biometric bypass key.
Enrollment invalidation fails closed to password login. Data/outbox/conflicts
are AES-256-GCM sealed with account/record-bound associated data and device
Keystore keys (StrongBox where supported, otherwise device Keystore).

Backups/device transfer, cleartext HTTP, redirect-following and sensitive screen
capture are disabled. Leaving the activity immediately locks its UI and dismisses
sensitive dialogs; an in-flight sync finishes before the database closes.
Passwords are never trimmed before submission. A compromised
OS/process can still abuse an unlocked session, observe input or read runtime
plaintext; Keystore cannot make that threat impossible. Debug APKs are
debuggable test software, not production anti-tamper builds.

TLS terminates at the internal Nginx Proxy Manager; the existing proxy-to-node
hop is HTTP on the private network, not end-to-end TLS to the pod. No claim of
application-layer E2EE or pinned certificates is made.

## GitLab and signing

Pipeline logic is `.gitlab/ci/pipeline.yml`; the root file is only GitLab's
three-line include entry point. Removing that last entry point would require a
project-wide CI configuration-path setting affecting `main`, so it is deliberately
not done during dev-only work. GitHub automatic Android builds are disabled;
its old release workflow remains manual and main-only.

Dev artifacts use `win.downops.wallettracker.debug` and the private dev hostname.
The shared native runner has only2GB RAM and cannot safely overlap Android
compilation with backend image/security work. The GitLab APK job is manual
until the owner sets `ANDROID_CI_CAPACITY_READY=true` after providing adequate
capacity or scheduling isolation. Contract checks still run automatically.
Builds are capped at one worker,768MB heap and384MB metaspace; JVM tests use
128MB. This work does not resize/restart the shared runner or alter other repos.
They do not replace the installed production package. Local builds use the local
debug certificate. CI defaults to an ephemeral debug certificate, which is **not
update-stable across runners**. Never uninstall an existing dev wallet merely
to bypass a signature mismatch; its unsynced data would be lost.

For stable CI signing, the owner must provision **a separate development key**:

- Vault KV-v2 mount: `downops` (reuse the existing app namespace, no new engine)
- Secret path: `apps/wallettracker/dev/mobile`
- Required keys: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`
- Optional `KEY_PASSWORD` only if different from the store password; otherwise
  the build reuses the store password in memory, not as a duplicated Vault field.
- OIDC role/policy: `wallettracker-android-ci-dev`, exact project31/dev claims
  and read-only access to that one record. Full non-secret plan:
  `delivery/dev-signing-vault-plan.json`.
- Then set project31 variable `DEV_SIGNING_READY=true`.

Do not copy the production APK signing key or backend identity into this record.
`downops/apps/wallettracker/mobile` is the existing production APK record;
`downops/apps/wallettracker/signing` is the legacy backend `SIGN_SECRET_WORD`,
not an Android keystore. The earlier proposed `wallettracker/dev/android-signing`
path was never provisioned by this task and must not be created.
No Vault records, policies or roles were written by this implementation. The API
uses the existing dev server RSA identity; no new API runtime secret is required.

## Verification

```sh
python3 delivery/verify_contract.py
./gradlew testDebugUnitTest assembleDebug connectedDebugAndroidTest --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Optional live private-network device check (no credentials or finance mutations):

```sh
adb shell am instrument -w -e liveDevApi true win.downops.wallettracker.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The non-secret installation/live acceptance receipt is `delivery/dev-verification.json`.

Device tests cover encrypted persistence/account isolation, conflict decisions,
durable undo, UI-edit/acknowledgement races and authenticated encryption's
record binding. Real fingerprint interaction still needs owner acceptance; no
biometric match is simulated or bypassed on the physical phone.
