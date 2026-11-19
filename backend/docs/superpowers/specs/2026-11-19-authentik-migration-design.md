# Replace Keycloak with Authentik -- design

Status: DRAFT for the user's approval. Charted and decided through the wayfinder map at
[.scratch/authentik-migration/map.md](../../../../.scratch/authentik-migration/map.md). Each section cites the ticket
that holds its reasoning; this spec does not re-argue them.

Tickets cited by number, all in `.scratch/authentik-migration/issues/`:

- 01: Can Authentik text staff a sign-in code on every sign-in?
- 02: Can Authentik send a password reset by SMS only?
- 03: Can Authentik tell the backend when a password changes?
- 04: What does Authentik's API offer for the backend's login operations?
- 05: Can Authentik issue the tokens universal login needs?
- 06: What does it take to run Authentik here?
- 07: Go or no-go on Authentik
- 08: The webhook contract between Authentik and the backend
- 09: Login look in Authentik
- 10: How workspaces and job roles map to Authentik groups
- 11: Swahili on the Authentik login pages
- 12: Where a password reset starts
- 13: Test the silent staff phone set-up
- 14: How e2e tests sign in under Authentik
- 15: Deploying and operating Authentik
- 16: Build order inside the cut-over branch

## Why

Login flows, the texted sign-in code and the SMS hooks should be configured in Authentik's admin and in blueprints,
not written as custom Java extensions (SPIs) on Keycloak. The aim is to **delete** `backend/keycloak/extensions`,
not to port it.

## Decisions (user, 2026-11-19)

- **Data:** dev data only. No users or passwords are migrated; Authentik is seeded fresh.
- **Approach:** a direct swap. There is no provider-neutral layer: the adapters are renamed and the claims changed.
- **Cut-over:** one branch removes Keycloak entirely. Production never runs both.
- **Version:** stock **authentik 2026.8.5**. No custom Authentik code, no fork, no Swahili patch.
- **Go/no-go:** go, after the silent staff phone set-up was proven on a local Authentik
  ([Go or no-go on Authentik](../../../../.scratch/authentik-migration/issues/07-go-no-go.md),
  [Test the silent staff phone set-up](../../../../.scratch/authentik-migration/issues/13-test-silent-phone-setup.md)).

## What carries over, and what changes

| Behaviour today | After the cut-over | Ticket |
|---|---|---|
| Texted code at every staff sign-in; production refuses to start without it | Same. Authentik's SMS stage (generic provider) calls the backend; the device is set up silently at first sign-in | 01, 13, 08 |
| Three wrong codes end the attempt | **Changed:** Authentik slows each retry. The backend caps sends at 5 an hour and 1 a minute | 07 |
| Reset by SMS: six-digit code, 15 minutes, English or Swahili | Same code and wording, now on a platform page `/reset`. The browser is then sent to Authentik's recovery link, which is never texted | 11, 12, 07 |
| "Your password changed" text | Same. Authentik's `password_set` event goes through a notification rule and webhook. The person's own change only, never an admin or API set | 03, 07, 08 |
| Universal login, workspace chosen by `X-Workspace` | Same. One provider and application serve all four consoles | 05, 10 |
| Phone number as the username | Same | 04 |
| Green login theme | **Changed:** variant A, "Split leaf", built with Authentik layout and CSS. Accepted differences listed below | 09 |
| Swahili login pages | **Changed:** Authentik's sign-in and set-password pages are English only. The reset request and all texts stay bilingual | 11 |
| Staff can't reset their own password | Same. `/reset` texts nothing to staff numbers | 12 |
| 10-hour session cap | Same, enforced by the console from the token's `auth_time`. 30-minute idle timeout | 07 |

## Part 1 -- Authentik configuration (blueprints)

All configuration is blueprints, in two folders:

- **`deploy/authentik/blueprints/production/`** is baked into the `-auth` image.
- **`deploy/authentik/blueprints/dev/`** is mounted only by the dev compose.

### Sign-in flow ([09](../../../../.scratch/authentik-migration/issues/09-login-look.md), [01](../../../../.scratch/authentik-migration/issues/01-staff-sign-in-code.md), [13](../../../../.scratch/authentik-migration/issues/13-test-silent-phone-setup.md), [15](../../../../.scratch/authentik-migration/issues/15-deployment-and-operations.md))

1. **Identification**, with `password_stage` set so username and password share one page. There is no separate
   password stage binding.
2. **Authenticator validation**, with `device_classes: [sms]`, `not_configured_action: configure`, configuration stage
   = the SMS set-up stage, and `last_auth_threshold: seconds=0`.
   - **When it applies:** the binding is created only when `STAFF_SIGN_IN_CODE` is true (`!Env`). Its policy passes
     staff only.
   - **Silent set-up:** the same policy copies the login's **username** into `prompt_data.phone`, so the first
     sign-in sets up the device with no phone screen. The device number is only a key.
3. **User login.**
4. **A policy on the flow refuses superusers.** This replaces today's 404 on the `master` realm.

The SMS set-up stage uses the generic provider, posting to the backend's sign-in code endpoint with a bearer secret,
through a webhook mapping that shapes the body as Part 3 describes.

### Provider, tokens and groups ([05](../../../../.scratch/authentik-migration/issues/05-tokens-and-universal-login.md), [10](../../../../.scratch/authentik-migration/issues/10-groups-and-roles.md))

- **One public OAuth2 provider and one application.** Each console origin has one exact redirect address and one
  logout address. A policy enforces PKCE S256.
- **`sub_mode: user_uuid`.** A dedicated **RSA** signing key, never HS256 or EdDSA.
- **"Include claims in id_token" stays ON.** Authentik builds the access token from the ID token.
- **One custom scope mapping.** Do not attach the stock `entitlements` mapping. It emits:
  - `roles`: from `user.all_groups()`, filtered to `staff`, `agent`, `customer` and `regulator`, as a flat list
  - `tenant_id`, `party_id` and `home_branch`: from user attributes
- **Scopes:** add `offline_access`; drop `email`.
- **Lifetimes:** access token 5 minutes, refresh 30 minutes (idle), sign-in session 10 hours.
- **Groups:** exactly four, `staff`, `agent`, `customer` and `regulator`. Job roles are granted and checked in the
  platform's `iam` tables.
- **The `job_roles` attribute** (amended 2026-11-19): a login may carry a `job_roles` attribute, emitted as its own
  `job_roles` claim. `AccessService.importFromToken` reads it **once**, at a staff member's first sign-in; nothing else
  reads it. This is how the dev users and `add-user.sh`'s first administrator get their roles.
- **The backend's service account and its non-expiring token,** with the key from `.env`. Its role holds:
  - "add user", globally
  - change, delete and reset-password only on users it created
  - add and remove on `agent` and `customer`
  - add only on `staff`
  - nothing on `regulator`

### Notifications ([03](../../../../.scratch/authentik-migration/issues/03-password-changed-text.md), [08](../../../../.scratch/authentik-migration/issues/08-webhook-contract.md))

A notification rule on `password_set` is sent to the event's own user through a generic webhook transport, with a
bearer secret. Its policy excludes request paths under `/api/v3/core/users/`, so admin, API and blueprint password
sets never trigger the text.

### Recovery ([12](../../../../.scratch/authentik-migration/issues/12-reset-request-page.md))

The brand's recovery flow is a one-step flow that redirects to `/reset`.

### Branding ([09](../../../../.scratch/authentik-migration/issues/09-login-look.md))

- **Layout:** the flow layout is `content_right`, with a **wordmark-free** version of `brand.jpg` as the background.
- **Logo:** a 48px `mark.svg`.
- **Custom CSS:** the prototype's `base.css` plus `a-split.css` (on the branch `prototype/authentik-login-look`):
  - today's green tokens
  - 48px rounded fields and the lime pill button
  - the white panel through `ak-flow-executor::part(main)`
  - the footer hidden

**Accepted differences:**
- the photo is lost on phones
- the panel isn't flush right or full height
- bold labels and required-field stars
- the system font
- Authentik's language switcher
- no "Accounts are issued by your administrator" line

### Dev-only blueprint ([14](../../../../.scratch/authentik-migration/issues/14-e2e-sign-in.md))

- The 14 test users (`staff.underwriter` through `regulator.tira`), with attributes, groups and `devpassword`.
- An app-password token for each, with a fixed dev key.
- An `e2e-sign-in-code` group, whose members get the code step even with `STAFF_SIGN_IN_CODE` off.

## Part 2 -- Backend

### Tokens ([05](../../../../.scratch/authentik-migration/issues/05-tokens-and-universal-login.md), [16](../../../../.scratch/authentik-migration/issues/16-build-order.md))

- **`SecurityConfig.authoritiesFor`** reads `roles` instead of `realm_access.roles`; `Workspace.resolve` is unchanged.
- **The issuer** is `https://<auth>/application/o/<slug>/`, and must match exactly, trailing slash included.
- **The issuer validator landed first, on `main`** (step 0 below). The audience validator comes with step 2.

### Login management ([04](../../../../.scratch/authentik-migration/issues/04-admin-api-for-logins.md), [10](../../../../.scratch/authentik-migration/issues/10-groups-and-roles.md))

The three adapters are rewritten for Authentik's API and lose "Keycloak" from their names: `StaffLogins`,
`AgentLogins` and `CustomerAccounts`. All 14 operations map. The traps:

- **Ids:** the API addresses users by an integer id, while the platform holds the UUID `sub`. Look one up from the
  other on each call.
- **Duplicate username:** Authentik answers **400** with a `username` field error, not 409.
- **Temporary password:** `set_password`, then a `reset_password` attribute that a check stage enforces. Save the
  **whole** attributes record, or `tenant_id` and `party_id` are lost.
- **Signing out a login that stays open:** delete sessions **and** refresh tokens.
- **Disabling:** `PATCH is_active=false` also ends the user's sessions and tokens.
- **Workspaces:** granted and revoked through the group's `add_user` and `remove_user`.

### Webhook endpoints ([08](../../../../.scratch/authentik-migration/issues/08-webhook-contract.md))

`KeycloakSignature` is deleted. Each endpoint has its own bearer secret, and both stay behind Caddy's
`/api/internal/*` block.

`POST /internal/staff-sign-in-code/sms`:
- **Body:** `{tenantId, userId (UUID), code, phone}`.
- **Phone:** the backend **ignores `phone`** and texts the staff record's phone, falling back to the login's phone.
- **Caps:** they move into `StaffSignInCodes`.
- **Replies,** shown to the person by Authentik:

| Outcome | Status | Text |
|---|---|---|
| texted | 204 | |
| no phone | 422 | "No phone number is on file for you. Ask an administrator." |
| over the cap | 429 | "Too many codes. Wait a minute and try again." |
| gateway failed | 503 | "We couldn't send your code. Try again." |

`POST /internal/password-changed`:
- **Body:** `{eventId, userId (UUID), tenantId, partyId, at}`.
- **Answered 202** at once, with the text sent in the background.
- **Dedupe** on `eventId`.
- **The text is stamped** with `at`.
- **Anything ignored on purpose** still gets a 2xx, so Authentik doesn't retry it.

### Password reset ([12](../../../../.scratch/authentik-migration/issues/12-reset-request-page.md), [07](../../../../.scratch/authentik-migration/issues/07-go-no-go.md))

New public endpoints serve the `/reset` page, reusing the public quote's parts: `PublicAccess` (tenant from
configuration, Turnstile, visit token), `PhoneCodes` and `PhoneNumbers.normalise`. `ResetCodes`' rules move in from
the extension:

- six digits
- 15 minutes
- five wrong tries spend the code
- an hourly cap, the same for unknown numbers

Staff numbers are answered the same way and texted nothing. When the code checks out, the backend calls
`POST /core/users/{id}/recovery/` with a 15-minute lifetime, and returns the link for the browser to follow. The link
must be built with the **public** host.

### Settings and guard ([15](../../../../.scratch/authentik-migration/issues/15-deployment-and-operations.md))

The settings are `AUTHENTIK_BASE_URL`, `AUTHENTIK_API_TOKEN`, `AUTHENTIK_ISSUER`, `SIGN_IN_CODE_WEBHOOK_SECRET`,
`PASSWORD_CHANGED_WEBHOOK_SECRET` and `STAFF_SIGN_IN_CODE`. `STAFF_SIGN_IN_CODE` is shared with the blueprint.

`ProductionSettingsGuard` refuses:
- dev defaults for the token and both secrets
- `STAFF_SIGN_IN_CODE` off
- an issuer without its trailing slash

## Part 3 -- Frontend

- **`claims.ts` and `realms.ts`** read `roles`.
- **The OIDC configuration** points at the Authentik issuer. Set `revokeTokensOnSignout: true`.
- **A forced sign-out 10 hours after `auth_time`.**
- **The `/reset` page:** public and bilingual (English/Kiswahili), shared by agents and customers, with Turnstile.
  - It always answers "If this number has a login, we've texted a code."
  - It takes the code on the same page, then sends the browser to the returned recovery link.
  - After the new password is set, the person lands on `/`.

## Part 4 -- E2E ([14](../../../../.scratch/authentik-migration/issues/14-e2e-sign-in.md))

- **One shared sign-in helper:** `getByLabel('Username')`, `getByLabel('Password')`,
  `getByRole('button', { name: 'Log in' })`. `storageState` keeps Authentik's session cookie.
- **`creditLife.ts`:** the password grant uses the user's dev app-password key.
- **A new spec:** an `e2e-sign-in-code` user signs in, reads the code from `:8083/__admin/requests`, types it, and
  reaches the Dashboard.
- **Runs:** `--workers=2`. If sign-in timeouts appear, try `--workers=1` first.

## Part 5 -- Deployment ([15](../../../../.scratch/authentik-migration/issues/15-deployment-and-operations.md))

- **The `-auth` image:** `FROM ghcr.io/goauthentik/server:2026.8.5`, with the production blueprints, CSS and images.
  Built and versioned by `release.sh`.
- **Server compose:** a server (1 GiB) and a worker (512 MiB). No Docker socket and no root.
- **Database:** an `authentik` database on the existing Postgres, with `max_connections` raised and the SSL mode set
  explicitly.
- **Backups:** `backup.sh` and `restore.sh` pair `authentik` with `lifeplatform`, replacing `keycloak`.
- **Caddy on `AUTH_DOMAIN`:** 404 on `/if/admin/*`; the admin is reached by SSH tunnel. `/api/v3/*` stays open,
  because the sign-in page needs it.
- **`bootstrap-server.sh`:** generates `AUTHENTIK_SECRET_KEY`, `AUTHENTIK_BOOTSTRAP_PASSWORD` and the backend's token
  key.
- **`add-user.sh`:** keeps its flags and adds `--workspace staff|agent|customer|regulator`. It prints a one-time
  password, with a forced change at first sign-in.
- **`remote-release.sh`:** waits for the server and worker to be healthy before the backend.
- **`smoke.sh`:** fails if a dev user exists.
- **`DEPLOYMENT-GUIDE.md`:** rewritten, including **planned signing-key rotation**. The JWKS lists one key, so
  rotating signs everyone out: announce it and do it at a quiet hour.

## Build order ([16](../../../../.scratch/authentik-migration/issues/16-build-order.md))

**Step 0, on `main`, separately: done 2026-11-19.** A test proved today's token validation, and `SecurityConfig.lazyDecoder`
now checks the **issuer** (`JwtValidators.createDefaultWithIssuer`; `SecurityConfigDecoderTest`).

The **audience** check moves into cut-over step 2 (the user's choice, 2026-11-19). Keycloak's access tokens carry no
`aud` claim, because the realm has no audience mapper, so the check can't be switched on without Keycloak
configuration that step 9 deletes. Until then, a token from another client of the same realm still passes the
decoder. The only such client in production is `lifeplatform-admin`, whose tokens hold no workspace role, so
`WorkspaceFilter` refuses them.

**Cut-over branch.** Each step ends with its affected tests and a manual check. Keycloak stays in the dev compose
until step 3.

1. **Authentik in dev:** production and dev blueprints. Check the decoded token's claims.
2. **Backend reads Authentik tokens:** the **audience validator** (`aud` must hold the SPA's client id), test helpers
   and the test `application.yml`.
3. **Frontend signs in with Authentik.** Keycloak leaves the dev compose.
4. **The three login adapters.**
5. **The two webhooks.**
6. **`/reset`,** on both the backend and the page.
7. **E2E.**
8. **Deployment.**
9. **Deletions:** `backend/keycloak/`, `deploy/keycloak/`, the realm JSON, `KeycloakSignature` and the old auth
   Dockerfile. Finish with a grep for `keycloak`.

**Merge gate,** one heavy run at a time, in this order:
1. **Full backend suite**, with the dev backend stopped.
2. **Full vitest.**
3. **Full e2e.**
4. **Fresh-bootstrap rehearsal:** the dev stack from `down -v`, plus a dry run of the server compose.

Then a solo whole-branch review, and merge `--no-ff`.

## Checks to make during the build

These were not confirmed at planning time. Check each early in its step, and bring any failure back to the user:

- **Redirect to `/reset`:** can the 2026.8.5 redirect stage point at an outside URL? The fallback is a link in the
  flow's title text.
- **PKCE:** does the `oauth_code_challenge_method` policy actually refuse a sign-in without S256?
- **`password_stage`:** with the separate password binding removed, is the password still asked exactly once?
- **Healthcheck:** the command for the server and worker (likely `ak healthcheck`).
- **The superuser-refusal policy:** it must not block the bootstrap admin over the SSH tunnel.
- **Token refresh:** does a refresh recalculate the claims, for example after a workspace is granted?
- **Rights:** the exact permission names for deleting refresh tokens through the service account's role.

## Out of scope

- Migrating users or passwords (dev data only).
- A provider-neutral login layer.
- Email, for anything.
- Contributing Swahili to Authentik upstream, a goodwill follow-up.
