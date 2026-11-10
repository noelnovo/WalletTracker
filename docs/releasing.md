# WalletTracker Android release guide

GitLab owns Android source, CI and APK publication. GitHub retains source/tag
backups. The GitLab project's `ci_config_path` pins a reviewed immutable copy of
`.gitlab-ci.yml`; editing the candidate's YAML cannot replace that trusted pipeline.

## Prepare and validate

1. Submit feature changes to DEV by MR. Keep version fields in
   `app/build.gradle.kts` and `docs/releases/X.Y.Z.md` consistent. The Android
   versionCode must increase; this development line retains `-alpha` in versionName.
2. The credential-free jobs run source/automation/JVM tests, genuine JaCoCo
   reporting and debug/unsigned release builds. Gradle is serialized per project,
   uses one worker and a 768 MiB heap; do not resize/restart the shared runner as
   an ordinary build step.
3. The shared immutable GitLab adapter checks current source/target ancestry,
   same-pipeline artifact identities and digests, then runs the real DT/Sonar gate.
   It never executes candidate Gradle or Python with scan credentials.
4. Require the authoritative successful pipeline and fast-forward merge. New
   source or target commits invalidate old evidence. Never accept skipped scans,
   remove conditions, suppress findings or add coverage exclusions to force green.
5. Promote DEV to MAIN through a separately green release MR. Preserve DEV;
   do not enable source-branch deletion. Confirm deployed API-v2 compatibility
   without registration, financial writes, migrations or backend deployment.

## Sign and publish

1. Create a fresh protected `vX.Y.Z` tag on the exact current MAIN commit.
   Never move an existing tag. The tag pipeline must pass its own tests/builds
   and security scan; a branch/MR pass is not tag release evidence.
2. The authorized owner plays the manual `release` job once. Its separately
   scoped Vault role can read only the existing production signing record,
   not scanner or unrelated application secrets. No developer needs key access.
3. The immutable controller checks live readiness, source/pipeline/APK digests,
   package/version/non-debuggable configuration and production API settings.
   Official checksum-pinned SDK tools sign with environment-only passwords.
   It verifies the original published certificate, not a replacement debug key.
4. The same approved job uploads APK/checksum/source receipt to GitLab's generic
   registry, downloads and verifies each, then publishes release asset links.
   Existing destinations fail closed. A partially published package requires
   operator investigation; do not blindly retry or overwrite a version.
5. Independently download the release APK and verify its checksum, source receipt
   and original signer before calling the release complete. Install in place;
   never uninstall or clear a user's encrypted wallet data.

## Controller maintenance

Changes to trusted CI/scanner/signing helpers require source review and tests,
an immutable code commit, a workflow commit that pins it, and an operator update
of the external CI setting. Keep shared scan credentials and signing credentials
in separate roles. The accepted shared privileged executor and Maintainer-owned
settings remain trusted administrative boundaries, not malicious-code isolation.
Use the existing normal Vault administrator Agent for approved provisioning;
never add a static admin token to CI, recover root or widen PAT scopes.
