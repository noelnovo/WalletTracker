# WalletTracker native Material 3 redesign

## Skills applied

- `mobile-android-design`: semantic Material 3 color pairs, type hierarchy,
  consistent shape/spacing tokens, 48dp+ controls, meaningful accessible labels,
  focused forms and progressive disclosure. Read the installed details,
  component, and Material 3 theming references.
- `android-ui-templates`: consulted the reviewed catalog and Jetsnack component
  reference for a consistent reusable design system and explicit content/action
  ownership. Reply's bounded adaptive layout approach informs readable content
  widths. No sample build scripts, assets, experimental APIs or obsolete
  Accompanist layouts were imported into the app.

The app uses Android Views, not Compose. Guidance is translated into native
Material widgets without a toolkit, SDK, navigation or dependency migration.
Compose-only skills are visible but intentionally not forced onto this project.

## Design system

`WalletDesign.kt` owns passive view components; `wallet_colors.xml` and its night
variant own the semantic palette. The modern activity inherits one palette base
theme so Activity text and Material widgets resolve identical colors. The legacy
reference theme remains untouched.

- Fuchsia brand actions and plum/pink layered surfaces, taken from the owner's
  portfolio/blog code through GitHub and GitLab MCP, with restrained error/review
  accents. Dark summary gradients use the blog's actual background stops.
- System sans typography: 30–34sp headline figures, 20–28sp section titles,
  16–17sp primary content and 12–14sp secondary labels. Money uses tabular figures.
- 16–24dp corners; 4/8dp spacing rhythm; 20dp page/card padding and 48–56dp actions.
- Original local line-vector icons, no network fonts, tracking or remote imagery.
- Native ripples, focus and accessibility button semantics for grouped action rows.
- Main content capped at 640dp, login at 480dp and forms at 600dp. Long overview
  content scrolls with the record list; large-font amounts stack under their labels.
  A reserved bottom action area keeps the add button clear of finance rows.

## Screen hierarchy

### Brand palette provenance

- `noelnovo/dev-blog`, MAIN `assets/css/style.css`, GitHub blob
  `c17c6184fa195b7f462e99230dd4e82db3265d81`; independently read the same palette
  from GitLab project 16 (`noelnovo/dev-blog`) MAIN.
- Blog: accent `#E52BE5`, background `#19091D`, foreground `#FFF7FF`, muted
  `#E2CFE5`, and gradient `#17091B → #4B0C4E → #A116AD`.
- `noelnovo/nn-PortFolio`, MAIN `src/components/Sections/Hero.tsx`, blob
  `dc29b6252c936c182d28e2ce9f8c67d5dfdcc6f5`, uses `fuchsia-700` for its hero,
  action borders and focus rings. `tailwind.config.js`, blob
  `378bd0d2f0472727180c36f0b49ddb982eea1311`, does not override fuchsia, so the
  standard `#A21CAF` value is used for accessible light-mode actions.
- Light surfaces and gradient tints adapt that brand rather than placing bright
  magenta text on white. Normal text/button/gradient contrast is verified at 4.5:1.

### Login

A compact brand mark, one surface card for credentials or fingerprint unlock,
and one primary action. Account creation and device-only mode remain secondary
text actions outside the credential card. Password visibility is a native end
icon; passwords remain excluded from view saved state. No authentication or
biometric policy is changed.

### Expenses and bills

Compact brand/title chrome, an honest all-time summary with separate currency
totals, date-grouped record cards, descriptive empty states and a single anchored
add action. The summary/list header participates in RecyclerView scrolling and
cannot be swiped as a finance record. Record deletion retains existing durable
undo behavior. Category management is a secondary action, not a competing CTA.

### Forms and categories

Amount-first entry, related fields grouped into rounded cards, a compact close
control, and fixed footer actions above system bars/IME. Categories have their
own management surface, per-category expense counts and one Add category action.
Category creation/editing is a focused form; no fake `+ Category` list record.

### Split bill

`BillEditor.kt` is a UI-only draft controller. The activity still owns save,
encrypted storage, authentication and synchronization.

1. **Bill:** required total; optional name; default date/currency.
2. **People & extras:** default two people, optional names, individual extra rows.
   Adding/editing an extra opens a separate focused price/owner/name form.
3. **Review:** select the whole-bill payer, inspect shared/extras totals and each
   person's share/amount owed, then Save split.

Back navigation retains the draft. Renaming a person preserves ownership by
object identity; removing an assigned person requires choosing a replacement.
All finance writes occur only on final save. The existing integer-cent splitter,
server payload contract and incompatible-server preservation remain unchanged.

### Settings

Wallet identity followed by grouped Wallet, Privacy and Data action rows. Sync
status and automatic-sync behavior are readable but do not dominate the header.
Legacy import appears only when a source exists; sign-out/reset retain their
existing confirmation, storage and undo semantics. About stays removed.

## Verification boundaries

Use JVM arithmetic tests, native emulator UI/storage tests, light/dark color
contrast checks, and controlled synthetic View-render previews. Mutation and
preview UI tests now require emulator hardware and skip physical phones.
`FLAG_SECURE` is never disabled; no real credentials, user finance records,
device screenshots or production data belong in previews. Phone checks remain
owner-controlled and data-preserving. This document is not release approval.

The previous `UI-UX-3.1.0-VERIFICATION.md` records the earlier functional pass,
not verification of this later visual redesign. Reverify before a new delivery.
Follow `PRODUCTION-RELEASE.md`; the paired backend extras validator still needs
its separate reviewed rollout before itemized Android sync is released.
