# Keycloak realm import

**One realm, `lifeplatform`, for everyone** (2026-11-06, universal login; four realms -- `staff`, `agents`,
`customers`, `regulators` -- before that). Imported by the `keycloak` container (Keycloak 26) from
`lifeplatform-realm.json` on a fresh volume. The server builds its realm from this same file
(`deploy/keycloak/build-realms.mjs`, see `deploy/README.md`).

- Client secrets (`dev-secret-*`) are dev-only placeholders, never used outside local `docker compose up`. Every user
  password below (`devpassword`) is equally dev-only.
- **The realm file only takes effect on a realm that does not exist yet.** `--import-realm` skips a realm already in
  Keycloak's database, so an edit here does nothing on a running dev stack. Apply a change with `kcadm.sh`, or
  `docker compose down -v` for a fresh import. (The old `scripts/apply-*` patch scripts targeted the four old realms
  and were removed with them.)
- Moving an existing dev stack from the four realms: restart `keycloak` (it imports `lifeplatform`), then delete the
  old four -- `docker exec infra-keycloak-1 /opt/keycloak/bin/kcadm.sh delete realms/staff` and the same for
  `agents`, `customers`, `regulators`, after `kcadm.sh config credentials --server http://localhost:8080 --realm master
  --user admin --password devadmin`. On Git Bash set `MSYS_NO_PATHCONV=1` first, or the container path is rewritten.
  Then write each seed user's `party_id` again (see *Test users*).

## Roles: workspaces and jobs

Two kinds of realm role, never confused:

- **Workspace roles** -- `staff`, `agent`, `customer`, `regulator`. Which console an account may open. The console
  sends the workspace it is in as `X-Workspace`, and the backend grants exactly one `ROLE_REALM_*` per request from it
  (`iam/infrastructure/Workspace.java`). An account may hold `agent` and `customer` together (one person, one
  password); a staff account never holds another workspace (decision U10).
- **Job roles** -- UNDERWRITER, SENIOR_UNDERWRITER (may decide against the engine's advice), CLAIMS_ASSESSOR,
  CLAIMS_MANAGER, FINANCE_OFFICER, FINANCE_APPROVER (the second person on finance acts, e.g. manual journals),
  CUSTOMER_SERVICE_REP, ADMIN (product authoring, configuration), and TIRA_READ_ONLY for regulators. They count only
  in the staff and regulator workspaces. (Since 2026-10-26 staff permissions come from the platform's own iam tables;
  these Keycloak job roles remain for the seed users and `APP_ACCESS_SOURCE=token`.)

## Clients

| Client | What it is |
|---|---|
| `lifeplatform-spa` | **The console** (Vite SPA on `http://localhost:5173`): a public client, authorization code + PKCE, tokens held in memory only. |
| `lifeplatform-app` | The original confidential client, secret `dev-secret-app`. Kept for direct API flows (the seeder's and e2e fixtures' password grants); its `localhost:3000` redirect is a leftover of the deleted Next.js portal. Not in production. |
| `lifeplatform-admin` | The service account (`manage-users`, `view-users`) the backend uses to create, open and close staff, agent and customer logins. Secret `dev-secret-admin`; `KEYCLOAK_ADMIN_CLIENT_SECRET` elsewhere. |

## Login theme

`themes/lifeplatform/` is the custom login page (the photo-led "Split leaf" design, in green, deliberately not the
console's palette). The realm sets `loginTheme: lifeplatform`. To see a change, the theme must be rendered by the
running container -- edits are not visible until Keycloak picks them up.

## Protocol mappers

Both clients map three user attributes onto token claims via `oidc-usermodel-attribute-mapper`, with
`access.token.claim: true` (the backend reads the **access** token, not the ID token):

- **`tenant_id`** -- every user. `TenantContextFilter` reads it at exactly one call site and rejects any request whose
  token lacks a parseable UUID there with `403 Forbidden` / `errorCode: TENANT_CLAIM_MISSING`, before the request ever
  reaches a controller.
- **`party_id`** -- agents and customers. Read via `jwt.getClaimAsString("party_id")` across several controllers to
  enforce ownership checks -- e.g. a customer may only read their own policy/claim/loan. Staff carry none (staff bypass
  ownership checks by role), and so a staff login can never be joined to a customer or agent login, which are matched
  by `party_id`.
- **`home_branch`** -- staff. Needed to post a journal.

## The `components` block -- `unmanagedAttributePolicy: ADMIN_EDIT`

**Do not reformat, "tidy", or delete the `components` block of the realm file without reading this section.** It is a
single escaped JSON string on purpose (that is the shape Keycloak's realm importer expects for
`kc.user.profile.config`), and it is the reason `party_id` can be written onto a user at all.

Keycloak (since 24, still in 26) enables the **declarative User Profile** for every realm and, by default, sets its
unmanaged-attribute policy to *Disabled*. Under that default, any user attribute whose key is not declared in the
profile is not merely rejected -- it is **silently dropped**. An Admin-API `PUT /admin/realms/{realm}/users/{id}`
carrying `"attributes": {"party_id": [...]}` returns `204 No Content`, and a follow-up `GET` on the same user shows no
`party_id` at all. No error, no warning, nothing in the server log.

`"unmanagedAttributePolicy": "ADMIN_EDIT"` is the fix: it permits attributes that the profile does not declare,
readable and writable **by administrators only** (the Admin API and the admin console) -- end users can neither see
nor edit them through account forms, so this is not the permissive `ENABLED` policy.

## Test users

Every user carries a `tenant_id` **attribute** set to the single well-known dev tenant
`11111111-1111-1111-1111-111111111111`. The platform has no tenant directory, so a tenant is simply a UUID appearing in
RLS-scoped rows.

`party_id` is deliberately **absent** from every user in the file and is instead set by `scripts/seed-dev-data.sh`
through the Keycloak Admin API, because a party's UUID is generated by `POST /parties/individuals` at seed time and
cannot be known when this file is written.

| Username | Workspace | Job roles | Why this user exists |
|---|---|---|---|
| `customer.owner` | customer | -- | owns policies, claims, a loan |
| `customer.other` | customer | -- | owns **nothing** -- the only way to see an ownership check actually deny |
| `agent.senior` | agent | -- | supervisor in the hierarchy |
| `agent.junior` | agent | -- | subordinate, so the hierarchy walk is exercised |
| `staff.underwriter` | staff | `UNDERWRITER` | records the underwriting decision that auto-issues a policy |
| `staff.senior` | staff | `UNDERWRITER`, `SENIOR_UNDERWRITER` | may decide against the engine's recommendation |
| `staff.assessor` | staff | `CLAIMS_ASSESSOR` | assesses a claim |
| `staff.manager` | staff | `CLAIMS_MANAGER` | approves settlement -- **must be a different user** than the assessor, because claims enforces separation of duties on the persisted assessor identity |
| `staff.finance` | staff | `FINANCE_OFFICER` | onboards agents, reads GL and regulatory returns |
| `staff.finance-approver` | staff | `FINANCE_OFFICER`, `FINANCE_APPROVER` | the second person on finance acts |
| `staff.admin` | staff | `ADMIN` plus the operational roles | product authoring and configuration; also used by the e2e suite |
| `regulator.tira` | regulator | `TIRA_READ_ONLY` | the read-only regulator portal (no console screens yet: sign-in says "no access yet") |

A separation-of-duties rule is only tested if two different users act: keep using `staff.assessor` and
`staff.manager` (or `staff.finance` and `staff.finance-approver`) for the two halves, never `staff.admin` for both.

## Redirect URIs

`lifeplatform-spa` accepts `http://localhost:5173/*` (the console). `lifeplatform-app` still lists
`["http://localhost:3000/*", "http://localhost:8080/*"]`. The server's realm gets its own origins from
`deploy/keycloak/build-realms.mjs` (`APP_DOMAIN`), not from these values.

## Forgot password (2026-10-28)

"Forgot your password?" runs the `lifeplatform reset credentials` flow: one request emails the link to the address on
file and texts a six-digit code to the phone the platform holds, and every name typed sees the same page. The link and
the code last 15 minutes; one account is texted at most once a minute and three times an hour, counted per account
across browsers. **Staff are sent nothing** (2026-11-06, decision U4): an account holding the `staff` role gets the
same page and no link or code, and an administrator resets them from Users & access -- by SMS to the phone on their
staff record, or shown once when there is none.

## "Your password was changed" (2026-11-06)

The extension's event listener `lifeplatform-password-changed` (named in the realm's `eventsListeners`) hears every
`UPDATE_PASSWORD` -- a change from the account menu, the new password at the end of a reset, the first change after a
temporary password -- and asks the platform, signed with the reset secret, to text the person: "Your Nlolo password was
changed on 6 Nov at 14:32. If this wasn't you, call {SUPPORT_PHONE}." (Swahili for Swahili sign-ins). The platform
sends to the client record's phone for agents and customers and the staff record's phone for staff. Keycloak reaches it
at `LP_PASSWORD_CHANGED_URL` (dev: `host.docker.internal:8080/internal/password-changed`). The `email` listener still
emails the same news when mail is on.

The text needs the platform's Keycloak extension, `extensions/` here. Build it before starting the stack -- the compose
file mounts the jar, and a missing one becomes an empty folder Keycloak cannot load:

    ./mvnw -f keycloak/extensions/pom.xml package        # from backend/

Keycloak calls the backend at `LP_RESET_SMS_URL` (dev: `host.docker.internal:8080`), signed with
`PASSWORD_RESET_SMS_SECRET`. The backend sends through the ordinary SMS gateway, which dev keeps switched off; to see
codes in dev, run the backend with `SMS_GATEWAY_LIVE=true` and `SMS_GATEWAY_BASE_URL=http://localhost:8083` (the mock)
and read them at http://localhost:8083/__admin/requests. Emails land in Mailpit, http://localhost:8025.
