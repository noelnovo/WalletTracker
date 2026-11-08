## WalletTracker 3.1.0

- Cohesive Material 3 redesign using the fuchsia/plum palette from noelnovo.dev and blog.noelnovo.dev, with matching light/dark surfaces, brand gradients, rounded cards and line icons.
- Clear spending hierarchy: all-time currency-separated totals, date-grouped expense cards, readable empty states and one primary add action.
- Simpler sign-in: separate account creation and returning-user fingerprint unlock, with a clearly labelled fingerprint preference.
- Aligned category fields and scrollable expense/bill forms with Save kept above the keyboard.
- Grouped settings action rows: automatic sync and wallet locking explained; old-data migration appears only when previous records exist. About removed.
- Categories now have an explicit Add category button rather than a fake list item.
- Guided Bill → People & extras → Review flow: start with two people, add more as needed, and optionally name them. Add individually assigned extras in a focused price/owner form; the rest of the total is shared equally. Back navigation preserves the draft and renaming preserves assignments.
- Live share preview, separate whole-bill payer selection, and saved amounts owed to the payer. A €15 bill with a €5 extra assigned to person 1 splits into €10 and €5.
- Exact cent arithmetic and validation prevent extras exceeding the bill total. Existing bills without extras keep equal splits.
- An older backend cannot silently overwrite itemized local shares; affected bills remain on the phone until a compatible server is available and Sync now is requested.

### Release compatibility

Release and verify the paired API-v2 bill-extras validator before publishing this
Android version. No database schema migration is required by itemized splitting.
Keep the existing production package and signing certificate; do not uninstall
the app or clear its data to update it.

Follow `delivery/PRODUCTION-RELEASE.md` for
DEV validation, the reviewed MAIN promotion, protected version tag, required
production approval and verified publication. Release notes alone are not a
replacement for the source, CI, backend readiness or signing/publication gates.
