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

**Spec-truth fixes:**

4. `RegisterCorporateRequest.contactInfo` — declare its real properties: `phoneNumber` (`^\+255\d{9}$`) and `email`, both optional, object itself required
5. `PageMeta` — add `required: [page, pageSize, totalElements]`
6. `openapi-policy.yaml` — add `SURRENDER` to `BenefitType` (present in Java, missing from the spec)
7. `openapi-underwriting.yaml` — **remove `medicalDisclosure`**, see §11

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

**`medicalDisclosure`.** Advertised in the spec, absent from the Java DTO,
silently discarded with a 201 (Spring Boot's `FAIL_ON_UNKNOWN_PROPERTIES=false`).
The `MedicalDisclosure` entity and repository exist with zero call sites, and
`RiskProfile` explicitly excludes occupation/smoker factors for want of a
structured source. A form here would appear to work and throw the user's input
away. Removed from the spec; wiring it is backend feature work.

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
