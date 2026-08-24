# Keycloak realm imports

Minimal, importable stub realms for local development (see
`docs/superpowers/plans/2026-08-05-m0-platform-bootstrap.md`, Task 7).

- Client secrets (`dev-secret-*`) are dev-only placeholders, never used outside
  local `docker compose up`. Every user password below (`devpassword`) is
  equally dev-only.
- The `staff` realm's six roles (UNDERWRITER, CLAIMS_ASSESSOR, CLAIMS_MANAGER,
  FINANCE_OFFICER, CUSTOMER_SERVICE_REP, ADMIN) are quoted verbatim from
  `docs/04-api-contracts.md`.
- `TIRA_READ_ONLY` (regulators realm) and the `lifeplatform-app` client ID are
  this project's own naming for the read-only TIRA portal concept described
  in `docs/04-api-contracts.md` §3 -- not sourced from an external spec.

## Protocol mappers

Every `lifeplatform-app` client now maps two user attributes onto token
claims via `oidc-usermodel-attribute-mapper`, with `access.token.claim: true`
(the backend, `SecurityConfig`/`TenantContextFilter`, reads the **access**
token, not the ID token):

- **`tenant_id`** -- all four realms. `TenantContextFilter`
  (`src/main/java/tz/co/nlolo/lifeplatform/iam/infrastructure/TenantContextFilter.java`)
  reads it at exactly one call site
  (`jwt.getClaimAsString("tenant_id")`) and rejects any request whose token
  lacks a parseable UUID there with `403 Forbidden` /
  `errorCode: TENANT_CLAIM_MISSING`, before the request ever reaches a
  controller. Without this mapper no token issued by any of the four realms
  carried `tenant_id` at all, so every authenticated request 403'd -- this is
  what actually made the platform loginable.
- **`party_id`** -- `customers` and `agents` only. Read via
  `jwt.getClaimAsString("party_id")` at 10 call sites across 6 controllers
  (`PolicyController`, `ClaimController` (x3 call sites plus one), `BillingController`,
  `PartyController`, `PolicyLoanController`, `AgentController`) to enforce
  ownership checks -- e.g. a customer may only read their own policy/claim/loan.
  Not added to `staff` (staff bypass ownership checks by role, not by
  `party_id` comparison) or `regulators` (regulators own no objects). This
  README previously said no claim mapper existed for `party_id` or
  `agentOfRecord` (M0-era); that second name was always aspirational --
  nothing under `src/main/java` reads an `agentOfRecord` claim, so it is not
  mapped here or anywhere.

## The `components` block — `unmanagedAttributePolicy: ADMIN_EDIT`

**Do not reformat, "tidy", or delete the `components` block at the bottom of any
of the four realm files without reading this section.** It is a single escaped
JSON string on purpose (that is the shape Keycloak's realm importer expects for
`kc.user.profile.config`), and it is the reason `party_id` can be written onto a
user at all.

Keycloak 24 enables the **declarative User Profile** for every realm and, by
default, sets its unmanaged-attribute policy to *Disabled*. Under that default,
any user attribute whose key is not declared in the profile is not merely
rejected — it is **silently dropped**. An Admin-API `PUT
/admin/realms/{realm}/users/{id}` carrying `"attributes": {"party_id": [...]}`
returns `204 No Content`, and a follow-up `GET` on the same user shows no
`party_id` at all. No error, no warning, nothing in the server log. This cost
real debugging time during M11 Task 9: the seeder's write-back appeared to
succeed on every run while the issued tokens never gained the claim, and the
symptom surfaced far away as a `403` from an ownership check.

`"unmanagedAttributePolicy": "ADMIN_EDIT"` is the fix: it permits attributes
that the profile does not declare, readable and writable **by administrators
only** (the Admin API and the admin console) — end users can neither see nor
edit them through account/registration forms, so this is not the permissive
`ENABLED` policy.

- **`customers` / `agents` need it functionally.** `scripts/seed-dev-data.sh`
  step 4 writes each user's `party_id` through the Admin API after the party is
  created, because a party's UUID does not exist until seed time (see the note
  under *Test users*). Remove the policy from either realm and that write-back
  becomes a silent no-op, the `party_id` protocol mapper has nothing to project,
  and every ownership-checked endpoint `403`s.
- **`staff` / `regulators` carry it for consistency, not necessity.** Neither
  realm needs a `party_id` write-back today. The policy is declared here anyway
  so all four files state their behaviour explicitly instead of inheriting an
  implicit Keycloak default that changed between major versions — and because
  `tenant_id` is itself an unmanaged attribute on these users, so under
  *Disabled* it is invisible and uneditable in the admin console, which is its
  own footgun. Purely additive: it changes no client, mapper, role, or user, and
  therefore nothing about which claims these two realms' tokens carry.

## Test users

Every user carries a `tenant_id` **attribute** (not a claim directly --
the mapper above projects it onto the token) set to the single well-known
dev tenant `11111111-1111-1111-1111-111111111111`. This needs no
coordination with any table: the platform has no tenant directory
(established in M10), so a tenant is simply a UUID appearing in RLS-scoped
rows. It is the same UUID `regreporting`'s V2 migration already seeds its
placeholder return definition against, so seeded reference and reporting
data line up.

`party_id` is deliberately **absent** from every user here and is instead
set by Task 9's seeder via the Keycloak Admin API, because a party's UUID is
generated by `POST /parties/individuals` at seed time and cannot be known
when this file is written.

| Realm | Username | Realm roles | Why this user exists |
|---|---|---|---|
| customers | `customer.owner` | -- | owns policies, claims, a loan |
| customers | `customer.other` | -- | owns **nothing** -- the only way to see an ownership check actually deny |
| agents | `agent.senior` | -- | supervisor in the hierarchy |
| agents | `agent.junior` | -- | subordinate, so the hierarchy walk is exercised |
| staff | `staff.underwriter` | `UNDERWRITER` | records the underwriting decision that auto-issues a policy |
| staff | `staff.assessor` | `CLAIMS_ASSESSOR` | assesses a claim |
| staff | `staff.manager` | `CLAIMS_MANAGER` | approves settlement -- **must be a different user** than the assessor, because claims enforces separation of duties on the persisted assessor identity |
| staff | `staff.finance` | `FINANCE_OFFICER` | onboards agents, reads GL and regulatory returns |
| regulators | `regulator.tira` | `TIRA_READ_ONLY` | the read-only regulator portal |

`ADMIN` is deliberately not among the staff users: `FINANCE_OFFICER` reaches
every finance-gated endpoint, and a dev `ADMIN` account invites using it as a
bypass instead of fixing a real gate.

## Redirect URIs

Each `lifeplatform-app` client's `redirectUris` was the wildcard `["*"]`
(any redirect target accepted) until this task; it is now
`["http://localhost:3000/*", "http://localhost:8080/*"]` with
`webOrigins: ["http://localhost:3000"]`. `localhost:3000` is M12's portal;
`localhost:8080` covers direct API-side flows. The client stays
`publicClient: false` -- the Next.js BFF exchanges the authorization code
server-side, so the client secret never reaches a browser and no public
client is needed.
