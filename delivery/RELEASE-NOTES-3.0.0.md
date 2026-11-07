## WalletTracker 3.0.0

- Concise Material 3 UI, fingerprint controls and improved forms.
- Categorized expenses and restaurant bill splitting with exact cent amounts.
- Encrypted local storage, offline changes, automatic sync while unlocked and explicit conflict resolution.
- Settings, logout, reset, swipe-to-delete and undo.
- Standard HTTPS/API-v2 authentication with rotating sessions; no shared server secret embedded in the app.
- File import removed.

### Upgrading from 2.2.2

The production package and signing certificate are preserved for an update-in-place.
Choose the destination wallet before moving old phone-local data. Existing valid
biometric credentials can authenticate the one-time login upgrade; otherwise sign
in with your account password. Invalid local entries remain available for review.
Legacy statements/other records are retained in encrypted recovery rather than
silently treated as expenses. Do not uninstall the app to resolve a signing issue.

Production registration is disabled. A locked or restarted app requires unlock
before it can refresh its session and resume synchronization.
