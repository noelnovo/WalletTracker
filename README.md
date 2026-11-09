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
| `.github/workflows/` | GitHub Actions job orchestration and permissions |
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

[WalletTracker validation](.github/workflows/validation.yml) runs on DEV, MAIN,
and pull requests: source checks, automation tests, JVM tests, and debug/unsigned
release builds. GitLab remains a passive backup with CI disabled.

### Security checks

Pull requests into DEV and MAIN must pass the trusted pre-merge workflow before
merging. It validates the exact head/base/merge candidate, builds in a separate
credential-free hosted job, then scans the candidate with a trusted-base controller.
The credentialed job never executes PR scripts/actions/build tools. It downloads
source independently from GitHub and treats SBOM/bytecode artifacts as bounded,
checksum-verified data, rejecting links, traversal and unexpected paths. Scanner
configuration and control scripts come from the trusted base, not the PR.

The controller uploads the resolved CycloneDX SBOM, waits for Dependency-Track
analysis, synchronizes this project's plugin settings from Vault, then runs SonarQube.
The assigned **SonarQube quality gate** evaluates the Dependency-Track plugin's
metrics: severity/fixable/policy thresholds are configured in SonarQube, not
hard-coded in Python. A missing plugin metric or failed quality gate blocks CI.
PR-controlled build jobs do not receive scanner credentials. A missing, failed,
cancelled or skipped producer cannot yield a passing security status. A changed
head/base/candidate invalidates the run. DEV and MAIN use different required
statuses (`security/sonarqube-dtrack-dev` and `security/sonarqube-dtrack-main`)
and strict up-to-date branch checks; a DEV result cannot authorize a MAIN merge.
Post-merge push validation remains a second check, not the merge gate. Both paths
serialize access to the shared Community Edition Sonar/DT project. This is snapshot
analysis with exact analysis-ID verification, not edition-gated native PR decoration.
MAIN release signing still requires its exact-commit successful push validation.

GitHub OIDC obtains short-lived Vault tokens: `security-ci-android-pr` only for
the trusted `pull_request_target` controller on DEV/MAIN, and `security-ci-android`
only for the post-merge push validation workflow. The PR build job has no OIDC
permission. Both readers' paths
are restricted to these `downops/` KV-v2 records:

| Record | Purpose |
| --- | --- |
| `services/dependency-track/clients/instance-005` | Android-only SBOM upload |
| `services/dependency-track/sonarqube/instance-005` | Android-only read-only plugin key |
| `services/sonarqube/clients/instance-005` | Project analysis token |
| `services/sonarqube/project-settings/instance-005` | Dedicated project-settings token |

Values are fetched at job time and sent through verified HTTPS or scanner
environment variables, never token arguments or tracked files. Plugin settings
are reconciled through the supported SonarQube API on each trusted run, so a
Vault key update is applied without manually copying it into the UI. The settings
identity administers only this project; SonarQube's mandatory default group also
grants analysis/project creation, an explicitly accepted platform limitation.
Its recovery password is operator-only and is not readable by CI. Dependency-Track
portfolio ACLs restrict the uploader and separate plugin reader to this project.

### Production releases

[WalletTracker production release](.github/workflows/android.yml) runs only on
version tags. It waits for successful MAIN validation of the **exact tag commit**
and reuses that run's immutable, checksum-bound unsigned APK. It does not repeat
the build. PR/DEV results cannot substitute for MAIN evidence, and missing,
failed, skipped, expired, or mismatched evidence blocks signing.

A release requires a matching version and release notes, a reviewed merge into
MAIN, and an exact protected `vX.Y.Z` tag. The production reviewer approves once
before Vault-backed signing. Input checksums, source, version, and configuration
are verified before Vault access and again by the signer; the separate
least-privilege publication job verifies and publishes the signed APK with its
ephemeral `GITHUB_TOKEN`. MAIN validates; the tag signs/publishes those validated
bytes. Two differently named runs remain, but duplicate validation is removed.

Android releases do not deploy the backend or run database migrations. Confirm
backend compatibility separately before releasing a client that needs new API
features. Never move an existing version tag or bypass production approval.

## Documentation

- [Architecture and security boundaries](docs/architecture.md)
- [Automation layout and commands](docs/automation.md)
- [Release procedure and troubleshooting](docs/releasing.md)
- Release notes: [3.1.1](docs/releases/3.1.1.md), [3.1.0](docs/releases/3.1.0.md), [3.0.0](docs/releases/3.0.0.md)

The old v1 screenshots have been removed. Personal wallet data is never used for
public screenshots.
