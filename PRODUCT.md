# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

**Primary: back-office insurance staff, at a desk, on a large monitor.** Confirmed.
They are the volume users and the console is designed for their screen. Six staff
roles exist in the `staff` Keycloak realm and each has a different centre of
gravity:

| Role | What they are doing in here |
| --- | --- |
| `UNDERWRITER` | Working the underwriting queue: opening cases, reading declared disclosures, accepting / loading / postponing / declining risk |
| `CLAIMS_ASSESSOR` | Assessing registered claims — findings, recommended amount, fraud indicator, contestability review |
| `CLAIMS_MANAGER` | Settlement decisions: approve or repudiate, approved amount, payee |
| `FINANCE_OFFICER` | GL postings, chart of accounts, treaties, regulatory returns, agent commission and payouts |
| `ADMIN` | Everything a finance officer sees, plus product authoring and configuration |
| `CUSTOMER_SERVICE_REP` | Undetermined. This role appears in **zero** `@PreAuthorize` expressions server-side, so it currently sees the ungated groups only. Recorded as an open question in `frontend/PLAN.md` §12.3, not as a designed audience. |

**Secondary: tied agents**, in the `agents` realm, with their own much smaller
console — their profile, the book of policies and claims they or their downline
wrote, the clients they registered, and an onboarding form. Server-side scoping
does the filtering; the agent UI reuses the staff page components with different
titles and the create actions hidden.

**Not audiences yet.** The `customers` and `regulators` realms authenticate but
mount no screens at all, deliberately — an authenticating route into an empty app
is worse than a 404. Whether either becomes a real surface is **undecided**, and
nothing designed today should assume a consumer-facing or regulator-facing
surface is coming.

## Product Purpose

A core administration platform for life insurance in Tanzania: the system of
record that carries a life policy from a registered party through underwriting,
issue, billing, servicing (loans, lapse, grace, surrender), claim, settlement,
reinsurance cession, general-ledger posting and statutory return.

Success is that a regulated insurer can run its book here — that every number a
staff member acts on is one the platform can actually prove, and every action
they take is journalled. This is an **Operate** product: nobody is being
persuaded, everybody is completing a task, and being wrong is expensive in a way
that being plain is not.

## Positioning

**A multi-tenant core platform sold to Tanzanian life insurers**, with Nlolo as
the vendor. Confirmed.

Two consequences that bind the design:

1. **The identity must be vendor-neutral and plausibly white-labellable.** No
   single insurer's brand may be baked in. There is no logo or brand asset
   anywhere in the repo today; `Life Platform` is a placeholder wordmark
   appearing in exactly two files (`components/AppShell.tsx`,
   `features/RealmPicker.tsx`).
2. **Tenancy is invisible on purpose and must stay that way.**
   `TenantContextFilter` reads `tenant_id` from the validated JWT only. There is
   no `X-Tenant-Id` header, no client-supplied tenant input, and Postgres RLS
   enforces isolation per connection. The UI never sends, displays or switches a
   tenant. Any future design that introduces a tenant picker is a security
   regression, not a feature.

The mechanism a neighbouring product could not truthfully copy is the discipline
already enforced in the code: **this console renders only what the platform can
prove.** No analytics endpoints exist, so no trend arrows exist. No client-side
premium arithmetic. No fabricated rows. That refusal is the position.

## Operating Context

- **Where.** Office desks, large monitors, keyboard-and-mouse, sustained
  sessions. The current build reflects this literally: a fixed `w-56` sidebar and
  responsive breakpoints in only 13 of ~199 source files. Desktop-first is
  correct; it is not an excuse for the console to break below a laptop width.
- **Rhythm of the work.** Queue-shaped. Four nav badges count *work waiting*,
  never total volume — individuals awaiting KYC, companies and groups awaiting
  KYC, open underwriting cases, unassessed claims — because those are the only
  counts the API can express honestly (one status filter at a time, no analytics
  endpoint). KYC is counted twice because the client register is two nav items:
  one combined count sitting beside one of them would not describe that list.
- **The business flow the nav follows.** Clients → New business (underwriting) →
  Policies & claims → Finance → Distribution → Records → Configuration.
  Configuration sits last deliberately: authoring a product is rare actuarial
  set-up, not daily operations.
- **Clients is two working areas, not one register.** Individuals, and corporates
  and groups. A natural person's KYC is an ID scan and a date of birth; an
  organisation's is a registration number and a certificate, and the two are
  reviewed by different people against different evidence. Both are searchable by
  name, and the split is a server-side `partyType` filter — as a client-side one
  it would report "the individuals among the newest 20 of 775" as the individual
  register and print a total belonging to neither area.
- **Drawer previews, page acts.** A table row opens a read-only slide-over with
  key facts and a link to the full page; the full page owns every mutating
  action. Settlement decisions, waivers and payouts must never live on a surface
  dismissable by clicking a backdrop. The full page is **not tabbed** — that was
  specified and then traded, because tabs hide a claim's evidence behind the
  assessment form that cites it. What it has instead is a pinned record rail of
  identifying facts and one emphasised acting panel leading the work column
  (`frontend/PLAN.md` §14.8).
- **Documents and evidence** are real parts of the job: claim evidence uploads,
  party KYC documents, commission statements, regulatory returns.
- **`traceId` is operational furniture.** It is surfaced and copyable on errors
  because it is the only thread back to the backend logs.

## Capabilities and Constraints

**Realms.** Four Keycloak realms — `staff`, `agents`, `customers`, `regulators` —
served by one React SPA through realm-scoped route subtrees, each mounting its
own `AuthProvider`. Public PKCE client `lifeplatform-spa`. Tokens live in memory
only.

**Screen inventory.** ~36 routed screens across 11 feature areas: policies,
claims, underwriting, party, products, distribution, finaccounting,
regreporting, reinsurance, audit, plus the realm picker. Declared once in
`src/screens.tsx`; the router and sidebar are both maps over it. Every screen
must declare a `reach` — a nav placement or the literal `'drill-in'` — so
"deliberately not in the sidebar" and "somebody forgot the nav item" cannot look
identical.

**Only entities with a real list endpoint get a nav item.** A nav item leading to
a "paste an ID" screen reads as broken software. Two nav items may point at one
endpoint where they are genuinely two working lists — the client register's
individuals and organisations areas — but each must be a real server-side filter
with its own honest total, and each gets its own path so the sidebar can say
which one you are in.

**A group member belongs to a contract, not to a company.** A group scheme's
members are its insured schedule, so a company with three schemes has three
rolls. The client record therefore reaches members *through* the scheme —
listing the client's schemes and linking each straight to its schedule — rather
than pretending a company has one member list. A schedule is **searchable by
member name**, which is the only way to answer "is this person covered" on a roll
of hundreds — and because the name lives in the party module while the member row
holds only an id, that search resolves names to ids across the module boundary
rather than filtering the page in hand. There is a second, party-side
group-membership model in the API (`/parties/{id}/groups/{groupId}/members`) that
would give a company members directly; it is unreachable and unpopulated, and
`frontend/PLAN.md` §14.9 records the two backend gaps that block it.

**Money is always a decimal string plus a `currencyCode`, never a JSON number.**
Backend validates `^-?\d+(\.\d{1,2})?$`. `Intl.NumberFormat` for display, the
same regex on inputs, and **no client-side arithmetic** — the backend computes
every total. Working currency is TZS.

**Numbers are counts, not analytics.** Stat cards show `totalElements` from the
four paged searches and nothing else. No trend arrows: there is no trend data,
and a card reading "↗ 12% wk/wk" with nothing behind it is worse than no card.

**Status vocabulary is fixed at six semantic buckets** — neutral, pending,
active, success, warning, danger — mapping ~25 backend enums through one shared
`StatusBadge`, so a "pending" invoice and a "pending" claim look alike. Enum
literals come from the OpenAPI specs and Java enums and are **never invented**.

**Two table variants.** A paged `DataTable` for the four genuinely paged
endpoints; a client-side sort/filter table with no pager for the eleven
bare-array endpoints. A pager over a fully-downloaded array lies about the
network.

**Idempotency-Key** is minted once per submit inside the Zustand action and
reused on retry, on the six endpoints that hard-require it. Auto-generating per
request would double-charge.

**Error handling is specified, not ad hoc.** `401` → silent renew then re-login;
`403` → an access panel, not a toast; `404` may be a *disguised* denial; `501`
means a deferred stub and its action renders disabled with a tooltip, never as a
live button; `400` with field errors binds onto the form.

**Business rules live in `src/gates/`**, exposed as `(record, asOfDate) => Gate[]`
and rendered by one `<GatePanel>`, testable with no DOM. Gates assert only what
the platform can prove — notably, the backend **ignores** the `asOf` parameter on
coverage-status, so no as-at-the-date-of-event coverage judgement may be
presented as if it were one.

**Undecided / deliberately deferred, and not to be designed around:**
endorsements UI (the change vocabulary is un-ratified), CORS bean, pagination for
the eleven bare-array endpoints, Keycloak identity brokering, commission-plan
deactivation, underwriting override, and the four undesigned bounded contexts
from the operations mockup (leads, needs analysis, quotations/proposals, service
cases). The `customers` and `regulators` surfaces are undecided.

## Brand Commitments

**None exist.** Confirmed: there is no logo, no brand asset, no colour
commitment, no typographic commitment, and no named identity anywhere in the
repository. `Life Platform` is a working placeholder, not a ratified name.

The one binding constraint is negative and comes from Positioning: the identity
must be **vendor-neutral and white-labellable**, because insurer tenants share
this console. It may be distinctive; it may not be one insurer's.

## Evidence on Hand

- **Real API, real data.** 20 controllers / ~79 operations, 15 OpenAPI specs at
  `backend/api/openapi/`, generated types at `frontend/src/types/api/`. Every
  value the console renders comes from this platform's own API.
- **A real design record.** `frontend/PLAN.md` — 41 settled decisions, plus a
  running record of what was found wrong and fixed. It is the most reliable
  document in the repo. `README.md` at the root is **stale** (it still describes
  Phase 0 with no code generated) and should not be trusted.
- **A real test estate.** ~466 unit tests (Vitest + RTL), 24 Playwright e2e specs
  driving the real Keycloak container. **Hard rule: zero fabricated JWTs
  anywhere.** This project has twice shipped a fully green suite over a
  completely unusable real credential path.
- **No mock data, no invented metrics, no placeholder copy.** An operations-console
  mockup reviewed in `PLAN.md` §13 hardcoded a 120-row chart of accounts, a
  payments feed and 21 report definitions; all were rejected. Future work must
  not reintroduce them.
- **Absent and must not be fabricated:** analytics or trend data, customer
  testimonials, pricing, benchmarks, uptime or performance claims, deployment
  claims, TIRA return catalogue/format (still an open item), IFRS17 cohort rules,
  and any brand asset.

## Product Principles

1. **Render only what the platform can prove.** If the API cannot supply it, it
   does not appear — not as a placeholder, not as an estimate, not as a
   plausible-looking zero. This is the product's position, not a limitation to
   design around.
2. **The consequence of being wrong is money or compliance, not a bad
   impression.** Mistaking `IN_DOUBT` for `FAILED`, or paying against a
   superseded commission plan, is a real operational error. Legibility of state
   outranks expression everywhere.
3. **Queues before dashboards.** Staff arrive to find out what is waiting for
   them. Counts of work waiting are honest; counts of total volume are noise.
4. **Preview is dismissable; acting is not.** Read-only facts may live in a
   slide-over. Anything that moves money, changes cover or closes a case belongs
   on a page a person navigated to on purpose.
5. **Vendor-neutral, tenant-invisible.** The console belongs to the platform, not
   to any insurer using it, and never reveals or offers a tenant.

## Accessibility & Inclusion

No formal standard is imposed by regulation or procurement. Confirmed.

**WCAG 2.2 AA is the working floor** for this project regardless: contrast on
every status bucket in both themes, a real keyboard path through every table,
drawer and form, visible focus (already enforced globally via `:focus-visible`
and never suppressed), accessible names on icon-only controls, and screen-reader
text wherever a number alone would mislead (the nav badges already do this).

**Language: English only, indefinitely.** Confirmed. No i18n layer is required
and copy may be written for English rhythm. Swahili is not a planned surface;
Tanzanian data conventions still apply (`+255` phone numbers, TZS money, and
day-first dates — `03/04/2026` read in the wrong order is why `DatePicker`
exists).

**Dark mode is a first-class requirement**, not a preference: it shipped from day
one as an explicit class toggle on `<html>`, and every token is defined twice.
Any new colour must be defined in both themes or it is incomplete.
