# M11 — `document`/`refdata` APIs, and a local environment that can actually be logged into

**Status:** design approved, pending implementation plan
**Date:** 2026-08-20
**Depends on:** M0–M10 (all merged to `main` at `f5f817e`, 642/642 tests green)
**Blocks:** M12 (Customer Portal) — on both deliverables

**Two deliverables, both prerequisites for the portal:**

- **A. The last two API gaps** (§1–§8) — four endpoints, one migration, an OpenAPI split.
- **B. A local environment a browser can log into** (§9) — added after a question about
  whether frontend work would be able to see real database changes exposed a blocker that
  642 passing tests cannot see: **no token Keycloak currently issues can call any endpoint
  on this platform.** Detail in §9.

---

## 1. Why this milestone exists

M10 completed the backend roadmap. A readiness review for frontend work then found that
`document` and `refdata` are the platform's only two modules that own real, working
capability behind **zero HTTP surface** — and that one of them hides an actual defect
rather than merely a gap.

**The defect: claim evidence is write-only over HTTP.** `ClaimEvidenceController` exposes
`POST /claims/{claimId}/evidence` (upload) and `GET /claims/{claimId}/evidence` (list
metadata, including each `documentRef`). There is no download endpoint anywhere on the
platform — verified by grepping every controller and every OpenAPI path. A claimant can
upload a death certificate, and both they and the claims assessor can see that it exists,
but **no one can ever retrieve the file over the API.** `DocumentApi.download` works and is
exercised by `ClaimEvidenceIntegrationTest`; nothing routes to it. A claims assessor
adjudicating a claim cannot open its evidence.

**The gap: `refdata` has no reader.** Nine seeded parameter sets (policy-loan interest
rate, contestability months, dunning escalation days, base premium rate, commission
clawback months, and others) are readable only in-process via `ReferenceDataApi`. A
frontend that needs to tell a customer "loan interest accrues at 12% p.a." has no
choice but to hardcode the number, which then silently drifts from the seeded value.

A draft OpenAPI document for both modules has existed since Phase 0
(`api/openapi/openapi-regreporting-document-refdata.yaml`) and `docs/04-api-contracts.md:60-61`
already documents an auth matrix for endpoints that were never built.

Designing the download endpoint then surfaced a third problem — the stored content type and
original filename are unrecoverable on the read path — which expands this milestone by one
small migration. §5.1 has the detail.

## 2. Scope correction recorded deliberately

Two things this milestone was initially scoped to include, and does not. Both are recorded
here so the omissions read as decisions rather than oversights.

**2.1 No `/parties/{partyId}/documents` or `/policies/{policyNumber}/documents` listing.**
The first design draft proposed these as the customer-facing document surface. They were
dropped after verification: **`ClaimEvidenceController:67` is the only caller anywhere on
the platform that ever uploads a document** (`grep -rn "\.upload("` across `src/main/java`,
excluding `document`'s own internals, returns exactly one hit). Every stored document
therefore has an `ownerContext` of the form `"claim:{claimId}"`. Nothing creates
`"party:…"` or `"policy:…"` documents, so both endpoints would return an empty array for
every caller, forever, while appearing in the contract as working features. That is the
vacuous-feature shape this platform has been bitten by before (a smoke test that booted an
empty jar for two milestones). Revisit when a producer exists — a customer-facing KYC
upload, or policy-schedule PDF generation.

**2.2 No reference-data lookup lists, because the backend does not accept them.** This
milestone was initially justified partly by "the portal needs occupation / ID-type /
country dropdowns." That premise was wrong. `RegisterIndividualRequest`
(`api/openapi/openapi-party.yaml`) requires exactly `fullName`, `dateOfBirth`, and
`contactInfo` (a `^\+255\d{9}$` phone and an optional email) — there is no occupation,
nationality, or ID-type field to populate. Every enum-like value the portal renders
(`PartyType`, `KycStatus`, claim types, policy statuses) is a Java enum already published
in a module's OpenAPI spec, so a generated TypeScript client receives them as union types
with no runtime call. `refdata`'s seeded content is operational parameters only, and the
one endpoint in §3 serves exactly that.

## 3. The four endpoints

| # | Endpoint | Realms / roles | Returns |
|---|---|---|---|
| 1 | `GET /claims/{claimId}/evidence/{documentRef}` | customers, agents, staff — plus object-level ownership | the document's own stored media type (§5.2) |
| 2 | `GET /documents/{documentRef}` | **staff only** | the document's own stored media type (§5.2) |
| 3 | `GET /documents/{documentRef}/metadata` | **staff only** | `application/json` |
| 4 | `GET /reference-codes/{codeSetKey}` | any authenticated realm, subject to a per-realm key allowlist (§4) | `application/json` |

Endpoint 1 lives in `claims/infrastructure/ClaimEvidenceController` (which already holds
both evidence operations and already injects `DocumentApi`). Endpoints 2–3 live in a new
`document/infrastructure/DocumentController`. Endpoint 4 lives in a new
`refdata/infrastructure/ReferenceDataController`.

**No module-graph change.** `claims` already declares `document::api` in its
`allowedDependencies`. `document` and `refdata` gain controllers only — neither gains a
dependency, and in particular `document` does **not** gain one on `claims`, `party`, or
`policy`. That direction is forbidden: `claims`, `party`, `policy`, and `underwriting` all
declare `document::api`, so any reverse edge is a cycle `NoCircularDependencyTest` fails on.
This constraint is the reason the customer-facing download lives in `claims` rather than in
a generic document controller — see §4.

## 4. The security invariant

**Endpoint 1 must perform two independent checks, and failing to do the second reintroduces
a known defect class.**

1. **Does the caller own the claim?** Reuse `ClaimController.enforceCustomerOwnClaimOnly`
   verbatim — the same static helper both existing evidence operations already call. A
   customer whose `party_id` claim does not match the claim's `claimantPartyId` gets 403
   same-tenant / 404 cross-tenant, per `docs/04-api-contracts.md:41`.
2. **Does the requested document actually belong to that claim?** Fetch
   `DocumentApi.getMetadata(documentRef)` and require `ownerContext` to equal
   `"claim:" + claimId` exactly. Anything else is a 404, indistinguishable from a
   nonexistent ref.

Check 2 is not redundant. Without it, a customer who legitimately owns claim A can pass
their own `claimId` together with a `documentRef` belonging to a stranger's claim B and
receive the file — the caller-supplied identifier is authorized while a *different*
caller-supplied identifier selects the resource. This is exactly the nested-resource IDOR
M7 shipped in `AgentController` (`GET /agents/{agentId}/commission-statements/{statementId}/accruals`
verified the `agentId` and never that `statementId` belonged to it), which the M7 final
review caught only after 26/26 contract tests were green — the defect lived entirely in the
untested combination of two individually-correct checks. §7 requires a test that fails
without check 2.

**Tenant isolation needs nothing new.** `DocumentApiImpl.findOrThrow` already fails loud on
a cross-tenant `documentRef`, throwing the identical `NoSuchElementException` with the
identical message as a nonexistent ref, so a caller cannot infer another tenant's document
exists. RLS remains the primary control; that check is documented defense-in-depth. Object
keys in MinIO are already tenant-prefixed. Endpoints 2–3 inherit all of this and need no
object-level check of their own, because staff are authorized tenant-wide by definition.

**Endpoint 4 needs no tenant check at all.** `refdata.reference_code_set` is deliberately
not tenant-scoped and carries no RLS policy — its migration says so explicitly
(`db-migrations/refdata/V1__create_refdata_schema.sql:2-5`: "Deliberately NOT tenant-scoped
— global reference data … No RLS here for exactly that reason: there is no tenant to
isolate").

**But endpoint 4 does need a per-realm key allowlist, and this was nearly missed.** "Global
reference data" is not the same as "data every realm may read." Auditing all nine seeded
keys against who should see them:

| Key | Customers | Agents | Staff | Regulators | Why |
|---|---|---|---|---|---|
| `TZ_CONTESTABILITY_MONTHS` | yes | yes | yes | yes | disclosed in policy terms; a claimant needs it |
| `TZ_REINSTATEMENT_WINDOW_MONTHS` | yes | yes | yes | yes | disclosed in policy terms |
| `TZ_SUSPENSION_TO_LAPSE_MONTHS` | yes | yes | yes | yes | disclosed in policy terms |
| `TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE` | yes | yes | yes | yes | the rate the customer is charged |
| `DUNNING_ESCALATION_DAYS` | yes | yes | yes | yes | when a customer is chased; disclosed anyway |
| `OFFLINE_RECEIPT_SLA_HOURS` | no | yes | yes | no | field-agent operational SLA |
| `POLICY_SUSPENSION_ELIGIBLE_CATEGORIES` | no | yes | yes | no | internal eligibility rule |
| `TZ_BASE_PREMIUM_RATE_PER_MILLE` | **no** | **no** | yes | no | **the pricing basis** — commercially sensitive |
| `TZ_COMMISSION_CLAWBACK_MONTHS` | **no** | yes | yes | no | agent commercial terms, not customer business |

So the controller carries an explicit **allowlist keyed by realm, defaulting to deny**: a
key not listed for the caller's realm returns 404, identical to a nonexistent key (§5.2), so
the allowlist itself cannot be used to discover which keys exist. Staff may read everything.
A new seeded key is invisible to every non-staff realm until someone deliberately adds it —
fail-closed, which is the right default for a table whose contents grow by migration and
whose sensitivity varies per row.

**`regulators` gets the same five publicly-disclosed keys as customers**, decided explicitly
rather than defaulted. A regulator's legitimate interest is in the statutory parameters a
policy discloses (contestability, reinstatement, lapse windows), not in the insurer's pricing
basis or its agents' commission terms — and this realm carries no fine-grained roles at all
(`SecurityConfig` synthesizes only `ROLE_REALM_REGULATORS`), so there is no finer gate to
apply. If a genuine supervisory need for the commercial parameters emerges, that is a
deliberate allowlist change with a reviewer, not an accident of a permissive default.

Without this, `GET /reference-codes/TZ_BASE_PREMIUM_RATE_PER_MILLE` with any customer token
returns the company's premium pricing basis. The draft spec's blanket
`customersAuth: [] / agentsAuth: [] / staffAuth: []` security block would have shipped
exactly that.

## 5. Contract shapes

### 5.1 A prerequisite the download endpoints expose: content type and filename are lost

Found while designing this spec, and it changes scope. `DocumentApiImpl.upload` accepts a
`contentType` parameter and passes it to MinIO's `putObject`, but **nothing on the read path
can recover it**: `MinioDocumentStorage.get` returns a bare `byte[]`, `document_record` has
no `content_type` column, and `DocumentMetadataView` does not carry one. The original
filename is never captured at all — `ClaimEvidenceController` does not read
`MultipartFile.getOriginalFilename()`.

`DocumentType` is not a substitute: `MinioDocumentStorage.bucketFor` maps it to a *bucket*
(`kyc-evidence`, `claim-evidence`, `underwriting-evidence`, `policy-documents`), not to a
media type. A `CLAIM_EVIDENCE` document is equally likely to be a JPEG or a PDF.

Without fixing this, endpoints 1–2 could serve only `application/octet-stream` with a
UUID for a filename — a customer's uploaded certificate photo could never render inline in
the portal, and a saved file would arrive named `3f9a…-b21c`. Both are cheap to fix in one
migration, so this milestone does:

- **`db-migrations/document/V2`** adds `content_type VARCHAR(255)` and
  `file_name VARCHAR(255)`, both **nullable** — existing rows genuinely have neither, and
  backfilling is impossible, so the read path must tolerate null (see below).
- `DocumentRecord`, `DocumentMetadataView`, and `DocumentApi.upload`'s signature carry both
  through. `ClaimEvidenceController` passes `MultipartFile.getOriginalFilename()`.
- **Width check** (per the platform convention that a CHECK/content width must be verified
  against real values in the same migration): `VARCHAR(255)` comfortably holds any real
  media type and any filename a browser sends. Also verified while here that V1's existing
  `owner_context VARCHAR(50)` still fits its only real producer — `"claim:"` plus a 36-char
  UUID is 42 characters — but note it leaves only 8 characters of headroom, so a future
  `"underwriting-case:{uuid}"` producer (18 + 36 = 54) would **not** fit and must widen the
  column in the same migration that introduces it. Recorded here because §2.1 anticipates
  exactly such producers.

### 5.2 Response shapes

**Endpoints 1 and 2** return the raw bytes with `Content-Type` from the stored
`content_type`, falling back to `application/octet-stream` when it is null (pre-V2 rows),
and `Content-Disposition: attachment; filename="…"` using the stored `file_name`, falling
back to `documentRef`. `DocumentApi.download` returns `byte[]`, so the response is fully
materialized in memory — acceptable at current evidence sizes (claim photos and
certificates), and called out in §8 as a known scaling limit rather than streamed now.

**Endpoint 3** returns `DocumentMetadataView`'s fields plus the two new ones:
`{ documentRef, ownerContext, documentType, contentType, fileName, uploadedBy, uploadedAt }`,
with `contentType` and `fileName` nullable in the schema for the same pre-V2 reason.
`ownerContext` is exposed as the opaque string it is; the spec documents its current
`"claim:{uuid}"` shape without promising it as a stable, parseable contract, because §2.1
expects new shapes when other producers appear.

**Endpoint 4** returns the code set as declared in the existing draft:
`{ codeSetKey, values: [ { code, label, value, jurisdiction } ] }`, mapping
`ReferenceCodeView` directly.

**An unknown `codeSetKey` returns 404, not an empty 200** — and this is forced by §4's
allowlist rather than chosen freely. An earlier draft of this spec had it return an empty
`values` array with 200, on the reasoning that a never-seeded key and a key with no rows are
the same state to `ReferenceDataApi.getCodes`. That would have made a *denied* key (404)
distinguishable from an *unknown* key (200), turning the allowlist into an enumeration
oracle: a customer probing `TZ_BASE_PREMIUM_RATE_PER_MILLE` would learn from the 404 that
the key exists but is withheld. Both cases now return an identical 404 with an identical
body, so "you may not read this" and "this does not exist" are indistinguishable — the same
principle `DocumentApiImpl.findOrThrow` already applies to cross-tenant document refs.

`jurisdiction` is returned per-row rather than filtered by a query parameter; the seeded
data is entirely `'TZ'`, so a filter would be untestable against real data today.

**Money and numeric values stay strings.** `reference_code_set.value` is `VARCHAR(255)` and
holds everything from `'24'` to `'12.5'` to a comma-separated category list. It is returned
verbatim as a string, never coerced to a JSON number — consistent with the platform-wide
decimal-as-string convention (`docs/06-database-schema.md:32`) and with the fact that the
column's semantics vary by key.

## 6. OpenAPI file split

`api/openapi/openapi-regreporting-document-refdata.yaml` currently holds two complete
`---`-separated documents (the third, regreporting, was extracted in M10). Its own header
has asked to be split since Phase 0, and `openApi().isValid()` / `ParseOptions.setResolve(true)`
cannot load a multi-document file — which is precisely why neither module could ever have a
contract test.

This milestone splits it into `api/openapi/openapi-document.yaml` and
`api/openapi/openapi-refdata.yaml`, then **deletes the bundled file**. Six references to it
by name exist across three files (verified by grep, not estimated), and they are handled
differently by kind:

- `docs/04-api-contracts.md:4` — the one **live** reference, a companion-file list that also
  still calls the bundle "three documents in one file". Rewritten to name the two new files.
- `docs/superpowers/plans/2026-08-20-m10-regreporting.md:65`, `:1220`, `:1225` and
  `docs/superpowers/specs/2026-08-20-m10-regreporting-design.md:46`, `:198` — five
  **historical** references that accurately describe the file as it stood during M10. These
  are left as written and gain a single forward-pointing note, because rewriting a completed
  milestone's spec and plan to match a later state destroys the record of what was true when
  those decisions were made.

Both new documents are brought in line with what actually ships: real `required` lists,
`$ref`s into `openapi-common.yaml` for `ProblemDetails`, 401/403/404 where reachable, and
every description containing a comma quoted — an unquoted flow-style description with a
comma broke a contract test's spec load in M4.

`docs/04-api-contracts.md` also gains two corrections found during this design:
`finaccounting` is still listed there as having no REST surface (M9 gave it three
endpoints), and the `document`/`refdata` rows in the auth-summary table are updated from
aspirational to actual.

## 7. Testing

**The IDOR negative test is the one that matters most.** A test must construct two claims
under different claimant parties in the same tenant, upload evidence to each, and assert
that requesting claim A's URL with claim B's `documentRef` returns 404 and no bytes. It
must be written so that removing check 2 from §4 makes it fail — verified by actually
removing the check and observing the failure, not by inspection. Every other authorization
test on this platform passes with that check absent.

**A real-MinIO round-trip test** proves upload→download returns byte-identical content, run
against the same Testcontainers MinIO harness `ClaimEvidenceIntegrationTest` already uses.
Asserting a 200 with a non-empty body is not sufficient; the bytes must match what was
uploaded. The same test asserts the response's `Content-Type` equals the media type the
upload declared (upload a JPEG, get `image/jpeg` back — not `application/octet-stream`) and
that `Content-Disposition` carries the original filename, since §5.1 exists precisely
because both were previously unrecoverable.

**A null-tolerance test for pre-V2 rows.** V2's two columns are nullable and existing rows
cannot be backfilled, so a test must insert a `document_record` row with
`content_type`/`file_name` NULL (simulating a document uploaded before this milestone) and
assert the download still succeeds, falling back to `application/octet-stream` and the
`documentRef` as filename, rather than throwing an NPE or returning a malformed header.
Without this test the fallback path is unexercised — and it is the path every document
already in a real deployment will take.

**Contract tests** for all three modules' endpoints against the newly-split spec files,
each pairing `openApi().isValid(SPEC_PATH)` with
`SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, schemaName)` on every JSON response —
`isValid` alone does not enforce primitive JSON types, measured on this platform. Note that
`SpecTypeConformance` silently no-ops on a `oneOf` without a discriminator (found in M10);
none of these four responses uses `oneOf`, so it applies cleanly here.

**Per-realm authorization tests** for each endpoint: a customer token gets 403 on endpoints
2–3, and 200 on endpoint 1 for their own claim; an agent token likewise; a staff token gets
200 on all of 1–3.

**Endpoint 4's allowlist needs its own positive and negative coverage, per realm.** A
customer token gets 200 on `TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE` and **404 on
`TZ_BASE_PREMIUM_RATE_PER_MILLE`** — the single most important assertion for this endpoint,
since that key is the pricing basis and the draft spec would have served it to anyone. An
agent token gets 200 on `OFFLINE_RECEIPT_SLA_HOURS` (customer-denied, agent-allowed, proving
the allowlist is genuinely per-realm rather than one shared list) and 404 on
`TZ_BASE_PREMIUM_RATE_PER_MILLE`. A staff token gets 200 on all nine. A `regulators` token
gets 200 on `TZ_CONTESTABILITY_MONTHS` and 404 on `TZ_BASE_PREMIUM_RATE_PER_MILLE`, matching
§4's table. A denied key and a nonexistent key must return byte-identical responses —
asserted by comparing the two responses directly, not merely by checking both are 404 — so
the allowlist cannot be used to enumerate which keys exist.

**Spec-parse tests** (`DocumentSpecParsesTest`, `RefdataSpecParsesTest`) mirroring the
existing per-module pattern, which also prove the split actually produced two loadable
single-document files.

The full suite must be run and reported for this milestone: it changes `docs/04-api-contracts.md`
and deletes a spec file that other documents reference, and endpoint 1 modifies a controller
whose existing tests every claims path depends on.

## 8. Deliberately deferred

- **Streaming downloads.** `DocumentApi.download` returns `byte[]`; a large document is
  fully buffered. Fine for claim photos and certificates; needs an `InputStream`-returning
  overload before anything stores large files.
- **Document deletion and retention.** The platform has no document-lifecycle policy and
  `DocumentApi` exposes no delete. Out of scope until one exists.
- **Party and policy document listing** — §2.1, blocked on a producer existing.
- **`refdata` write endpoints.** Values are seeded by migration. A maintenance UI is a
  back-office concern (a later milestone), and letting anything mutate global,
  non-tenant-scoped regulatory parameters over HTTP needs its own authorization design.
- **A `jurisdiction` query filter on endpoint 4** — every seeded row is `'TZ'`, so the
  filter would ship untested against real data. `ReferenceDataApi.getValue(key, jurisdiction)`
  already exists in-process for when a second jurisdiction appears.
- **A code-set discovery endpoint** (`GET /reference-codes` listing every key). Of the nine
  seeded keys, the portal reads only the handful it names explicitly (the loan interest rate
  and contestability period being the clear ones); nothing needs to enumerate the set, and a
  discovery endpoint would also expose back-office parameters like
  `DUNNING_ESCALATION_DAYS` to a customer token for no reason.
- **`MinioDocumentStorage.bucketFor`'s `default` branch.** It switches over `DocumentType`
  with `default -> GENERAL_BUCKET`, so a sixth document type added later would silently
  route to `policy-documents` rather than failing to compile — the opposite of the
  exhaustive-switch-without-default discipline M10 adopted for `MetricName`. Noted, not
  changed: it is pre-existing, outside this milestone's four endpoints, and changing it
  means deciding what bucket each future type belongs in. Worth a hardening pass with the
  next document-related feature.
- **The seeded values themselves remain PLACEHOLDERS.** `refdata/V1`'s own table comment
  says they must not go live without Legal/Compliance/Product/Actuarial sign-off. Exposing
  them over HTTP does not change that, and the endpoint's OpenAPI description says so, so a
  frontend rendering "12% p.a." to a customer is rendering an unconfirmed number.

---

# Deliverable B — a local environment that can be logged into

## 9. Why this is in scope, and why no test caught it

The question "when building the frontend, will I be able to see changes in the database,
since the tests ran in Docker?" turned out to have a much worse answer than expected.

**The persistent stack exists and is fine.** `infra/docker-compose.yml` is a real
development environment, entirely separate from the throwaway Testcontainers instances the
test suite creates and destroys: Postgres on a named `postgres-data` volume (so data
survives `docker compose down`), MinIO, Redis, Keycloak, Mailpit (catches outbound mail),
a WireMock mobile-money gateway, the app on `:8080`, and pgAdmin on `:5050` behind
`--profile tools` for browsing tables directly. A row written by the portal is visible in
pgAdmin immediately. That half of the answer is simply yes.

**But no token that Keycloak can currently issue will reach a single controller.** The four
realm files (`keycloak/*-realm.json`) contain **zero protocol mappers** — verified by grep,
not inferred. `TenantContextFilter` rejects any request whose token lacks a parseable
`tenant_id` claim with a 403 `TENANT_CLAIM_MISSING`, before MVC dispatch. So every request
made with a genuine Keycloak token 403s, on every endpoint, in every realm. The platform is
unusable through its own identity provider.

`keycloak/README.md` predicted exactly this and it was never followed up: *"No user
federation, custom claim mappers (e.g. `party_id`, `agentOfRecord`), or production secrets
are configured here — that lands with the modules that need them, starting M1."* Eleven
milestones later, no module ever did.

**Why 642 tests are green anyway.** Every test fabricates its own JWT
(`jwt().authorities(…).jwt(builder -> builder.claim("tenant_id", …))`), so the tests prove
the authorization *logic* is correct while never proving a *real issued token* can satisfy
it. This is the M1 lesson recurring one layer up: back then, every test's DataSource
connected as the Postgres superuser while the app's real runtime role had no privileges on
any schema. Same shape — a synthetic test identity standing in for the real runtime one —
now at the identity provider instead of the database. Worth recording as a standing check:
**whenever a test constructs its own credential, something is unverified about the real
one.**

## 10. The claim mappers

Exactly two custom claims are needed. Determined by enumerating every claim the codebase
reads, rather than from the README's guess:

| Claim | Read where | Consequence if absent |
|---|---|---|
| `tenant_id` | `TenantContextFilter` (1 site) | **403 on every request, every endpoint** |
| `party_id` | 10 sites — customer object-level checks, and how an agent resolves its own agent identity (`AgentController:231`) | customer/agent endpoints 403; staff/regulator paths unaffected |
| `realm_access` | `SecurityConfig` role synthesis | native Keycloak claim — **no mapper needed** |
| `sub` | actor/`uploadedBy` attribution | standard OIDC — **no mapper needed** |

The README's `agentOfRecord` is not real: nothing in `src/main/java` reads it. Agents are
identified by `party_id` like customers. Not adding it.

Per realm, therefore:

| Realm | `tenant_id` | `party_id` | Why |
|---|---|---|---|
| `customers` | yes | yes | every customer endpoint does an object-level ownership check |
| `agents` | yes | yes | `AgentController` resolves the calling agent from `party_id`, never from the path |
| `staff` | yes | no | staff bypass ownership checks by role; no `party_id` is ever read for them |
| `regulators` | yes | no | tenant-scoped by design (M10), but owns no objects |

Both are `oidc-usermodel-attribute-mapper`s reading a user attribute of the same name into
the access token, added to each realm's `lifeplatform-app` client.

`tenant_id` is a fixed, well-known dev UUID hardcoded as a user attribute in the realm
JSON. It needs no coordination with anything, because **this platform has no tenant
directory** (established in M10) — a tenant is simply a UUID appearing in RLS-scoped rows.

`party_id` cannot be static, because a party's UUID is generated by
`POST /parties/individuals`. §12 handles that.

## 11. Test users

Multiple users per realm, not one, because the backend enforces distinctions a single
account cannot exercise:

- **`customers`: two users.** One owning policies/claims, one owning nothing. The second is
  what makes it possible to manually confirm an object-level authorization check actually
  denies — with one customer, every BOLA check passes trivially and a regression is
  invisible.
- **`agents`: two users** in a supervisor/subordinate relationship, since `AgentController`'s
  read access walks the agent hierarchy and a flat single agent never exercises that walk.
- **`staff`: four users** — `UNDERWRITER`, `CLAIMS_ASSESSOR`, `CLAIMS_MANAGER`,
  `FINANCE_OFFICER`. Assessor and manager must be **distinct people**: claims enforces
  separation of duties on the persisted assessor identity, so one account holding both roles
  cannot complete a claim end-to-end and would misrepresent the real workflow. `ADMIN` is
  omitted — `FINANCE_OFFICER` covers every finance-gated endpoint, and a dev `ADMIN`
  encourages using it as a bypass.
- **`regulators`: one user** with `TIRA_READ_ONLY`. Two endpoints, no object ownership.

All passwords are obvious dev-only values, documented as such in `keycloak/README.md`
alongside the existing note about `dev-secret-*` client secrets.

## 12. Demo data — driven through the real APIs, not SQL inserts

**This platform is too event-driven for `INSERT` statements to produce valid state.**
Issuing a policy through `PolicyApi` causes `billing` to generate a schedule and invoices,
`distribution` to accrue commission, `reinsurance` to record a cession, `finaccounting` to
post journal entries, and `regreporting` to update four projections. A hand-inserted
`policy` row has none of that: the portal would show a policy with no invoices and no
premium due, which looks like a broken frontend rather than incomplete seed data — and
debugging that costs far more than the seeder does.

So `scripts/seed-dev-data.sh` drives real HTTP, in order:

1. Obtain a `staff` token from Keycloak (password grant against the dev realm).
2. Create the two customer parties and the two agents via `POST /parties/individuals` and
   `POST /agents`, capturing the generated UUIDs.
3. **Write those UUIDs back as `party_id` user attributes via the Keycloak Admin REST API**,
   closing the loop §10 left open. This is why the seeder must own party creation rather
   than the realm JSON carrying static `party_id` values — the API generates the id, so the
   token's claim has to be set after the fact. It also means the seeder is not idempotent by
   default: re-running it against an already-seeded stack would create duplicate parties, so
   it detects existing seed state and exits rather than doubling it.
4. Issue policies via `POST /policies/manual-issue` (staff), then let the event chain run.
5. Collect a premium via the billing → payment request/confirm loop against the WireMock
   gateway, so at least one policy shows a real payment history.
6. Register and settle one claim (assessor then manager, exercising separation of duties),
   with an evidence upload — which also gives Deliverable A's download endpoint real data
   to serve.
7. Originate one policy loan, so the loan screens have content.

**The seeder is also the platform's first genuine end-to-end smoke test through the real
identity provider.** If it completes, a real Keycloak token demonstrably works against
every major write path — a claim no existing test can make. If it 403s at step 1, the claim
mappers are wrong, which is precisely the failure this deliverable exists to prevent.

## 13. Also worth fixing while here

- **`redirectUris: ["*"]`** on every realm's `lifeplatform-app` client. Acceptable for a
  confidential client in local dev, but the wildcard is replaced with explicit
  `http://localhost:3000/*` (the portal) and `http://localhost:8080/*` entries, so the dev
  config models the real one instead of teaching a habit that must be undone later.
- **`lifeplatform-app` stays a confidential client** (`publicClient: false`). This suits the
  Next.js choice: the authorization-code exchange happens server-side in the BFF, so the
  client secret never reaches the browser. No second public client is needed, and adding one
  would put a token in browser storage for no reason.
- **A startup runbook**, as a new `docs/09-local-development.md`: bring the stack up, apply
  migrations, seed, log in, where the pgAdmin/Mailpit/MinIO consoles live, and how to reset
  to clean state. Nothing documents this sequence today, and §9's blocker is partly a
  consequence of no one ever being asked to run it end to end.

  **The runbook must call out that migrations are manual.** Verified: `pom.xml` contains
  neither Flyway nor Liquibase, so nothing applies schema on application startup —
  `scripts/migrate.sh` is the only path, and `docker compose up` on a fresh volume therefore
  leaves an entirely empty database behind a running app. Every request then fails on
  missing relations rather than anything that names the real cause. This is a five-minute
  fix in a runbook and an afternoon lost without one.

  Also verified while checking the seeder's feasibility: `directAccessGrantsEnabled` is
  already `true` on all four realms' clients, so §12's password grant works without a realm
  change.

## 14. Deliverable B — deliberately out of scope

- **Production Keycloak configuration.** Realm-per-tenant vs. shared realm, real user
  federation, `party_id` provisioning at customer onboarding, token lifetimes, and secret
  management are a deployment concern, not a local-dev one. This deliverable makes local
  development possible and explicitly does not model production identity.
- **How `party_id` gets onto a real customer's token in production.** The seeder sets it via
  the Admin API for two dev users; a real onboarding flow must set it as part of registering
  a party, which is an unbuilt integration between `party` and Keycloak. Recorded because
  the portal will depend on it existing before any real deployment.
- **Automated CI execution of the seeder.** It targets a long-lived local stack, not an
  ephemeral CI container. Making it CI-safe means solving idempotency properly (§12.3) and
  is not needed to unblock M12.
