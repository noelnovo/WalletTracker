# WalletTracker 3.1.0 UI/UX verification

Date: 2026-11-07. This is historical evidence from the first functional pass,
before the later fuchsia visual redesign, **not** a publishable release receipt
or production approval. Changes were uncommitted on local DEV at that point.
See `UI-UX-DESIGN.md` for the subsequent design and its verification boundaries.

## Updated sources

- Android DEV fast-forwarded to GitHub `0a150b145831595b97d93be9b07dac7b07ec3915` before reintegrating the UI changes.
- Backend local DEV fast-forwarded to GitHub MAIN `2d8c27f7a525e980ddc68170e82279160a5f6954`; GitHub no longer advertises a backend DEV branch.
- Android `delivery/PRODUCTION-RELEASE.md` on DEV exactly matches the fetched MAIN guide.
- GitLab backup fetch failed: its configured SSH endpoint refused the connection. Backup equality has not been verified.
- Local changes were preserved and reapplied without Git merge conflicts.

## Results

| Check | Result |
| --- | --- |
| Android JVM tests | 11 tests, zero failures/errors |
| Android delivery tests | 42 tests, OK |
| Backend application tests | 85 passed; existing legacy SQLAlchemy warnings |
| Backend delivery tests | 27 tests, OK |
| Backend rendered manifest consistency | Passed |
| Secret-free Android API contract check | Passed; no live readiness request |
| Debug APK and instrumentation APK compilation | Passed |
| Optimized unsigned release APK, including R8/lint-vital | Passed; version 3.1.0, versionCode 30100 |
| Isolated emulator UI/storage suite | Runner reported `OK (12 tests)` |
| Owner-approved physical phone update | Dev updated in place to 3.1.0-dev; production remains 3.0.0 |

The final emulator run used Android 36.1, a 720×1280 portrait viewport at 320dpi,
and synthetic local-wallet records. UI assertions verify the complete Save
control while the IME is visible, actual category/description text-start
alignment, separate registration navigation and password clearing, and an
unnamed €5 extra producing €10/€5 shares from a €15 total with default person
names. Storage tests cover ciphertext binding, outbox/conflicts/undo, itemized
round-trip, old-server rejection and fresh-operation explicit retry.

Real biometric matching was not performed. Enrollment-dependent biometric
checks may be skipped when strong biometrics are unavailable. The connected
physical Pixel was not used for these mutation tests. After the owner explicitly
approved a dev-app update, the installed development signer was compared with
the new debug APK and matched. Only `win.downops.wallettracker.debug` was
updated with `adb install -r` and launched; version 3.1.0-dev (30100) and a
running process were confirmed. Production remains 3.0.0 (30000). No app was
uninstalled, no app data was cleared, and no synthetic finance records were
inserted on the phone. Sensitive screen-capture flags were not disabled. Emulator
animations were disabled for deterministic testing; production app settings
were not changed.

Initial verification caught and fixed a TextInputLayout child LayoutParams
crash. An emulator System UI ANR temporarily blocked window focus; it was
resolved before rerunning, without weakening assertions or app security flags.
A duplicate hidden navigation-label test selector was replaced with the actual
Bills navigation item's accessible description. Final strengthened UI tests
passed after rebuilding their instrumentation APK.

## Release boundary

Version and notes are prepared in `app/build.gradle.kts` and
`delivery/RELEASE-NOTES-3.1.0.md`. No commit, push, MAIN promotion, version tag,
Vault signing access, backend deployment, or APK publication was performed.

The paired backend extras validator must be separately released and verified
before publishing the Android client. It uses existing JSON payload storage;
this feature requires no database schema migration. Follow the current MAIN/DEV
production release guide for reviewed promotion, protected tag creation and the
single production approval before signing and verified publication. Restore
GitLab backup connectivity/equality through the authorized backup process;
do not bypass protections or force-push.
