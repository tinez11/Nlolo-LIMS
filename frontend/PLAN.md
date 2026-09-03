# Frontend Rebuild — Design Decisions & Implementation Plan

Status: **approved 2026-08-24**, 41 decisions settled. Supersedes the deleted
`frontend/customer-portal` (Next.js + NextAuth).

Backend reference: 20 controllers / 68 endpoints, 15 OpenAPI specs at
`backend/api/openapi/`, 4 Keycloak realms at `backend/keycloak/`.

---

## 1. What this is

A **single React SPA at `frontend/`** serving all four Keycloak realms through
realm-scoped routes, with different nav per realm and role. **Staff realm first.**

The UI copies the *form* of a Squarespace/Attio-style CRM — sidebar nav, page
header, stat row, data table, right-hand slide-over — but every value rendered
comes from this platform's own API. No mock data, no invented metrics.

## 2. Stack

| Concern | Choice |
| --- | --- |
| Build | Vite + React + TypeScript, SPA |
| Routing | React Router v7, declarative mode |
| Styling | Tailwind v4 (CSS-first `@theme`) + shadcn/ui |
| Icons | Lucide |
| Auth | `react-oidc-context` (`oidc-client-ts`), not `keycloak-js` |
| Server state | Zustand + Axios — **no TanStack Query** |
| API types | `openapi-typescript`, types-only, per module |
| Forms | react-hook-form + zod |
| Tests | Vitest + RTL, MSW for data shapes, Playwright for e2e |

**Accepted cost of React Router:** list filters and pagination need a hand-rolled
`useSearchParams` wrapper. TanStack Router would have given typed search params
free; if filter-heavy tables dominate, this is the decision most likely to be
revisited.

## 3. Auth

Realm-scoped routes — `/staff/*`, `/agents/*`, `/customers/*`, `/regulators/*` —
each mounting its own `AuthProvider` for that realm's issuer. A picker at `/` for
first-time arrivals. `react-oidc-context` takes exactly one `authority`, so one
provider per route subtree avoids re-mount races and cross-realm token bleed, and
gives per-realm code splitting for free.

Issuers (from `backend/src/main/resources/application.yml`):

```
http://localhost:8081/realms/{customers,agents,staff,regulators}
```

**A new public PKCE client `lifeplatform-spa`** is added to the realm JSON.
`lifeplatform-app` is `publicClient: false` in all four realms and stays
untouched for backend/service use.

**Tokens live in memory only.** `sessionStorage` holds just the transient PKCE
verifier and state. A page reload re-authenticates silently against Keycloak's
SSO cookie rather than re-prompting. Storage keys are namespaced per realm.
`automaticSilentRenew` on; logout hits Keycloak's `end_session` endpoint.

Because this leans on the Keycloak SSO cookie, it must be **verified against the
real container**, not assumed.

**Tenancy is invisible.** `TenantContextFilter` reads `tenant_id` from the
validated JWT only; there is no `X-Tenant-Id` header or any client-supplied
tenant input, and Postgres RLS enforces it per connection. The UI never sends or
displays a tenant. No switcher.

Authorities arrive as `ROLE_REALM_<REALM>` plus every `realm_access.roles` entry,
so nav gating reads straight off the token.

## 4. API layer

**Codegen:** `openapi-typescript`, types-only, one module per spec, **all 15
specs**. The deleted script covered only 11 — it skipped `distribution`,
`finaccounting`, `regreporting`, `reinsurance`, precisely what a staff console
needs. Types-only because no spec has an `operationId`, so any method-name
generator (orval, openapi-generator, kubb) would emit garbage names for all 68
operations. Hand-written typed Axios functions per domain sit on top.

**Base URL** from `VITE_API_BASE_URL`. The specs' `servers: [.../v1]` block is
documentation of intent — the app sets no `context-path` and serves at root, so
a client trusting `servers[0]` would 404 on every call. Vite proxy in dev; CORS
bean when deployed (there is currently **no CORS config anywhere** in the
backend).

**Idempotency-Key** is generated once inside each Zustand mutation action and
held for the lifetime of that submit, so a retry reuses it. An interceptor
asserts presence on the six endpoints that hard-require it and throws loudly in
dev. Auto-generating per request would mint a fresh key on retry and
double-charge — exactly what the mechanism exists to prevent.

Hard-required: `POST /invoices/{id}/payment-request`, `POST /claims`,
`POST /agents`, `POST /agents/{id}/commission-statements/{sid}/payout`,
`POST /claims/{cid}/recoveries/{rid}/confirm`, `POST /treaties`.

**Errors** are normalized by a response interceptor into
`{status, errorCode, detail, traceId}` from the backend's `application/problem+json`
(`ProblemDetails` requires `type`, `title`, `status`, `traceId`). Stores hold it
in an `error` slot. Handling:

- `401` → silent renew, then re-login on failure
- `403` → an access panel, not a toast
- `404` → may be a *disguised* denial (refdata allowlist, IDOR guards)
- `501` → `POST /policies/{n}/surrender` and the process-status endpoint are
  deferred stubs; their actions render disabled with a tooltip, never as live
  buttons
- `400` with `errors[{field, message}]` → bound onto form fields via `setError`

`traceId` is surfaced and copyable — it's the only thread back to backend logs.

**Money is always a decimal string** plus `currencyCode`, never a JSON number
(backend validates `^-?\d+(\.\d{1,2})?$`). Strings stay the source of truth,
`Intl.NumberFormat` for display, the same regex on inputs, and **no client-side
arithmetic** — the backend computes every total. A decimal library is a
deliberate per-screen exception, not the default.

**Unknown request keys are silently dropped platform-wide** (no `spring.jackson`
config anywhere; `additionalProperties: false` only on 3 response schemas), so a
misspelled field returns 201 with the data discarded. The generated types are the
real guard — the compiler catches it before the request exists.

## 5. State

One Zustand store per backend domain (`policyStore`, `claimStore`, …), each built
over a shared `createResourceSlice` helper holding entities plus `status` and
`error`. Mirrors the backend's module boundaries so a screen's data source is
obvious, without a single global store or duplicated fetch boilerplate.

## 6. Design system

Inter variable with a system fallback. Near-black on white, hairline borders,
generous whitespace, one comfortable row density. **Dark mode from day one** —
Tailwind v4's `@theme` plus shadcn's `.dark` makes it near-free at scaffold and a
tedious retrofit later.

Avatars: shadcn `Avatar` with initials and a hue hashed from the party ID. No
generated-avatar library — insurance parties have no photos, so the fallback *is*
the avatar, and a hosted generator would be an external call from an insurance app.

**Drawer previews, page acts.** A table row opens a read-only slide-over with key
facts and a "Full detail" link; the full page is tabbed and owns every mutating
action. This keeps settlement decisions, waivers, and payouts off a surface
dismissable by clicking the backdrop. The reference design does the same thing —
its drawer carries a "‹ FULL PROFILE" link.

**Stat cards are counts only.** There are no analytics endpoints; the only
obtainable numbers are `totalElements` from the 4 paged searches. No trend
arrows — there is no trend data, and a card reading "↗ 12% wk/wk" with nothing
behind it is worse than no card.

**Two table variants.** A paged `DataTable` for the 4 paged endpoints
(`/claims`, `/policies`, `/gl-postings`, group members); a client-side
sort/filter table with **no pager** for the 11 bare-array endpoints. A pager over
a fully-downloaded array lies about the network and breaks when the array grows.
`PageMeta` has no `required` list, so `page`/`pageSize`/`totalElements` all
generate optional and need defending.

**One shared `StatusBadge`** maps every domain enum onto six semantic buckets, so
a "pending" invoice and a "pending" claim look alike. Enum literals come from the
specs and Java enums — never invented. `refdata` holds only 9 numeric placeholder
code sets and backs **no** dropdown anywhere; every option list is a spec enum.

Confirmed mappings for the ambiguous cases:

| Literal | Bucket | Why |
| --- | --- | --- |
| `IN_DOUBT` | warning | unresolved, not failed |
| `LOADED` | success | an acceptance with premium loading |
| `POSTPONED` | warning | a soft decline, not a queue state |
| `FORCED_LAPSE_TRIGGERED` | danger | |
| `WAIVED` | neutral | closed without payment |
| `IN_GRACE` | warning | |
| `REOPENED` | pending | |

## 7. Staff nav

**Operations** — Policies, Claims, Products — always visible.
**Finance** — GL Postings, Chart of Accounts, Treaties, Regulatory Returns,
Agents — gated on `FINANCE_OFFICER` / `ADMIN`.

Only entities with a real list endpoint get a nav item. Parties, agents,
underwriting cases, payments, payout batches, and documents are fetch-by-ID only;
they're reached by drilling in from a policy or claim, or via a command-palette
lookup. A nav item leading to "paste an ID" reads as broken software.

## 8. First slice

Foundation — scaffold, real-Keycloak PKCE auth, codegen, design tokens,
`StatusBadge`, both `DataTable` variants — then **Policies end-to-end**: list →
drawer → full detail page.

Policies is the right proving entity: it's paged (exercises the real pager), it
has the deepest detail surface (beneficiaries, invoices, loans, cessions,
coverage status, surrender value) so it stress-tests the drawer/page split, and
it's readable by all four realms so the components carry over to Customers next.

**Definition of done — one gate:** a Playwright e2e that logs into the real
docker-compose Keycloak `staff` realm as `staff.underwriter`, lands on Policies,
opens a row's drawer, reaches the full detail page, and asserts real data from
the real backend.

## 9. Testing

Vitest + RTL for components and stores. MSW may mock **data shapes only**.
Playwright drives the auth path against the real Keycloak container from
`backend/infra/docker-compose.yml`.

**Hard rule: zero fabricated JWTs anywhere in the suite.** Every token in a test
comes from real Keycloak. This project has twice shipped a fully green suite over
a completely unusable real credential path (M1 database, M11 Keycloak) because
tests minted their own identities. A brand-new SPA doing PKCE against four realms
is the highest-risk possible place to repeat it — mocked auth would pass whether
or not `lifeplatform-spa` is configured at all.

`e2e/auth.setup.ts` from the deleted portal did exactly this and is worth
recovering from git rather than reinventing.

CI: repoint the existing `frontend-build-and-test` job from
`frontend/customer-portal` to `frontend/` (Node 22, `npm ci` → lint → test →
build). Wire e2e as a separate job but **do not gate merges on it yet** — the
compose stack takes minutes to become healthy, and a flaky-by-infrastructure
required check trains people to ignore CI.

## 10. Changes outside `frontend/`

**Blockers — the SPA cannot work without these:**

1. `lifeplatform-spa` public PKCE client (S256) added to `backend/keycloak/staff-realm.json`, redirect `http://localhost:5173/*`.

   **Editing that file is not sufficient on an existing environment**, and this was found the hard way during implementation. Keycloak keeps its own data in Postgres here (`KC_DB=postgres`, volume `infra_postgres-data`), and `--import-realm` imports a realm only if it does **not already exist** — so on any stack that has been started before, the edit is skipped in complete silence. No warning, no error; the SPA just fails with an opaque "invalid client" at the Keycloak login page. A fresh volume (and therefore CI) imports it correctly, which is what makes the trap easy to miss.

   `backend/scripts/apply-spa-client.sh` closes it: idempotent, creates-or-updates via the Admin API, and reads the client back to verify rather than trusting the write's status code.
2. `.github/workflows/ci-cd.yml` → `frontend-build-and-test` repointed to `frontend/`

**Correctness:**

3. ~~All **43** `nullable: true` → `type: [X, "null"]`, because openapi-typescript silently drops the nullability.~~ **This premise was wrong and was measured to be wrong during implementation.** `openapi-typescript@7.13.0` honors `nullable: true` as a compatibility shim even under an `openapi: 3.1.0` header, emitting `string | null` for both spellings. No generated type was ever lying.

   What was actually done: the **35 scalar** fields were still rewritten to `type: [X, "null"]`, on the narrower grounds that the document should not contradict its own version header or depend on a shim a future major may drop — a lateral change for this toolchain, with no behaviour difference. The **5 nullable-`$ref`** fields deliberately keep `nullable: true`, because there is no 3.1-pure spelling that works here: `oneOf`/`anyOf` with a `{type: "null"}` branch makes `swagger-request-validator` match *every* value, silently turning validation off while tests stay green. Each of the 5 carries a comment explaining the asymmetry.

   The genuinely valuable generated-type fix in this area was **`PageMeta` `required`** (item 5) — those three fields really were optional and are now required.

   **Measured 2026-09-03, while adding the group-scheme paths:** `swagger-request-validator` **does not catch a scalar type mismatch in a response body.** Declaring `activeMemberCount` as `type: string` while the controller returns the number `2` passes. Probed both ways to be sure of the boundary — the same schema with a bogus `required: [...]` entry fails loudly, on both the new paths and the existing ones, so the validator is genuinely running and genuinely reaching these schemas. What it enforces is structure (required, presence, path/verb/status coverage); what it does not enforce is that a declared scalar type matches the JSON emitted.

   So a contract test proves the endpoint is *described*, not that every field's type is right. Assert values with `jsonPath` alongside `isValid` — a `jsonPath("$.x").value(2)` catches what the schema check does not — and treat "the contract test is green" as a weaker claim than it sounds.

**Spec-truth fixes:**

4. `RegisterCorporateRequest.contactInfo` — declare its real properties: `phoneNumber` (`^\+255\d{9}$`) and `email`, both optional, object itself required
5. `PageMeta` — add `required: [page, pageSize, totalElements]`
6. `openapi-policy.yaml` — add `SURRENDER` to `BenefitType` (present in Java, missing from the spec)
7. `openapi-underwriting.yaml` — ~~**remove `medicalDisclosure`**~~ removed, and disclosures then built as their own resource, see §11

**Housekeeping:**

8. Commit the pending 113-file `frontend/customer-portal` deletion

**Found by the frontend, fixed in the backend:**

9. `GET /parties` had **no `ORDER BY` at all** — `PageRequest.of(page, pageSize)` with no `Sort`, and neither the derived query methods nor the JPQL `search` supplied one. Two consequences, and the second is the serious one:

   - A newly registered party came back in whatever position the scan yielded — in practice last, so the KYC review queue buried the very thing it exists to surface. This is what a Playwright test caught: a party registered through the real agents-realm flow could not be found in the staff queue at all.
   - Paginating an unordered query is unsound. Postgres promises nothing about two `LIMIT/OFFSET` queries agreeing on row order, so a reviewer walking page 1 → 2 could be shown one party twice and never be shown another. **A KYC queue that can silently omit a party is a compliance problem, not a cosmetic one.**

   Now `Sort.by(desc("createdAt"), desc("partyId"))`, matching the `DESC createdAt` convention already used by claims, policies, the underwriting queue and the audit journal. `partyId` is what makes the order *total*: `createdAt` is assigned in Java by `Instant.now()`, so a batch registration can genuinely tie, and a tie in the leading key restores an undefined order for exactly those rows.

   `GET /parties/{id}/groups/{groupId}/members` had the same defect and is fixed the same way, sorted oldest-first — a membership roll reads as a roll, and joining order is the only order it has. Sorting matters more there, not less: a group scheme can hold thousands of members, so it is the endpoint most likely to actually be paged through.

   `GET /gl-postings` looked like a third instance and is **not** one — its repository methods carry `OrderByPostedAtDesc` in their names, so it is ordered despite the bare `PageRequest.of`. Only a tie-breaker is missing there; left alone deliberately, since adding one means renaming four derived query methods for a marginal gain.

10. **A third instance of the same defect, and the worst of them.** `DistributionApiImpl.resolveApplicablePlan` took `.findFirst()` off `findByTenantIdAndProductIdAndStatus`, also with no `ORDER BY`. Nothing supersedes a prior ACTIVE plan when a new one is created, so two ACTIVE plans for one product is reachable — and the winner was whichever row Postgres returned first. **This decides the rate an agent is paid**, on both the read endpoint and the accrual path (`resolveAgentWithPlan`, called by distribution's `PolicyEventListener`), which share the same resolution.

    Now `OrderByCreatedAtDescCommissionPlanIdDesc`. Reverting the fix and re-running proves the test real: unordered it resolves `0.0500`, the superseded plan, where the current one says `0.0700`.

    The staff-portal CRUD audit had already recorded this as deferred gap #6 and said to fix the `ORDER BY` half independently of the UI work. That half is now done; superseding on create and a deactivation path stay deferred, since both need a decision about commission already accrued under the old plan.

    **Three instances in three modules, all the same shape:** a `Pageable` or a `findFirst()` over a query with no total order. Worth a sweep rather than waiting for a fourth to surface as a test failure.

11. **The sweep found a fourth, and it is the only one no test would ever have caught.** `product.rating_table` shipped with a NON-unique index on `(product_version_id, factor_type)`, so a version could hold the same `(factorType, band)` twice with different multipliers — and both readers, `strictMultiplier` (premium quoting) and `resolveRatingMultiplier` (underwriting's rules engine), filter to the band then take `findFirst()`. The multiplier that applied depended on row order. Reachable through the authoring form, which lets a user add the same band twice.

    Fixed the same way the base rate table already was: rejected where the rows are authored, with a message naming the band, **and** a `UNIQUE` constraint (`V4__rating_table_unique_band.sql`) so a writer bypassing the application cannot create the state either.

    Two queries were found ordered but not *totally* ordered, and deliberately left: `ProductVersionRepository.findActiveAsOf` (`effectiveDate DESC`, no unique tie-breaker) and `GET /gl-postings` (`postedAt DESC`, likewise). Both need same-valued rows to matter and neither is reachable through a UI path today. Recorded so the next reader does not have to re-derive that they were considered.

    **The pattern to carry forward:** on this platform, "which one of these rows applies" is the single most reliably-wrong piece of logic. Four for four, the answer was decided by scan order, and in three of the four cases the wrong answer is a wrong amount of money rather than a visibly broken screen. Any new query that resolves *one* row out of many needs a total order before it ships.

## 11. Deliberately deferred

**Endorsements UI.** `EndorsementRequest.changes` is opaque JSONB the backend
reads *nothing* from — `getChanges()` has no non-test caller, and the one
downstream listener is a documented no-op. `endorsementType` is a free-form
`VARCHAR(50)` with no enum anywhere. Two test fixtures already disagree on the
keys (`{"newAddress": …}` vs `{"address": …}`), which is itself proof nothing
reads them. Whatever a UI sent would become the de facto schema, stored
permanently in an append-only table. The vocabulary needs ratifying — and then
enforcing in the spec and backend — before any UI mints it. Same shape as the M7
commission-semantics doc void.

**`medicalDisclosure`.** ~~Advertised in the spec, absent from the Java DTO,
silently discarded with a 201 (Spring Boot's `FAIL_ON_UNKNOWN_PROPERTIES=false`).
The `MedicalDisclosure` entity and repository exist with zero call sites, and
`RiskProfile` explicitly excludes occupation/smoker factors for want of a
structured source. A form here would appear to work and throw the user's input
away. Removed from the spec; wiring it is backend feature work.~~ **Closed** —
but as its own resource, not as a field on the create request. `POST/GET
/underwriting/cases/{caseId}/disclosures` plus `DisclosurePanel` on the case
detail screen. A proposal form is answered in one sitting and can be re-taken
when new evidence arrives, and a correction is *additional* evidence rather than
an edit, which a field on `OpenCaseRequest` cannot express.

The deferral's own reasoning still holds and shaped the fix: the
platform does not own the question set, so it records the question **as asked**
in the applicant's hearing rather than validating against a canonical list it
would be inventing. And it is deliberately not an input to the decision —
`SimpleRulesEngine` has no validated thresholds, so rating on a declared
condition needs an actuary, while *recording* the evidence has an honest answer
today. This is what gives `Claim.requiresContestabilityReview`, computed and
shown on every claim since M6, something to actually review.

**Other deferrals:** CORS bean; the underwriting case-queue endpoint; party
search; paginating the 11 bare-array endpoints; `additionalProperties: false` on
request schemas; Keycloak identity brokering (one hub realm federating the four,
which would collapse §3 to a single authority).

## 12. Risks on the record

1. **The agents realm has no ownership scoping.** Agent-of-record filtering is
   documented as deferred, so an agent token currently reads *every* policy in
   the tenant. Fix before building any agent-facing UI.
2. ~~**`UNDERWRITER` has no landing screen.** There is no underwriting case-list
   endpoint, so the role's primary workflow has no queue. First backend
   follow-up, ahead of party search.~~ **Closed.** `GET /underwriting/cases`
   (paged, status-filterable) and `UnderwritingQueuePage` shipped in
   `6520768`..`60ff669`. Party search closed too — `GET /parties` supports `q`
   and `kycStatus`, and backs `KycReviewPage` and `PartyPicker`.
3. **`CUSTOMER_SERVICE_REP` appears in zero `@PreAuthorize`.** A CSR sees the
   Operations group and nothing else. Confirm that's intended, not an oversight.

---

## 13. Restructure — Phase 0

Added 2026-08-27, after reviewing an operations-console mockup
(`nloloopsconsole.html` + `operationsprocessflow.html`) against the real API.

**What the review found.** 34 mockup screens against 79 backend operations: 12
fully servable and *all 12 already built here*; 5 render but thin; 17 with no
backend at all. The mockup's contribution is therefore not new screens — it is a
better information architecture over screens that already work, plus a wish-list
of four bounded contexts the platform has never had.

**Do not trust the mockup's citations.** It attributes Lead & Advisory,
Proposals and Customer Service to "Addendum A §2.18/§2.20/§2.21". No such
document exists; grepping all eleven `backend/docs/` deliverables returns zero
hits for Lead, Quotation, Proposal, Complaint or Customer Service. Those
contexts are undesigned. The mockup's "Design note" callouts are the author's
own proposals wearing the costume of ratified decisions.

Backend gaps and the pricing decision are specified separately in
`backend/docs/superpowers/specs/2026-08-27-m13-pricing-and-read-gaps-design.md`.
This section covers only the frontend, which blocks on none of it.

### C3 — one `FormField` — **done** (`d8f4246`)

`FormField` had been copy-pasted into twelve feature files, byte-identical in
eleven. Promoted to `components/FormField.tsx`; 219 lines deleted. It sits
beside `Field`, which is a different thing — `Field` is the read-only
label/value row in a detail panel.

### C1 — a screen manifest

The screen list lives twice with no shared source: `App.tsx` holds 34 `<Route>`
entries, `AppShell.tsx` holds `NAV_BY_REALM` with 14 items. They are the #2 and
#3 most-churned files in the frontend's history. Adding a screen means editing
both, and nothing catches the drift — 20 routes are reachable only by drilling
in, which is deliberate per §7, but indistinguishable from an accidental
omission.

One `screens.tsx` manifest; the router and the nav become maps over it.

**Shipped in `b3dca87`, with one change from the plan above.** The intent was to
move `AppShell`'s `implemented` flag onto the manifest. It was deleted instead:
all 14 entries were `true`, so the flag was vestigial, and the distinction it
never captured is the one that matters — most screens are deliberately *not* in
the sidebar per §7. A required `reach` field replaces it: either a nav placement
or the literal `'drill-in'`. Because it is required, a new screen cannot be added
without declaring how a user reaches it, so "deliberately drill-in only" and
"somebody forgot the nav item" stop looking identical.

`PageHeader` moved out of `AppShell` into its own module in the same change. It
was imported by 26 pages, so a manifest importing pages would have closed the
cycle pages → AppShell → screens → pages — and the manifest builds its elements
at module scope, so a badly-resolved cycle surfaces as an undefined component at
first render, not as a build error.

### C2 — a `gates` module

The business rules that make this an insurance console rather than a CRUD app
live nowhere. They are absent (claims coverage) or inlined in page JSX, where
they can only be tested by rendering a page.

`gates/` exposes `(record, asOfDate) => Gate[]` — `{ok, hard, title, detail}` —
with `claimGates` and `issueGates` first, rendered by one `<GatePanel>`. The
seam sits above the API layer, so the whole rule set is testable against fixture
records with no DOM.

**Correction — the original premise for C2 was wrong.** This section first
claimed the mockup's best idea (judge a claim against the policy as it stood on
the date of event) was "already paid for", because `coverage-status` and
`in-force` both accept `asOf` and `policyStore` dropped it. The dropped
parameter was real; the conclusion was not. **The endpoint ignores `asOf`**: it
echoes the date into the response and queries `findByPolicyNumberAndActiveTrue`,
and `policy.coverage` has no `effective_from`/`effective_to` columns, so no
per-benefit history exists to query. Verified on the wire (two dates 25 years
apart, byte-identical coverage on a 2026 policy), in `PolicyApiImpl`, and in the
schema. Checking that a parameter exists is not checking that it works.

So the shipped gates assert only what the platform can prove: risk commencement
from `PolicyView.issueDate` (hard, genuinely date-bounded), a **soft** flag when
today's status means the coverage history needs a person — soft because a lapsed
policy must route a claim to investigation, never auto-refuse it — and whether
the benefit is on record at all, labelled as current rather than as-at-the-day.
`src/gates/claimGates.ts` carries the full reasoning, and the backend gap is an
open item in the M13 design spec.

### Deliberately not adopted from the mockup

- **Client-side premium arithmetic.** The mockup's `price()` computes premiums in
  the browser from a rate table. That is what §4 forbids, and its rate shape
  (absolute rates per mille) does not match the backend's (multipliers with no
  base). Pricing moves to the backend — see the M13 spec.
- **Fabricated data.** The mockup hardcodes a 120-row chart of accounts, a
  payments feed and 21 report definitions. Rendering invented figures is the
  trap §6's "stat cards are counts only" rule exists to avoid.
- **Screens for the four undesigned contexts.** Leads, needs analysis,
  quotations, proposals and service cases are backend work first.

---

## 14. Design decisions — 2026-09-02

Added after an `$impeccable` pass over `frontend/src`: an unanchored design
review, a deterministic detector run, and a live authenticated browser
assessment against the running stack. Two new documents landed at the repo root
and are now the durable record for anything design-facing:

- **`PRODUCT.md`** — product truth. Confirmed: primary user is back-office staff
  at a desk on a large monitor; this is a **multi-tenant product sold to
  Tanzanian insurers**, so the identity must stay vendor-neutral and
  white-labellable; **English only**, indefinitely; **WCAG 2.2 AA is a
  self-imposed floor**, not a regulatory mandate.
- **`DESIGN.md`** (+ `.impeccable/design.json`) — the incumbent visual system,
  extracted rather than invented. North Star **"The Ledger"**.

The critique snapshot lives at `.impeccable/critique/` and carries the full
backlog. This section records only what was **decided**, so a later reader does
not have to reconstruct it from a report.

### 14.1 Two defects fixed, both P0

**Right-aligned numeric columns were not right-aligned.** `DataTable` put
`text-right` on the `<td>` but wrapped every cell in
`<div className="flex h-11 items-center">`. `text-align` does not position a
flex item; `justify-content` does. So the `<th>` right-aligned and the `<td>`
did not — measured at ~103px of drift on `/staff/policies` and ~140px on
`/staff/gl-postings`. It hid on policies only because every value there happens
to be the same character length.

This one matters beyond tidiness: global `font-variant-numeric: tabular-nums` is
set on `body` specifically so figures compare vertically, and the flex wrapper
was discarding the alignment that makes tabular figures worth having. Two
decimal points in one TZS column would not have lined up. `DataTable` is the
only `<table>` in the codebase, so one conditional fixed every numeric column in
the console.

**People were UUIDs on every screen where a decision is made.** `PartyName`
already existed, worked, and was used in exactly one place. Meanwhile 9 read-only
party references rendered a raw uuid — including the underwriting queue's
Applicant column, where the live stack showed **17 of 20 rows carrying the
identical uuid**, making the queue impossible to triage without opening every
case. Two of those sites carried the note *"No party lookup endpoint exists
yet"*, which stopped being true when party search closed (§12.2).

`PartyName` now caches per id at module scope with in-flight deduplication, so
the old objection — a second request per row — costs one request per distinct
person for a whole page. The id is never discarded; it stays on the `title`.

Two things were deliberately **not** changed, because the review had them wrong:
`ClientsPage` already leads with a `displayName` column and its "Party id" is a
legitimate secondary id column; and **`agentOfRecordId` is an agent id, not a
party id**, so resolving it through `PartyName` would 404.

### 14.2 Density is deliberate, and DESIGN.md now says so

Measured type distribution: 226 uses of 0.75rem, 98 of 0.875rem, 94 of
0.6875rem, 16 of 0.625rem, and exactly one each of the two largest sizes.
**Confirmed as intended** — an operator wants rows on screen, and density beats
comfort on this surface. DESIGN.md was corrected to describe 0.75rem as the
workhorse rather than as a "label" size the code contradicts.

Two genuine drifts stay on the record: `ui/button.tsx`'s 0.8125rem small size
and `RealmPicker`'s 1.125rem `<h1>` exist in no scale. And the 16 uses of
0.625rem are the one part worth raising — density justifies 12px, nothing
justifies 10px.

### 14.3 Decided: gates become mandatory scaffolding for a mutation

**The design-specificity verdict was "authored in its judgment, generic in its
form."** The shell is a self-declared Attio-style CRM (§2) and that stays — it
works, and replacing it buys nothing. What does not stay is that `GatePanel`,
described in DESIGN.md as *the component that makes this an insurance console
rather than a CRUD app*, ships on **one screen out of thirty-four**, and that
`issueGates` — promised by name in §13 C2 — was never written.

**The decision: a mutating surface declares its preconditions before it renders
a submit button.** Not a garnish on the screens that happened to get one. This
is what puts the domain on the glass without touching the shell, and it reuses a
seam that already exists and is already tested with no DOM.

The doctrine from §13 C2 governs every new gate and is not negotiable: **a gate
asserts only what the platform can actually prove.** `claimGates` is the
reference implementation precisely because it refuses to claim
coverage-as-at-the-date-of-event — the endpoint that looks like it answers that
ignores its own `asOf` parameter. A gate that overstates is worse than no gate,
because staff will learn to trust it.

Candidate surfaces, with what looks provable today. **Each needs its data path
verified on the wire before its gate is written** — checking that a field exists
is not checking that it is populated, which is the mistake §13 C2 already
records making once:

| Surface | Likely provable | Notes |
| --- | --- | --- |
| `IssuePolicyPage` | policyholder `kycStatus`; agent-of-record `licenseStatus` / `licenseExpiryDate`; product version active as-of | The `issueGates` that §13 C2 promised. Writing business through an expired agent licence is a real regulatory gate and the data is already on `AgentView`. KYC should be **soft** unless the backend is confirmed to refuse it — a hard gate the API does not enforce is the UI inventing a rule. |
| `ClaimSettlementPanel` | assessor ≠ decider; approved amount vs the policy's sum assured; contestability review outstanding | The highest-value one. `ClaimsApiImpl` already refuses the same person assessing and deciding, and today the manager discovers that as a 422 **after** committing. The UI holds both identities and can say so first. |
| Reinstate a lapsed policy | current status; premium arrears | Must state what arrears the reinstatement creates. |
| KYC verify / reject | identity evidence on record | Also fixes the defect where Verify/Reject are gated on *this session's* upload, so a reviewer returning to a document already listed cannot act on it. |
| `LoansPanel` | cash value | Already has a disabled-reason tooltip. A gate should say plainly that cash value is 0.00 platform-wide until the backend credits it, rather than hiding the reason in a `title`. |

Deliberately **excluded** for now: anything needing an underwriting decision
joined to a policy, because `GET /policies` does not re-surface
`underwritingCaseId` (recorded in `UnderwritingCaseDetailPage`). That is a
backend gap, not a gate to invent around.

### 14.4 Still open, in priority order

Carried from the critique snapshot. Items 2 and 3 are **done** (2026-09-03, see
§14.6); the rest stands.

1. **Nothing confirms, and nothing asks before the irreversible.** Zero
   confirmation dialogs and zero success confirmations exist. Approving a payout
   is acknowledged only by the panel vanishing. Wants a `ConfirmAct` step and a
   persistent success block — decision, amount, timestamp, journal reference.
2. ~~**Four AA breaches**: `--subtle-foreground` at 3.35–3.50:1 light and
   4.31–4.42:1 dark at 11px; `--input` at ~1.27:1 against `--surface`; no skip
   link (19 tab stops to the first row); no `aria-invalid`/`aria-describedby` on
   ~98 inputs, with `FormField` nesting the error inside the `<label>`.~~ **All
   four fixed** — §14.6.
3. ~~**The design system is a copy-paste convention.** 7 byte-identical `Panel`
   components, 8 `FilterChip`s, 98 input class strings in 24 variants.~~
   **Extracted** — §14.6. What remains of this item is the smaller half: 17
   hand-rolled 11px labels still bypass `FormField`, and there is no lint rule
   banning `border-input` outside `components/ui/`, so nothing yet stops the
   habit recurring a fifth time.
4. **No tabs anywhere**, though §6 and `PRODUCT.md` both promise tabbed detail
   pages and `@radix-ui/react-tabs` is installed and imported nowhere. Detail
   pages are 8 co-equal panels with every heading at body size.
5. **The stat row is dead weight.** Every list screen passes one stat into a
   four-column grid: measured at 1440px, a 282px card in a 1216px row, 77% empty,
   restating the count the pager prints below it.
6. **Nothing built for eight-hour use** — no keyboard shortcuts, no command
   palette (§7 assumes one exists), no bulk actions, and Claims search matches
   policy number only, so an assessor cannot find a claim by claimant name.

Three dead dependencies to remove: `@radix-ui/react-tabs`,
`@radix-ui/react-tooltip`, `@radix-ui/react-avatar`.

**Not a priority, and recorded so it is not re-raised:** at 390px the console is
unusable — the 224px sidebar takes 57% of the viewport and the 166px remainder
scrolls horizontally. Desktop-at-a-desk is the confirmed primary context and
**1024×768 holds up cleanly** with no clipping or overflow. That is the real
floor and it passes.

### 14.5 The e2e fallout, and the latent trap it exposed

Resolving ids to names broke 3 of 89 e2e tests, all in two specs, and the
diagnosis is worth keeping because the symptom pointed nowhere near the cause.

**`staff-beneficiaries`** failed with a timeout on a missing share-percent
input. The failure snapshot showed the browser sitting on `/staff/parties/<id>`
under an `<h1>` of "Amina Owner": the click had navigated off the page.
`addPartyRow` picked from the party search dropdown with a bare
`page.getByText('Amina Owner').click()`, and the Policyholder field on that same
page now renders that name inside a link. **The link exists on first paint; the
search results arrive a request later**, so Playwright matched the link, clicked
it, and left before the dropdown had rendered anything.

**The latent trap:** roughly twenty specs use that identical unscoped
`getByText('Amina Owner').click()` and every one of them passed. They survive
only because their picker sits on a *create form*, where the party's name
appears nowhere else on the page. That is luck. Any screen that starts showing a
party name breaks its spec the same way, with the same misleading symptom. Only
the two genuinely-broken specs were changed — rewriting eighteen passing ones is
churn — but `selectPickerOption` in `staff-beneficiaries.spec.ts` carries the
explanation for whoever hits it next.

**Two fixes worth more than the failures they cleared:**

- `staff-issue-policy` asserted the raw uuid was *visible*, to prove the picker
  put the selected party's id in the payload. Swapping that for the name would
  have been **weaker** than the test it replaced — it would pass for any party
  called "Amina Owner" and stop checking which id round-tripped. It now asserts
  the policyholder link's `href`, which checks both halves at once.
- The beneficiaries read-view test then hit strict mode, because this fixture's
  beneficiary *is* the policyholder and both now resolve to a name. Scoping the
  assertion to the Beneficiaries panel was not cosmetic: page-wide, the
  policyholder's name alone would satisfy it **even if the beneficiary still
  rendered a raw uuid** — the exact regression the test exists to catch. It
  would have passed while proving nothing.

The general shape, and the reason it recurs here: **rendering a name where an id
used to be creates page-wide duplicates of text that specs assumed was unique.**
Unit tests cannot see any of it — 557 passed green through all three failures.

**A third shape, found 2026-09-03 while adding the group-scheme specs:** a page
carrying real `<select>` elements makes a bare `getByRole('option')` ambiguous,
because a native `<option>` answers to the option role exactly as the party
picker's cmdk items do. On the scheme form it resolved to "Select a group
product" — permanently invisible, so the click retried for the full 60s timeout
and the error named the wrong control entirely. Always give the option a name.
Two neighbouring traps from the same afternoon: `getByLabel('Annual salary')`
also matches "Multiple of annual salary" (labels match on substring — pass
`exact: true`), and a policy number on `/staff/policies` is a row-activation
*button*, not a link, so `getByRole('link', {name: /^POL-/})` waits forever.

**Known flake, not a regression:** `staff-beneficiaries`'s two picker tests
failed once with the option "outside of the viewport" after ~100 retries, in a
four-spec batch. Bisected properly rather than assumed: they pass with the
change in place both isolated and in the same four-spec batch re-run, and the
run that failed was the only one of three to do so. A popover-positioning race,
pre-existing and intermittent. Worth fixing when someone touches that spec —
scrolling the trigger into view before opening it — but it is not caused by, and
does not block, the group-scheme work.

### 14.6 The design system became components, and the AA breaches went with it

Done 2026-09-03, clearing items 2 and 3 of §14.4. The two halves were one job:
the accessibility fix could not be made 98 times, so it had to be made once, in
a component that did not exist yet.

**Extracted.** `Panel` (8 copies, hashed first — all 8 byte-identical),
`FilterChip` (9 copies, 3 variants), and `components/ui/input` exporting `Input`,
`Select` and `Textarea` over **136 controls in 24 class-string variants**. Two of
those variants were a size scale nobody had named: `h-9`/`text-sm` and
`h-8`/`text-xs` are now `md` and `sm`, matching `Button` so a field and the
button beside it agree. Per-field one-offs — `w-20`, `uppercase`, `font-mono`,
`text-right`, `pl-7` — stay as classes, because they are one-offs and not
variants.

Only one difference across all of it turned out to be real: `AuditLogPage`
renders event types, which are code, so `font-mono` survives as a `mono` prop
rather than being flattened.

**All four AA breaches, with the numbers worked rather than copied.**

| | was | now |
|---|---|---|
| `--subtle-foreground` light | 3.50:1 | **4.85:1** (`0.63` → `0.55`) |
| `--subtle-foreground` dark | 4.18:1 on surface | **5.76:1** (`0.57` → `0.65`) |
| `--input` light | 1.27:1 | **3.11:1** (`0.918` → `0.66`) |
| `--input` dark | 1.36:1 | **3.04:1** (14% → 38% white) |

The critique proposed `--input: 0.80`. Checked instead of taken: for a neutral
grey, relative luminance is approximately L³, a model that reproduces the
critique's own measured 1.27:1 at L=0.918 exactly — and at L=0.80 gives 1.87:1,
still failing. 3:1 needs L ≤ 0.67.

`--input` is now **deliberately darker than `--border`**, which does not move.
They were equal only because nobody had separated the two ideas: a panel edge is
decoration and 1.4.11 does not reach it, while the edge of a field is the only
thing saying *you can type here*.

**Skip link** in `AppShell`, `sr-only` until focused, with `tabIndex={-1}` on
`<main id="main">` so focus actually lands there — without it the browser scrolls
and leaves focus in the sidebar, and the next Tab carries on through the nav as
if nothing happened.

**`FormField` no longer wraps everything in a `<label>`.** It renders a real
`<label htmlFor>`, puts the error outside as a sibling with its own id and
`role="alert"`, and hands the control its `id`, `aria-invalid` and
`aria-describedby` through context (`components/fieldControl.ts` — its own module,
because a file exporting both a component and a hook breaks Fast Refresh). The
red border keys off `aria-invalid` rather than a separate class, so what a field
looks like and what it announces cannot drift apart.

The old shape was a real defect, not just untidy: everything inside a `<label>`
folds into the control's accessible name, so a rejected field announced "Sum
assured Must be at least 0.01" as its **name** — and kept announcing it after the
user fixed the problem, because a name is not a thing that changes.

**Verified:** typecheck, lint, 662 unit tests, and the full e2e suite. Running
e2e was not optional here — this touched every form on the platform, and §14.5
records a component rewrite breaking 11 of 24 spec files with every unit test
green.
