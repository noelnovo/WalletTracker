# WalletTracker

A native Android wallet for expenses, categories, and shared bills, with encrypted
on-device records, fingerprint unlock, and optional account sync over HTTPS.

## Features

- Record expenses and organize them into categories.
- Split bills with exact-cent shares and per-person extras.
- Use a local-only wallet or sync an authenticated account with API v2.
- Automatically request fingerprint unlock when reopening a remembered account;
  cancellation leaves a retry button and password fallback.
- Neutral light and black/charcoal dark themes with restrained fuchsia accents.
- Stable bottom navigation with cached tab pages and background wallet loading.

Local records use Android Keystore-backed encryption. API v2 uses HTTPS and
short-lived access tokens with rotated refresh tokens—not the retired v1 hybrid
encryption protocol. Screens remain protected against screenshots and recording.

## Project layout

| Directory | Purpose |
| --- | --- |
| `app/` | Android application, JVM tests, and emulator-only UI/storage tests |
| `.gitlab-ci.yml` | GitLab CI delivery: validation, security gates, and releases |
| `scripts/ci/` | Source/API compatibility checks |
| `scripts/build/` | Debug/production build and signing helpers |
| `scripts/release/` | Verified APK publication and non-publishing signing checks |
| `scripts/shared/` | Shared source-identity and credential-boundary guards |
| `tests/automation/` | Offline tests for build/release automation |
| `config/` | Non-secret CI, signing-policy, and historical verification records |
| `docs/` | Architecture, automation, and release documentation |

Python scripts are small CI helpers; Gradle still builds and tests the Android
application. Run Python entry points from the repository root with `python3 -m`.

## Development

Requires Java 21 and Android SDK 36. No production signing credentials are needed.

```sh
python3 -m scripts.ci.verify_contract
python3 -m unittest discover -s tests/automation -p 'test_*.py'
sh gradlew testDebugUnitTest assembleDebug assembleRelease
```

UI/storage tests create synthetic records and must run only on an isolated
emulator, never on a personal phone. Install updates in place; do not clear wallet
data or substitute a different signing identity.

## CI and releases

GitLab CI runs on DEV, MAIN, and merge requests: source checks, automation
tests, JVM tests, the JaCoCo coverage report, and debug/unsigned release
builds. The GitHub repository is a passive backup mirror without workflows.

### Security checks

The project uses an immutable external GitLab CI entrypoint. Merge requests and
protected DEV/MAIN/version-tag pushes call the canonical shared GitLab adapter.
Build/test jobs have no scanner or signing JWT. A separate pinned scanner reads
only four own-project Vault records and treats candidate source, compiled classes,
SBOM and JaCoCo XML as bounded artifact data. Dependency-Track finishes before
plugin settings are reconciled and the exact Sonar analysis is evaluated. Missing
metrics or failed/cancelled/skipped producers cannot authorize publication.

Fast-forward-only, successful-pipeline-required merges ensure that the tested
candidate contains the current target. Advancing either target or source requires
fresh checks. The unchanged assigned Sonar quality gate owns all thresholds.
The accepted shared privileged runner remains a trusted administrative boundary,
not isolation against a malicious runner administrator or project Maintainer.

### Production releases

The manual release job runs only on protected exact-version tags matching current
MAIN, after the same-pipeline security job succeeds. Its immutable controller never
imports candidate Python or runs Gradle. Checksum-pinned official SDK tools verify
the package, version, non-debuggable state and original production certificate.
A separate signing-only Vault role supplies the existing keystore; passwords use
environment references, never command-line values. No signing key is generated.

The job uploads the signed APK, checksum and source receipt to GitLab's generic
package registry, downloads and verifies each file, then publishes release links.
Existing release/package destinations fail closed: do not retag or overwrite them.
One approval authorizes signing followed by automatic verified publication.
An alpha versionName is retained while this development line is labelled alpha.

Android releases do not deploy the backend or run database migrations. Confirm
backend compatibility separately before releasing a client that needs new API
features.

## Documentation

- [Architecture and security boundaries](docs/architecture.md)
- [Automation layout and commands](docs/automation.md)
- [Release procedure and troubleshooting](docs/releasing.md)
- Release notes: [3.1.1](docs/releases/3.1.1.md), [3.1.0](docs/releases/3.1.0.md), [3.0.0](docs/releases/3.0.0.md)

The old v1 screenshots have been removed. Personal wallet data is never used for
public screenshots.
