# Reporting module, batch 1: Management reports

Date: 2026-10-11
**Status:** merged cf0fca62 (2026-10-11). Implemented (batch 1) on feature/reporting-management, last commit 97d50d6d. 117 backend tests
across the reporting and neighbouring classes and the reports e2e spec pass; dev read model rebuilt with
0 failures. Deviations from this spec, all found while building: the clock is a ReportingClock component
(a second Clock bean breaks Spring Modulith's Moments); audit/api gained @NamedInterface("api").

## 1. Goal

A general reporting module for operational and management reports, separate from
`regreporting` (TIRA returns), `reinsurance` (bordereau, statement) and `finaccounting`
(IFRS 17 extracts), which stay where they are.

The full catalogue (about 35 reports across Finance, Distribution, Claims, Underwriting,
Policy servicing and Management) ships in batches of 4-6 reports, one category at a time.
**This spec covers batch 1, Management**, plus the shared machinery every later batch reuses:
date filters, the category catalogue, export, and rebuild.

Batch 1 reports:

1. Policies in force
2. Lapse rate (catalogue key `lapse-rate`) and persistency (`persistency`) -- two catalogue
   entries, because they take different date filters
3. New business trend
4. Premium collections summary
5. Clients (individual, corporate, group)

## 2. Boundary rule

The module never reads another module's tables or repositories. It learns about the
platform from domain events, and calls public `*Api` interfaces only for history and for
detail events do not carry.

`package-info.java`:

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "audit::api", "party::api", "product::api", "distribution::api" })
package tz.co.nlolo.lifeplatform.reporting;
```

- `audit::api` -- `AuditApi.listEvents(prefix, range, pageable)`, the domain-event journal, for
  rebuild.
- `party::api` -- `PartyApi.getPartyDetail` (type, KYC, date of birth, sex, registration time)
  for the clients report, and display names for scheme holders and agents.
- `product::api` -- `ProductApi.getProduct(productId).productName()` for row labels.
- `distribution::api` -- `DistributionApi.getAgent(agentId).partyId()`, to name an agent.

(Corrected 2026-10-11 while planning: refdata holds no product names, so it is not a
dependency.) Labels are resolved when a report runs, cached for that one request, so a
renamed product shows its current name.

## 3. Module shape

```
reporting/
├── api/             ReportingApi, report views, ReportCategory, DateFilterKind
├── domain/          pure logic: PayloadReader, CivilDates, PolicyStatus, AnnualisedPremium,
│                    StatusTimeline, age bands
├── application/     event handlers, live listener, RebuildService, the report queries
└── infrastructure/  ProjectionStore and query SQL (JdbcTemplate), ReportController,
                     xlsx/csv/pdf writers
```

Storage is JdbcTemplate, not JPA: every write is an idempotent `INSERT ... ON CONFLICT`, and
every report is an aggregate query, neither of which an entity model helps with. A
module-local test asserts no SQL string in `reporting` names another module's schema.

`api/` is the module's public Java interface, as everywhere on this platform. REST
controllers live in `infrastructure/`.

Listener beans get explicit names (`reportingPolicyEventListener`, ...). `billing`,
`distribution`, `reinsurance` and `regreporting` already declare `PolicyEventListener`, and a
duplicate simple name fails context startup.

## 4. Event handling

### 4.1 One handler path, two callers

Each event type maps to one handler method. Two callers use it:

- **Live:** `@TransactionalEventListener(phase = AFTER_COMMIT)` on `DomainEventEnvelope<?>`,
  running the handler in a `PROPAGATION_REQUIRES_NEW` `TransactionTemplate`, with tenant
  save/set/restore and the bounded optimistic-lock retry. These mechanics are copied from
  `regreporting.application.PolicyEventListener`, including its reason a plain
  `@Transactional` would silently never commit.
- **Rebuild:** `RebuildJob` (section 7) feeds journal entries to the same methods.

A handler failure increments `lifeplatform_reporting_event_processing_failed_total{eventType}`
and logs. It never propagates to the producer.

### 4.2 Payload values are read type-tolerantly

Live payloads carry Java objects (`UUID`, `LocalDate`, nested `Map`). Journal payloads are
JSON, so the same fields arrive as strings. Every field is read through one helper that
accepts either (`String.valueOf(value)` then parse). No handler casts `(UUID) payload.get(..)`.

### 4.3 Idempotency

`reporting.applied_event (tenant_id, event_id, PRIMARY KEY)`. A handler inserts the event id
in the same transaction as its change and does nothing if the row exists. Replaying over
live data, or rebuilding twice, never double-counts.

### 4.4 Events consumed

| Event | Effect |
|---|---|
| `policy.PolicyIssued` | Upsert `report_policy`: product, version, holder, agent, sum assured, premium, frequency, issue date, `salesChannel`, `branchCode`, `portfolioCode` |
| `policy.PolicyActivated` | Set `activated_on`, `productCategory`, `groupScheme`; fill any field `PolicyIssued` did not supply; status change `ACTIVE`; lives row (`livesCovered`) |
| `policy.PolicyNotTakenUp` | Set `not_taken_up_on` (never in force, so no status change) |
| `policy.PolicySuspended` / `policy.PolicyResumed` | Status `SUSPENDED` / back to `ACTIVE` (suspended still counts as in force, section 5) |
| `policy.PolicyLapsed` | Status `LAPSED` |
| `policy.PolicyReinstated` | Status `ACTIVE` |
| `policy.PolicyMadePaidUp` | Status `PAID_UP` (in force) |
| `policy.PolicyMatured` | Status `MATURED` |
| `policy.PolicyExpired` | Status `EXPIRED` |
| `policy.PolicySurrendered` | `CLAIM_TERMINATED` when `claimId` is present; otherwise `ENDED` for a group scheme (a credit-life scheme whose last borrower left) and `SURRENDERED` for an individual policy (published when a policyholder surrender is APPROVED -- cover stops then) |
| `policy.SurrenderPaid` | Not consumed: it reports the money for a surrender already recorded at approval, and changes no status (corrected 2026-10-11 while planning, from `PolicyApiImpl.approveSurrender`) |
| `policy.PolicyCancelledFreeLook` | Status `CANCELLED_FREE_LOOK` |
| `policy.AnnuityEnded` | Status `ENDED` |
| `policy.GroupMemberAdded` / `policy.GroupMemberExited` / `policy.CoveredLifeAdded` / `policy.CoveredLifeEnded` | Lives row: `livesCovered` after the change, `schemeTotalCovered` where present, kind `JOINED` / `EXITED` |
| `billing.PremiumCollected` | Insert `report_collection` |
| `party.PartyRegistered` | Insert `report_client`, enriched from `PartyApi` |
| `party.PartyKycStatusChanged` | Update KYC status |
| `party.PartyDetailsAmended` | Re-read detail from `PartyApi` (the payload carries no changed values) |

`GroupMemberAdded` carries no member id (verified in `PolicyApiImpl`), so members cannot be
tracked one by one. Every member and covered-life event does carry `livesCovered` -- the
lives on the policy AFTER the change -- so a scheme's membership is a series of counts.

Every status-changing event also appends a `report_policy_status_change` row. Event names
and payload fields were verified against the producers in `PolicyApiImpl` and
`PartyApiImpl`, and `asyncapi-events.yaml` was reconciled to match them in the same change
(21 published-but-undocumented events added, `PolicyIssued`/`PolicyActivated` fields
completed, 14 never-published channels marked `x-published: false`). Note
`party.PartyDetailsUpdated` is one of those 14: the code publishes `PartyDetailsAmended`.

A status change for a policy with no `report_policy` row (activation event missing) creates
the row with product `UNKNOWN` (`new UUID(0,0)`) and increments
`lifeplatform_reporting_unattributed_total{eventType}`. It is never dropped.

## 5. Data model (schema `reporting`)

Rows are per policy, per event, per client -- never pre-summed -- so any date range is a
query-time filter.

| Table | One row per | Key columns |
|---|---|---|
| `report_policy` | policy | tenant_id, policy_number (PK with tenant), holder_party_id, product_id, product_version_id, product_category, portfolio_code, group_scheme, agent_id, sales_channel, branch_code, sum_assured, premium, premium_frequency, currency, issue_date, activated_on, not_taken_up_on |
| `report_policy_status_change` | status change | tenant_id, event_id (PK with tenant), policy_number, to_status, cause (event type), effective_on, occurred_at |
| `report_collection` | premium collected | tenant_id, event_id (PK with tenant), invoice_id, policy_number, amount, currency, collected_on, channel |
| `report_client` | party | tenant_id, party_id (PK with tenant), party_type, kyc_status, display_name, registered_on, date_of_birth, sex |
| `report_lives_change` | member/covered-life change | tenant_id, event_id (PK with tenant), policy_number, kind (ACTIVATED, JOINED, EXITED), lives_covered, scheme_total_covered, effective_on, occurred_at |
| `applied_event` | applied event | tenant_id, event_id |

There is no "current status" column. Status is always derived from the history, so the
order events arrive in (live, or replayed with equal timestamps) cannot leave a stale
status behind. The latest change on or before a date wins, ordered by `effective_on`,
then `occurred_at`, then `event_id`.

Migrations in `backend/db-migrations/reporting/`, with RLS using the
`NULLIF(current_setting(...), '')` predicate form, grants matching `regreporting`, and
`reporting` added to the `MODULES` list in `scripts/migrate.sh`.

Rules:

- **Dates are Tanzania calendar days.** Every timestamp converts to its `Africa/Dar_es_Salaam`
  date before storage.
- **In force on date D:** the policy's latest status change on or before D left it in
  `ACTIVE`, `SUSPENDED` or `PAID_UP`.
- **A group scheme is one policy.** Its members are counted in the clients report.
- **Annualised premium** = premium x `product.api.PremiumFrequency` instalments per year
  (MONTHLY 12, QUARTERLY 4, ANNUALLY 1). **Single premiums (SINGLE) are reported
  separately**, never blended into an APE figure. An unrecognised frequency is counted on a
  metric and excluded from annualised totals, never defaulted to 12.
- **Channel:** `MOBILE_MONEY` when `PremiumCollected.payerRef` is present, else `FIELD_CASH`.

## 6. Reports

Common filters on every report: product, product category, agent, sales channel, branch,
client type. Each returns JSON rows and exports the same rows.

Date filter kinds: **AS_OF** (snapshot on one date), **PERIOD** (from-to, inclusive),
**COHORT** (activation months, followed forward), **AS_OF_AND_PERIOD** (clients).

Every report returns one shape, `ReportResult`: key, title, a one-line filter summary, and
one or more sections, each with typed columns (TEXT, COUNT, MONEY, PERCENT, DATE) and rows
whose values are all strings -- money and counts never travel as JSON numbers, the
platform rule. The screen and all three export formats render this one shape.

### 6.1 Policies in force -- AS_OF

Count, sum assured, annualised premium, single premiums; grouped by product (or category,
agent, branch). Optional comparison as-of date adds a second column set and the change.

### 6.2 Lapse rate / persistency

- **Lapse rate -- PERIOD:** lapses in period / ((in force at start + in force at end) / 2),
  shown gross and net of reinstatements in the same period.
- **Persistency -- COHORT:** of policies activated in the chosen months, the share in force
  at the start of month 13, 25, 37 and 61 (activation date + 12, 24, 36, 60 months), by
  count and by annualised premium. A month column is shown only once the whole cohort has
  reached it. Excluded: `CANCELLED_FREE_LOOK`, `NOT_TAKEN_UP`, single-premium policies, and
  investment portfolios (`SAV`, `DEP`, `PEN`, `DANN`).

### 6.3 New business trend -- PERIOD

Per month: activations (count, sum assured, annualised premium, single premiums), offers
not taken up, take-up rate (activated / (activated + not taken up)).

### 6.4 Premium collections summary -- PERIOD

Collected amount and receipt count by month, product and channel; top products and top
policies by amount. Billed-vs-collected is the Finance batch.

### 6.5 Clients -- AS_OF plus registration PERIOD

- Summary: clients by type and KYC status; new registrations in the period; clients with no
  in-force policy on the as-of date.
- Individuals: age bands 0-17, 18-24, 25-34, 35-44, 45-54, 55-64, 65+ (age on the as-of date)
  by sex; policies per client; clients with in-force vs only lapsed/ended policies. Unknown
  date of birth or sex is its own row.
- Groups and corporates: per scheme -- holder, product category, members covered on the
  as-of date, members joined and exited in the period, sum assured, premium.

## 7. Rebuild

`POST /reports/rebuild` (ADMIN), async, one tenant (the caller's). Steps:

1. Delete the tenant's rows from every `reporting` table, including `applied_event`.
2. Page through `AuditApi.listEvents` for prefixes `policy.`, `billing.`, `party.` in
   `occurred_at` order, oldest first, calling the section 4 handlers.
3. Live listeners keep running throughout; idempotency (4.3) absorbs any overlap.

`GET /reports/rebuild/status` returns state (`IDLE`, `RUNNING`, `DONE`, `DONE_WITH_FAILURES`,
`FAILED`), events replayed, events failed, start and end time. The console's Reports page
offers ADMINs a **Rebuild reporting data** button with a status check, because the read model
starts empty on first deploy.

The journal starts in August 2026 (oldest audit partition). An event the audit listener
failed to store sits in `audit.failed_event` and cannot be replayed by this job; the
rebuild status says how many handler failures occurred, and the audit dead letters remain
an audit-module concern.

`AuditApi.listEvents`' JPQL query has no `ORDER BY`, so the job's `Pageable` sort
(`occurredAt` ascending) is what orders the replay. The job pages with a stable secondary
key so equal timestamps are not skipped or repeated between pages.

## 8. REST API

All under `/reports`, documented in the OpenAPI spec and covered by the existing request
validator tests.

- `GET /reports/catalogue` -- `[{key, title, category, dateFilterKind}]`
- `GET /reports/{key}?asOf=|from=&to=|cohortFrom=&cohortTo=&productId=&productCategory=&agentId=&salesChannel=&branchCode=&clientType=&groupBy=`
- `GET /reports/{key}/export?format=xlsx|csv|pdf&...same filters`
- `POST /reports/rebuild`, `GET /reports/rebuild/status`

A date parameter that does not match the report's kind is a 400 ProblemDetail naming the
expected parameters.

**Access:** staff realm, roles `ADMIN`, `FINANCE_OFFICER`, `FINANCE_APPROVER` (user's
choice -- no new role). Rebuild is `ADMIN` only.

## 9. Console

- A **Reports** group in the staff console nav, visible to the three roles.
- Catalogue page grouped by category (only Management populated in batch 1).
- Report page: a date control matching the report's filter kind, the common filters, a
  results table, export buttons.
- Follows `DESIGN.md` and the existing console patterns.

## 10. Testing

- Unit: in-force-on-date, lapse rate, persistency cohorts and month eligibility, age bands,
  annualised premium, Dar es Salaam date conversion.
- Handler tests feeding each payload twice -- Java-typed (live) and JSON-string (journal).
- Integration (Testcontainers): drive real lifecycle events, assert each report.
- **Rebuild equivalence:** after live processing, rebuild from the journal and assert the
  tables match row for row.
- Modulith verification: only `audit::api`, `party::api`, `refdata::api`.
- Playwright e2e at the end of the batch.

## 11. Batch 2: Accounting (outline -- gets its own spec)

Agreed order: Management (this spec) first, Accounting next. Recorded here so the decisions
are not lost:

- **Boundary:** accounting reports call `finaccounting::api`; they never project the GL from
  events. The GL is authoritative, and a second copy could disagree with it. Batch 2 adds
  `finaccounting::api` to `allowedDependencies` and a few read methods to `FinaccountingApi`
  (balances over a month range with opening/movement/closing, an account ledger, a journal
  list over a range).
- **Dates are month ranges.** `gl_posting` is dated by `period` (YYYY-MM), not by day.
- **Chart-driven.** Statement lines come from the account-code class (1 asset, 2 liability,
  3 equity, 4/7 income, 5/6/8 expense, 9 clearing) and the account hierarchy, never from
  hard-coded account numbers. Control accounts carry their subledger owner (1210 BILLING,
  2110 CLAIMS, 2210 DISTRIBUTION, ...).
- **Reports:** trial balance (opening/debits/credits/closing, roll-up, comparison); general
  ledger / account statement with running balance; journal register; income statement with
  comparison; statement of financial position as of a month end; control account
  reconciliation (GL vs each subledger's own control total, via one new method on the
  billing, claims and distribution APIs); non-zero clearing/suspense accounts; premium levy
  and commission withholding tax accrued vs paid.
- Existing finance screens (unit-linked reconciliation, unposted events, IFRS 17 extracts,
  periods, year-end) stay in `finaccounting`.

## 12. Out of scope for batch 1

- Scheduled report runs and storing generated files in MinIO (exports are on demand).
- Payment-method detail beyond mobile money vs field cash (needs a field on
  `PremiumCollected`).
- Collection reversals and refunds (Finance batch).
- The other five categories' reports.
