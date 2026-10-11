# Reporting module, batch 1: Management reports

Date: 2026-10-11
Status: design approved in conversation, awaiting written-spec review

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
2. Lapse rate / persistency
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
    allowedDependencies = { "audit::api", "party::api", "refdata::api" })
package tz.co.nlolo.lifeplatform.reporting;
```

- `audit::api` -- `AuditApi.listEvents(prefix, range, pageable)`, the domain-event journal, for
  rebuild.
- `party::api` -- `PartyApi` detail (date of birth, sex) for the clients report.
- `refdata::api` -- product display names and currencies.

## 3. Module shape

```
reporting/
├── api/             ReportingApi, report views, ReportCategory, DateFilterKind
├── domain/          ReportPolicy, ReportPolicyStatusChange, ReportCollection,
│                    ReportClient, ReportSchemeMember, AppliedEvent; report definitions
├── application/     event handlers per source module, RebuildJob, the five report queries
└── infrastructure/  repositories, ReportController, xlsx/csv/pdf writers
```

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
| `policy.PolicyIssued` | Upsert `report_policy` (status `ISSUED`): product, version, holder, agent, sum assured, premium, frequency, issue date, `salesChannel`, `branchCode`, `portfolioCode` |
| `policy.PolicyActivated` | Set status `ACTIVE`, activation date, `productCategory`, `groupScheme`, `livesCovered`; fill any field `PolicyIssued` did not supply |
| `policy.PolicyNotTakenUp` | Status `NOT_TAKEN_UP` |
| `policy.PolicySuspended` / `policy.PolicyResumed` | Status `SUSPENDED` / back to `ACTIVE` (suspended still counts as in force, section 5) |
| `policy.PolicyLapsed` | Status `LAPSED` |
| `policy.PolicyReinstated` | Status `ACTIVE` |
| `policy.PolicyMadePaidUp` | Status `PAID_UP` (in force) |
| `policy.PolicyMatured` | Status `MATURED` |
| `policy.PolicyExpired` | Status `EXPIRED` |
| `policy.PolicySurrendered` | Status `CLAIM_TERMINATED` when `claimId` present, else `ENDED` (credit-life scheme whose last borrower left) |
| `policy.SurrenderPaid` | Status `SURRENDERED` (policyholder surrender; no timestamp on payload, uses envelope `occurredAt`) |
| `policy.PolicyCancelledFreeLook` | Status `CANCELLED_FREE_LOOK` |
| `policy.AnnuityEnded` | Status `ENDED` |
| `policy.GroupMemberAdded` / `policy.GroupMemberExited` | Insert / close `report_scheme_member` |
| `billing.PremiumCollected` | Insert `report_collection` |
| `party.PartyRegistered` | Insert `report_client`, enriched from `PartyApi` |
| `party.PartyKycStatusChanged` | Update KYC status |
| `party.PartyDetailsAmended` | Re-read detail from `PartyApi` (the payload carries no changed values) |

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
| `report_policy` | policy | tenant_id, policy_number (PK with tenant), holder_party_id, product_id, product_version_id, product_category, portfolio_code, group_scheme, lives_covered, agent_id, sales_channel, branch_code, sum_assured, premium, premium_frequency, currency, issue_date, activated_on, status, status_since, version |
| `report_policy_status_change` | status change | tenant_id, policy_number, from_status, to_status, effective_on, event_id |
| `report_collection` | premium collected | tenant_id, event_id (PK), invoice_id, policy_number, amount, currency, collected_on, channel |
| `report_client` | party | tenant_id, party_id, party_type, kyc_status, registered_on, date_of_birth, sex, version |
| `report_scheme_member` | scheme member | tenant_id, policy_number, policy_member_id, member_party_id (null for a freeform borrower), joined_on, left_on, exit_reason |
| `applied_event` | applied event | tenant_id, event_id |

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
**COHORT** (activation months, followed forward).

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

`GET /reports/rebuild/status` returns state (`IDLE`, `RUNNING`, `DONE`, `FAILED`), events
replayed, events failed, start and end time.

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
