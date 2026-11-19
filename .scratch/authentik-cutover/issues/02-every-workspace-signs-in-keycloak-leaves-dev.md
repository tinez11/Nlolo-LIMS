# 02: Every workspace signs in through Authentik; Keycloak leaves dev

**What to build:** Agents, customers and the regulator sign in through Authentik as staff now do. One login in two groups (an agent who is also a customer) switches between workspaces by `X-Workspace`, as today. Sign-in is hardened:
- PKCE S256 is required by policy
- the public sign-in flow refuses superusers
- signing out revokes the tokens (`revokeTokensOnSignout`)
- the console signs a person out 10 hours after sign-in, using the token's `auth_time`

Keycloak is removed from the dev stack.

Spec: Parts 1 and 3, and the carry-over table (the 10-hour cap). The decisions are in tickets 05, 07, 10 and 15 of `.scratch/authentik-migration/`.

**Blocked by:** 01

**Status:** resolved (2026-11-19, branch `authentik-cutover`)

- [x] `agent.senior`, `customer.owner` and `regulator.tira` each sign in to their console
- [x] A test login in both `agent` and `customer` reaches both workspaces, and is refused a workspace it doesn't hold
- [x] A sign-in attempt without PKCE S256 is refused
- [x] A superuser is refused on the public sign-in flow; the bootstrap admin still works through the admin route
- [x] After signing out, the old refresh token no longer works
- [x] A session older than 10 hours by `auth_time` is signed out (a console test with a controlled clock)
- [x] The dev stack no longer contains Keycloak and every workspace still signs in

## Comments

**2026-11-19, done.** What was checked, live against the dev stack (Authentik 2026.8.5, no Keycloak):

- **Every workspace:** `agent.senior` reached the agent Dashboard, `customer.owner` the customer Home ("Welcome, Amina"), and `regulator.tira` the console's "no access yet" page, as before: regulators have no screens. `staff.underwriter` still reaches the Dashboard with 14 permissions.
- **Two workspaces, one login:** a new dev login, `agent.customer` (groups `agent` and `customer`), was offered both workspaces and opened each. Replaying the console's own calls: the agent call answered 200 with `X-Workspace: agent`, the customer call 200 with `customer`, and each answered 403 with `staff` and with `regulator`.
- **PKCE:** an expression policy on the application requires a challenge with method S256. A sign-in with no PKCE, or with `plain`, ends on "Permission denied"; S256 gets its code. The token endpoint doesn't run application policies for a refresh, and the policy passes when there is no authorize context, so refresh and the library page are unaffected.
- **Superusers:** a deny stage, bound with a policy re-evaluated against the pending user, refuses `akadmin` on the public flow ("This login cannot sign in here."). `akadmin` still reaches the admin interface through the stock `default-authentication-flow`.
- **Sign-out:** the console revoked the access and refresh tokens (both 200), ended the Authentik session, and came back to the sign-in page. The refresh token it held was then refused (`invalid_grant`).
- **The 10-hour cap:** `useSessionCap` signs out at `auth_time` + 10 hours, checking once a minute and on `visibilitychange`. Six tests with a controlled clock. A real refresh keeps `auth_time` unchanged, in both the access and the ID token.
- **Keycloak** is gone from the dev compose (its old container removed). The backend starts without it.
- **Tests:** frontend `src/auth` (51 tests, including the new `sessionCap` and `signOut`); `tsc` clean. No backend code changed.

Found while building:

- **Authentik's revoke endpoint sends no CORS headers** (2026.8.5; the token and userinfo endpoints do). A browser on the console's origin can't call it, and oidc-client-ts's `revokeTokensOnSignout` then abandoned the whole sign-out. The console now revokes through `/oauth/revoke/` on its own origin (Vite forwards it in dev; Caddy must in production, see [Production deployment](10-production-deployment.md)), and redirects even if the revoke fails.
- **Authentik keeps refresh tokens alive after its session ends** (`RefreshToken.session` is SET_DEFAULT on delete), so revocation is what kills them.
- **The stock provider invalidation flow doesn't end the Authentik session.** With a logout address registered it returned straight to the console, which signed back in silently. The provider now has its own `lifeplatform-sign-out` flow with a user-logout stage, and registers the console's `/` as its logout address.
- **Superusers' admin route bypasses the platform's flow.** [Production deployment](10-production-deployment.md) now says Caddy must close `default-authentication-flow` on the public domain.
- **`party_id` reaches the logins only through the dev seeder**, which wrote it onto the Keycloak users. For these checks it was copied from the old Keycloak database onto the Authentik logins through the API (`agent.customer` shares `agent.senior`'s party). A blueprint re-apply of `dev-users.yaml` replaces `attributes` and drops it again.
- **The seed scripts still use Keycloak**, recorded in [E2E suite on Authentik](09-e2e-suite-on-authentik.md).
