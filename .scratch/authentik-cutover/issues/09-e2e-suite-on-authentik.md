# 09: E2E suite on Authentik

**What to build:** The Playwright suite signs in through Authentik:
- **One shared sign-in helper,** used by all the setup projects, finds fields by label: "Username", "Password", and the "Log in" button. `storageState` keeps Authentik's session cookie.
- **API-level setup** mints tokens with the password grant using each dev user's **app-password token**. The dev-only blueprint creates those tokens with a fixed dev key.
  - **The provider's `grant_types`** (found in ticket 01) lists only `authorization_code` and `refresh_token`, and an unlisted grant is refused. The password grant must be allowed for this, and only in dev: the production blueprint must not gain it.
  - **The PKCE policy** (ticket 02) only judges requests that carry the authorize endpoint's context, so the password grant at the token endpoint passes it.
- **The dev seed scripts** (added 2026-11-19, found in ticket 02): `backend/scripts/seed-lib.sh` and the demo and fixture scripts that use it still get tokens from Keycloak's password grant. Keycloak left the dev stack in ticket 02, so they fail until they mint tokens the same way as the e2e setup. Ticket 11's fresh-bootstrap rehearsal needs them.
- **New specs:**
  - the staff sign-in code end to end through the mock SMS gateway (the `e2e-sign-in-code` user)
  - a `/reset` happy path

The suite runs at `--workers=2` with the dev stack's memory limits. If the first run shows sign-in timeouts, try `--workers=1` before changing anything else.

Spec: Part 4. The decision is in ticket 14 of `.scratch/authentik-migration/`.

**Blocked by:** 02, 05, 07, 08

**Status:** resolved (2026-11-20, branch `authentik-cutover`)

- [x] Every setup project signs in through the shared helper (all 10 pass)
- [x] The specs that use API-level setup mint tokens with app passwords and pass
- [x] The sign-in code spec and the reset spec pass
- [x] Each changed spec passes on its own (the full suite runs in ticket 11), except `staff-underwriting`'s "a proposal states the contract", which fails for a reason older than this ticket (below)
- [x] `seed-dev-data.sh` seeds a fresh dev database against Authentik

## Comments

**2026-11-20, built.**

- **Shared helper:** `frontend/e2e/signIn.ts`.
  - `signIn()` opens a console, fills Authentik's page by label ("Username", "Password", "Log in"), waits for the console's landing page, and saves the session.
  - `fillSignIn()` fills the page alone. `approverSession.ts` uses it as a fallback.
  - `apiToken()` mints a token with the password grant.
- **The ten setups use the helper.** `agentSession.ts` lost its 20-minute re-sign-in: it existed for Keycloak's 30-minute idle timeout, and Authentik's session lasts the sign-in stage's ten hours.
- **The dev blueprint (`deploy/authentik/blueprints/dev/dev-users.yaml`):**
  - Adds `password` to the provider's `grant_types`. Production still lists only the code flow and refresh. A partial entry on the existing provider applies cleanly, because the file applies production first.
  - Gives each test user except `e2e.sign-in-code` an app-password token with the key `dev-app-password-<username>`. Token identifiers must be slugs, so dots become hyphens there.
  - Checked live:
    - the token carries the same claims as a browser sign-in
    - the PKCE policy passes it
    - the login's real password is refused
    - `client_credentials` is refused
- **Found and fixed: a blueprint re-apply wiped `party_id`.** The worker re-applies the dev file whenever its content changes. An apply replaces a user's attributes whole, so the agents' and customers' `party_id` vanished and `/agents/me` answered 403. Fixed: the dev users are now `state: created`, made when missing and never rewritten. Checked: `party_id` survives both an API apply and `ak apply_blueprint`.
- **Seed and demo scripts:**
  - `seed-lib.sh`:
    - `token_for <username>`: Authentik's password grant with the app password; it fails loudly.
    - `set_party_id <username> <partyId>`: reads the attributes, adds `party_id`, and PATCHes them back, with the dev bootstrap token.
  - `realm_secret` is gone.
  - `seed-dev-data.sh` Step 4 writes `party_id` through `set_party_id`.
  - The money-case, books-match and field-receipt scripts and the four demo scripts source `seed-lib.sh` in place of their own Keycloak `token_for`.
  - `hazina-demo.mjs` and `apply-staff-sign-in-flow.sh` still speak to Keycloak. They are left for ticket 11 to rewrite or delete.
- **New specs, in a new `public` Playwright project** (signed out; no setup dependency):
  - `public-sign-in-code.spec.ts`: password, then the code read from the mock SMS gateway's journal (`e2e/smsGateway.ts`), then the Dashboard. The backend's cap (one a minute, five an hour, in memory) limits how often it can run.
  - `public-password-reset.spec.ts`: builds a fresh agent with a login over the API each run, then goes from Authentik's "Forgot username or password?" to `/reset`, the texted code, Authentik's "Choose a new password", and a sign-in with it in a new browser. It takes about two minutes, so it has a three-minute budget.
  - Both need the backend running with `SMS_GATEWAY_LIVE=true` and the gateway on the mock (the default). `playwright.config.ts` now says so.
- **Fresh-database seed check:**
  - a scratch database, `lifeplatform_seedcheck`, in the dev Postgres
  - `migrate.sh staging` applied all 330 migrations across 25 modules
  - the backend ran against it, and `seed-dev-data.sh` seeded it in 3 minutes (exit 0, "The books match")
  - afterwards the scratch database was dropped, and the four dev logins' `party_id` were restored (the seed writes them onto the shared Authentik users)
- **Dev data:** `e2e.sign-in-code`'s staff record had the phone `+255700000098`, left over from ticket 05's tests. It was cleared, as a fresh install has none.

**Verified:**
- the setup project, 10/10
- `public`, 2/2
- `staff-client-kyc-lifecycle`, `staff-credit-life-premium-basis`, `staff-activity-log` and `agents-my-profile` pass
- `staff-underwriting` passes 10 of 11, each failing test re-run alone
- `tsc` and eslint are clean

**For ticket 11 (the full suite):**
- **A silent sign-in through Authentik costs about 3 seconds idle**, against well under a second with Keycloak, and more under load on this 7.8 GB machine.
  - Every page load makes one, and the suite already runs on one worker.
  - In a batch run, three tests stalled on "Signing you in" and passed when re-run alone.
  - "A postponed case is not finished" (two sessions, four reloads) needed `test.slow()`.
  - Expect more of these in the full run. Raising the suite's 60-second test budget is the likely fix, not a change to the specs.
- **Older than the cut-over, not fixed here:**
  - `staff-underwriting` "a proposal states the contract": "Open case" is disabled because the seeded Demo Term Life has no base rate table ("Not priced"), and the open-case form now gates on the quote. The spec needs a priced product.
  - `seed-dev-data.sh` Step 6 asks for a policy loan as `customer.owner`, and gets 403: loans have been staff-only (`loans.manage`) since 2026-10-08. The step is non-fatal by design.
