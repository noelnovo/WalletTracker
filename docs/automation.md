# Android automation

All commands below run from the repository root. Python modules use explicit
package imports rather than depending on the old flat `delivery/` search path.

| Stage | Entry point | Boundary |
| --- | --- | --- |
| Compatibility checks | `python3 -m scripts.ci.verify_contract` | Source checks; optional API probe |
| Automation tests | `python3 -m unittest discover -s tests/automation -p 'test_*.py'` | Synthetic fixtures; no live signing or publication |
| App tests/build | `sh gradlew testDebugUnitTest assembleDebug assembleRelease` | Gradle; unsigned production output |
| Retain validated input | `python3 -m scripts.ci.package_validated_apk` | Trusted MAIN push, completed checks/build only |
| Release evidence | `python3 -m scripts.ci.release_validation` | Read-only exact-source MAIN run/jobs/artifact proof; waits instead of rebuilding |
| Verify downloaded input | `python3 -m scripts.ci.package_validated_apk --verify` | Source/attempt/checksum/version/configuration checks before Vault access |
| Stable dev build | `python3 -m scripts.build.build_dev` | Restricted DEV signing identity only |
| Production signing | `python3 -m scripts.build.build_prod` | Protected MAIN tag and approval; signs the validated APK without rebuilding |
| Publication | `python3 -m scripts.release.publish_prod` | Verified same-run artifact; no Vault access |
| Signing exercise | `python3 -m scripts.release.check_prod` | Protected check tag; no retained/publishable APK |
| Publication exercise | `python3 -m scripts.release.check_publication` | Owned disposable draft; existing check-tag restrictions |

Signing/publication commands are CI entry points, not ordinary local development
commands. Their source, identity, certificate, and approval checks remain intact.
[`validation.yml`](../.github/workflows/validation.yml) validates DEV/MAIN/PRs;
[`android.yml`](../.github/workflows/android.yml) is tag-only production release.
MAIN validation input is retained for 90 days and named by exact source/run/attempt;
tag jobs download only the verified immutable artifact ID. Neither successful
PR/DEV builds nor stale/missing MAIN evidence authorize release. There is no
automatic rebuild/fallback that silently masks failed validation.
Generated artifacts go under ignored `artifacts/`; signing exercises use
`artifacts/signing-check/`. Gradle output remains under `app/build/`.
Downloaded unsigned inputs live in ignored `validated-input/`, separate from the
signed artifacts published by the production job. Historical check helpers are
retained but are not scheduled by these workflows.

`config/ci/` contains compatibility/readiness configuration; `config/signing/`
contains non-secret policy plans and role declarations. Historical receipts live
in `config/verification/` and are not proof of a current deployment or approval.
Release notes live in `docs/releases/X.Y.Z.md`.

See [releasing](releasing.md) for the protected release workflow. Never run wallet
mutation tests on a physical phone, print credentials, or replace a signing key.

## UI/navigation validation — 2026-11-08

- Debug and optimized unsigned release builds passed, with 14 JVM tests and 44
  offline automation tests.
- The isolated emulator passed 24 UI/storage/migration cases, including navigation
  instance reuse, unchanged-data updates, scroll retention across tab switches and
  Activity recreation, and light/dark text contrast. An initial run was blocked
  by an emulator System UI ANR; the successful rerun had normal window focus.
- The USB-connected Pixel 9 received an in-place DEV update with its existing
  signing certificate. The system fingerprint prompt appeared automatically;
  the user supplied the real fingerprint authentication.
- ADB exercised 12 Expenses/Bills/Settings switches without editing wallet
  records. Navigation bounds stayed stable. The measured run recorded 689 frames,
  one janky frame (0.15%), and a 7 ms 95th-percentile frame time. These are
  measurements from that run, not a before/after benchmark or a universal guarantee.
- Both light and dark modes retained the unlocked wallet/navigation on the phone;
  its original dark-mode setting was restored afterwards.

Production installation/data and published tags were untouched. No real wallet
screenshots were captured or added to documentation. This evidence describes a
local DEV build, not a new production release.
