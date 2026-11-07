# Wallet Tracker

> **WalletTracker 3 uses API v2.** See the
> [developer release guide](delivery/PRODUCTION-RELEASE.md) and
> [architecture, security boundaries, sync behavior and Vault signing plan](delivery/DEV-REFACTOR.md).
> The historical v1 overview below is retained for migration context; the new
> launcher has no file import or hybrid-encrypted API protocol. GitHub Actions
> validates DEV and publishes approved MAIN releases; GitLab is a passive backup
> with CI disabled. The feature/protocol descriptions and screenshots below are historical.

An Android expense tracking app built in Kotlin with end-to-end encryption, biometric authentication, and a fully automated CI/CD pipeline.

## Features

### Expense Management
- Create and manage expense categories with custom names
- Log expenses with amount, date, and category
- Browse expenses per category
- Import expenses from spreadsheets

### Metrics & Analytics
- View total spending, expense count, and average per expense
- Breakdown by category with percentage bars
- Monthly breakdown with progress indicators
- Filter metrics by category and/or month

### Offline & Online Mode
The app supports two modes controlled at runtime:
- **Online mode** — syncs data with the backend API, leveraging end-to-end encrypted communication
- **Offline mode** — all data is stored locally in SQLite; no network required

The active mode is injected via Hilt (`AppMode`) and repository implementations swap transparently between HTTP services and SQLite services.

### Biometric Login
- Login with fingerprint or device biometrics via Android BiometricPrompt
- Credentials are retrieved securely from the local session store after biometric verification
- Falls back to username/password login

### End-to-End Cryptography
All API communication is encrypted using a hybrid RSA + AES/GCM scheme:

1. **Key exchange** — on registration, the client generates a 2048-bit RSA key pair and exchanges public keys with the server
2. **Hybrid encryption** — outgoing requests are encrypted with AES-256-GCM; the AES key is encrypted with the server's RSA public key (OAEP/SHA-256)
3. **Request signing** — each request is signed using RSA/PSS (SHA-256) with the client's private key; the server verifies the signature
4. **Response decryption** — responses are decrypted using the client's RSA private key to unwrap the AES key, then AES/GCM to decrypt the payload

Keys are stored in the local SQLite session table and never leave the device unencrypted.

## CI/CD Pipeline

The workflow is [`.github/workflows/android.yml`](.github/workflows/android.yml).
Pushing DEV or MAIN runs checks and unsigned compilation; it does **not** publish
a release. A real release requires an exact protected `vX.Y.Z` tag on MAIN:

1. Update the version in `app/build.gradle.kts` and add matching release notes.
2. Pass DEV CI and merge the reviewed release PR into MAIN.
3. An authorized release maintainer creates the protected version tag on MAIN.
4. Checks, unsigned compilation and release-context validation run automatically.
5. The production reviewer approves **once**, before Vault-backed signing.
6. CI signs with the existing production identity, verifies the APK and publishes
   it automatically with its job-scoped `GITHUB_TOKEN`.

Developers do not need SSH, Kubernetes or Vault credentials. Ordinary source-code
write access does not grant protected-tag creation or production-review rights.
Signing keys never belong in Git; the signing job authenticates to Vault using
restricted GitHub OIDC. The publication job has separate permissions and no
second production review. Registration is controlled by the server, not hidden
or hard-disabled in the APK. An Android release does not deploy the backend or
run database migrations/backups.

See [the complete release procedure and troubleshooting guide](delivery/PRODUCTION-RELEASE.md).

## Tech Stack

- **Language**: Kotlin
- **Architecture**: MVVM + Repository pattern
- **Dependency injection**: Hilt
- **Database**: SQLite via `DatabaseHelper` (custom helper, no ORM)
- **Networking**: Retrofit + OkHttp
- **Cryptography**: Java Security (`RSA/ECB/OAEPWithSHA-256AndMGF1Padding`, `AES/GCM/NoPadding`, `SHA256withRSA/PSS`)
- **Biometrics**: AndroidX BiometricPrompt
- **Secrets management**: HashiCorp Vault

## Screenshots

![Login](images/login.jpg)

![Categories](images/categories.jpg)

![Expenses](images/expenses.jpg)

![Create expense](images/create%20expense.jpg)
