# Universal login, password recovery and change — design

Date: 2026-11-06. Status: approved in conversation, awaiting written-spec review.

## Goal

One sign-in for everyone. A person types a username or email and a password on one page; the platform works out
whether they are staff, an agent, a policyholder or a regulator and opens the right workspace. Forgot-password works
by SMS code; every user can change their own password; every password change is announced by SMS. A security audit of
the finished system follows as a separate, final step.

## Decisions (user, 2026-11-06)

| # | Decision |
|---|----------|
| U1 | Merge the four Keycloak realms (`staff`, `agents`, `customers`, `regulators`) into one realm. |
| U2 | No real users exist yet: no migration tooling; dev seeds and e2e accounts are recreated in the new realm. |
| U3 | Sign-in identifier is **username or email** only (Keycloak's native behaviour). People without email keep their phone number as their username and type it exactly as saved; invite SMS states the username. |
| U4 | Self-service forgot-password for customers, agents and regulators. **Staff cannot reset themselves**; an administrator resets them. |
| U5 | No forced password expiry for anyone (NIST 800-63B). Changes are forced only after a reset. |
| U6 | No email provider yet. SMS is the only required channel; email stays a switch (`MAIL_ENABLED`) that, when on, adds a link/notice beside the SMS. Nothing depends on email. |
| U7 | Workspace selection by an `X-Workspace` request header (option a), not one Keycloak client per workspace. |
| U8 | The "if this wasn't you" SMS gives a support number from a setting, `SUPPORT_PHONE`, filled in later. |
| U9 | Security audit runs **after** steps 1–3 are merged, audits the finished system and produces a ranked report; fixes are decided from the report. |

Earlier decisions carried over unchanged: 8-character minimum, `notUsername`, `notEmail`, `passwordHistory(3)`,
brute-force lockout, 15-minute reset link/code, English and Swahili, the "Split leaf" login theme
(`backend/keycloak/themes/lifeplatform/`) which deliberately differs from the console.

## §1 One realm, one login, workspaces

### Keycloak

- New realm `lifeplatform` replaces the four. Four **workspace roles**: `staff`, `agent`, `customer`, `regulator`.
  Staff job roles (UNDERWRITER, ADMIN, …) remain realm roles as today.
- One public PKCE client `lifeplatform-spa`, plus the confidential backend client and the admin service account(s)
  needed by the login-creation services (one service account with `manage-users` on the one realm replaces the
  per-realm ones).
- Carried over from the existing realms: password policy, brute-force lockout, events (90 days), account-page
  restrictions (no self-edit of email/name), the custom reset-credentials flow and the `lifeplatform-reset-delivery`
  authenticator, EN+SW theme.
- `loginWithEmailAllowed: true`, `duplicateEmails: false`.
- Seeds: every dev/e2e user recreated in the new realm with its workspace role(s). The e2e party that is both agent
  and policyholder becomes one account with both roles.
- `deploy/keycloak/build-realms.mjs` generates one production realm (redirect URIs from `APP_DOMAIN`) instead of four;
  its test follows.
- The four `backend/keycloak/*-realm.json` files are deleted.

### Console

- `/` starts sign-in for everyone. `RealmPicker` and the realm-scoped route prefixes as an authentication mechanism are
  removed; one `AuthProvider` with the single issuer is mounted at the root.
- After sign-in the console reads the workspace roles from the token:
  - exactly one → open that workspace;
  - several → "Continue as …" choice; the choice is remembered for the session and a switcher appears in the account
    menu (no password asked again);
  - none → a plain page: "This account has no access yet — contact the insurer." plus Sign out.
- Every API request carries `X-Workspace: <staff|agent|customer|regulator>` for the active workspace.

### Backend

- `SecurityConfig` validates tokens from the one issuer (`app.security.issuer`), replacing the four-issuer resolver.
- Authorities per request:
  - Read the workspace roles from `realm_access.roles`.
  - Resolve the active workspace: the `X-Workspace` header if present; if absent and the token holds exactly one
    workspace role, that one; otherwise reject (400 with a ProblemDetail naming the header).
  - Header names a workspace the token does not hold → 403.
  - Grant exactly one synthetic `ROLE_REALM_<STAFF|AGENTS|CUSTOMERS|REGULATORS>` — the existing names, so the ~185
    existing checks are untouched.
  - Staff job-role authorities (`ROLE_UNDERWRITER`, …) are granted **only** when the active workspace is `staff`.
- `TenantContextFilter` is unchanged (still reads `tenant_id`).
- `KeycloakStaffLogins`, `KeycloakAgentLogins`, `KeycloakCustomerAccounts` target the one realm and add/remove the
  workspace role instead of creating a user in a separate realm. Giving an existing person a second workspace adds a
  role to their existing account (matched by `party_id`, then username).
- CORS is not involved (Caddy serves console and `/api` on one origin); `X-Workspace` needs no allow-listing.

## §2 Forgot password, admin resets, change password, notices

### Forgot password

- One "Forgot password?" on the one login page; it asks for username or email.
- The following page is identical for every input (existing account or not, staff or not, phone or not):
  "If this account can reset its password, we've texted a 6-digit code to the phone on file (and emailed a link if
  email is switched on). Enter the code below. Staff: ask your administrator to reset your password."
- Customers, agents, regulators: the existing `ResetDeliveryAuthenticator` texts the code (15 minutes, 5 wrong tries),
  then the new-password page with "sign out other devices" ticked.
- Staff (account holds the `staff` role): nothing is sent; the authenticator stops silently and the page is unchanged.
- **Per-account SMS cooldown (new):** at most 1 code per minute and 5 per hour per account, kept in Keycloak's
  single-use object store keyed by user id (today's cooldown is per authentication session and can be bypassed by
  starting new sessions). Exceeding it sends nothing; the page is unchanged.

### Admin resets

- Staff, from Users & access: "Reset password" generates a temporary password, sends it by SMS (email too if enabled;
  shown once on screen if the person has no phone), and sets `UPDATE_PASSWORD` as a required action. Pattern copied
  from the agent-login panel.
- Agents (agent page) and customers (portal access): existing reset actions stay, pointed at the one realm.

### Change password

- "Change password" in the account menu of every workspace. It sends the person to Keycloak's application-initiated
  action `kc_action=UPDATE_PASSWORD` on the themed page: asks for the current password, re-authenticates if the
  authentication is older than 5 minutes, shows the rules up front, "sign out my other devices" ticked by default.
- No console-side password form and no backend password endpoint.

### "Your password was changed" notice

- An event listener in the Keycloak extension reacts to `UPDATE_PASSWORD` (ignoring the duplicate
  `UPDATE_CREDENTIAL` Keycloak 26 also emits) and to admin password resets, and POSTs to the backend over the same
  signed internal channel the reset code uses (`/internal/password-reset/sms` family).
- The backend texts: "Your Nlolo password was changed on {date} at {time}. If this wasn't you, call {SUPPORT_PHONE}."
  (EN+SW), email too when enabled. No phone → nothing sent, recorded.
- `SUPPORT_PHONE` is a new `.env` setting; `ProductionSettingsGuard` refuses a placeholder value in `prod`.

## §3 Build order, testing, docs

Three branches, each merged before the next:

1. **One realm + universal login** (§1).
2. **Resets and notices** (§2 forgot password, admin resets, notice).
3. **Change password** (§2 change password).

Then **step 4: security audit** (U9) — realm, Caddy (TLS/HSTS, headers, rate limits), backend, Postgres and
SeaweedFS exposure, backups, server, secrets — as a ranked written report.

### Testing

- Backend unit tests for workspace resolution: role held; role not held (403); no header with one role; no header with
  several (400); staff job roles absent outside `staff`; never more than one `ROLE_REALM_*`.
- Backend integration tests switch to single-realm tokens.
- Keycloak extension tests: staff skip; per-account cooldown (minute and hour limits).
- Console vitest: workspace choice, switcher, no-access page, `X-Workspace` on requests.
- E2E: sign-in fixtures rewritten for the one login. Full backend suite and full e2e (`--workers=2`) at the end of
  step 1; affected tests only after steps 2 and 3. A real SMS reset exercised against the dev mock (`:8083`).

### Docs

`docs/04-api-contracts.md` §3, `docs/07-infrastructure-architecture.md`, `docs/10-frontend-development.md`,
`backend/keycloak/README.md` and `deploy/README.md` describe one realm, the `X-Workspace` rule, the reset/change flows
and `SUPPORT_PHONE`.

## Out of scope

Phone-number sign-in; staff 2FA; password expiry; an email provider; fixes arising from the step-4 audit.
