# Configure the APK workflow using an existing Vault administrator UI session

No signing key, password, GitHub PAT or root/recovery token is needed here.
The role JSON files contain only public claim bindings and policy names.

GitHub API verification reports `use_immutable_subject=true` and prefix
`repo:noelnovo@128699907/WalletTracker@909094292`. The corrected JSON bodies
include those IDs in `bound_subject`. The earlier name-only format fails with
`invalid subject (sub) claim`. Updating this binding changes no policy or key.
Use the complete corrected body, not a partial single-field write that might
reset omitted role settings.

1. Sign in to `https://vault.downops.win/ui/` using your normal administrator
   authentication. Being a Kubernetes administrator does not log the Vault CLI in.
2. Open **Access → Auth Methods** and look for the existing `github-actions/`
   JWT authentication method. Do not delete/recreate an existing method.
3. Use **Tools → API Explorer** (or open the `>_` UI console and enter `api`).
   Read `GET /auth/github-actions/config`. Verify:
   - `oidc_discovery_url`: `https://token.actions.githubusercontent.com`
   - `bound_issuer`: `https://token.actions.githubusercontent.com`
   If missing/different, stop for a scoped configuration review; do not overwrite
   unknown working trust. No OAuth client ID, client secret or redirect URI is
   required for these JWT workload roles.
4. Read each role first. If absent, use **Try it out** on the matching POST
   endpoint, paste the complete matching JSON body and **Execute**:

   | Endpoint | Request body |
   | --- | --- |
   | `POST /auth/github-actions/role/wallettracker-mobile-ci-dev` | [`role-dev.json`](role-dev.json) |
   | `POST /auth/github-actions/role/wallettracker-mobile-ci-prod` | [`role-prod.json`](role-prod.json) |

   If an existing role differs from the reviewed body, stop before overwriting.
   Use the API Explorer's JSON request editor, not the small UI CLI: the latter
   does not support structured `bound_claims` maps, heredocs, jq or shell pipes.
5. Read both roles back with GET on the same paths. Confirm exact repository IDs,
   dev/tag/workflow/environment bindings, intended single policy, batch tokens,
   TTL/max900 and no default policy. Leave old roles/policies and signing secrets
   unchanged. Tell the operator only that these steps succeeded—never send tokens.

If the UI returns403, that UI login also lacks the required administrative
permission. Do not use recovery or bootstrap/root credentials to work around it.
Signing stays disabled until trust and role configuration are verified.
