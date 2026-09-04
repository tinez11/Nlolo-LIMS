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
facts and a "Full detail" link; the full page owns every mutating action. This
keeps settlement decisions, waivers, and payouts off a surface dismissable by
clicking the backdrop. The reference design does the same thing — its drawer
carries a "‹ FULL PROFILE" link.

The full page was specified here as **tabbed**, and it is not — see §14.8. Tabs
would have hidden a claim's evidence behind the assessment form that cites it.
The hierarchy that tabs were standing in for is delivered instead by
`DetailLayout`'s pinned record rail and `Panel emphasis`, which promotes the one
panel a page exists to act through. Recorded as a traded promise rather than an
omission, so nobody re-adds tabs looking for a missing feature.

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

1. ~~**Nothing confirms, and nothing asks before the irreversible.** Zero
   confirmation dialogs and zero success confirmations exist. Approving a payout
   is acknowledged only by the panel vanishing.~~ **Done** — §14.7. Two of the
   critique's own specifics were checked and not followed: KYC rejection is not
   irreversible, and no endpoint returns a journal reference.
2. ~~**Four AA breaches**: `--subtle-foreground` at 3.35–3.50:1 light and
   4.31–4.42:1 dark at 11px; `--input` at ~1.27:1 against `--surface`; no skip
   link (19 tab stops to the first row); no `aria-invalid`/`aria-describedby` on
   ~98 inputs, with `FormField` nesting the error inside the `<label>`.~~ **All
   four fixed** — §14.6.
3. ~~**The design system is a copy-paste convention.** 7 byte-identical `Panel`
   components, 8 `FilterChip`s, 98 input class strings in 24 variants, 17
   hand-rolled 11px labels bypassing `FormField`, no lint ban on
   `border-input`.~~ **Done in full** — §14.6, including the lint rule, which
   is what makes it stay done.
4. ~~**No tabs anywhere**, though §6 and `PRODUCT.md` both promise tabbed detail
   pages and `@radix-ui/react-tabs` is installed and imported nowhere. Detail
   pages are 8 co-equal panels with every heading at body size.~~ **Resolved
   without tabs, deliberately** — §14.8. The hierarchy problem was real and is
   fixed; tabs were the wrong instrument for it.
5. ~~**The stat row is dead weight.** Every list screen passes one stat into a
   four-column grid: measured at 1440px, a 282px card in a 1216px row, 77% empty,
   restating the count the pager prints below it.~~ **Done** — §14.8.
6. **Nothing built for eight-hour use** — no keyboard shortcuts, no command
   palette (§7 assumes one exists), no bulk actions, and Claims search matches
   policy number only, so an assessor cannot find a claim by claimant name.

Two dead dependencies to remove: `@radix-ui/react-tooltip` and
`@radix-ui/react-avatar`. `@radix-ui/react-tabs` joins them — §14.8 decided
against tabs, so nothing will ever import it.

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

**Known flake, still open, not a regression.** `staff-beneficiaries`'s picker
tests intermittently fail with the option "outside of the viewport" after ~100
retries. Bisected properly rather than assumed: they pass with the day's changes
in place both isolated and in a re-run of the same batch, so nothing this session
caused it. Observed roughly one run in four across the day, on two different
tests in that file.

**One attempted fix, which did not work, recorded so nobody repeats it.** The
first theory was overflow below the fold, so `PartyPicker`'s popover gained
`collisionPadding` and a list capped at
`var(--radix-popover-content-available-height)`. It still flaked on the next
three runs. That change is **kept** — a dropdown running past the fold is a real
defect regardless — but its comment now says plainly that it is not the cure.

**What the evidence actually points at.** Reading the failure screenshot rather
than theorising further: the page is scrolled DOWN to the Loans panel with the
beneficiaries form off the TOP of the viewport. So the trigger is above the fold,
not below it, and the likely mechanism is a portalled, `position: fixed` popover
chasing a trigger that lives inside the scrolling `<main>` — Playwright scrolls to
bring the option into view, that scroll moves the trigger, Radix repositions, and
the two chase each other until the timeout. cmdk's own `scrollIntoView` on the
auto-selected first item is a plausible trigger for the first scroll.

If so the fix is structural — render the content un-portalled so it scrolls with
its container, or pin the scroll before opening — not another positioning prop.
Worth an hour when someone next has cause to be in that component.

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

**The last 17 fields, and the rule that keeps them.** Those labels were the old
FormField shape inlined by hand, carrying the same error-inside-the-label defect
plus an 11px caption competing with the system's 12px. Converting them turned up
forms rendering errors in a shared block *below the row* — which
`aria-describedby` cannot reference without guessing whose error it is — so each
now sits under its own field. `FormField` gained a `className` passthrough for a
field that must join its parent's layout (`flex-1`), documented as not being for
restyling the field.

`border-input` is now **lint-banned in `src/features`**. Keyed on the class, not
on `<input>` as an element: five raw inputs survive in features and all five are
correct — three checkboxes, which are not text fields, and two react-dropzone
inputs, which must be raw for `getInputProps()` to attach. The rule was probed by
reintroducing a hand-rolled field and watching it fail; a guard nobody has seen
fail is not yet a guard.

**Verified:** typecheck, lint, 662 unit tests, and the full e2e suite — twice,
either side of the label conversion. Running e2e was not optional here: this
touched every form on the platform, and it caught two regressions of mine that
typecheck, lint and every unit test missed. `FormField` moving from an implicit
wrapping `<label>` to an explicit `htmlFor` silently un-named `DatePicker` and
`PartyPicker`, which do not read the field context — 17 failures across 8 specs.
And `role="alert"` on the error changed its role away from `paragraph`, which one
spec asserted on. §14.5 already records the same shape breaking 11 of 24 spec
files with every unit test green; this is the third time.

### 14.7 Confirmations and receipts, written from the backend rather than the brief

Done 2026-09-03, clearing item 1 of §14.4. Two components, six surfaces, and two
places where the critique's own prescription was checked and found wrong.

**`ConfirmAct`** — inline, not a modal. A dialog you dismiss by clicking the
backdrop is the wrong shape for "move money", and inline keeps the values being
committed on screen above it. Validation runs **first**: `handleSubmit` parks the
validated values and the confirmation describes them, because confirming an
amount and then being told it was malformed is how a confirmation becomes a
formality to click past.

**`Receipt`** — persistent where the form was, because a toast cannot be re-read
or screenshotted for a file note.

**Every consequence line is a fact checked in the Java**, applying the §14.3 gate
doctrine — *assert only what the platform can prove* — to outcomes:

| Surface | What it says, and where that came from |
| --- | --- |
| Approve settlement | Money moves **and the policy closes permanently**. `PaymentEventListener` closes the policy on settlement and `Policy` has no transition out of MATURED/SURRENDERED — `Claim.reopen()`'s own Javadoc records the asymmetry. Nothing on that screen would otherwise reveal it. |
| Reject claim | Says it **can** be reopened, because `reopen()` accepts REJECTED. |
| Request payout | Names amount and payee; a failed payout cannot be retried from that form, because `payment` dedupes on the Idempotency-Key and drops a resubmission carrying the old one. |
| Submit assessment | It *is* the decision — `decideIfPossible` runs on every POST — and a second one 409s, **except** on POSTPONED, which the copy distinguishes. |
| Waive invoice | `waive()` has no status guard at all and WAIVED is never overwritten by a later payment. On an already-paid invoice the copy says exactly that. |
| Reinstate policy | LAPSED → REINSTATED and never written back to ACTIVE; undoing it means lapsing again, as a separate event. |

**Two departures from the critique, both deliberate.**

It listed **KYC rejection among the irreversible six. It is not** —
`Party.updateKycStatus` assigns with no guard on the previous status, so a
rejected identity can be verified later. It still gets a confirmation, because it
gates whether someone can hold a policy, but the copy says it is re-decidable.
Dressing it as permanent would be the expensive kind of wrong: a warning that
cries wolf teaches the room to click through *all* of them, including the ones
that mean it.

It also asked the receipt to carry a **journal reference. No endpoint returns
one**, and `POST .../payout` answers 202 with no body at all. Printing the
client's own idempotency key in a slot labelled "reference" would look exactly
like a server-issued receipt number and be nothing of the kind — the same defect
as a stat card with no data behind it. So the payout receipt carries the period
and amount off the statement already loaded, and says plainly that the money is
requested rather than paid.

**Receipts only where the screen does not otherwise say.** Settlement (invisible
policy closure) and payout (202, no body) get one. Waive, reinstate and KYC each
flip a status badge already on the page; a receipt restating it would be a second
copy of the same fact, and §14.5 records what duplicated page text does to this
suite.

**Verified:** typecheck, lint, 675 unit tests (13 new, covering that the
confirming button never carries a generic assent and that the receipt announces
as `status` rather than `alert`), and the full e2e suite. Five spec files gained
the second click — which is the suite proving the guard is really there, since
every one of them failed to reach its action without it.

### 14.8 Hierarchy without tabs, and the stat row that was 77% empty

Two of the three remaining critique items, taken together because they were the
same problem seen from two ends: **nothing on a screen said what mattered on it.**

**The tabs promise was not kept, on purpose.** `PRODUCT.md` says "the full page
is tabbed and owns every mutating action", and `@radix-ui/react-tabs` has sat
installed and unimported since M12. Building it would have hidden the evidence
behind the form that cites it: a claims assessor writes findings *from* the
attached certificates, and tabs put those one click and one lost scroll position
away. So the promise was traded, knowingly, for the thing it was a proxy for.

What the pages have instead:

- **`DetailLayout`** owns the two-column body all eight detail pages had written
  out longhand. The 320px column is the **record rail** — identifying facts only,
  `sticky` at the gutter, capped at `100dvh - 3rem` with its own scroll so a
  record taller than the screen cannot pin its top and hide its last rows.
- **`Panel emphasis`** promotes exactly one panel to the Title tier with a
  Rule-Strong edge, and it leads its column. Achromatic — a status tint here
  would make colour mean hierarchy, and colour on this platform means state.
- **Acts leave the rail.** The policy page had five panels in that 320px column
  against two in the wide one, including its suspend/resume/reinstate control.
  *Preview is dismissable; acting is not* applies to a margin as much as to a
  drawer: a person reads a narrow column as a summary.

Where emphasis went, and where it deliberately did not:

| Page | Change |
| --- | --- |
| `ClaimDetailPage` | Assessment / settlement / reopen take emphasis and lead. Rail = Claim + Event details. Reinsurance moved to the wide column. |
| `PartyDetailPage` | KYC verification takes emphasis and leads — it was **last of five**, under four read-only registers. Rail = Identity, Person, Address. Documents and Also-an-agent moved out. |
| `UnderwritingCaseDetailPage` | The assessment panel takes emphasis. Rail = Case + Decision. |
| `PolicyDetailPage` | **No emphasis.** Most visits are somebody looking up an invoice. Lifecycle leaves the rail but goes last, not first. Rail = Policy + Coverage. |
| `ProductDetailPage`, `AgentDetailPage` | No emphasis; the act is occasional. |
| `GroupSchemePage` | No emphasis, and the reason is a rule: it is the one screen that keeps a stat row, so a 1rem heading beside a 1.5rem figure and a 1.25rem title would be a **third size above body**. The Two-Peaks Rule beat the fix. |

**The stat row: nine screens had one number in a four-column grid.** Not a
judgement call — `onSelect` is passed by nobody, so no stat card on this platform
was ever interactive, and every one of the nine held a single `totalElements` the
pager already prints as "1–20 of 143". One number is a sentence, not a card:
`CountLine` renders it in the page header under the description ("**775**
policies · in this tenant"), and the table now starts ~130px higher. `StatCards`
survives for `GroupSchemePage`, the only screen with numbers to compare, and now
sizes to its content instead of forcing four columns onto whatever it is handed.

All three `Stat` states came across intact, because they are the honest part: a
spinner only while a value is genuinely in flight, an em dash labelled
"Not available" once the load has failed, and a hint that always says what was
counted. Neither component is a live region — the pager below already announces
the same total politely.

**Two things the rendered result showed that the source could not.**

1. `Field`'s `note` sat inside the value cell, which the label had already
   narrowed to ~170px of the rail — so every note past four words wrapped two or
   three times into a ragged right-aligned block, three of them stacked on the
   policy panel. The note now takes its own full-width line (a second `<dd>`
   after one `<dt>`, which is valid and also true: it qualifies the value, so a
   screen reader should reach it). Most notes are now one line.
2. Browser surfaces were still OS defaults. That stopped being cosmetic the
   moment the rail got its own bounded scroll — an unstyled 15px chrome gutter
   inside a 320px column is a quarter of the room the figures need. Scrollbar,
   `::selection` and the input caret are now themed from the tokens, achromatic
   under the Stamp Rule.

**Verified:** typecheck, lint, 675 unit tests, and the full e2e suite. Rendered
evidence at 1440px and 500px, plus scrolled captures proving the rail actually
sticks rather than merely carrying the class. `staff-clients`'s
"shows every panel" test passes unchanged through the reorder, which is the
locators being name-based rather than positional — the one piece of luck in this
change, and only because §14.5 had already forced that discipline.

**Not done, and not smuggled in:** the `DEATH`-as-page-title
SCREAMING_ENUM and the raw product UUID in the policy header are copy problems
for `clarify`, not layout. The count line's unfiltered hint ("in this tenant")
now sits one line under a description that often says the same thing; that
redundancy is real, mine, and left for a copy pass rather than reworded across
nine screens here. Item 6 — the eight-hour-use gap — is untouched.

#### The picker flake from §14.5, finally pinned

The reorder broke 2 of 93 e2e tests, both the same click — selecting an option in
`PartyPicker` on the policy page's beneficiary editor — and both were the
intermittent failure §14.5 recorded as unexplained. Making it deterministic is
what explained it.

**Two causes, stacked.**

1. **The list chases its own trigger.** `Popover.Content` is portalled and
   fixed-position, so Radix re-anchors it to the trigger on every scroll of the
   `<main>` column. Scroll toward an option and the trigger moves, the list
   re-anchors, and the option moves with it — Playwright reported "element is
   visible, enabled and stable … element is outside of the viewport" 105 times in
   a row. Fixed with `modal` on `Popover.Root`, which locks page scroll while the
   list is open. That is the correct behaviour independent of the test: while
   choosing from a list anchored to a field, the page underneath should not move.
2. **The editor was under an unbounded ledger.** This was mine. The first
   ordering put Invoices — twenty-plus rows on the seeded policy, and growing for
   the life of the contract — above Beneficiaries, which is three rows and a term
   of the contract. A page that leads with a panel of unbounded height buries
   every panel after it, and the failure screenshot is the proof: the viewport
   frozen mid-invoice-table with the picker's trigger below the fold.

**Rule taken from it: bounded panels before unbounded ones.** Contract terms
first, ledgers after. "Most visits are looking up an invoice" argued for Invoices
leading, and it was the wrong conclusion — second place costs an invoice-reader
one short panel of scroll, while first place cost the beneficiary editor its
reachability.

The two causes were independent: the scroll lock alone fixed one of the two
tests, the reorder fixed the other, and both changes are worth keeping.

### 14.9 Clients becomes two areas, and the company-to-members hop

The register was one list of every party with a `Type` column hidden below `sm`,
which is a poor way to say "this is a person" and "this is a company" when the two
are different jobs done by different people against different evidence.

**What the platform could and could not support**, established before designing:

| Wanted | Reality |
| --- | --- |
| Filter the register by party type | `GET /parties` took `kycStatus`, `q`, `page`, `pageSize` only. **Added.** |
| Individual vs Corporate/Group | `PartyType` is three values — `INDIVIDUAL`, `CORPORATE`, `GROUP` — so the split is 1 vs 2, not 1 vs 1. |
| "ABC Company to its members" | **Two different models.** See below. |

**The type filter is server-side, and that was not optional.** A client-side split
over one page would present "the individuals among the newest 20 of 775" as the
individual register and print a total belonging to neither area — the same class of
lie as a pager over a fully-downloaded array. `PartyRepository.search` was already a
null-safe JPQL query with three optional dimensions, so this is a fourth clause plus
a param through `PartyApiImpl` and the controller. Callers that pass no type still
take the original derived-query branches, which is what makes the change additive.

It takes a **collection**, not a single value: the second area is corporates AND
groups, and as two requests it could not be paged or totalled as one list. As a
single value it would also have silently stopped listing GROUP parties the day one
could be created.

**Two API defects surfaced on the way, both worth keeping in view.**

1. **A latent 500 in the existing search.** `(:q IS NULL OR ... LIKE LOWER(CONCAT('%', :q, '%')))`
   reads as null-safe and was not: with a null bind, Postgres cannot infer the
   parameter's type inside `lower('%' || ? || '%')`, defaults it to bytea, and the
   statement dies on `function lower(bytea) does not exist`. It is a SQL
   *preparation* failure, so the null guard never gets to short-circuit. It stayed
   hidden because the only caller routed to this query exclusively when `q` was
   present — the null branch of a clause written to handle null had never once
   executed. Fixed with an explicit `CAST(:q AS string)`.
2. **The contract validator cannot express an array query parameter.** This is the
   platform's first one, and `swagger-request-validator` rejects the repeated form
   ("Multiple values found for parameter but it is not an array") and then feeds the
   comma-separated form to a JSON parser ("Unrecognized token 'CORPORATE'"). So
   `partyType` is declared as a **pattern-constrained string** —
   `^(INDIVIDUAL|CORPORATE|GROUP)(,(...))*$` — which keeps real spec-level validation
   of the allowed values while Spring still binds it to a `List<PartyType>` natively.
   Recorded because the next array parameter will hit the same wall.

**The nav is two items, one component.**

- `clients/individuals` and `clients/organisations` — two real paths, not one path
  with a query parameter, because `NavLink` derives its active state from the path
  and a shared path would light up both items at once.
- Each carries its **own** pending badge. One combined count on one of the two would
  be a number that does not describe the list beside it: click it and the area shows
  fewer rows than the badge promised, the rest being behind the other item.
- `kyc` survives as a redirect that **preserves the query string** — the badge's own
  links and staff bookmarks are `kyc?kycStatus=PENDING`, and dropping the query would
  silently answer a different question while looking like a broken filter.
- The `Type` column now earns its place per area: dropped in Individuals, where every
  row is the same type, and promoted out of `secondary` in Corporate & groups, where
  it is the real distinction between a company and a group. Rendered as English
  through one shared `partyTypeLabel`, so the register cannot say "Company" while the
  record it opens says "CORPORATE".

**"Group Members" is two models, and only one has data.**

| | Party-side membership | Scheme schedule |
| --- | --- | --- |
| Endpoint | `/parties/{id}/groups/{groupId}/members` | `/group-schemes/{policyNumber}/members` |
| Literally "a company's members" | yes | no — members belong to a contract |
| Rows today | **none possible** | real, and e2e-tested |

The party-side model is the shape the request drew and it is a dead end: `registerGroup`
exists in `PartyApi` with **no HTTP endpoint** (its own javadoc defers the decision),
and `addGroupMember` rejects any party that is not GROUP-type — so no GROUP party can
be created and no membership can exist. Building that panel would have shipped a
permanently empty list. Two gaps to close if it is ever wanted: expose `registerGroup`,
and decide whether a CORPORATE may hold members directly. One useful finding for that
day: `PartyController.listGroupMembers` **ignores its own `partyId` path variable** and
queries by `groupId` alone, and `GroupMembership.groupPartyId` is a party id — so for a
GROUP party `groupId == partyId` and members are reachable without the
"list this party's groups" endpoint that does not exist. The path asserts a containment
it never checks, which is also a small API defect.

So the client record reaches members **through** the scheme: a `Group schemes` panel
listing the client's `GROUP_LIFE` policies, each row linking *straight to* the schedule
rather than to the policy record that owns it, and — where there is exactly one scheme —
the first five lives inline with the server's own member total. That takes the route
from four hops (Clients, client, Policies, the GRP row, policy page, Member schedule)
to one, for somebody who already knows they want the roll.

Three details that are decisions rather than defaults:

- **Derived from the policies panel's own page — no extra request.** `productCategory`
  has been on `PolicyView` since M3, so a scheme is identifiable from a list row.
- **Shown for any client holding a scheme, not only for organisations.** Nothing
  server-side requires a scheme's employer to be a corporate, and the dev tenant's
  seeded schemes are in fact held by an individual — a type-only gate would have hidden
  real cover from the record of the person who holds it. Organisations still see the
  panel when empty, because "no schemes" and no panel are different statements.
- **Its own store slot, keyed by party.** Reusing `policyStore.members` — keyed by
  policy number and not by page size — would have left the scheme page briefly showing
  five of five hundred with a pager agreeing.

**One bug this introduced and fixed.** The register's row click used
`navigate('../parties/{id}', { relative: 'path' })`, which counts URL *segments*: `..`
meant `/staff` only while the register lived at the one-segment `kyc`. At
`clients/individuals` the same `..` resolved to `/staff/clients`, matched no route, and
fell through the catch-all — every row click landed on the realm picker. Every screen is
a flat child of the realm route, so the navigate is now route-relative, which is what
the link always meant and is independent of how many segments a screen's path has.

**Deferred, and named rather than left to be rediscovered:** the party-side group model
above. (Member-name search WAS on this list and is now built -- see §14.10.)

**Verified:** 881 backend tests (5 new, each asserting a type that must be ABSENT —
a filter silently dropped would still return the rows each area wanted and pass any
check that only looked for what should be there); frontend typecheck, lint and 683 unit
tests (8 new, including that the organisations area covers CORPORATE *and* GROUP and
that the type serialises comma-separated); and the full e2e suite, with new coverage for
the two areas being separately filtered, search in the organisations area, the redirect
keeping its filter, and the client-record route to a real schedule.

**Postscript: the store slot the split broke.** The register kept its rows in a
SINGLE store slot with a constant track key, on the recorded reasoning that it was
"the same on-screen table either way, so only the most recently requested filter
should win a race". True for one register; false for two. Switching from Individuals
to Corporate & groups rendered the PREVIOUS area's rows under the new area's heading
and caption for as long as the next request was in flight -- twenty cells reading
"Individual" inside a table captioned "Corporate and group clients", which is exactly
the confusion the split exists to end.

The slot is now keyed by area (`individuals` / `organisations` / `all`), and NOT by
`kycStatus` or `q`: within one area those are the same table and the newest request
should still win. Worth recording for how it was caught -- the spec passed run alone
and failed in the full suite, because in isolation there was no prior area loaded to
bleed through. A single-spec green is not evidence for a screen that shares state with
another screen.

### 14.10 Searching a member roll, across a module boundary

§14.9 left this named as deferred. It is now built, and the interesting part is not the
search box — it is that **a member row holds a party id and no name.**

A group scheme's `PolicyMember` carries `memberPartyId`, `gradeCode`, `joinedOn`,
`status` and `underwritingStatus`. The name belongs to the party module, which exists
to guard exactly that kind of data, and the console has always resolved it per row
through `PartyName`. So "find the member called Juma on this 500-life scheme" cannot be
answered inside the policy module at all, and the two obvious ways to answer it are
both wrong:

- **Filter the fetched page client-side.** On a 25-row page of a 500-life roll this
  searches 5% of the schedule and reports "not covered" for somebody who is. It is the
  worst possible place for this particular lie: the question being asked is whether a
  person is insured.
- **Join across the two modules' tables.** The module boundary forbids it, and it is the
  boundary that keeps party PII in one place.

**So the party module answers the name question and the policy module filters on the
result** — `PartyApi.partyIdsMatchingName(q)` returning a `Set<UUID>`, the same ids-only
idiom `partyIdsRegisteredBy` already established for agents-realm scoping (and
`PolicyApi.policyNumbersForAgentTeam` before that). `policy` already declared
`party::api` in its `allowedDependencies` and already injects `PartyApi`, so no new
coupling was introduced.

**The empty set is the whole trap, and it is handled in the application layer.** No
party matching the term means no member matches — but an empty collection in a SQL `IN`
is a Postgres syntax error, and a *null* one means "no name filter" and would return the
entire schedule for a search that matched nobody. `PolicyApiImpl.listMembers`
short-circuits to `Page.empty(pageable)` before the query, which is the same guard the
benefit lookup a few lines below it already needed. `partyIdsMatchingName` also refuses
a blank term rather than resolving it to `LIKE '%%'`, because "no search" must not become
"every party in the tenant".

Two costs, accepted and written down rather than discovered later:

- **The id set is bounded only by the tenant's party count.** A one-letter `q` in a large
  tenant resolves a lot of ids into an `IN` clause. That is the price of not joining
  across module schemas; the javadoc says so, and the UI sends a submitted term rather
  than keystrokes.
- **It matches a name and nothing else.** A grade code or a join date is on the member row
  and could be filtered directly, but a schedule is searched for a *person*.

**On screen** the search sits beside the existing status chips on the member panel,
because the two compose and "left, called Juma" is a real question a claim assessor asks.
It is URL-backed like every other filter on this console, resets the page offset, and the
empty state says `No member matching "..."` rather than `No members` — the roll is not
empty, this search of it is.

**One thing this exposed about the test estate.** Widening `PolicyApi.listMembers` broke
four call sites in `GroupSchemeIntegrationTest` that Maven's incremental compile never
recompiles: a scoped `-Dtest=` run stayed green and only `clean test-compile` surfaced
them. Same lesson as before, and the reason a signature change here always gets a clean
build. Two further self-inflicted failures worth the record, both from writing a test
payload from memory instead of from the spec: a three-digit phone suffix (a `+255` number
is nine digits, so registration 400s) and `flatBenefit` where the field is
`flatBenefitAmount` — and because unknown request keys are silently dropped
platform-wide, the second arrived as a 409 about the benefit basis rather than as
"unknown field".

**Verified:** the full backend suite with 3 new contract tests — each pinning the row
that must be ABSENT and the total that must have shrunk, since a filter silently dropped
would still return the member the caller asked for; one of them puts a same-matching
person on a DIFFERENT scheme and asserts they stay off this roll, because getting that
wrong would put someone else's employee on an employer's schedule. Frontend typecheck,
lint, 686 unit tests, and `staff-group-schemes` 4/4 against the real stack, including
that a search narrows the roll, that the excluded member disappears, and that a
no-match search reads as an empty search rather than an empty scheme.

### 14.11 One exception answering two questions: the Groupkili product

Reported from the running console: the GROUP_LIFE product **Groupkili** (`group08`) opened
to `This record does not exist, or it is not available to your role.` with a Try again and
a traceId.

Both halves of that sentence were false, and it was reachable in three clicks.

**The state.** Groupkili's only version is effective **2026-09-05** and retires
2026-09-30. Today was 2026-09-04. So:

- `product_definition.status = ACTIVE`, because **publishing a version flips the
  definition to ACTIVE regardless of the version's effective date** — so `GET /products`
  lists it and the console makes it clickable.
- `findActiveAsOf(tenant, product, today)` bounds on `effectiveDate <= asOf` and returns
  nothing, because the version starts tomorrow.

**The defect.** `getActiveSnapshot` threw `ProductNotFoundException` for that empty
result — the same exception, and therefore the same `PRODUCT_NOT_FOUND` code and 404, as
a product that does not exist. And it threw it from the VERSION lookup, which ran *before*
the definition lookup, so the "does this product exist" question was never even asked.

The console then did exactly what it should with a bare 404: this platform deliberately
returns 404 where a 403 would leak existence, so `mayBeDenied` is true for every 404 and
the copy has to hedge — "does not exist, **or it is not available to your role**". A
correct rule, applied to a response that had lied about what happened, produced a
sentence that told a staff member the record was missing and hinted their permissions
were at fault, seconds after they clicked it in the catalogue.

**A future-dated version is not an error.** Publishing ahead of the effective date is what
product authoring is *for*, and a retirement date means a product can also outlive every
version it ever had. Two normal states, reported as a missing record.

**The fix.**

1. **`NoActiveProductVersionException`**, its own type, mapped to 404 with its own
   `NO_ACTIVE_PRODUCT_VERSION` code. The status stays 404 — `premium-quote` already
   documents 404 for this same condition, and the resource asked for (the version in
   force on a date) genuinely is not there. What was wrong was that a client could not
   tell the two apart, so a distinct code is the whole fix.
2. **The definition is resolved FIRST.** Asking "is there such a product" before "is any
   version of it in force" is what keeps the two answers different, and swapping them back
   is what the second new test fails on.
3. **The message is written for a person**, because it reaches the console as `detail` and
   is rendered verbatim: it names the date asked about and, where one exists, the date the
   next version takes effect — the actual remedy is a date and it is guessable from
   nothing else. It names **no productId**: the caller asked about one product, by id, in
   the path it called, and repeating it puts a UUID in front of somebody looking at the
   product's name at the top of the same screen. The id stays on the exception for logs.
   Dates stay ISO: unambiguous is the requirement, and the platform's day-first convention
   exists because `03/04/2026` reads two ways while `2026-09-05` does not.
4. **The spec now declares the 404** with both codes. It declared only a 200 — so the
   contract said this response could not happen, and the validator every product contract
   test runs through had never seen it.
5. **The console renders it as a fact, not a failure**: the server's sentence in the
   Active version panel, with no Try again, because retrying the same date does the same
   thing. `RatingBasis` already said the honest thing for a null versionId and needed no
   change.

**Also fixed, from looking at the screen rather than the code.** The new
`Corporate & groups` nav item truncated to `Corporate & grou...` in the 224px sidebar.
Nav labels are deliberately `truncate`, but a label that ALWAYS truncates is a label
chosen badly — and this is the one item whose whole job is to be distinguishable at a
glance. It reads `Corporate/Group`; the page's own `<h1>` still carries the fuller phrase.

**Verified:** `ProductContractTest` 9/9, with 3 new: a product whose only version starts
tomorrow returns `NO_ACTIVE_PRODUCT_VERSION` and NOT `PRODUCT_NOT_FOUND`, with the next
date in the detail, and resolves normally when asked as of that date (proof the version is
real and only the date was wrong); a genuinely absent product still returns
`PRODUCT_NOT_FOUND`, which is the regression guard for the reordering; and a DRAFT product
with no versions covers the no-next-date branch. Rendered evidence on the real Groupkili
record, before and after.

**Left alone, deliberately:** the product is still ACTIVE in the catalogue while having no
version in force. That is arguably the deeper modelling question — an "ACTIVE" product
nobody can quote or issue against today — but changing what `status` means, or filtering
the catalogue by it, would change what every other screen and caller sees. The read is now
honest about it; whether the catalogue should say something too is a product decision, not
a bug fix.

### 14.12 Billing had two work queues and no way to see either

Asked to look at what `communication`, `payment` and `billing` actually implement, and then
to act on the first three findings. The through-line of all three: **billing is
write-mostly**. It computes, escalates, publishes events and raises Prometheus alerts, and
almost none of it was readable. The one read that existed was per-policy invoices, on the
policy record.

#### Item 1 — the arrears sweep had zero callers

`publishPendingNotifications(tenantId)` was a **public method on `BillingApiImpl` that
nothing in the codebase called.** Not a controller, not a scheduler, not a test, not
another module. It walked the tenant's unresolved arrears cases, escalated dunning levels,
and published `billing.PremiumOverdue` and — at level 5 —
`billing.PolicyLapseRecommended`, which `policy` consumes to lapse the contract.

So the escalation ladder was implemented, tested in isolation, and **never ran.** A policy
could sit unpaid indefinitely at dunning level 1.

**Why it is now its own bean.** The obvious wiring — call it from the invoice read on
`BillingApiImpl` — is exactly the trap this platform has already been bitten by:
self-invocation on a Spring proxy skips the proxy, so `@Transactional` never starts, and
`@TransactionalEventListener(AFTER_COMMIT)` listeners **silently never fire**. The rows
would have escalated and the events would have vanished. `ArrearsNotificationSweep` is a
separate `@Component` so the call crosses a proxy boundary, and its Javadoc records why it
must stay one.

Three further decisions, none of them incidental:

1. **`REQUIRES_NEW`,** because this is a write hanging off a read. A failed sweep must not
   roll back or fail the invoice list somebody is looking at — so it also runs inside a
   `try/catch` that logs and continues. A collections side-effect is not allowed to break
   a read.
2. **Opportunistic, not scheduled.** `pg_cron` would have been the other answer, and the
   platform already uses it for the commission-period close. It is wrong here: a sweep
   needs a `TenantContext` to see any rows at all under RLS, and a background thread has
   none — it would fail closed to zero rows, silently, forever. That failure mode is
   already on the record in this repo. Hanging the sweep off a request means the tenant is
   established by the caller's own token.
3. **The N+1 went with it.** The invoice read was fetching each invoice's arrears case one
   query at a time; it now resolves the whole page in one `findBy...InvoiceIdIn` and maps
   dunning level by invoice id.

**Proven on real data, not on a mock:** `POL-2AAC4C16` went `ACTIVE` → `LAPSED` through the
newly-wired path, with 5 × `billing.PremiumOverdue` and 1 ×
`billing.PolicyLapseRecommended` landing in `audit.audit_log`. Before this, that transition
was unreachable.

#### Item 2 — the collections queue

`GET /arrears`, finance-gated (`REALM_STAFF` and `FINANCE_OFFICER`/`ADMIN`), plus the
`Arrears` screen under Finance.

- **`minDunningLevel` is a FLOOR, not an exact match.** The question a collections officer
  has is "what is at this level or worse"; an exact filter hides the level-5 cases from
  somebody triaging upward from 3.
- **Level 5 is labelled as what it means**, not as a number. It is not "the worst level" —
  it is the level that publishes `PolicyLapseRecommended`, so a row at 5 is a policy on its
  way out rather than one being chased. `LAPSE_RECOMMENDATION_LEVEL` is exported so the
  screen and the API module agree on that.
- **`resolved` is sent when explicitly `false`,** because `false` is the live queue and
  dropping a falsy value would silently widen it to include settled history.
- **Sort is total:** `dunningLevel DESC, openedAt ASC, arrearsCaseId ASC`. Two cases at the
  same level opened in the same second must not be able to swap places between page 1 and
  page 2.

#### Item 3 — field receipts, decided yes, and why

The question was whether agent-captured cash needed a read surface at all. It did, and the
evidence was already in the repo: `observability/alert-rules.yml` carries a live
medium-severity **`FieldReceiptReconciliationOverdue`** — *"one or more agent-captured
receipts have exceeded the `OFFLINE_RECEIPT_SLA_HOURS` refdata parameter without a matching
`PaymentConfirmed`"* — against an entity whose **only endpoint was capture**.

An alert that names a count, and nothing anywhere that can name a receipt. The only
available follow-up was a hand-written database query. That is the whole justification.

`GET /field-receipts`, finance-gated, sorted `capturedAtServer ASC, receiptId ASC` —
oldest unmatched cash first, because age against the SLA is the reason to look.

What the screen does that a generic table would not:

- **It defaults to `RECONCILIATION_OVERDUE`.** Every other queue on this console opens on
  everything. This one opens on the breach, because that is what somebody arriving here has
  been paged about — and the count line says so (`2 overdue receipts · past the
  reconciliation SLA`) rather than leaving a non-neutral default to be discovered. `ALL` is
  an explicit sentinel so "show me everything" stays distinguishable from a fresh visit.
- **Both timestamps, side by side.** `Reached us` (`capturedAtServer`) is what the SLA is
  measured against; `Collected` (`capturedAtClient`) is the agent's own clock. The gap is
  the diagnosis: hours apart means the field was offline, minutes apart means we were slow,
  and those lead somewhere completely different. Showing one and hiding the other would
  throw that away. The seeded evidence shows a 6-hour gap.
- **`clientIdempotencyKey` is deliberately omitted** from the view. It is a de-duplication
  mechanism, not a fact about the money.
- **The agent is an id, shown as 8 characters with the full value on `title`.** It is a
  distribution `AgentProfile` id, and no by-id agent-name lookup takes one — `PartyName`
  resolves a PARTY id. Rendering the full 36-character UUID squeezed the policy number and
  the amount onto two lines each, which the first screenshot caught.
- **Rows go to the policy**, where the invoice the cash should have matched lives. This
  screen finds the mismatch; the policy record explains it.

**A latent bug fell out of building it.** `lib/status.ts` was missing
`RECONCILED: 'success'` from the `fieldReceipt` bucket map. The literal has been in the
table's CHECK constraint since the first billing migration and became reachable when
`FieldReceipt.reconcile()` was implemented — so a matched receipt was rendering through
`StatusBadge`'s unrecognised-literal path. Correct behaviour for a genuinely unknown
literal, wrong answer for a known one. **Nothing on this console displayed a field receipt
at all**, which is exactly why nobody had seen it.

#### Store design, and the lesson it inherited

`billingStore` has **two separate slots**, not one shared list. Arrears and field receipts
answer two different questions, and one slot would let one queue render under the other's
heading while the next request was in flight — the same defect the client register produced
when Clients became two areas (§14.9), which passed alone and failed in the full suite.
Invoices stay out of this store entirely: they are read per policy and live in
`policyStore`, keyed by the policy number they belong to.

#### Verified

Backend 897 tests, `BillingContractTest` 28/28, `BillingApiIntegrationTest` 16/16.
Frontend unit 686, full e2e 102 passed. Six new e2e across `staff-arrears.spec.ts` and
`staff-field-receipts.spec.ts`, each pair covering the queue, the filter round-trip, and
the finance gate arriving as an access panel for a claims assessor — the falsifiable half,
since an ungated endpoint would render a perfectly ordinary table and nothing would look
wrong.

**One e2e trap worth recording.** Both new screens use a `StatusBadge` as the *label* of
their filter chips, so `getByText('Pending reconciliation')` matches the chip **and every
row it filtered to** — a strict-mode violation that only appears once the table has data.
The chips are addressed by role instead: a chip is a button, a cell is not. This is the
third shape of the same recurring e2e lesson, after accessible-name coupling and
rendering-a-name-where-an-id-belongs.

#### Not done, and stated as such

- **`communication` is still 0 classes.** Three tables with no RLS policies and no
  `app_role` grants, no mail dependency on the classpath, and an event catalog plus
  AsyncAPI document that both claim it as a consumer on 23 channels. So every
  `PremiumOverdue` this milestone made real is published to a subscriber that does not
  exist. That is a milestone, not a follow-up, and it is not started.
- Item 4 of the four findings — the payment reconciliation surface — was not in scope for
  "1 to 3".
