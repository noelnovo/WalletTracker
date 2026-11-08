# Fuchsia UI and Android 3.1.0 release validation

## Brand and skills

The final visual direction follows the owner's portfolio/blog, inspected through
GitHub/GitLab MCP. Exact color/blob provenance is in `UI-UX-DESIGN.md`.
`mobile-android-design` and the reviewed `android-ui-templates` catalog informed
native Material 3 typography, semantic colors, component ownership, readable
content widths and the Bill → People & extras → Review flow. Android Views,
API-v2, encryption, authentication and the integer-cent bill calculation remain.

## Local validation

- Debug app and instrumentation APK compile successfully; 11 JVM tests pass.
- 42 secret-free Android delivery tests pass.
- The fuchsia UI/storage suite reports `OK (16 tests)`, covering sign-in flow,
  keyboard-visible complete Save actions, actual category/description alignment,
  grouped settings/category actions, split arithmetic through UI, draft/back
  navigation and rename-preserved extra ownership.
- Light/dark text and button color roles meet 4.5:1 contrast. The blog-matched
  gradient's foreground contrast ranges from 6.19:1 to 18.33:1 in dark mode.
- Controlled native View-render previews use only synthetic emulator data.
  No actual user/device screenshots or credentials are captured; `FLAG_SECURE`
  remains enabled. Mutating preview scenarios are guarded to emulator hardware.
- The add action has a reserved area, not an overlay on finance rows. Split
  review shows amounts before the whole-bill payer selector.

The owner changed device preference during work: the earlier green dev build was
updated in place on the Pixel with its matching signer, without clearing data or
touching production. The Pixel was then disconnected and the owner explicitly
requested ADB emulator development for the fuchsia iteration. No subsequent
physical-phone update or mutation test is claimed.

GitHub DEV CI must compile/check the exact pushed commit, including the optimized
unsigned release, before MAIN promotion. Local preview/test outcomes are not a
production signing approval or a publishable receipt. Real fingerprint matching
still requires owner device acceptance; enrollment-dependent checks may skip.

## Release gates

Android version is 3.1.0 (30100), with `RELEASE-NOTES-3.1.0.md`. The owner has
requested reviewed MAIN promotion and pipeline release with his GitHub approval.
Use `PRODUCTION-RELEASE.md`: protected MAIN version tag, one production review,
original signer, verified artifact/digests and automatic publication afterward.

Backend 2.1.0 extras support is a separate prerequisite. Its source was pushed
to GitHub DEV as `1f2170426f831cfd7cd554f32b5f2248f1eaa163`; 85 local application
tests and 27 delivery tests passed. Its first trusted run `42404842646` passed
Python/delivery/image checks but failed the required Sonar stage, so it must not
be promoted on that run. DownOps handles reviewed backend metadata ancestry
reconciliation, exact CI/security provenance and Deployment-only promotion;
there is no schema migration, key rotation, data reset, or full Argo hook replay.

Do not waive failed CI/security gates, reuse old image/tag approvals, change
protections/readiness flags to conceal failures, or approve the owner's GitHub
production review on his behalf.
