# Role Dashboards (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every working role a dashboard — underwriter, claims, finance, agent — built only from figures the platform can already prove, replacing the staff "Today" page and becoming the agent's landing page.

**Architecture:** A new Spring Modulith module `dashboard` composes the existing module APIs into one typed view per role, served by one endpoint per role (`GET /dashboards/{underwriting|claims|finance|agent}`), the shape `omnichannel` already uses for the customer dashboard. The console renders those views with one shared `KpiCard`, a work panel, and a static per-role rail of alerts and quick actions. The only new arithmetic anywhere is one monthly-total query added to the reporting module, which owns that arithmetic.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith, JUnit 5 + Mockito, Testcontainers Postgres 16, OpenAPI 3.1 (`openapi-typescript` for the client types); React + TypeScript, Zustand, Tailwind v4, Vitest + React Testing Library, Playwright.

## Global Constraints

- A figure is a **count** (`totalElements` of a search filtered by one status) or a **report's own number**. A delta or chart is allowed only where every value on it is a report value. Nothing is summed, averaged or differenced in the browser. (PRODUCT.md, "A figure is a count, or a report's own number", revised 2026-10-14.)
- Money on the wire is `{ "amount": "<decimal string>", "currencyCode": "TZS" }` — the common `Money` schema — never a JSON number. Months travel as `"YYYY-MM"` strings, percentages as decimal strings.
- Working currency is TZS. Figures in any other currency are not shown on a dashboard in phase 1.
- A delta compares two **complete** months. The current month is always "to date" and is never one side of a delta.
- A delta is drawn in ink. Never green or red: a direction is not a verdict (DESIGN.md, the Stamp Rule).
- Chart series are ink and greys. The six status hues stay reserved for state.
- A KPI card is a **link** to exactly the list or report it counted, already filtered — or it is not clickable. Never a link to an unfiltered list that would show more rows than the card says.
- A figure that could not be read renders "Not available" with an em dash. Never `0`, never a spinner that spins after a failure.
- A figure never wraps across lines. It steps down from the Display tier to the Headline tier in a narrow card; it is never clipped and never abbreviated ("1.28M").
- Dates are the civil date in `Africa/Dar_es_Salaam`, never UTC (memory: UTC-vs-civil day bug).
- The activity feed says what happened, never who: the audit log records no actor. Only whitelisted payload references leave the server. Agents get no feed — the log cannot be scoped to them.
- No new `Clock` bean (a second `Clock` bean breaks Modulith — see `reporting/application/ReportingClock.java`). Composers take `LocalDate today` as a parameter.
- Never run Maven in Docker. Use the host `./mvnw`. Stop the dev backend before any `./mvnw clean`.
- Never run Prettier.
- No fabricated JWTs in e2e — real Keycloak only.

## Design decisions settled before this plan

Agreed with the product owner on 2026-10-14, after a gap analysis of the spec against the platform and an approved mockup (`https://claude.ai/artifact/FhvjsTYJZj1miwGmjvQSCt`):

1. Trends and charts only where a report backs every value. PRODUCT.md and DESIGN.md were revised first.
2. Server-side: one endpoint per role, composing module APIs. The browser never fans out to a dozen searches.
3. Phase 1 = provable today. Phase 2 = backend gaps (underwriting outcome filter, date ranges, receivables total and aging, claim amounts). Phase 3 = further charts.
4. The console keeps The Ledger palette. The inspiration images contributed structure only.
5. The dashboard **replaces** the staff "Today" page (one landing page, not two). `/staff/today` redirects to `/staff/dashboard`, keeping its query.
6. Agents **land on** the dashboard instead of "My profile".
7. KPI cards are clickable links to their filtered source, under the constraint above.
8. The customer dashboard already exists (`omnichannel`, `GET /customer/dashboard`) and is out of scope.

### Phase 1 figures, and where each card links

Every figure below has been traced to a real internal call and a real screen. "Unlinked" means the destination cannot yet reproduce the figure's filter, so the card is shown but is not clickable.

| Role | Figure | Source (internal call) | Card links to |
|---|---|---|---|
| Underwriter | Open cases | `UnderwritingApi.listCases(OPEN, null, null, page 1)` | `/staff/underwriting?status=OPEN` |
| Underwriter | In review | `listCases(IN_REVIEW, …)` | `/staff/underwriting?status=IN_REVIEW` |
| Underwriter | Accepted, awaiting issue | `listCasesAwaitingIssue(null, page 1)` | unlinked — no screen lists exactly these |
| Claims | Awaiting assessment | `ClaimsApi.searchClaims(REGISTERED, …)` | `/staff/claims?status=REGISTERED` |
| Claims | Under assessment | `searchClaims(UNDER_ASSESSMENT, …)` | `/staff/claims?status=UNDER_ASSESSMENT` |
| Claims | Awaiting settlement | `searchClaims(SETTLEMENT_REQUESTED, …)` | `/staff/claims?status=SETTLEMENT_REQUESTED` |
| Claims | Approved, not yet paid | `searchClaims(APPROVED, …)` | `/staff/claims?status=APPROVED` |
| Claims | Reopened | `searchClaims(REOPENED, …)` | `/staff/claims?status=REOPENED` |
| Claims | Settled (all time) | `searchClaims(SETTLED, …)` | `/staff/claims?status=SETTLED` |
| Finance | Premiums collected, last complete month (+ delta vs the month before) | `ReportingApi.monthlyPremiumCollections` (new, Task 1) | `/staff/reports/premium-collections?from=YYYY-MM-01&to=YYYY-MM-<last>` |
| Finance | Total assets, total liabilities | `ReportingApi.run("balance-sheet", month)` → section "Assets"/"Liabilities" totals `balance` | `/staff/reports/balance-sheet?monthFrom=YYYY-MM` |
| Finance | Net result, year to date | `ReportingApi.run("income-statement", Jan..this month)` → section "Result" | `/staff/reports/income-statement?monthFrom=YYYY-01&monthTo=YYYY-MM` |
| Finance | Journals awaiting approval | `ManualJournalApi.list("SUBMITTED", null, null).size()` | unlinked — `ManualJournalsPage` does not read `?status=` |
| Finance | Receipts past reconciliation SLA | `BillingApi.searchFieldReceipts("RECONCILIATION_OVERDUE", page 1)` | `/staff/field-receipts?status=RECONCILIATION_OVERDUE` |
| Finance | Bank transfers not yet made | `PaymentApi.listAwaitingEftExecution().size()` | `/staff/bank-transfers` (the list IS that queue) |
| Finance | Arrears at the lapse level | `BillingApi.searchArrears(5, false, page 1)` | `/staff/arrears?minDunningLevel=5` |
| Finance | Premiums by month, this year vs last (chart) | `monthlyPremiumCollections(Jan last year .. this month)` | the chart's "Open the report" link |
| Agent | My clients | `PartyApi.searchParties(null, <subject>, null, null, page 1)` | `/agents/clients` |
| Agent | Clients awaiting KYC | `searchParties(PENDING, <subject>, …)` | `/agents/clients?kycStatus=PENDING` |
| Agent | KYC rejected (alert) | `searchParties(REJECTED, <subject>, …)` | `/agents/clients?kycStatus=REJECTED` |
| Agent | Policies in my book | `PolicyApi.searchPolicies(null, null, null, <team>, null, page 1)` | `/agents/policies` |
| Agent | Commission, last closed period | `DistributionApi.listStatements(<agentId>, "YYYY-MM")` | `/agents/commission` |

Phase 2 (drawn dashed, never fetched): accepted vs declined, turnaround, cases referred to a senior, receivables total and aging, benefit amounts paid, open document requests across claims, applications in underwriting for an agent.

Not on the platform (listed in each rail's "Not here yet"): SLA deadlines on cases and claims, a risk-level field, assigning a claim to an assessor, a cash-flow statement, quotes, leads and follow-ups.

### Recent activity (added 2026-10-14, the product owner's call)

Each staff dashboard shows its own module's latest events, from the audit log: `AuditApi.listEvents(eventTypePrefix, DateRange(null, null), page sorted by occurredAt desc)` — the same call `AuditLogController` makes, and staff-readable the same way.

| Dashboard | Event prefixes | Left out as system noise |
|---|---|---|
| Underwriting | `underwriting.` (only `UnderwritingDecisionMade` exists — so in practice "recent decisions") | — |
| Claims | `claims.` | — |
| Finance | `billing.`, `payment.`, `finaccounting.`, merged newest first | `finaccounting.GlPostingRecorded`, `finaccounting.PaaRevenueEarned`, `billing.PremiumInvoiceGenerated` — one per posting or per invoice run, they would bury everything a person did |

Two limits, both written into `AuditApi` itself and carried onto the screen:

- **It says what happened, not who did it.** Events carry no actor in general. The feed's subtitle says so, and nothing presents it as compliance evidence.
- **Payloads are raw JSON.** The server passes through a whitelisted set of references (`caseId`, `claimId`, `policyNumber`, …) and an amount where one exists; the console words each event type and links it by those references. An event type the console has no wording for is shown by its humanised name, never dropped.

**The agent dashboard has no feed, deliberately.** The audit log is tenant-wide and records no owner or actor, so there is nothing to scope it to an agent by: showing it would show an agent every other agent's business.

### Out of scope for phase 1, and why

From the spec's "shared dashboard" list:

- **A date filter.** Each figure's period is fixed by the doctrine — the last complete month, the current month, the year to date — and the work-queue searches have no date filter yet (phase 2). A filter that changed some cards and silently not others would be worse than none.
- **Notifications in the top bar.** Nothing on the platform notifies staff. The communication module sends SMS and portal messages to customers; there is no per-user inbox, no read/unread state and no rule for who is told what. A bell would have nothing behind it. What staff have already does the job the spec names ("highlight overdue tasks, missing documents and exceptions"): the sidebar badges and each dashboard's "Needs attention" panel. A real notification centre is a new domain, not a widget.
- **Global search.** The console's existing "Go to…" (Ctrl K) already is one.

## Before you start

- [ ] **The working tree must be clean of unrelated work.** As of this plan, `main` has uncommitted changes from an earlier piece of work (contextual breadcrumbs and client shortcuts: `frontend/src/features/party/clientContext.tsx`, `ClientActions.tsx` and their tests, plus edits to the policy, claim, underwriting and client pages). Its existing specs have not been run. Ask the product owner whether to commit it, stash it, or finish it first. Do not mix it into this branch.
- [ ] The doctrine edits to `PRODUCT.md` and `DESIGN.md` made on 2026-10-14 belong to THIS feature. They are committed in Task 0.

```bash
git checkout main
git checkout -b role-dashboards
```

## File Structure

**Backend — reporting (one addition):**
- Create `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/api/MonthlyAmount.java` — one month's collections in one currency.
- Modify `reporting/api/ReportingApi.java` — `monthlyPremiumCollections(YearMonth, YearMonth)`.
- Modify `reporting/infrastructure/ReportQueries.java` — `monthlyCollections(...)` SQL.
- Modify `reporting/application/ReportingApiImpl.java` — the implementation.
- Modify `reporting/application/BalanceSheetReport.java`, `IncomeStatementReport.java` — a comment naming the dashboard as a reader of their section titles.

**Backend — new module `dashboard`** (`backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/`):
- `package-info.java` — the module and its allowed dependencies.
- `api/Money.java` — money on the wire.
- `api/UnderwritingDashboardView.java`, `api/ClaimsDashboardView.java`, `api/FinanceDashboardView.java`, `api/AgentDashboardView.java` — one typed view per role.
- `application/UnderwritingDashboard.java`, `ClaimsDashboard.java`, `FinanceDashboard.java`, `AgentDashboard.java` — one composer per role. Each depends only on other modules' `api`.
- `application/ReportFigures.java` — reads a section total out of a `ReportResult`.
- `application/Safely.java` — runs one figure's read, returning null (and logging) on failure, so one broken source costs one card.
- `infrastructure/DashboardController.java` — the four endpoints.
- `api/ActivityItem.java` and `application/RecentActivity.java` — the audit-log feed (Task 5a).
- `backend/api/openapi/openapi-dashboard.yaml` — the contract.

**Backend tests:**
- `reporting/ReportQueriesIntegrationTest.java` (modify) — the monthly query, cross-checked against the report's own total.
- `dashboard/application/UnderwritingDashboardTest.java`, `ClaimsDashboardTest.java`, `FinanceDashboardTest.java`, `AgentDashboardTest.java`, `ReportFiguresTest.java` — plain Mockito, no Spring.
- `dashboard/DashboardContractTest.java` — HTTP, security per role, OpenAPI validation, composers mocked.

**Frontend:**
- Create `frontend/src/api/dashboard.ts` — the four reads.
- Create `frontend/src/store/dashboardStore.ts` — one keyed resource per dashboard.
- Create `frontend/src/components/KpiCard.tsx` — the link card and its row.
- Create `frontend/src/features/dashboard/dashboards.ts` — which dashboards an identity gets, mirroring the endpoints' expressions.
- Create `frontend/src/features/dashboard/DashboardPage.tsx` — header, refresh, one tab per dashboard held.
- Create `frontend/src/features/dashboard/DashboardRail.tsx` — alerts, quick actions, "Not here yet".
- Create `frontend/src/features/dashboard/UnderwritingDashboardPanel.tsx`, `ClaimsDashboardPanel.tsx`, `FinanceDashboardPanel.tsx`, `AgentDashboardPanel.tsx`, `PremiumsChart.tsx`, `ActivityFeed.tsx` (Task 11a).
- Modify `frontend/src/screens.tsx` — the routes, `homeFor`, `REALM_HOME.agents`.
- Delete `frontend/src/features/home/TodayPage.tsx`, `TodayPage.test.tsx`; prune what only they used in `frontend/src/navBadges.ts`.
- Modify e2e sign-in helpers: `frontend/e2e/auth*.setup.ts` (7 files), `approverSession.ts`, `agentSession.ts`.

---

### Task 0: Commit the doctrine this feature rests on

**Files:**
- Modify (already edited, uncommitted): `PRODUCT.md`, `DESIGN.md`

- [ ] **Step 1: Confirm only the doctrine hunks are staged**

```bash
git add PRODUCT.md DESIGN.md
git diff --cached --stat
```
Expected: exactly `PRODUCT.md` and `DESIGN.md`.

- [ ] **Step 2: Commit**

```bash
git commit -m "docs: a figure is a count or a report's own number

The reporting module made some figures provable that were not before, so
'counts only, no trend arrows' is replaced by: a trend, delta or chart only
where a report computed every value on it, never summed in the browser,
deltas in ink, chart series achromatic. Dashboards read one server-side
endpoint per role.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 1: Monthly premium collections in the reporting module

The finance KPI, its delta and its chart all need one number per month. The `premium-collections` report splits every month by channel and dimension, so a caller would have to re-add its rows. That arithmetic belongs to the module that owns collections.

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/api/MonthlyAmount.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/api/ReportingApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/infrastructure/ReportQueries.java` (beside `collections(...)`, around line 200)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/application/ReportingApiImpl.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/ReportQueriesIntegrationTest.java`

**Interfaces:**
- Produces: `record MonthlyAmount(YearMonth month, String currency, BigDecimal amount)` in `reporting.api`; `List<MonthlyAmount> ReportingApi.monthlyPremiumCollections(YearMonth from, YearMonth to)` — oldest first, a month with nothing collected is absent.

- [ ] **Step 1: Write the failing test**

Add to `ReportQueriesIntegrationTest`, after `collectionsAreTotalledByMonthAndChannelWithTheTopPolicies` (it reuses that test's helpers `events`, `Sale`, `product`, `d(...)`, `run(...)`, `rows(...)`):

```java
    /**
     * One number per month, for a dashboard's figure and chart. The invariant the dashboard rests on is the second
     * assertion: the monthly points add up to the premium-collections report's own total for the same period, so a
     * chart drawn from these can never disagree with the report a finance officer opens from it.
     */
    @Test
    void monthlyCollectionsAgreeWithTheCollectionsReportsOwnTotal() {
        events.sell(tenant, Sale.of("M-1", product, d("2026-01-02")));
        events.collect(tenant, "M-1", "10000.00", d("2026-01-05"), true);
        events.collect(tenant, "M-1", "4000.00", d("2026-01-26"), false);
        events.collect(tenant, "M-1", "10000.00", d("2026-02-05"), true);

        ReportResult report = run("premium-collections", ReportFilter.period(d("2026-01-01"), d("2026-02-28")));
        String currency = rows(report, 0).get(0).get("currency");

        List<MonthlyAmount> months = reportingApi.monthlyPremiumCollections(YearMonth.of(2026, 1), YearMonth.of(2026, 2));

        assertThat(months).extracting(m -> m.month() + " " + m.currency() + " " + m.amount().toPlainString())
            .containsExactly("2026-01 " + currency + " 14000.00", "2026-02 " + currency + " 10000.00");
        assertThat(months.stream().map(MonthlyAmount::amount).reduce(BigDecimal.ZERO, BigDecimal::add).toPlainString())
            .isEqualTo(report.sections().get(0).totals().get("amount"));
    }
```

Add the imports the class does not already have: `tz.co.nlolo.lifeplatform.reporting.api.MonthlyAmount`, `java.math.BigDecimal`, `java.time.YearMonth`, `java.util.List`.

- [ ] **Step 2: Run it to verify it fails to compile**

Run (from `backend/`): `./mvnw -o -q test-compile`
Expected: FAIL — `cannot find symbol: class MonthlyAmount`.

- [ ] **Step 3: Create `MonthlyAmount`**

```java
package tz.co.nlolo.lifeplatform.reporting.api;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * One month's premium collections in one currency, totalled by the module that owns collections.
 *
 * <p>The same rows the {@code premium-collections} report sums, grouped by month alone, so a chart's points are read
 * from here rather than re-added by whoever draws them. Exists for the role dashboards (2026-10-14), whose rule is that
 * every value on a chart is one a report computed.
 */
public record MonthlyAmount(YearMonth month, String currency, BigDecimal amount) {}
```

- [ ] **Step 4: Declare it on `ReportingApi`**

Add to the interface, after `run(...)`:

```java
    /**
     * Premiums collected per month, {@code from} through {@code to} inclusive, oldest first, one entry per month and
     * currency. A month in which nothing was collected is ABSENT rather than zero: the caller decides whether a gap is
     * drawn as nothing, and a zero would be a claim the projection did not make.
     */
    List<MonthlyAmount> monthlyPremiumCollections(YearMonth from, YearMonth to);
```

Add `import java.time.YearMonth;` if absent.

- [ ] **Step 5: Add the query to `ReportQueries`**

Directly after `collections(...)`:

```java
    /**
     * {@link #collections}' rows grouped by month and currency only. No client or product joins: there is no filter to
     * apply, and the total must equal that report's own total for the same dates (asserted in
     * {@code ReportQueriesIntegrationTest.monthlyCollectionsAgreeWithTheCollectionsReportsOwnTotal}).
     */
    public List<MonthlyAmount> monthlyCollections(UUID tenantId, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT to_char(r.collected_on, 'YYYY-MM') AS month, r.currency, SUM(r.amount) AS amount"
                + " FROM reporting.report_collection r WHERE r.tenant_id = ? AND r.collected_on BETWEEN ? AND ?"
                + " GROUP BY month, r.currency ORDER BY month, r.currency",
            (rs, i) -> new MonthlyAmount(YearMonth.parse(rs.getString("month")), rs.getString("currency").trim(),
                rs.getBigDecimal("amount")),
            tenantId, from, to);
    }
```

Add imports: `tz.co.nlolo.lifeplatform.reporting.api.MonthlyAmount`, `java.time.YearMonth` (if absent).

- [ ] **Step 6: Implement it in `ReportingApiImpl`**

`ReportingApiImpl` does not yet hold `ReportQueries`. Add the field `private final ReportQueries queries;`, append `ReportQueries queries` as the LAST constructor parameter and assign it (`this.queries = queries;`). Then:

```java
    @Override
    public List<MonthlyAmount> monthlyPremiumCollections(YearMonth from, YearMonth to) {
        return queries.monthlyCollections(TenantContext.get(), from.atDay(1), to.atEndOfMonth());
    }
```

Add imports: `tz.co.nlolo.lifeplatform.reporting.api.MonthlyAmount`, `tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries`, `java.time.YearMonth`.

- [ ] **Step 7: Run the test**

Stop the dev backend first if it is running (it locks `target/`). Then, from `backend/`:

Run: `./mvnw -o -q clean test-compile && ./mvnw -o test -Dtest=ReportQueriesIntegrationTest -DfailIfNoSpecifiedTests=false`
Expected: `Tests run: <n>, Failures: 0, Errors: 0` for `ReportQueriesIntegrationTest`, `BUILD SUCCESS`. Compare `<n>` to the test count before this task: it must be exactly one higher (memory: vacuous verification — a filter that ran nothing also says BUILD SUCCESS).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/reporting backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/ReportQueriesIntegrationTest.java
git commit -m "feat(reporting): premiums collected per month, for a dashboard to draw

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: The `dashboard` module and the underwriter's dashboard

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/package-info.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/api/Money.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/api/UnderwritingDashboardView.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/application/Safely.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/application/UnderwritingDashboard.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/infrastructure/DashboardController.java`
- Create: `backend/api/openapi/openapi-dashboard.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/dashboard/application/UnderwritingDashboardTest.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/dashboard/DashboardContractTest.java`

**Interfaces:**
- Produces: `record Money(String amount, String currencyCode)` with `static Money of(BigDecimal amount, String currencyCode)` (null amount → null) in `dashboard.api`.
- Produces: `static <T> T Safely.read(String figure, Supplier<T> read)` in `dashboard.application` — null on any exception, logged with the figure's name.
- Produces: `UnderwritingDashboardView build()` on `UnderwritingDashboard`.
- Produces: `GET /dashboards/underwriting`.

- [ ] **Step 1: Write the failing composer test**

```java
package tz.co.nlolo.lifeplatform.dashboard.application;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseStatus;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnderwritingDashboardTest {

    private final UnderwritingApi underwriting = mock(UnderwritingApi.class);
    private final UnderwritingDashboard dashboard = new UnderwritingDashboard(underwriting);

    private static Page<UnderwritingCaseView> total(long n) {
        return new PageImpl<>(List.of(), PageRequest.of(0, 1), n);
    }

    @Test
    void countsEachQueueByItsOwnStatusAndListsTheOldestOpenCasesFirst() {
        UUID caseId = UUID.randomUUID();
        UUID applicant = UUID.randomUUID();
        UnderwritingCaseView open = mock(UnderwritingCaseView.class);
        when(open.caseId()).thenReturn(caseId);
        when(open.applicantPartyId()).thenReturn(applicant);
        when(open.status()).thenReturn(UnderwritingCaseStatus.OPEN);
        when(open.sumAssuredAmount()).thenReturn(new BigDecimal("25000000"));
        when(open.sumAssuredCurrency()).thenReturn("TZS");

        when(underwriting.listCases(eq(UnderwritingCaseStatus.OPEN), isNull(), isNull(),
            argThat(p -> p != null && p.getPageSize() == 1))).thenReturn(total(238));
        when(underwriting.listCases(eq(UnderwritingCaseStatus.OPEN), isNull(), isNull(),
            argThat(p -> p != null && p.getPageSize() == UnderwritingDashboard.QUEUE_SIZE)))
            .thenReturn(new PageImpl<>(List.of(open)));
        when(underwriting.listCases(eq(UnderwritingCaseStatus.IN_REVIEW), isNull(), isNull(), any())).thenReturn(total(41));
        when(underwriting.listCasesAwaitingIssue(isNull(), any())).thenReturn(total(12));

        UnderwritingDashboardView view = dashboard.build();

        assertThat(view.openCases()).isEqualTo(238L);
        assertThat(view.casesInReview()).isEqualTo(41L);
        assertThat(view.casesAwaitingIssue()).isEqualTo(12L);
        assertThat(view.queue()).singleElement().satisfies(c -> {
            assertThat(c.caseId()).isEqualTo(caseId);
            assertThat(c.applicantPartyId()).isEqualTo(applicant);
            assertThat(c.status()).isEqualTo("OPEN");
            assertThat(c.sumAssured().amount()).isEqualTo("25000000.00");
            assertThat(c.sumAssured().currencyCode()).isEqualTo("TZS");
        });

        // "Next in your queue" is what has waited LONGEST -- the opposite of the queue page's newest-first.
        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(underwriting, org.mockito.Mockito.times(2))
            .listCases(eq(UnderwritingCaseStatus.OPEN), isNull(), isNull(), pages.capture());
        assertThat(pages.getAllValues()).anySatisfy(p -> {
            assertThat(p.getPageSize()).isEqualTo(UnderwritingDashboard.QUEUE_SIZE);
            assertThat(p.getSort().getOrderFor("createdAt")).isNotNull();
            assertThat(p.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.ASC);
        });
    }

    /** One broken source costs one card. A count that could not be read is null -- never 0, which would be a claim. */
    @Test
    void aCountThatCannotBeReadIsNullAndTheRestStillArrive() {
        when(underwriting.listCases(eq(UnderwritingCaseStatus.OPEN), isNull(), isNull(), any()))
            .thenThrow(new IllegalStateException("underwriting is down"));
        when(underwriting.listCases(eq(UnderwritingCaseStatus.IN_REVIEW), isNull(), isNull(), any())).thenReturn(total(41));
        when(underwriting.listCasesAwaitingIssue(isNull(), any())).thenReturn(total(12));

        UnderwritingDashboardView view = dashboard.build();

        assertThat(view.openCases()).isNull();
        assertThat(view.queue()).isEmpty();
        assertThat(view.casesInReview()).isEqualTo(41L);
    }
}
```

The view's counts are `Long` (boxed), so "could not read" is `null`.

- [ ] **Step 2: Run it to verify it fails**

Run (from `backend/`): `./mvnw -o -q test-compile`
Expected: FAIL — `package tz.co.nlolo.lifeplatform.dashboard.api does not exist`.

- [ ] **Step 3: Create the module**

`dashboard/package-info.java`:

```java
/**
 * Role dashboards (2026-10-14): one typed view per working role, composed from the module APIs that own each figure.
 *
 * <p>Composition only. This module stores nothing and computes nothing a module already computes: a count is a
 * search's {@code totalElements} filtered by one status, and a money figure is a report's own number. Its one rule is
 * PRODUCT.md's -- a figure is a count or a report's own number -- and a figure it cannot read is null, never zero.
 *
 * <p>Nothing depends on this module, so it can depend on any {@code api} without risking a cycle. The customer
 * dashboard is NOT here: it predates this module and lives in {@code omnichannel}.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {
    "underwriting::api", "claims::api", "billing::api", "payment::api", "finaccounting::api",
    "reporting::api", "party::api", "policy::api", "distribution::api", "audit::api" })
package tz.co.nlolo.lifeplatform.dashboard;
```

`dashboard/api/Money.java`:

```java
package tz.co.nlolo.lifeplatform.dashboard.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money on the wire: a decimal string and its currency, never a JSON number (PRODUCT.md). The common {@code Money}
 * schema in {@code openapi-common.yaml}, which is also the console's own {@code Money} type in {@code lib/money.ts}.
 */
public record Money(String amount, String currencyCode) {

    /** Null in, null out: an absent amount is absent on the card, not "TZS 0.00". */
    public static Money of(BigDecimal amount, String currencyCode) {
        return amount == null || currencyCode == null
            ? null
            : new Money(amount.setScale(2, RoundingMode.HALF_UP).toPlainString(), currencyCode);
    }
}
```

`dashboard/api/UnderwritingDashboardView.java`:

```java
package tz.co.nlolo.lifeplatform.dashboard.api;

import java.util.List;
import java.util.UUID;

/**
 * The underwriter's dashboard. Every count is a search's total for one status; null means it could not be read.
 *
 * @param queue the open cases that have waited longest, oldest first -- empty when the count could not be read
 */
public record UnderwritingDashboardView(Long openCases, Long casesInReview, Long casesAwaitingIssue,
                                        List<QueuedCase> queue) {

    /** A case as the queue panel shows it. Names are resolved by the console from the ids, as the queue page does. */
    public record QueuedCase(UUID caseId, UUID applicantPartyId, UUID lifeAssuredPartyId, UUID productId,
                             Money sumAssured, String status) {}
}
```

`dashboard/application/Safely.java`:

```java
package tz.co.nlolo.lifeplatform.dashboard.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * One figure's read, isolated. A dashboard reads from up to nine modules, and one module being down must cost one
 * card rather than the whole page -- the same rule as the sidebar badges, where a failed count is absent and never 0.
 */
final class Safely {

    private static final Logger log = LoggerFactory.getLogger(Safely.class);

    private Safely() {}

    static <T> T read(String figure, Supplier<T> read) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            log.warn("dashboard could not read {}", figure, e);
            return null;
        }
    }
}
```

`dashboard/application/UnderwritingDashboard.java`:

```java
package tz.co.nlolo.lifeplatform.dashboard.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.dashboard.api.Money;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView.QueuedCase;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseStatus;

import java.util.List;

@Service
public class UnderwritingDashboard {

    static final int QUEUE_SIZE = 5;
    private static final PageRequest TOTAL_ONLY = PageRequest.of(0, 1);

    private final UnderwritingApi underwriting;

    public UnderwritingDashboard(UnderwritingApi underwriting) {
        this.underwriting = underwriting;
    }

    public UnderwritingDashboardView build() {
        Long open = Safely.read("open cases",
            () -> underwriting.listCases(UnderwritingCaseStatus.OPEN, null, null, TOTAL_ONLY).getTotalElements());
        Long inReview = Safely.read("cases in review",
            () -> underwriting.listCases(UnderwritingCaseStatus.IN_REVIEW, null, null, TOTAL_ONLY).getTotalElements());
        Long awaitingIssue = Safely.read("cases awaiting issue",
            () -> underwriting.listCasesAwaitingIssue(null, TOTAL_ONLY).getTotalElements());

        // Oldest first: "next in your queue" means what has waited longest, where the queue PAGE sorts newest first.
        List<QueuedCase> queue = Safely.read("the open-case queue", () -> underwriting.listCases(
                UnderwritingCaseStatus.OPEN, null, null,
                PageRequest.of(0, QUEUE_SIZE, Sort.by(Sort.Direction.ASC, "createdAt")))
            .map(c -> new QueuedCase(c.caseId(), c.applicantPartyId(), c.lifeAssuredPartyId(), c.productId(),
                Money.of(c.sumAssuredAmount(), c.sumAssuredCurrency()), c.status().name()))
            .getContent());

        return new UnderwritingDashboardView(open, inReview, awaitingIssue, queue == null ? List.of() : queue);
    }
}
```

`dashboard/infrastructure/DashboardController.java`:

```java
package tz.co.nlolo.lifeplatform.dashboard.infrastructure;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.application.UnderwritingDashboard;

/**
 * One endpoint per role. Each {@code @PreAuthorize} is the SAME expression the module behind the figures applies to
 * its own endpoints, so a dashboard can never show a role figures its own screens would refuse it.
 */
@RestController
public class DashboardController {

    private final UnderwritingDashboard underwriting;

    public DashboardController(UnderwritingDashboard underwriting) {
        this.underwriting = underwriting;
    }

    @GetMapping("/dashboards/underwriting")
    @PreAuthorize("hasRole('UNDERWRITER')")
    public ResponseEntity<UnderwritingDashboardView> underwriting() {
        return ResponseEntity.ok(underwriting.build());
    }
}
```

- [ ] **Step 4: Run the composer test**

Run: `./mvnw -o test -Dtest=UnderwritingDashboardTest -DfailIfNoSpecifiedTests=false`
Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 5: Write the contract**

`backend/api/openapi/openapi-dashboard.yaml`:

```yaml
openapi: 3.1.0
info:
  title: Dashboard API
  version: "0.1.0-draft"
  description: >
    Role dashboards (2026-10-14). One read per working role, each composed server-side from the modules that own the
    figures. A count is a search's total for one status; a money figure is a report's own number; a delta compares two
    complete months. Any figure that could not be read is null -- never zero. Money is a decimal string, months are
    "YYYY-MM", percentages are decimal strings.
servers:
  - url: https://api.nlolo-lifeplatform.tz/v1
paths:
  /dashboards/underwriting:
    get:
      summary: The underwriter's dashboard (UNDERWRITER)
      security:
        - staffAuth: []
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { $ref: '#/components/schemas/UnderwritingDashboardView' }
        '403': { $ref: '#/components/responses/Problem' }
components:
  responses:
    Problem:
      description: An error, as RFC 7807 problem details
      content:
        application/problem+json:
          schema: { $ref: 'openapi-common.yaml#/components/schemas/ProblemDetails' }
  schemas:
    Money:
      oneOf:
        - type: 'null'
        - { $ref: 'openapi-common.yaml#/components/schemas/Money' }
    Count:
      type: [integer, 'null']
      minimum: 0
    UnderwritingDashboardView:
      type: object
      additionalProperties: false
      required: [openCases, casesInReview, casesAwaitingIssue, queue]
      properties:
        openCases: { $ref: '#/components/schemas/Count' }
        casesInReview: { $ref: '#/components/schemas/Count' }
        casesAwaitingIssue: { $ref: '#/components/schemas/Count' }
        queue:
          type: array
          items:
            type: object
            additionalProperties: false
            required: [caseId, applicantPartyId, status]
            properties:
              caseId: { type: string, format: uuid }
              applicantPartyId: { type: string, format: uuid }
              lifeAssuredPartyId: { type: [string, 'null'], format: uuid }
              productId: { type: [string, 'null'], format: uuid }
              sumAssured: { $ref: '#/components/schemas/Money' }
              status: { type: string, enum: [OPEN, IN_REVIEW, DECIDED] }
```

- [ ] **Step 6: Write the failing contract test**

`backend/src/test/java/tz/co/nlolo/lifeplatform/dashboard/DashboardContractTest.java`:

```java
package tz.co.nlolo.lifeplatform.dashboard;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.dashboard.api.Money;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.application.UnderwritingDashboard;
import tz.co.nlolo.lifeplatform.reporting.ReportingTestMigrations;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dashboards over real HTTP: who may read which, and that each response is what the spec says.
 *
 * <p>The composers are mocked. What they compute is their own unit tests' business; this class proves the wire --
 * security per role, and the shape against {@code openapi-dashboard.yaml} with {@code additionalProperties: false}, so
 * a field added to a view without the spec fails here. The database is the reporting test set only because the
 * application context needs one to start; nothing here reads it.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class DashboardContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-dashboard.yaml";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            ReportingTestMigrations.ALL);
    }

    @Autowired private MockMvc mockMvc;
    @MockBean private UnderwritingDashboard underwriting;

    private static JwtRequestPostProcessor staff(String... roles) {
        SimpleGrantedAuthority[] authorities = new SimpleGrantedAuthority[roles.length + 1];
        authorities[0] = new SimpleGrantedAuthority("ROLE_REALM_STAFF");
        for (int i = 0; i < roles.length; i++) {
            authorities[i + 1] = new SimpleGrantedAuthority("ROLE_" + roles[i]);
        }
        return jwt().authorities(authorities)
            .jwt(b -> b.subject("staff-user").claim("tenant_id", UUID.randomUUID().toString()));
    }

    @Test
    void anUnderwriterReadsTheUnderwritingDashboard() throws Exception {
        when(underwriting.build()).thenReturn(new UnderwritingDashboardView(238L, 41L, null, List.of(
            new UnderwritingDashboardView.QueuedCase(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
                new Money("25000000.00", "TZS"), "OPEN"))));

        mockMvc.perform(get("/dashboards/underwriting").with(staff("UNDERWRITER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.openCases").value(238))
            // Could not be read: null on the wire, never 0.
            .andExpect(jsonPath("$.casesAwaitingIssue").isEmpty())
            .andExpect(jsonPath("$.queue[0].sumAssured.amount").value("25000000.00"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void aClaimsAssessorIsRefusedTheUnderwritingDashboard() throws Exception {
        mockMvc.perform(get("/dashboards/underwriting").with(staff("CLAIMS_ASSESSOR")))
            .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 7: Run the contract test and the module boundary check**

Run: `./mvnw -o test -Dtest='DashboardContractTest,ModularityTests' -DfailIfNoSpecifiedTests=false`
Expected: both classes `Failures: 0, Errors: 0`. `ModularityTests` failing with "non-exposed type" means a composer reached into another module's `application` or `infrastructure` package — every import must be from `<module>.api`.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard backend/api/openapi/openapi-dashboard.yaml backend/src/test/java/tz/co/nlolo/lifeplatform/dashboard
git commit -m "feat(dashboard): a dashboard module, and the underwriter's dashboard

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: The claims dashboard

One view serves both claims roles. The figures are the same; only the queue differs — a manager's work is the settlement decision, an assessor's is the unassessed claim.

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/api/ClaimsDashboardView.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/application/ClaimsDashboard.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/infrastructure/DashboardController.java`
- Modify: `backend/api/openapi/openapi-dashboard.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/dashboard/application/ClaimsDashboardTest.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/dashboard/DashboardContractTest.java`

**Interfaces:**
- Consumes: `Safely.read` (Task 2).
- Produces: `ClaimsDashboardView build(boolean manager)` on `ClaimsDashboard`; `GET /dashboards/claims`.

- [ ] **Step 1: Write the failing composer test**

```java
package tz.co.nlolo.lifeplatform.dashboard.application;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.dashboard.api.ClaimsDashboardView;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClaimsDashboardTest {

    private final ClaimsApi claims = mock(ClaimsApi.class);
    private final ClaimsDashboard dashboard = new ClaimsDashboard(claims);

    private void count(ClaimStatus status, long n) {
        when(claims.searchClaims(eq(status), isNull(), isNull(), isNull(), argThat(p -> p != null && p.getPageSize() == 1)))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), n));
    }

    private ClaimView claim(ClaimStatus status) {
        ClaimView c = mock(ClaimView.class);
        when(c.claimId()).thenReturn(UUID.randomUUID());
        when(c.policyNumber()).thenReturn("POL-00A4B7A1");
        when(c.claimantPartyId()).thenReturn(UUID.randomUUID());
        when(c.claimType()).thenReturn(ClaimType.DEATH);
        when(c.dateOfEvent()).thenReturn(LocalDate.of(2026, 9, 29));
        when(c.status()).thenReturn(status);
        return c;
    }

    private void everyStage() {
        count(ClaimStatus.REGISTERED, 17);
        count(ClaimStatus.UNDER_ASSESSMENT, 9);
        count(ClaimStatus.SETTLEMENT_REQUESTED, 6);
        count(ClaimStatus.APPROVED, 4);
        count(ClaimStatus.REOPENED, 2);
        count(ClaimStatus.SETTLED, 128);
    }

    @Test
    void countsEveryStageAndGivesAManagerTheSettlementQueue() {
        everyStage();
        when(claims.searchClaims(eq(ClaimStatus.SETTLEMENT_REQUESTED), isNull(), isNull(), isNull(),
            argThat(p -> p != null && p.getPageSize() == ClaimsDashboard.QUEUE_SIZE)))
            .thenReturn(new PageImpl<>(List.of(claim(ClaimStatus.SETTLEMENT_REQUESTED))));

        ClaimsDashboardView view = dashboard.build(true);

        assertThat(view.registered()).isEqualTo(17L);
        assertThat(view.underAssessment()).isEqualTo(9L);
        assertThat(view.settlementRequested()).isEqualTo(6L);
        assertThat(view.approved()).isEqualTo(4L);
        assertThat(view.reopened()).isEqualTo(2L);
        assertThat(view.settled()).isEqualTo(128L);
        assertThat(view.queueStatus()).isEqualTo("SETTLEMENT_REQUESTED");
        assertThat(view.queue()).singleElement().satisfies(q -> {
            assertThat(q.policyNumber()).isEqualTo("POL-00A4B7A1");
            assertThat(q.claimType()).isEqualTo("DEATH");
            assertThat(q.dateOfEvent()).isEqualTo(LocalDate.of(2026, 9, 29));
        });
    }

    /** An assessor cannot decide a settlement; their queue is the claim nobody has assessed yet. */
    @Test
    void givesAnAssessorTheUnassessedQueue() {
        everyStage();
        when(claims.searchClaims(eq(ClaimStatus.REGISTERED), isNull(), isNull(), isNull(),
            argThat(p -> p != null && p.getPageSize() == ClaimsDashboard.QUEUE_SIZE)))
            .thenReturn(new PageImpl<>(List.of(claim(ClaimStatus.REGISTERED))));

        ClaimsDashboardView view = dashboard.build(false);

        assertThat(view.queueStatus()).isEqualTo("REGISTERED");
        assertThat(view.queue()).singleElement().satisfies(q -> assertThat(q.status()).isEqualTo("REGISTERED"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -o -q test-compile`
Expected: FAIL — `cannot find symbol: class ClaimsDashboard`.

- [ ] **Step 3: Implement**

`dashboard/api/ClaimsDashboardView.java`:

```java
package tz.co.nlolo.lifeplatform.dashboard.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Every open claim by its stage, plus the queue that is the reader's own work. Counts are all-time totals for one
 * status -- there is no date filter on the claims search yet (phase 2), so "settled" means ever, and the card says so.
 *
 * @param queueStatus which status {@code queue} lists: SETTLEMENT_REQUESTED for a manager, REGISTERED for an assessor
 */
public record ClaimsDashboardView(Long registered, Long underAssessment, Long settlementRequested, Long approved,
                                  Long reopened, Long settled, String queueStatus, List<QueuedClaim> queue) {

    public record QueuedClaim(UUID claimId, String policyNumber, UUID claimantPartyId, String claimType,
                              LocalDate dateOfEvent, String status) {}
}
```

`dashboard/application/ClaimsDashboard.java`:

```java
package tz.co.nlolo.lifeplatform.dashboard.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.dashboard.api.ClaimsDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.ClaimsDashboardView.QueuedClaim;

import java.util.List;

@Service
public class ClaimsDashboard {

    static final int QUEUE_SIZE = 5;
    private static final PageRequest TOTAL_ONLY = PageRequest.of(0, 1);

    private final ClaimsApi claims;

    public ClaimsDashboard(ClaimsApi claims) {
        this.claims = claims;
    }

    /** @param manager whether the reader decides settlements -- which queue is THEIR work */
    public ClaimsDashboardView build(boolean manager) {
        ClaimStatus queueStatus = manager ? ClaimStatus.SETTLEMENT_REQUESTED : ClaimStatus.REGISTERED;
        List<QueuedClaim> queue = Safely.read("the " + queueStatus + " queue", () -> claims.searchClaims(
                queueStatus, null, null, null, PageRequest.of(0, QUEUE_SIZE, Sort.by(Sort.Direction.ASC, "createdAt")))
            .map(c -> new QueuedClaim(c.claimId(), c.policyNumber(), c.claimantPartyId(), c.claimType().name(),
                c.dateOfEvent(), c.status().name()))
            .getContent());

        return new ClaimsDashboardView(count(ClaimStatus.REGISTERED), count(ClaimStatus.UNDER_ASSESSMENT),
            count(ClaimStatus.SETTLEMENT_REQUESTED), count(ClaimStatus.APPROVED), count(ClaimStatus.REOPENED),
            count(ClaimStatus.SETTLED), queueStatus.name(), queue == null ? List.of() : queue);
    }

    private Long count(ClaimStatus status) {
        return Safely.read(status + " claims",
            () -> claims.searchClaims(status, null, null, null, TOTAL_ONLY).getTotalElements());
    }
}
```

In `DashboardController`, add the constructor parameter `ClaimsDashboard claims`, the field, and:

```java
    /**
     * Assessors and managers share it; the queue is chosen by role. Mirrors the claims module's own expression for
     * reading claims, which admits both.
     */
    @GetMapping("/dashboards/claims")
    @PreAuthorize("hasRole('CLAIMS_ASSESSOR') or hasRole('CLAIMS_MANAGER')")
    public ResponseEntity<ClaimsDashboardView> claims(Authentication authentication) {
        boolean manager = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_CLAIMS_MANAGER"::equals);
        return ResponseEntity.ok(claims.build(manager));
    }
```

Imports: `org.springframework.security.core.Authentication`, `org.springframework.security.core.GrantedAuthority`, `tz.co.nlolo.lifeplatform.dashboard.api.ClaimsDashboardView`, `tz.co.nlolo.lifeplatform.dashboard.application.ClaimsDashboard`.

In `openapi-dashboard.yaml`, add the path beside `/dashboards/underwriting`:

```yaml
  /dashboards/claims:
    get:
      summary: The claims dashboard (CLAIMS_ASSESSOR or CLAIMS_MANAGER)
      description: The queue is the settlement decisions for a manager and the unassessed claims for an assessor.
      security:
        - staffAuth: []
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ClaimsDashboardView' }
        '403': { $ref: '#/components/responses/Problem' }
```

and the schema under `components.schemas`:

```yaml
    ClaimStatus:
      type: string
      enum: [REGISTERED, UNDER_ASSESSMENT, APPROVED, REJECTED, SETTLEMENT_REQUESTED, SETTLED, REOPENED]
    ClaimsDashboardView:
      type: object
      additionalProperties: false
      required: [registered, underAssessment, settlementRequested, approved, reopened, settled, queueStatus, queue]
      properties:
        registered: { $ref: '#/components/schemas/Count' }
        underAssessment: { $ref: '#/components/schemas/Count' }
        settlementRequested: { $ref: '#/components/schemas/Count' }
        approved: { $ref: '#/components/schemas/Count' }
        reopened: { $ref: '#/components/schemas/Count' }
        settled: { $ref: '#/components/schemas/Count' }
        queueStatus: { $ref: '#/components/schemas/ClaimStatus' }
        queue:
          type: array
          items:
            type: object
            additionalProperties: false
            required: [claimId, policyNumber, claimantPartyId, claimType, status]
            properties:
              claimId: { type: string, format: uuid }
              policyNumber: { type: string }
              claimantPartyId: { type: string, format: uuid }
              claimType: { type: string, enum: [MATURITY, DEATH, DISABILITY, CRITICAL_ILLNESS] }
              dateOfEvent: { type: [string, 'null'], format: date }
              status: { $ref: '#/components/schemas/ClaimStatus' }
```

- [ ] **Step 4: Extend the contract test**

Add `@MockBean private ClaimsDashboard claims;` and:

```java
    @Test
    void aClaimsManagerReadsTheClaimsDashboardWithTheSettlementQueue() throws Exception {
        when(claims.build(true)).thenReturn(new ClaimsDashboardView(17L, 9L, 6L, 4L, 2L, 128L, "SETTLEMENT_REQUESTED",
            List.of(new ClaimsDashboardView.QueuedClaim(UUID.randomUUID(), "POL-00A4B7A1", UUID.randomUUID(), "DEATH",
                java.time.LocalDate.of(2026, 9, 29), "SETTLEMENT_REQUESTED"))));

        mockMvc.perform(get("/dashboards/claims").with(staff("CLAIMS_MANAGER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.queueStatus").value("SETTLEMENT_REQUESTED"))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void anAssessorGetsTheAssessorsQueue() throws Exception {
        when(claims.build(false)).thenReturn(new ClaimsDashboardView(17L, 9L, 6L, 4L, 2L, 128L, "REGISTERED", List.of()));

        mockMvc.perform(get("/dashboards/claims").with(staff("CLAIMS_ASSESSOR")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.queueStatus").value("REGISTERED"));
    }

    @Test
    void anUnderwriterIsRefusedTheClaimsDashboard() throws Exception {
        mockMvc.perform(get("/dashboards/claims").with(staff("UNDERWRITER"))).andExpect(status().isForbidden());
    }
```

Imports: `tz.co.nlolo.lifeplatform.dashboard.api.ClaimsDashboardView`, `tz.co.nlolo.lifeplatform.dashboard.application.ClaimsDashboard`.

- [ ] **Step 5: Run the tests**

Run: `./mvnw -o test -Dtest='ClaimsDashboardTest,DashboardContractTest,ModularityTests' -DfailIfNoSpecifiedTests=false`
Expected: all three classes `Failures: 0, Errors: 0`; `ClaimsDashboardTest` runs 2, `DashboardContractTest` runs 5.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard backend/api/openapi/openapi-dashboard.yaml backend/src/test/java/tz/co/nlolo/lifeplatform/dashboard
git commit -m "feat(dashboard): the claims dashboard, with each role's own queue

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: The finance dashboard

The finance dashboard is the one with money, a delta and a chart, so it is the one where the doctrine bites. Its figures come from three places: the new monthly collections (Task 1), two report runs read through `ReportFigures`, and four counts.

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/application/ReportFigures.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/api/FinanceDashboardView.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/application/FinanceDashboard.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/infrastructure/DashboardController.java`
- Modify: `backend/api/openapi/openapi-dashboard.yaml`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/application/BalanceSheetReport.java`, `IncomeStatementReport.java` (comment only)
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/dashboard/application/ReportFiguresTest.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/dashboard/application/FinanceDashboardTest.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/dashboard/DashboardContractTest.java`

**Interfaces:**
- Consumes: `ReportingApi.monthlyPremiumCollections(YearMonth, YearMonth)` and `MonthlyAmount` (Task 1); `Money.of`, `Safely.read` (Task 2).
- Produces: `static BigDecimal ReportFigures.sectionTotal(ReportResult, String sectionTitle, String column)`; `static BigDecimal ReportFigures.netResult(ReportResult)`; `FinanceDashboardView build(LocalDate today)` on `FinanceDashboard`; `GET /dashboards/finance`.

- [ ] **Step 1: Write the failing `ReportFigures` test**

The shapes below are copied from the reports' own code: `BalanceSheetReport.section(...)` titles each class section from `LedgerAccounts.CLASS_NAMES` ("Assets", "Liabilities") with a `balance` total; `IncomeStatementReport` emits a "Result" section with one row of `outcome` ("Profit for the period" / "Loss for the period") and an unsigned `amount`.

```java
package tz.co.nlolo.lifeplatform.dashboard.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportFiguresTest {

    private static ReportResult result(ReportSection... sections) {
        return new ReportResult("k", "t", "", Instant.now(), List.of(sections));
    }

    private static ReportSection classSection(String title, String total) {
        return new ReportSection(title, List.of(), List.of(), Map.of("accountCode", "Total", "balance", total));
    }

    private static ReportSection resultSection(String outcome, String amount) {
        return new ReportSection("Result", List.of(), List.of(Map.of("outcome", outcome, "amount", amount)), Map.of());
    }

    @Test
    void readsASectionsTotalByTitle() {
        ReportResult sheet = result(classSection("Assets", "742580120.00"), classSection("Liabilities", "512340210.00"));

        assertThat(ReportFigures.sectionTotal(sheet, "Assets", "balance")).isEqualByComparingTo("742580120.00");
        assertThat(ReportFigures.sectionTotal(sheet, "Liabilities", "balance")).isEqualByComparingTo("512340210.00");
    }

    /** Absent is null: a missing section is a card reading "Not available", never a quiet zero. */
    @Test
    void aMissingSectionOrColumnIsNull() {
        ReportResult sheet = result(classSection("Assets", "1.00"));

        assertThat(ReportFigures.sectionTotal(sheet, "Equity", "balance")).isNull();
        assertThat(ReportFigures.sectionTotal(sheet, "Assets", "comparison")).isNull();
    }

    @Test
    void aLossIsNegativeAndAProfitPositive() {
        assertThat(ReportFigures.netResult(result(resultSection("Profit for the period", "31204880.00"))))
            .isEqualByComparingTo("31204880.00");
        assertThat(ReportFigures.netResult(result(resultSection("Loss for the period", "1200.00"))))
            .isEqualByComparingTo("-1200.00");
        assertThat(ReportFigures.netResult(result())).isNull();
    }
}
```

- [ ] **Step 2: Write the failing composer test**

```java
package tz.co.nlolo.lifeplatform.dashboard.application;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.dashboard.api.FinanceDashboardView;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalApi;
import tz.co.nlolo.lifeplatform.payment.api.PaymentApi;
import tz.co.nlolo.lifeplatform.reporting.api.MonthlyAmount;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinanceDashboardTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    private final ReportingApi reporting = mock(ReportingApi.class);
    private final ManualJournalApi journals = mock(ManualJournalApi.class);
    private final BillingApi billing = mock(BillingApi.class);
    private final PaymentApi payments = mock(PaymentApi.class);
    private final FinanceDashboard dashboard = new FinanceDashboard(reporting, journals, billing, payments);

    private static MonthlyAmount m(int year, int month, String amount) {
        return new MonthlyAmount(YearMonth.of(year, month), "TZS", new BigDecimal(amount));
    }

    private void stubCountsAndReports() {
        when(journals.list(eq("SUBMITTED"), isNull(), isNull())).thenReturn(Collections.nCopies(4, null));
        when(billing.searchFieldReceipts(eq("RECONCILIATION_OVERDUE"), any()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 7));
        when(billing.searchArrears(eq(5), eq(false), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 19));
        when(payments.listAwaitingEftExecution()).thenReturn(Collections.nCopies(3, null));
        when(reporting.run(eq("balance-sheet"), any())).thenReturn(new ReportResult("balance-sheet", "", "", Instant.now(),
            List.of(new ReportSection("Assets", List.of(), List.of(), Map.of("balance", "742580120.00")),
                new ReportSection("Liabilities", List.of(), List.of(), Map.of("balance", "512340210.00")))));
        when(reporting.run(eq("income-statement"), any())).thenReturn(new ReportResult("income-statement", "", "",
            Instant.now(), List.of(new ReportSection("Result", List.of(),
                List.of(Map.of("outcome", "Profit for the period", "amount", "31204880.00")), Map.of()))));
    }

    @Test
    void readsEveryFigureFromItsOwnerAndComparesTwoCompleteMonths() {
        stubCountsAndReports();
        when(reporting.monthlyPremiumCollections(YearMonth.of(2025, 1), YearMonth.of(2026, 10))).thenReturn(List.of(
            m(2025, 10, "46200000.00"), m(2026, 8, "49100000.00"), m(2026, 9, "50204310.00"), m(2026, 10, "22600000.00")));

        FinanceDashboardView view = dashboard.build(TODAY);

        // The last COMPLETE month on 14 October is September; October is to date and is never one side of a delta.
        assertThat(view.premiumsMonth()).isEqualTo("2026-09");
        assertThat(view.premiumsCollected().amount()).isEqualTo("50204310.00");
        assertThat(view.premiumsChangePercent()).isEqualTo("2.2");
        assertThat(view.totalAssets().amount()).isEqualTo("742580120.00");
        assertThat(view.totalLiabilities().amount()).isEqualTo("512340210.00");
        assertThat(view.balanceSheetMonth()).isEqualTo("2026-10");
        assertThat(view.netResult().amount()).isEqualTo("31204880.00");
        assertThat(view.resultFrom()).isEqualTo("2026-01");
        assertThat(view.resultTo()).isEqualTo("2026-10");
        assertThat(view.journalsAwaitingApproval()).isEqualTo(4L);
        assertThat(view.receiptsOverdue()).isEqualTo(7L);
        assertThat(view.arrearsAtLapse()).isEqualTo(19L);
        assertThat(view.transfersAwaiting()).isEqualTo(3L);
        // The chart: this year to date, last year in full, one point per month that has collections.
        assertThat(view.premiumsThisYear()).extracting(FinanceDashboardView.MonthPoint::month)
            .containsExactly("2026-08", "2026-09", "2026-10");
        assertThat(view.premiumsLastYear()).extracting(FinanceDashboardView.MonthPoint::month).containsExactly("2025-10");
        assertThat(view.currentMonth()).isEqualTo("2026-10");
    }

    /** No delta against nothing: an absent or zero previous month is not a 100% rise. */
    @Test
    void noDeltaWhenThePreviousMonthHasNothing() {
        stubCountsAndReports();
        when(reporting.monthlyPremiumCollections(any(), any())).thenReturn(List.of(m(2026, 9, "50204310.00")));

        FinanceDashboardView view = dashboard.build(TODAY);

        assertThat(view.premiumsCollected().amount()).isEqualTo("50204310.00");
        assertThat(view.premiumsChangePercent()).isNull();
    }

    /** The working currency only. A USD receipt is not added to a TZS total. */
    @Test
    void ignoresCollectionsInAnyOtherCurrency() {
        stubCountsAndReports();
        when(reporting.monthlyPremiumCollections(any(), any())).thenReturn(List.of(
            new MonthlyAmount(YearMonth.of(2026, 9), "USD", new BigDecimal("900.00")), m(2026, 9, "50204310.00")));

        assertThat(dashboard.build(TODAY).premiumsCollected().amount()).isEqualTo("50204310.00");
    }

    @Test
    void asksTheBalanceSheetForThisMonthAndTheIncomeStatementForTheYearToDate() {
        stubCountsAndReports();
        when(reporting.monthlyPremiumCollections(any(), any())).thenReturn(List.of());

        dashboard.build(TODAY);

        org.mockito.Mockito.verify(reporting).run(eq("balance-sheet"),
            argThat((ReportFilter f) -> YearMonth.of(2026, 10).equals(f.monthFrom()) && f.monthTo() == null));
        org.mockito.Mockito.verify(reporting).run(eq("income-statement"),
            argThat((ReportFilter f) -> YearMonth.of(2026, 1).equals(f.monthFrom())
                && YearMonth.of(2026, 10).equals(f.monthTo())));
    }
}
```

`journals.list(...)` returns `List<ManualJournalView>`; only its size is read, so `Collections.nCopies(4, null)` is a list of four. The same for `listAwaitingEftExecution()`.

- [ ] **Step 3: Run both to verify they fail**

Run: `./mvnw -o -q test-compile`
Expected: FAIL — `cannot find symbol: class ReportFigures`.

- [ ] **Step 4: Implement `ReportFigures`**

```java
package tz.co.nlolo.lifeplatform.dashboard.application;

import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Reads a report's OWN number out of its result -- the dashboard's only way to show a ledger figure, so it can never
 * disagree with the report a finance officer opens from the card.
 *
 * <p>Coupled to two titles the reports emit: the balance sheet's class sections ("Assets", "Liabilities", from
 * {@code LedgerAccounts.CLASS_NAMES}) and the income statement's "Result" section. Both reports carry a comment naming
 * this class, so a rename is made knowingly. A title or column that is not there is null, which the card shows as
 * "Not available" -- never zero.
 */
final class ReportFigures {

    private ReportFigures() {}

    static BigDecimal sectionTotal(ReportResult result, String sectionTitle, String column) {
        if (result == null) return null;
        for (ReportSection section : result.sections()) {
            if (sectionTitle.equals(section.title())) {
                String value = section.totals().get(column);
                return value == null || value.isBlank() ? null : new BigDecimal(value);
            }
        }
        return null;
    }

    /** The income statement's result, signed: the report states a loss as an unsigned amount beside its outcome. */
    static BigDecimal netResult(ReportResult result) {
        if (result == null) return null;
        for (ReportSection section : result.sections()) {
            if ("Result".equals(section.title()) && !section.rows().isEmpty()) {
                Map<String, String> row = section.rows().get(0);
                String amount = row.get("amount");
                if (amount == null || amount.isBlank()) return null;
                BigDecimal value = new BigDecimal(amount);
                String outcome = row.getOrDefault("outcome", "");
                return outcome.startsWith("Loss") ? value.negate() : value;
            }
        }
        return null;
    }
}
```

- [ ] **Step 5: Implement the view and the composer**

`dashboard/api/FinanceDashboardView.java`:

```java
package tz.co.nlolo.lifeplatform.dashboard.api;

import java.util.List;

/**
 * The finance officer's dashboard. Money figures are report values; counts are one status each. Months are "YYYY-MM".
 *
 * @param premiumsMonth the last COMPLETE month, the one {@code premiumsCollected} is for
 * @param premiumsChangePercent {@code premiumsMonth} against the month before, one decimal place, signed; null when
 *     either month is missing or the earlier one is zero -- there is no change against nothing
 * @param balanceSheetMonth the month the balance sheet runs through (the current month)
 * @param resultFrom the first month of the year-to-date income statement
 * @param resultTo the last month of it (the current month)
 * @param premiumsThisYear one point per month of the current year that has collections, oldest first
 * @param premiumsLastYear the same for the previous calendar year
 * @param currentMonth the month in progress, so the console draws it as "to date" rather than as a complete month
 */
public record FinanceDashboardView(
    String premiumsMonth, Money premiumsCollected, String premiumsChangePercent,
    String balanceSheetMonth, Money totalAssets, Money totalLiabilities,
    String resultFrom, String resultTo, Money netResult,
    Long journalsAwaitingApproval, Long receiptsOverdue, Long transfersAwaiting, Long arrearsAtLapse,
    List<MonthPoint> premiumsThisYear, List<MonthPoint> premiumsLastYear, String currentMonth) {

    public record MonthPoint(String month, Money amount) {}
}
```

`dashboard/application/FinanceDashboard.java`:

```java
package tz.co.nlolo.lifeplatform.dashboard.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.dashboard.api.FinanceDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.FinanceDashboardView.MonthPoint;
import tz.co.nlolo.lifeplatform.dashboard.api.Money;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalApi;
import tz.co.nlolo.lifeplatform.payment.api.PaymentApi;
import tz.co.nlolo.lifeplatform.reporting.api.MonthlyAmount;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class FinanceDashboard {

    /** PRODUCT.md: "Working currency is TZS." Another currency's collections are never added to this one's. */
    static final String WORKING_CURRENCY = "TZS";

    /**
     * The dunning level that publishes PolicyLapseRecommended -- the same 5 as billing's own
     * {@code ArrearsNotificationSweep.LAPSE_RECOMMENDATION_LEVEL} and the console's sidebar badge.
     */
    static final int LAPSE_RECOMMENDATION_LEVEL = 5;

    private static final PageRequest TOTAL_ONLY = PageRequest.of(0, 1);

    private final ReportingApi reporting;
    private final ManualJournalApi journals;
    private final BillingApi billing;
    private final PaymentApi payments;

    public FinanceDashboard(ReportingApi reporting, ManualJournalApi journals, BillingApi billing, PaymentApi payments) {
        this.reporting = reporting;
        this.journals = journals;
        this.billing = billing;
        this.payments = payments;
    }

    /** @param today the civil date in Africa/Dar_es_Salaam, supplied by the caller */
    public FinanceDashboardView build(LocalDate today) {
        YearMonth current = YearMonth.from(today);
        YearMonth lastComplete = current.minusMonths(1);
        YearMonth beforeThat = lastComplete.minusMonths(1);

        Map<YearMonth, BigDecimal> monthly = Safely.read("monthly premium collections",
            () -> reporting.monthlyPremiumCollections(YearMonth.of(current.getYear() - 1, 1), current).stream()
                .filter(m -> WORKING_CURRENCY.equals(m.currency()))
                .collect(Collectors.toMap(MonthlyAmount::month, MonthlyAmount::amount, BigDecimal::add)));

        BigDecimal collected = monthly == null ? null : monthly.get(lastComplete);
        BigDecimal previous = monthly == null ? null : monthly.get(beforeThat);

        ReportResult sheet = Safely.read("the balance sheet",
            () -> reporting.run("balance-sheet", ReportFilter.month(current)));
        ReportResult income = Safely.read("the income statement",
            () -> reporting.run("income-statement", ReportFilter.months(YearMonth.of(current.getYear(), 1), current)));

        return new FinanceDashboardView(
            lastComplete.toString(), Money.of(collected, WORKING_CURRENCY), changePercent(collected, previous),
            current.toString(),
            Money.of(ReportFigures.sectionTotal(sheet, "Assets", "balance"), WORKING_CURRENCY),
            Money.of(ReportFigures.sectionTotal(sheet, "Liabilities", "balance"), WORKING_CURRENCY),
            YearMonth.of(current.getYear(), 1).toString(), current.toString(),
            Money.of(ReportFigures.netResult(income), WORKING_CURRENCY),
            Safely.read("journals awaiting approval", () -> (long) journals.list("SUBMITTED", null, null).size()),
            Safely.read("overdue field receipts",
                () -> billing.searchFieldReceipts("RECONCILIATION_OVERDUE", TOTAL_ONLY).getTotalElements()),
            Safely.read("bank transfers awaiting", () -> (long) payments.listAwaitingEftExecution().size()),
            Safely.read("arrears at the lapse level",
                () -> billing.searchArrears(LAPSE_RECOMMENDATION_LEVEL, false, TOTAL_ONLY).getTotalElements()),
            points(monthly, current.getYear()), points(monthly, current.getYear() - 1), current.toString());
    }

    /** A change between two report values -- the only arithmetic here, and it is a difference of two report numbers. */
    static String changePercent(BigDecimal now, BigDecimal before) {
        if (now == null || before == null || before.signum() == 0) return null;
        return now.subtract(before).multiply(BigDecimal.valueOf(100))
            .divide(before, 1, RoundingMode.HALF_UP).toPlainString();
    }

    private static List<MonthPoint> points(Map<YearMonth, BigDecimal> monthly, int year) {
        if (monthly == null) return List.of();
        return monthly.entrySet().stream()
            .filter(e -> e.getKey().getYear() == year)
            .sorted(Map.Entry.comparingByKey())
            .map(e -> new MonthPoint(e.getKey().toString(), Money.of(e.getValue(), WORKING_CURRENCY)))
            .toList();
    }
}
```

With the test's numbers: `(50204310.00 − 49100000.00) × 100 ÷ 49100000.00 = 2.249…` → `"2.2"`.

- [ ] **Step 6: Name the dashboard in the two reports it reads**

In `BalanceSheetReport.java`, directly above `sections.add(section(LedgerAccounts.CLASS_NAMES.get(classRoot), ...))`, add:

```java
            // The finance dashboard reads the "Assets" and "Liabilities" section totals by these titles
            // (dashboard.application.ReportFigures). Renaming a class section breaks those two cards, knowingly.
```

In `IncomeStatementReport.java`, directly above `sections.add(new ReportSection("Result", ...`, add:

```java
        // The finance dashboard reads this section's title, "outcome" and "amount" (dashboard.application.ReportFigures).
```

- [ ] **Step 7: Wire the endpoint and the contract**

In `DashboardController`, add the constructor parameter `FinanceDashboard finance`, the field, a zone constant and:

```java
    /** The civil date the business runs on. Never UTC: a UTC "today" is yesterday until 03:00 in Dar es Salaam. */
    private static final java.time.ZoneId CIVIL = java.time.ZoneId.of("Africa/Dar_es_Salaam");

    /** The same expression as every finance endpoint (39 of them) and the reports. */
    @GetMapping("/dashboards/finance")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<FinanceDashboardView> finance() {
        return ResponseEntity.ok(finance.build(java.time.LocalDate.now(CIVIL)));
    }
```

In `openapi-dashboard.yaml`, add the path:

```yaml
  /dashboards/finance:
    get:
      summary: The finance dashboard (FINANCE_OFFICER or ADMIN)
      description: >
        Premiums for the last complete month with its change against the month before; the balance sheet through the
        current month; the income statement for the year to date; four work-waiting counts; premiums by month for this
        year and last. Every money figure is a report's own number, in TZS.
      security:
        - staffAuth: []
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { $ref: '#/components/schemas/FinanceDashboardView' }
        '403': { $ref: '#/components/responses/Problem' }
```

and the schemas:

```yaml
    Month:
      type: string
      pattern: '^\d{4}-\d{2}$'
    MonthPoint:
      type: object
      additionalProperties: false
      required: [month, amount]
      properties:
        month: { $ref: '#/components/schemas/Month' }
        amount: { $ref: '#/components/schemas/Money' }
    FinanceDashboardView:
      type: object
      additionalProperties: false
      required: [premiumsMonth, premiumsCollected, premiumsChangePercent, balanceSheetMonth, totalAssets,
                 totalLiabilities, resultFrom, resultTo, netResult, journalsAwaitingApproval, receiptsOverdue,
                 transfersAwaiting, arrearsAtLapse, premiumsThisYear, premiumsLastYear, currentMonth]
      properties:
        premiumsMonth: { $ref: '#/components/schemas/Month' }
        premiumsCollected: { $ref: '#/components/schemas/Money' }
        premiumsChangePercent: { type: [string, 'null'], pattern: '^-?\d+(\.\d)?$' }
        balanceSheetMonth: { $ref: '#/components/schemas/Month' }
        totalAssets: { $ref: '#/components/schemas/Money' }
        totalLiabilities: { $ref: '#/components/schemas/Money' }
        resultFrom: { $ref: '#/components/schemas/Month' }
        resultTo: { $ref: '#/components/schemas/Month' }
        netResult: { $ref: '#/components/schemas/Money' }
        journalsAwaitingApproval: { $ref: '#/components/schemas/Count' }
        receiptsOverdue: { $ref: '#/components/schemas/Count' }
        transfersAwaiting: { $ref: '#/components/schemas/Count' }
        arrearsAtLapse: { $ref: '#/components/schemas/Count' }
        premiumsThisYear: { type: array, items: { $ref: '#/components/schemas/MonthPoint' } }
        premiumsLastYear: { type: array, items: { $ref: '#/components/schemas/MonthPoint' } }
        currentMonth: { $ref: '#/components/schemas/Month' }
```

Note: the common `Money.amount` pattern is `^-?\d+(\.\d{1,2})?$`, so a loss (negative net result) is valid.

Add to the contract test `@MockBean private FinanceDashboard finance;` and:

```java
    @Test
    void aFinanceOfficerReadsTheFinanceDashboard() throws Exception {
        when(finance.build(org.mockito.ArgumentMatchers.any())).thenReturn(new FinanceDashboardView(
            "2026-09", new Money("50204310.00", "TZS"), "2.2", "2026-10", new Money("742580120.00", "TZS"),
            new Money("512340210.00", "TZS"), "2026-01", "2026-10", new Money("-1200.00", "TZS"),
            4L, 7L, 3L, null,
            List.of(new FinanceDashboardView.MonthPoint("2026-09", new Money("50204310.00", "TZS"))), List.of(),
            "2026-10"));

        mockMvc.perform(get("/dashboards/finance").with(staff("FINANCE_OFFICER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.premiumsCollected.amount").value("50204310.00"))
            .andExpect(jsonPath("$.arrearsAtLapse").isEmpty())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void anUnderwriterIsRefusedTheFinanceDashboard() throws Exception {
        mockMvc.perform(get("/dashboards/finance").with(staff("UNDERWRITER"))).andExpect(status().isForbidden());
    }
```

Imports: `tz.co.nlolo.lifeplatform.dashboard.api.FinanceDashboardView`, `tz.co.nlolo.lifeplatform.dashboard.application.FinanceDashboard`.

- [ ] **Step 8: Run the tests**

Run: `./mvnw -o test -Dtest='ReportFiguresTest,FinanceDashboardTest,DashboardContractTest,ModularityTests' -DfailIfNoSpecifiedTests=false`
Expected: all four classes `Failures: 0, Errors: 0`; `ReportFiguresTest` 3, `FinanceDashboardTest` 4, `DashboardContractTest` 7.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/application/BalanceSheetReport.java backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/application/IncomeStatementReport.java backend/api/openapi/openapi-dashboard.yaml backend/src/test/java/tz/co/nlolo/lifeplatform/dashboard
git commit -m "feat(dashboard): the finance dashboard, every money figure a report's own

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: The agent dashboard

The agent's figures are scoped the way the agent's own screens are: clients by who registered them (the token subject), policies by the agent's hierarchy team (from the `party_id` claim).

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/api/AgentDashboardView.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/application/AgentDashboard.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/infrastructure/DashboardController.java`
- Modify: `backend/api/openapi/openapi-dashboard.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/dashboard/application/AgentDashboardTest.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/dashboard/DashboardContractTest.java`

**Interfaces:**
- Consumes: `Money.of`, `Safely.read` (Task 2).
- Produces: `AgentDashboardView build(String subject, UUID partyId, LocalDate today)` on `AgentDashboard`; `GET /dashboards/agent`.

- [ ] **Step 1: Write the failing composer test**

```java
package tz.co.nlolo.lifeplatform.dashboard.application;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tz.co.nlolo.lifeplatform.dashboard.api.AgentDashboardView;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionStatementView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyType;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentDashboardTest {

    private static final String SUBJECT = "agent-subject";
    private static final UUID PARTY = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID DOWNLINE = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    private final PartyApi parties = mock(PartyApi.class);
    private final PolicyApi policies = mock(PolicyApi.class);
    private final DistributionApi distribution = mock(DistributionApi.class);
    private final AgentDashboard dashboard = new AgentDashboard(parties, policies, distribution);

    private static <T> PageImpl<T> total(long n) {
        return new PageImpl<>(List.of(), PageRequest.of(0, 1), n);
    }

    @Test
    void scopesClientsToTheirRegistrarAndPoliciesToTheTeam() {
        PartyView pending = new PartyView(UUID.randomUUID(), PartyType.INDIVIDUAL, KycStatus.PENDING, "Amina Hassan");
        when(parties.searchParties(isNull(), eq(SUBJECT), isNull(), isNull(), any())).thenReturn(total(309));
        when(parties.searchParties(eq(KycStatus.PENDING), eq(SUBJECT), isNull(), isNull(),
            argThat(p -> p != null && p.getPageSize() == 1))).thenReturn(total(23));
        when(parties.searchParties(eq(KycStatus.PENDING), eq(SUBJECT), isNull(), isNull(),
            argThat(p -> p != null && p.getPageSize() == AgentDashboard.QUEUE_SIZE))).thenReturn(new PageImpl<>(List.of(pending)));
        when(parties.searchParties(eq(KycStatus.REJECTED), eq(SUBJECT), isNull(), isNull(), any())).thenReturn(total(1));
        when(distribution.resolveAgentTeam(PARTY)).thenReturn(List.of(AGENT, DOWNLINE));
        when(policies.searchPolicies(isNull(), isNull(), isNull(), eq(Set.of(AGENT, DOWNLINE)), isNull(), any()))
            .thenReturn(total(412));
        when(distribution.agentIdForParty(PARTY)).thenReturn(Optional.of(AGENT));
        when(distribution.listStatements(AGENT, "2026-09")).thenReturn(List.of(mockStatement("1284500.00")));

        AgentDashboardView view = dashboard.build(SUBJECT, PARTY, TODAY);

        assertThat(view.clients()).isEqualTo(309L);
        assertThat(view.clientsAwaitingKyc()).isEqualTo(23L);
        assertThat(view.clientsKycRejected()).isEqualTo(1L);
        assertThat(view.policiesInBook()).isEqualTo(412L);
        // The last CLOSED period on 14 October is September.
        assertThat(view.commissionPeriod()).isEqualTo("2026-09");
        assertThat(view.commissionEarned().amount()).isEqualTo("1284500.00");
        assertThat(view.awaitingKyc()).singleElement().satisfies(c -> {
            assertThat(c.displayName()).isEqualTo("Amina Hassan");
            assertThat(c.kycStatus()).isEqualTo("PENDING");
        });
    }

    /** A party that is not an agent here has no team and no statement: nulls and an empty book, not an error. */
    @Test
    void aPartyWithNoAgentProfileHasNoCommissionAndNoBook() {
        when(parties.searchParties(any(), eq(SUBJECT), isNull(), isNull(), any())).thenReturn(total(0));
        when(distribution.resolveAgentTeam(PARTY)).thenReturn(List.of());
        when(policies.searchPolicies(isNull(), isNull(), isNull(), eq(Set.of()), isNull(), any())).thenReturn(total(0));
        when(distribution.agentIdForParty(PARTY)).thenReturn(Optional.empty());

        AgentDashboardView view = dashboard.build(SUBJECT, PARTY, TODAY);

        assertThat(view.policiesInBook()).isZero();
        assertThat(view.commissionEarned()).isNull();
        assertThat(view.commissionStatus()).isNull();
    }

    private static CommissionStatementView mockStatement(String amount) {
        CommissionStatementView s = mock(CommissionStatementView.class);
        when(s.totalAmount()).thenReturn(new BigDecimal(amount));
        when(s.totalCurrency()).thenReturn("TZS");
        when(s.status()).thenReturn(null);
        return s;
    }
}
```

`PartyView` is a four-field record (`partyId, partyType, kycStatus, displayName`), so it is built directly. `CommissionStatementView.status()` returns an enum; the mock returns null for it and the composer must tolerate that.

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -o -q test-compile`
Expected: FAIL — `cannot find symbol: class AgentDashboard`.

- [ ] **Step 3: Implement**

`dashboard/api/AgentDashboardView.java`:

```java
package tz.co.nlolo.lifeplatform.dashboard.api;

import java.util.List;
import java.util.UUID;

/**
 * An agent's dashboard, scoped exactly as the agent's own screens are: clients by who registered them, policies by
 * the agent's hierarchy team.
 *
 * @param commissionPeriod the last closed period ("YYYY-MM"), the one {@code commissionEarned} is for
 * @param commissionEarned null when the party is not an agent here, or has no statement for that period
 * @param awaitingKyc the newest clients whose KYC is still pending, so the agent can send their documents
 */
public record AgentDashboardView(Long clients, Long clientsAwaitingKyc, Long clientsKycRejected, Long policiesInBook,
                                 String commissionPeriod, Money commissionEarned, String commissionStatus,
                                 List<ClientAwaitingKyc> awaitingKyc) {

    public record ClientAwaitingKyc(UUID partyId, String displayName, String kycStatus) {}
}
```

`dashboard/application/AgentDashboard.java`:

```java
package tz.co.nlolo.lifeplatform.dashboard.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.dashboard.api.AgentDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.AgentDashboardView.ClientAwaitingKyc;
import tz.co.nlolo.lifeplatform.dashboard.api.Money;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionStatementView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AgentDashboard {

    static final int QUEUE_SIZE = 5;
    private static final PageRequest TOTAL_ONLY = PageRequest.of(0, 1);

    private final PartyApi parties;
    private final PolicyApi policies;
    private final DistributionApi distribution;

    public AgentDashboard(PartyApi parties, PolicyApi policies, DistributionApi distribution) {
        this.parties = parties;
        this.policies = policies;
        this.distribution = distribution;
    }

    /**
     * @param subject the token subject -- what clients are scoped on, as {@code PartyController} scopes them
     * @param partyId the token's {@code party_id} -- what the hierarchy team and the agent profile hang off
     * @param today the civil date in Africa/Dar_es_Salaam
     */
    public AgentDashboardView build(String subject, UUID partyId, LocalDate today) {
        Long clients = Safely.read("my clients",
            () -> parties.searchParties(null, subject, null, null, TOTAL_ONLY).getTotalElements());
        Long awaitingKyc = Safely.read("clients awaiting KYC",
            () -> parties.searchParties(KycStatus.PENDING, subject, null, null, TOTAL_ONLY).getTotalElements());
        Long rejected = Safely.read("clients with KYC rejected",
            () -> parties.searchParties(KycStatus.REJECTED, subject, null, null, TOTAL_ONLY).getTotalElements());
        // Newest first, the same order the party search uses everywhere: an agent's most recent visit is the one
        // whose documents are in hand.
        List<ClientAwaitingKyc> queue = Safely.read("the KYC queue", () -> parties.searchParties(KycStatus.PENDING,
                subject, null, null, PageRequest.of(0, QUEUE_SIZE,
                    Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("partyId"))))
            .map(p -> new ClientAwaitingKyc(p.partyId(), p.displayName(), p.kycStatus().name()))
            .getContent());

        Long book = Safely.read("policies in my book", () -> policies.searchPolicies(null, null, null,
            Set.copyOf(distribution.resolveAgentTeam(partyId)), null, TOTAL_ONLY).getTotalElements());

        String period = YearMonth.from(today).minusMonths(1).toString();
        CommissionStatementView statement = Safely.read("commission statement", () -> distribution
            .agentIdForParty(partyId)
            .flatMap(agentId -> distribution.listStatements(agentId, period).stream().findFirst())
            .orElse(null));

        return new AgentDashboardView(clients, awaitingKyc, rejected, book, period,
            statement == null ? null : Money.of(statement.totalAmount(), statement.totalCurrency()),
            statement == null || statement.status() == null ? null : statement.status().name(),
            queue == null ? List.of() : queue);
    }
}
```

In `DashboardController`, add the constructor parameter `AgentDashboard agent`, the field and:

```java
    /**
     * Scoped by the token, never by a parameter: the subject for clients, {@code party_id} for the team -- the two
     * claims the agent's own client and policy screens are scoped on. A token without {@code party_id} is refused,
     * as {@code PolicyController.resolveOwnAgentTeamOrThrow} refuses it.
     */
    @GetMapping("/dashboards/agent")
    @PreAuthorize("hasRole('REALM_AGENTS')")
    public ResponseEntity<AgentDashboardView> agent(@AuthenticationPrincipal Jwt jwt) {
        String partyId = jwt.getClaimAsString("party_id");
        if (partyId == null) {
            throw new AccessDeniedException("Agent token carries no party_id claim");
        }
        return ResponseEntity.ok(agent.build(jwt.getSubject(), UUID.fromString(partyId), java.time.LocalDate.now(CIVIL)));
    }
```

Imports: `org.springframework.security.access.AccessDeniedException`, `org.springframework.security.core.annotation.AuthenticationPrincipal`, `org.springframework.security.oauth2.jwt.Jwt`, `java.util.UUID`, `tz.co.nlolo.lifeplatform.dashboard.api.AgentDashboardView`, `tz.co.nlolo.lifeplatform.dashboard.application.AgentDashboard`.

In `openapi-dashboard.yaml`, add the path:

```yaml
  /dashboards/agent:
    get:
      summary: The agent's dashboard (agents realm, scoped to the token)
      security:
        - agentsAuth: [agent]
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema: { $ref: '#/components/schemas/AgentDashboardView' }
        '403': { $ref: '#/components/responses/Problem' }
```

and the schema:

```yaml
    AgentDashboardView:
      type: object
      additionalProperties: false
      required: [clients, clientsAwaitingKyc, clientsKycRejected, policiesInBook, commissionPeriod, commissionEarned,
                 commissionStatus, awaitingKyc]
      properties:
        clients: { $ref: '#/components/schemas/Count' }
        clientsAwaitingKyc: { $ref: '#/components/schemas/Count' }
        clientsKycRejected: { $ref: '#/components/schemas/Count' }
        policiesInBook: { $ref: '#/components/schemas/Count' }
        commissionPeriod: { $ref: '#/components/schemas/Month' }
        commissionEarned: { $ref: '#/components/schemas/Money' }
        commissionStatus: { type: [string, 'null'] }
        awaitingKyc:
          type: array
          items:
            type: object
            additionalProperties: false
            required: [partyId, displayName, kycStatus]
            properties:
              partyId: { type: string, format: uuid }
              displayName: { type: string }
              kycStatus: { type: string, enum: [PENDING, VERIFIED, REJECTED] }
```

- [ ] **Step 4: Extend the contract test**

Add `@MockBean private AgentDashboard agent;` and:

```java
    private static JwtRequestPostProcessor agentToken(String partyId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
            .jwt(b -> {
                b.subject("agent-subject").claim("tenant_id", UUID.randomUUID().toString());
                if (partyId != null) b.claim("party_id", partyId);
            });
    }

    @Test
    void anAgentReadsTheirOwnDashboard() throws Exception {
        UUID party = UUID.randomUUID();
        when(agent.build(org.mockito.ArgumentMatchers.eq("agent-subject"), org.mockito.ArgumentMatchers.eq(party),
            org.mockito.ArgumentMatchers.any())).thenReturn(new AgentDashboardView(309L, 23L, 1L, 412L, "2026-09",
                new Money("1284500.00", "TZS"), null,
                List.of(new AgentDashboardView.ClientAwaitingKyc(UUID.randomUUID(), "Amina Hassan", "PENDING"))));

        mockMvc.perform(get("/dashboards/agent").with(agentToken(party.toString())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clients").value(309))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void anAgentTokenWithNoPartyIdIsRefused() throws Exception {
        mockMvc.perform(get("/dashboards/agent").with(agentToken(null))).andExpect(status().isForbidden());
    }

    @Test
    void staffAreRefusedTheAgentDashboard() throws Exception {
        mockMvc.perform(get("/dashboards/agent").with(staff("ADMIN"))).andExpect(status().isForbidden());
    }
```

Imports: `tz.co.nlolo.lifeplatform.dashboard.api.AgentDashboardView`, `tz.co.nlolo.lifeplatform.dashboard.application.AgentDashboard`.

- [ ] **Step 5: Run every dashboard test and the boundary check**

Run: `./mvnw -o test -Dtest='UnderwritingDashboardTest,ClaimsDashboardTest,ReportFiguresTest,FinanceDashboardTest,AgentDashboardTest,DashboardContractTest,ModularityTests' -DfailIfNoSpecifiedTests=false`
Expected: all seven classes `Failures: 0, Errors: 0`; `AgentDashboardTest` 2, `DashboardContractTest` 10.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard backend/api/openapi/openapi-dashboard.yaml backend/src/test/java/tz/co/nlolo/lifeplatform/dashboard
git commit -m "feat(dashboard): the agent dashboard, scoped exactly as the agent's own screens

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5a: Recent activity from the audit log

Each staff dashboard gains the latest events of its own module. Read the "Recent activity" section of the design decisions above first: the feed says what happened and never who, and the agent dashboard has none.

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/api/ActivityItem.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard/application/RecentActivity.java`
- Modify: `dashboard/api/UnderwritingDashboardView.java`, `ClaimsDashboardView.java`, `FinanceDashboardView.java` — a last component `List<ActivityItem> recentActivity`
- Modify: `dashboard/application/UnderwritingDashboard.java`, `ClaimsDashboard.java`, `FinanceDashboard.java` — read it
- Modify: `backend/api/openapi/openapi-dashboard.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/dashboard/application/RecentActivityTest.java`
- Modify tests: `UnderwritingDashboardTest.java`, `ClaimsDashboardTest.java`, `FinanceDashboardTest.java`, `DashboardContractTest.java` — the new constructor argument and view component

**Interfaces:**
- Consumes: `AuditApi.listEvents(String eventTypePrefix, DateRange range, Pageable pageable)` returning `Page<AuditEntryView>`; `record AuditEntryView(UUID eventId, String eventType, Instant occurredAt, String payloadJson)`; `record DateRange(Instant from, Instant to)` — all in `tz.co.nlolo.lifeplatform.audit.api`. `Money`, `Safely.read` (Task 2).
- Produces: `record ActivityItem(String eventType, Instant occurredAt, Map<String, String> refs, Money amount)`; `List<ActivityItem> RecentActivity.latest(String... prefixes)` — at most `RecentActivity.SIZE` (8), newest first, noise removed.

- [ ] **Step 1: Write the failing `RecentActivity` test**

```java
package tz.co.nlolo.lifeplatform.dashboard.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import tz.co.nlolo.lifeplatform.audit.api.AuditApi;
import tz.co.nlolo.lifeplatform.audit.api.AuditEntryView;
import tz.co.nlolo.lifeplatform.dashboard.api.ActivityItem;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecentActivityTest {

    private final AuditApi audit = mock(AuditApi.class);
    private final RecentActivity activity = new RecentActivity(audit, new ObjectMapper());

    private static AuditEntryView event(String type, String at, String payload) {
        return new AuditEntryView(UUID.randomUUID(), type, Instant.parse(at), payload);
    }

    @Test
    void mergesItsPrefixesNewestFirstAndDropsSystemNoise() {
        when(audit.listEvents(eq("billing."), any(), any())).thenReturn(new PageImpl<>(List.of(
            event("billing.PremiumCollected", "2026-10-14T08:00:00Z",
                "{\"policyNumber\":\"POL-1\",\"amount\":{\"amount\":62500.00,\"currencyCode\":\"TZS\"}}"),
            event("billing.PremiumInvoiceGenerated", "2026-10-14T09:00:00Z", "{}"))));
        when(audit.listEvents(eq("payment."), any(), any())).thenReturn(new PageImpl<>(List.of(
            event("payment.EftDisbursementExecuted", "2026-10-14T10:00:00Z", "{\"disbursementId\":\"d-1\"}"))));

        List<ActivityItem> items = activity.latest("billing.", "payment.");

        assertThat(items).extracting(ActivityItem::eventType)
            .containsExactly("payment.EftDisbursementExecuted", "billing.PremiumCollected");
        ActivityItem premium = items.get(1);
        assertThat(premium.refs()).containsEntry("policyNumber", "POL-1");
        // A number in the payload keeps its cents: "62500.00", never "62500.0".
        assertThat(premium.amount().amount()).isEqualTo("62500.00");
        assertThat(premium.amount().currencyCode()).isEqualTo("TZS");
    }

    /** Only whitelisted references leave the server -- a payer's phone number does not reach a dashboard. */
    @Test
    void passesOnlyWhitelistedReferences() {
        when(audit.listEvents(eq("billing."), any(), any())).thenReturn(new PageImpl<>(List.of(
            event("billing.PremiumCollected", "2026-10-14T08:00:00Z",
                "{\"policyNumber\":\"POL-1\",\"payerRef\":\"+255712345678\"}"))));

        assertThat(activity.latest("billing.").get(0).refs()).containsOnlyKeys("policyNumber");
    }

    /** The settled and approved amounts live under their own names on claim events. */
    @Test
    void readsAClaimsSettledAmount() {
        when(audit.listEvents(eq("claims."), any(), any())).thenReturn(new PageImpl<>(List.of(
            event("claims.ClaimSettled", "2026-10-14T08:00:00Z",
                "{\"claimId\":\"c-1\",\"settledAmount\":{\"amount\":\"10000000.00\",\"currencyCode\":\"TZS\"}}"))));

        ActivityItem settled = activity.latest("claims.").get(0);

        assertThat(settled.refs()).containsEntry("claimId", "c-1");
        assertThat(settled.amount().amount()).isEqualTo("10000000.00");
    }

    /** An unreadable payload still says what happened and when; it just names nothing. */
    @Test
    void survivesAPayloadItCannotRead() {
        when(audit.listEvents(eq("claims."), any(), any())).thenReturn(new PageImpl<>(List.of(
            event("claims.ClaimRegistered", "2026-10-14T08:00:00Z", "not json"))));

        ActivityItem item = activity.latest("claims.").get(0);

        assertThat(item.eventType()).isEqualTo("claims.ClaimRegistered");
        assertThat(item.refs()).isEmpty();
        assertThat(item.amount()).isNull();
    }

    @Test
    void keepsTheNewestEightAndAsksForTheNewestFirst() {
        List<AuditEntryView> twelve = java.util.stream.IntStream.range(0, 12)
            .mapToObj(i -> event("claims.ClaimAssessed", "2026-10-14T0" + (i % 10) + ":0" + (i / 10) + ":00Z", "{}"))
            .toList();
        when(audit.listEvents(eq("claims."), any(), argThat(p -> p != null
            && p.getSort().getOrderFor("occurredAt") != null
            && p.getSort().getOrderFor("occurredAt").isDescending()))).thenReturn(new PageImpl<>(twelve));

        assertThat(activity.latest("claims.")).hasSize(RecentActivity.SIZE);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run (from `backend/`): `./mvnw -o -q test-compile`
Expected: FAIL — `cannot find symbol: class RecentActivity`.

- [ ] **Step 3: Implement**

`dashboard/api/ActivityItem.java`:

```java
package tz.co.nlolo.lifeplatform.dashboard.api;

import java.time.Instant;
import java.util.Map;

/**
 * One event from the audit log, as a dashboard shows it: what happened and when -- never who. The audit log records
 * no actor in general ({@code AuditApi.listEvents}), and a feed that implied one would be presenting an event stream as
 * compliance evidence.
 *
 * @param refs the whitelisted references from the event's payload (case, claim, policy, receipt, disbursement), so the
 *     console can word the event and link it; only these keys ever leave the server
 * @param amount the event's money where it carries one; null otherwise
 */
public record ActivityItem(String eventType, Instant occurredAt, Map<String, String> refs, Money amount) {}
```

`dashboard/application/RecentActivity.java`:

```java
package tz.co.nlolo.lifeplatform.dashboard.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.audit.api.AuditApi;
import tz.co.nlolo.lifeplatform.audit.api.AuditEntryView;
import tz.co.nlolo.lifeplatform.audit.api.DateRange;
import tz.co.nlolo.lifeplatform.dashboard.api.ActivityItem;
import tz.co.nlolo.lifeplatform.dashboard.api.Money;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The latest events of one or more modules, for a dashboard's "Recent activity" (2026-10-14).
 *
 * <p>Reads the audit log the same way {@code AuditLogController} does -- newest first, no date bounds -- and keeps the
 * newest {@link #SIZE} across the prefixes asked for. Each prefix is read separately and the results merged by time:
 * ordering, not arithmetic.
 */
@Component
public class RecentActivity {

    static final int SIZE = 8;

    /** Read per prefix before the noise is dropped, so a burst of noise still leaves events to show. */
    private static final int FETCH = 40;

    /**
     * Events the platform raises by itself in bulk -- one per GL posting, one per earned-revenue run, one per invoice
     * in a billing run. Kept, they would fill the feed and bury everything a person did.
     */
    static final Set<String> NOISE = Set.of("finaccounting.GlPostingRecorded", "finaccounting.PaaRevenueEarned",
        "billing.PremiumInvoiceGenerated");

    /** The only payload keys that leave the server. A payer's phone number or a free-text reason never does. */
    static final List<String> REF_KEYS = List.of("caseId", "outcome", "claimId", "claimType", "policyNumber",
        "receiptId", "disbursementId", "purpose");

    /** Where an event keeps its money: plainly, or under the name of what the money was. */
    private static final List<String> AMOUNT_KEYS = List.of("amount", "settledAmount", "approvedAmount");

    private final AuditApi audit;
    private final ObjectReader json;

    public RecentActivity(AuditApi audit, ObjectMapper mapper) {
        this.audit = audit;
        // Decimals as BigDecimal, so "62500.00" keeps its cents instead of becoming the double 62500.0.
        this.json = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    public List<ActivityItem> latest(String... prefixes) {
        return Arrays.stream(prefixes)
            .flatMap(prefix -> audit.listEvents(prefix, new DateRange(null, null),
                PageRequest.of(0, FETCH, Sort.by(Sort.Direction.DESC, "occurredAt"))).getContent().stream())
            .filter(e -> !NOISE.contains(e.eventType()))
            .sorted(Comparator.comparing(AuditEntryView::occurredAt).reversed())
            .limit(SIZE)
            .map(this::item)
            .toList();
    }

    private ActivityItem item(AuditEntryView event) {
        Map<String, String> refs = new LinkedHashMap<>();
        Money amount = null;
        try {
            JsonNode payload = json.readTree(event.payloadJson() == null ? "{}" : event.payloadJson());
            for (String key : REF_KEYS) {
                JsonNode value = payload.get(key);
                if (value != null && value.isValueNode() && !value.isNull()) refs.put(key, value.asText());
            }
            for (String key : AMOUNT_KEYS) {
                JsonNode money = payload.get(key);
                if (money != null && money.isObject() && money.hasNonNull("amount") && money.hasNonNull("currencyCode")) {
                    JsonNode value = money.get("amount");
                    BigDecimal decimal = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText());
                    amount = Money.of(decimal, money.get("currencyCode").asText());
                    break;
                }
            }
        } catch (Exception unreadable) {
            // An unreadable payload still says WHAT happened and WHEN; it just names nothing.
            refs.clear();
            amount = null;
        }
        return new ActivityItem(event.eventType(), event.occurredAt(), Map.copyOf(refs), amount);
    }
}
```

`Map.copyOf` loses insertion order; the console does not depend on it.

- [ ] **Step 4: Run it**

Run: `./mvnw -o test -Dtest=RecentActivityTest -DfailIfNoSpecifiedTests=false`
Expected: `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Step 5: Add the feed to the three staff views and composers**

Add `List<ActivityItem> recentActivity` as the LAST record component of `UnderwritingDashboardView`, `ClaimsDashboardView` and `FinanceDashboardView`, with this line in each record's javadoc:

```java
 * @param recentActivity this module's latest events, newest first -- what happened, never who; empty when unreadable
```

Then in each composer, add a `RecentActivity` constructor parameter (last) and field, read the feed and pass it last:

```java
// UnderwritingDashboard -- constructor: (UnderwritingApi underwriting, RecentActivity activity)
        List<ActivityItem> recent = Safely.read("recent activity", () -> activity.latest("underwriting."));
        return new UnderwritingDashboardView(open, inReview, awaitingIssue, queue == null ? List.of() : queue,
            recent == null ? List.of() : recent);

// ClaimsDashboard -- constructor: (ClaimsApi claims, RecentActivity activity)
        List<ActivityItem> recent = Safely.read("recent activity", () -> activity.latest("claims."));
        // ... existing arguments, then:  recent == null ? List.of() : recent

// FinanceDashboard -- constructor: (ReportingApi reporting, ManualJournalApi journals, BillingApi billing,
//                                    PaymentApi payments, RecentActivity activity)
        List<ActivityItem> recent = Safely.read("recent activity",
            () -> activity.latest("billing.", "payment.", "finaccounting."));
        // ... existing arguments, then:  recent == null ? List.of() : recent
```

Import `tz.co.nlolo.lifeplatform.dashboard.api.ActivityItem` in each.

- [ ] **Step 6: Update the composer and contract tests for the new argument**

- `UnderwritingDashboardTest`, `ClaimsDashboardTest`, `FinanceDashboardTest`: add a field `private final RecentActivity activity = mock(RecentActivity.class);` and pass `activity` as the composer's last constructor argument. No stub is needed for the existing tests: an unstubbed Mockito mock returns an empty `List` for `latest(...)`. In each class's first test, assert `assertThat(view.recentActivity()).isEmpty();`.
- Add to `UnderwritingDashboardTest`:

```java
    /** The feed is the underwriting module's own, and a broken audit read costs the feed and nothing else. */
    @Test
    void readsUnderwritingsOwnActivityAndSurvivesItsFailure() {
        when(underwriting.listCases(any(), isNull(), isNull(), any())).thenReturn(total(0));
        when(underwriting.listCasesAwaitingIssue(isNull(), any())).thenReturn(total(0));
        when(activity.latest("underwriting.")).thenThrow(new IllegalStateException("audit is down"));

        UnderwritingDashboardView view = dashboard.build();

        assertThat(view.recentActivity()).isEmpty();
        assertThat(view.openCases()).isZero();
    }
```

- `DashboardContractTest`: every `new UnderwritingDashboardView(...)`, `new ClaimsDashboardView(...)` and `new FinanceDashboardView(...)` gains a last argument. In `anUnderwriterReadsTheUnderwritingDashboard`, pass a real item so the schema is exercised:

```java
            List.of(new tz.co.nlolo.lifeplatform.dashboard.api.ActivityItem("underwriting.UnderwritingDecisionMade",
                java.time.Instant.parse("2026-10-14T08:00:00Z"), java.util.Map.of("caseId", UUID.randomUUID().toString(),
                    "outcome", "ACCEPTED"), null))
```

and `List.of()` in the others.

- [ ] **Step 7: Extend the contract**

In `openapi-dashboard.yaml`, add under `components.schemas`:

```yaml
    ActivityItem:
      type: object
      additionalProperties: false
      description: One audit-log event -- what happened and when, never who.
      required: [eventType, occurredAt, refs, amount]
      properties:
        eventType: { type: string }
        occurredAt: { type: string, format: date-time }
        refs:
          type: object
          additionalProperties: { type: string }
        amount: { $ref: '#/components/schemas/Money' }
```

and to each of `UnderwritingDashboardView`, `ClaimsDashboardView` and `FinanceDashboardView`: add `recentActivity` to `required`, and under `properties`:

```yaml
        recentActivity: { type: array, items: { $ref: '#/components/schemas/ActivityItem' } }
```

- [ ] **Step 8: Run every dashboard test and the boundary check**

Run: `./mvnw -o -q clean test-compile && ./mvnw -o test -Dtest='RecentActivityTest,UnderwritingDashboardTest,ClaimsDashboardTest,ReportFiguresTest,FinanceDashboardTest,AgentDashboardTest,DashboardContractTest,ModularityTests' -DfailIfNoSpecifiedTests=false`
Expected: all eight classes `Failures: 0, Errors: 0`; `UnderwritingDashboardTest` now runs 3. The `clean` is required: three constructors changed, and an incremental compile can leave a stale test class that still passes (memory: Maven incremental compile hides test breaks). Stop the dev backend first.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard backend/api/openapi/openapi-dashboard.yaml backend/src/test/java/tz/co/nlolo/lifeplatform/dashboard
git commit -m "feat(dashboard): each staff dashboard shows its module's recent activity

From the audit log, newest first, system noise left out. What happened and
when, never who: the log records no actor, and the feed says so.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: The client, the store and the `KpiCard`

**Files:**
- Create: `frontend/src/api/dashboard.ts`
- Create: `frontend/src/store/dashboardStore.ts`
- Create: `frontend/src/components/KpiCard.tsx`
- Test: `frontend/src/components/KpiCard.test.tsx`

**Interfaces:**
- Consumes: `openapi-dashboard.yaml` (Tasks 2–5), generated into `frontend/src/types/api/dashboard.ts` by `npm run generate:api`.
- Produces (`api/dashboard.ts`): types `UnderwritingDashboard`, `ClaimsDashboard`, `FinanceDashboard`, `AgentDashboard`; functions `getUnderwritingDashboard()`, `getClaimsDashboard()`, `getFinanceDashboard()`, `getAgentDashboard()`, each `Promise<…>`.
- Produces (`store/dashboardStore.ts`): `type DashboardKind = 'underwriting' | 'claims' | 'finance' | 'agent'`; `useDashboardStore` with `load(kind)`; selectors `selectUnderwriting`, `selectClaims`, `selectFinance`, `selectAgent`, each returning a `Resource<…>`.
- Produces (`components/KpiCard.tsx`): `interface Kpi { label: string; value: string | null; hint: string; to?: string; delta?: { direction: 'up' | 'down'; text: string } }`; `KpiRow({ kpis }: { kpis: Kpi[] })`; `function countValue(n: number | null | undefined): string | null`.

- [ ] **Step 1: Generate the types**

Run (from `frontend/`): `npm run generate:api`
Expected: the output lists `openapi-dashboard.yaml -> src/types/api/dashboard.ts`. (`src/types/api/` is git-ignored; nothing to commit from this step.)

- [ ] **Step 2: Write the API client**

`frontend/src/api/dashboard.ts`:

```ts
import { get } from '@/lib/http';
import type { components } from '@/types/api/dashboard';

/**
 * The role dashboards (2026-10-14). One read per dashboard, composed server-side from the modules that own each
 * figure -- the browser never fans out to the underlying searches or adds anything up. A figure that could not be
 * read is null and renders "Not available"; it is never 0.
 */

export type UnderwritingDashboard = components['schemas']['UnderwritingDashboardView'];
export type ClaimsDashboard = components['schemas']['ClaimsDashboardView'];
export type FinanceDashboard = components['schemas']['FinanceDashboardView'];
export type AgentDashboard = components['schemas']['AgentDashboardView'];

export function getUnderwritingDashboard(): Promise<UnderwritingDashboard> {
  return get<UnderwritingDashboard>('/dashboards/underwriting');
}

export function getClaimsDashboard(): Promise<ClaimsDashboard> {
  return get<ClaimsDashboard>('/dashboards/claims');
}

export function getFinanceDashboard(): Promise<FinanceDashboard> {
  return get<FinanceDashboard>('/dashboards/finance');
}

export function getAgentDashboard(): Promise<AgentDashboard> {
  return get<AgentDashboard>('/dashboards/agent');
}
```

- [ ] **Step 3: Write the store**

`frontend/src/store/dashboardStore.ts`:

```ts
import { create } from 'zustand';
import {
  getAgentDashboard,
  getClaimsDashboard,
  getFinanceDashboard,
  getUnderwritingDashboard,
  type AgentDashboard,
  type ClaimsDashboard,
  type FinanceDashboard,
  type UnderwritingDashboard,
} from '@/api/dashboard';
import { idle, track, type Resource } from './createResourceSlice';

export type DashboardKind = 'underwriting' | 'claims' | 'finance' | 'agent';

/**
 * One slot per dashboard rather than one shared slot: a person holding two roles switches tabs, and the tab they
 * leave must not hand its figures to the tab they open while the next read is in flight.
 */
interface DashboardState {
  underwriting: Resource<UnderwritingDashboard>;
  claims: Resource<ClaimsDashboard>;
  finance: Resource<FinanceDashboard>;
  agent: Resource<AgentDashboard>;
  load: (kind: DashboardKind) => Promise<void>;
}

export const useDashboardStore = create<DashboardState>((set, getState) => ({
  underwriting: idle(),
  claims: idle(),
  finance: idle(),
  agent: idle(),

  load: (kind) => {
    switch (kind) {
      case 'underwriting':
        return track('dashboard.underwriting', getState().underwriting, (next) => set({ underwriting: next }),
          getUnderwritingDashboard);
      case 'claims':
        return track('dashboard.claims', getState().claims, (next) => set({ claims: next }), getClaimsDashboard);
      case 'finance':
        return track('dashboard.finance', getState().finance, (next) => set({ finance: next }), getFinanceDashboard);
      case 'agent':
        return track('dashboard.agent', getState().agent, (next) => set({ agent: next }), getAgentDashboard);
    }
  },
}));

export const selectUnderwriting = (s: DashboardState) => s.underwriting;
export const selectClaims = (s: DashboardState) => s.claims;
export const selectFinance = (s: DashboardState) => s.finance;
export const selectAgent = (s: DashboardState) => s.agent;
```

- [ ] **Step 4: Write the failing `KpiCard` test**

`frontend/src/components/KpiCard.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { KpiRow, countValue } from './KpiCard';

function renderRow(kpis: Parameters<typeof KpiRow>[0]['kpis']) {
  return render(
    <MemoryRouter>
      <KpiRow kpis={kpis} />
    </MemoryRouter>,
  );
}

describe('KpiCard', () => {
  /** The card is a link to exactly what it counted, and its name is the whole sentence, not a bare number. */
  it('links to its filtered source and is named by its sentence', () => {
    renderRow([{ label: 'Open cases', value: '238', hint: 'awaiting first review', to: '/staff/underwriting?status=OPEN' }]);

    const link = screen.getByRole('link', { name: '238 Open cases, awaiting first review. Open the list.' });
    expect(link).toHaveAttribute('href', '/staff/underwriting?status=OPEN');
  });

  /** Nothing honest to open: no link at all, rather than a link to an unfiltered list showing more than the card says. */
  it('is not a link when it has no destination', () => {
    renderRow([{ label: 'Journals awaiting approval', value: '4', hint: 'submitted' }]);

    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.getByText('4')).toBeInTheDocument();
  });

  /** Could not be read: "Not available", never 0, and not a link to a list that would contradict it. */
  it('says a figure is not available rather than showing zero', () => {
    renderRow([{ label: 'Open cases', value: null, hint: 'awaiting first review', to: '/staff/underwriting?status=OPEN' }]);

    expect(screen.getByText('Not available')).toBeInTheDocument();
    expect(screen.queryByText('0')).not.toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  /** A direction is not a verdict: the delta has no status colour. */
  it('draws a delta in ink', () => {
    renderRow([{ label: 'Premiums collected, Sep', value: 'TZS 50,204,310.00', hint: 'last complete month',
      delta: { direction: 'up', text: '2.2% vs Aug' } }]);

    const delta = screen.getByText('2.2% vs Aug');
    expect(delta.closest('[data-delta]')?.className).not.toMatch(/status-(success|danger)/);
  });

  /** A figure never wraps: "50,204,31 / 0" reads as two numbers. */
  it('never lets a figure wrap', () => {
    renderRow([{ label: 'Total assets', value: 'TZS 742,580,120.00', hint: 'balance sheet' }]);

    expect(screen.getByText('TZS 742,580,120.00').className).toMatch(/whitespace-nowrap/);
  });

  it('formats a count, and keeps null as null', () => {
    expect(countValue(1234)).toBe('1,234');
    expect(countValue(0)).toBe('0');
    expect(countValue(null)).toBeNull();
    expect(countValue(undefined)).toBeNull();
  });
});
```

- [ ] **Step 5: Run it to verify it fails**

Run (from `frontend/`): `npx vitest run src/components/KpiCard.test.tsx`
Expected: FAIL — `Failed to resolve import "./KpiCard"`.

- [ ] **Step 6: Implement `KpiCard`**

`frontend/src/components/KpiCard.tsx`:

```tsx
import { ArrowDown, ArrowUp } from 'lucide-react';
import { Link } from 'react-router-dom';
import { cn } from '@/lib/cn';

/**
 * One dashboard figure (2026-10-14).
 *
 * <b>A link to exactly what it counted, or not a link.</b> `to` is the list or report the figure was read from,
 * already filtered, so the rows a person lands on are the rows the number promised. A figure whose destination cannot
 * reproduce its filter has no `to`, and is drawn as a card that is not clickable.
 *
 * <b>Never wraps.</b> The figure is `whitespace-nowrap`, and the card is a size container: it is set in the Display
 * tier when the card is wide enough and steps down to the Headline tier when it is not. Both are tiers already on the
 * page (the stat figure and the page title), so stepping down adds no third size (DESIGN.md, the Two-Peaks Rule).
 *
 * <b>A delta is ink.</b> Up is a direction, not a verdict (the Stamp Rule).
 */
export interface Kpi {
  label: string;
  /** Already formatted -- a count from `countValue`, or money from `formatMoney`. Null = could not be read. */
  value: string | null;
  hint: string;
  to?: string;
  delta?: { direction: 'up' | 'down'; text: string };
}

const COUNT = new Intl.NumberFormat('en-GB');

/** A count for display. Null and undefined stay null: an absent count is "Not available", never "0". */
export function countValue(n: number | null | undefined): string | null {
  return n === null || n === undefined ? null : COUNT.format(n);
}

function Body({ kpi }: { kpi: Kpi }) {
  return (
    <>
      <span className="block text-xs font-medium text-muted-foreground">{kpi.label}</span>
      {kpi.value === null ? (
        // The dash is decoration; the words below it say what it means, once, to everyone.
        <span className="mt-1 block text-headline text-subtle-foreground" aria-hidden>—</span>
      ) : (
        <span className="mt-1 block whitespace-nowrap text-headline @[20rem]:text-display">{kpi.value}</span>
      )}
      {kpi.delta && kpi.value !== null && (
        <span data-delta className="mt-0.5 flex items-center gap-1 text-xs text-foreground">
          {kpi.delta.direction === 'up' ? <ArrowUp className="size-3" aria-hidden /> : <ArrowDown className="size-3" aria-hidden />}
          <span>{kpi.delta.text}</span>
        </span>
      )}
      <span className="mt-0.5 block text-xs text-subtle-foreground">{kpi.value === null ? 'Not available' : kpi.hint}</span>
    </>
  );
}

function KpiCard({ kpi }: { kpi: Kpi }) {
  const frame = '@container block min-w-0 rounded-lg border border-border bg-surface px-4 py-3';
  // A failed figure is never a link: it would open a list whose count contradicts the "Not available" on the card.
  if (!kpi.to || kpi.value === null) {
    return <div className={frame}><Body kpi={kpi} /></div>;
  }
  return (
    <Link
      to={kpi.to}
      aria-label={`${kpi.value} ${kpi.label}, ${kpi.hint}. Open the list.`}
      className={cn(
        frame,
        'transition-colors hover:border-border-strong hover:bg-hover active:bg-selected active:duration-0',
      )}
    >
      <Body kpi={kpi} />
    </Link>
  );
}

/**
 * The row. `auto-fill` with a 16rem floor lays out only as many columns as can hold a full TZS amount, so a narrow
 * screen gets fewer cards across rather than squeezed figures.
 */
export function KpiRow({ kpis }: { kpis: Kpi[] }) {
  return (
    <div className="grid grid-cols-[repeat(auto-fill,minmax(16rem,1fr))] gap-3">
      {kpis.map((kpi) => (
        <KpiCard key={kpi.label} kpi={kpi} />
      ))}
    </div>
  );
}
```

`text-headline`, `text-display`, `text-subtle-foreground`, `border-border-strong`, `bg-hover`, `bg-selected`, `bg-surface` are all existing tokens and tiers (`index.css`). `@container` and `@[20rem]:` are Tailwind v4 built-ins.

- [ ] **Step 7: Run the test, the type check, lint and the design guards**

Run (from `frontend/`): `npx vitest run src/components/KpiCard.test.tsx src/test/designGuards.test.ts && npm run typecheck && npm run lint`
Expected: `KpiCard.test.tsx` 6 passed, `designGuards.test.ts` all passed, typecheck and lint clean.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/api/dashboard.ts frontend/src/store/dashboardStore.ts frontend/src/components/KpiCard.tsx frontend/src/components/KpiCard.test.tsx
git commit -m "feat(dashboard): the dashboard client, store and a figure card that links to its source

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: The dashboard page, routing, and retiring "Today"

**Files:**
- Create: `frontend/src/features/dashboard/dashboards.ts`
- Create: `frontend/src/features/dashboard/DashboardPage.tsx`
- Create: `frontend/src/features/dashboard/DashboardRail.tsx`
- Test: `frontend/src/features/dashboard/dashboards.test.ts`
- Modify: `frontend/src/screens.tsx`
- Modify: `frontend/src/screens.test.ts`
- Delete: `frontend/src/features/home/TodayPage.tsx`, `frontend/src/features/home/TodayPage.test.tsx`
- Modify: `frontend/src/navBadges.ts` (prune)

**Interfaces:**
- Consumes: `DashboardKind` (Task 6), `staffRoles`, `readIdentity` (`@/auth/claims`), `RecordTabs`, `PageHeader`.
- Produces: `dashboardsFor(realm: Realm, identity: TokenIdentity): DashboardKind[]`; `DashboardPage({ realm }: { realm: 'staff' | 'agents' })`; `DashboardRail({ alerts, actions, absent }: { alerts: Alert[]; actions: QuickAction[]; absent: string[] })` with `interface Alert { tone: 'warning' | 'danger' | 'calm'; text: ReactNode }` and `interface QuickAction { label: string; description: string; to: string }`. The panels of Tasks 8–11 are imported by `DashboardPage` under the names `UnderwritingDashboardPanel`, `ClaimsDashboardPanel`, `FinanceDashboardPanel`, `AgentDashboardPanel`, each taking no props.

- [ ] **Step 1: Write the failing test for who gets which dashboard**

`frontend/src/features/dashboard/dashboards.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import type { readIdentity } from '@/auth/claims';
import { dashboardsFor } from './dashboards';

const as = (...roles: string[]) => ({ roles }) as unknown as ReturnType<typeof readIdentity>;

/**
 * Each tab mirrors its endpoint's own @PreAuthorize exactly. A tab the endpoint refuses would be a 403 dressed as a
 * page; a tab missing for a role the endpoint admits would hide a dashboard somebody is entitled to.
 */
describe('dashboardsFor', () => {
  it('gives each staff role its own dashboard, in the business order', () => {
    expect(dashboardsFor('staff', as('REALM_STAFF', 'UNDERWRITER'))).toEqual(['underwriting']);
    expect(dashboardsFor('staff', as('REALM_STAFF', 'CLAIMS_ASSESSOR'))).toEqual(['claims']);
    expect(dashboardsFor('staff', as('REALM_STAFF', 'CLAIMS_MANAGER'))).toEqual(['claims']);
    expect(dashboardsFor('staff', as('REALM_STAFF', 'FINANCE_OFFICER'))).toEqual(['finance']);
    expect(dashboardsFor('staff', as('REALM_STAFF', 'ADMIN'))).toEqual(['finance']);
    expect(dashboardsFor('staff', as('REALM_STAFF', 'UNDERWRITER', 'FINANCE_OFFICER', 'CLAIMS_MANAGER')))
      .toEqual(['underwriting', 'claims', 'finance']);
  });

  /** A customer service rep holds none of these roles, and is given no empty dashboard. */
  it('gives a role with nothing to show no dashboard', () => {
    expect(dashboardsFor('staff', as('REALM_STAFF', 'CUSTOMER_SERVICE_REP'))).toEqual([]);
  });

  it('gives every agent the agent dashboard', () => {
    expect(dashboardsFor('agents', as('REALM_AGENTS'))).toEqual(['agent']);
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npx vitest run src/features/dashboard/dashboards.test.ts`
Expected: FAIL — `Failed to resolve import "./dashboards"`.

- [ ] **Step 3: Implement `dashboards.ts`**

```ts
import { staffRoles, type readIdentity } from '@/auth/claims';
import type { Realm } from '@/auth/realms';
import type { DashboardKind } from '@/store/dashboardStore';

/**
 * Which dashboards this identity gets, in the business order the sidebar follows (new business, claims, finance).
 *
 * Each condition is the SAME expression as its endpoint's @PreAuthorize in DashboardController:
 *   underwriting  hasRole('UNDERWRITER')
 *   claims        hasRole('CLAIMS_ASSESSOR') or hasRole('CLAIMS_MANAGER')
 *   finance       hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))
 *   agent         hasRole('REALM_AGENTS')
 * Change one and the other must change with it.
 */
export function dashboardsFor(realm: Realm, identity: ReturnType<typeof readIdentity>): DashboardKind[] {
  if (realm === 'agents') return ['agent'];
  if (realm !== 'staff') return [];
  const roles = staffRoles(identity);
  const kinds: DashboardKind[] = [];
  if (roles.UNDERWRITER) kinds.push('underwriting');
  if (roles.CLAIMS_ASSESSOR || roles.CLAIMS_MANAGER) kinds.push('claims');
  if (roles.FINANCE_OFFICER || roles.ADMIN) kinds.push('finance');
  return kinds;
}

export const DASHBOARD_LABEL: Record<DashboardKind, string> = {
  underwriting: 'Underwriting',
  claims: 'Claims',
  finance: 'Finance',
  agent: 'My business',
};
```

Run the test again: `npx vitest run src/features/dashboard/dashboards.test.ts` — Expected: 3 passed.

- [ ] **Step 4: Write `DashboardRail.tsx`**

```tsx
import { ChevronRight, CircleAlert, Info } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Panel } from '@/components/Panel';
import { cn } from '@/lib/cn';

/**
 * The dashboard's right-hand column: what needs attention, what this role can do, and what is deliberately missing.
 *
 * Not a record rail. DESIGN.md's Record-Rail Rule keeps ACTS out of the 320px rail on a record page, because a
 * summary column is read as facts and an act read as a fact is taken without reading. These quick actions are links
 * into the screens that own each act -- every one of which has its own confirmation -- not acts in their own right.
 */
export interface Alert {
  tone: 'warning' | 'danger' | 'calm';
  text: ReactNode;
}

export interface QuickAction {
  label: string;
  description: string;
  to: string;
}

const TONE: Record<Alert['tone'], string> = {
  warning: 'bg-status-warning-bg text-status-warning-fg',
  danger: 'bg-status-danger-bg text-status-danger-fg',
  calm: 'border border-border bg-surface-muted text-muted-foreground',
};

export function DashboardRail({ alerts, actions, absent }: { alerts: Alert[]; actions: QuickAction[]; absent: string[] }) {
  return (
    <div className="space-y-5">
      {alerts.length > 0 && (
        <Panel title="Needs attention">
          <ul className="space-y-2 px-4 pb-4">
            {alerts.map((alert, i) => (
              <li key={i} className={cn('flex gap-2.5 rounded-md px-3 py-2.5 text-sm', TONE[alert.tone])}>
                {alert.tone === 'calm' ? <Info className="mt-0.5 size-4 shrink-0" aria-hidden />
                  : <CircleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />}
                <span>{alert.text}</span>
              </li>
            ))}
          </ul>
        </Panel>
      )}
      <Panel title="Quick actions" subtitle="Only what your role can do.">
        <ul className="px-2 pb-2">
          {actions.map((action) => (
            <li key={action.to}>
              <Link
                to={action.to}
                className="flex items-center gap-3 rounded-md px-2 py-2 transition-colors hover:bg-hover active:bg-selected active:duration-0"
              >
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-medium">{action.label}</span>
                  <span className="block text-xs text-muted-foreground">{action.description}</span>
                </span>
                <ChevronRight className="size-4 shrink-0 text-subtle-foreground" aria-hidden />
              </Link>
            </li>
          ))}
        </ul>
      </Panel>
      {absent.length > 0 && (
        <Panel title="Not here yet">
          <div className="px-4 pb-4 text-xs text-muted-foreground">
            <p>Left out on purpose — the platform has no concept behind them yet:</p>
            <ul className="mt-1.5 list-disc space-y-1 pl-4">
              {absent.map((item) => <li key={item}>{item}</li>)}
            </ul>
          </div>
        </Panel>
      )}
    </div>
  );
}
```

- [ ] **Step 5: Write `DashboardPage.tsx`**

The page imports the four panels created in Tasks 8–11. Until those tasks land, create each panel file as a minimal stub so this task compiles on its own:

```tsx
// frontend/src/features/dashboard/UnderwritingDashboardPanel.tsx  (replaced in Task 8)
export function UnderwritingDashboardPanel() {
  return null;
}
```

Create the same one-function stub for `ClaimsDashboardPanel.tsx` (`ClaimsDashboardPanel`, replaced in Task 9), `FinanceDashboardPanel.tsx` (`FinanceDashboardPanel`, replaced in Task 10) and `AgentDashboardPanel.tsx` (`AgentDashboardPanel`, replaced in Task 11).

`frontend/src/features/dashboard/DashboardPage.tsx`:

```tsx
import type { ReactElement } from 'react';
import { useAuth } from 'react-oidc-context';
import { readIdentity } from '@/auth/claims';
import { PageHeader } from '@/components/PageHeader';
import { RecordTabs, type TabDef } from '@/components/RecordTabs';
import { EmptyState } from '@/components/states';
import type { DashboardKind } from '@/store/dashboardStore';
import { AgentDashboardPanel } from './AgentDashboardPanel';
import { ClaimsDashboardPanel } from './ClaimsDashboardPanel';
import { DASHBOARD_LABEL, dashboardsFor } from './dashboards';
import { FinanceDashboardPanel } from './FinanceDashboardPanel';
import { UnderwritingDashboardPanel } from './UnderwritingDashboardPanel';

const PANEL: Record<DashboardKind, () => ReactElement | null> = {
  underwriting: UnderwritingDashboardPanel,
  claims: ClaimsDashboardPanel,
  finance: FinanceDashboardPanel,
  agent: AgentDashboardPanel,
};

/**
 * Where a working role lands (2026-10-14). Replaces "Today": its work-waiting counts are now each dashboard's work
 * panel, so there is one landing page, not two.
 *
 * One tab per dashboard the person holds. Tabs, not a stack: a person holding underwriting and finance does two
 * separate jobs and reads one at a time, and an inactive tab is not mounted, so it costs no request (RecordTabs).
 * The tab lives in the URL, so a link can open on the right one.
 */
export function DashboardPage({ realm }: { realm: 'staff' | 'agents' }) {
  const auth = useAuth();
  const kinds = dashboardsFor(realm, readIdentity(auth.user?.access_token));

  const tabs: TabDef[] = kinds.map((kind) => {
    const Panel = PANEL[kind];
    return { value: kind, label: DASHBOARD_LABEL[kind], content: <Panel /> };
  });

  return (
    <>
      <PageHeader title="Dashboard" description="What is waiting for you, and the figures behind your work." />
      <div className="px-6 pt-1 pb-8">
        {kinds.length === 0 ? (
          <EmptyState title="No dashboard for your role" description="None of your roles has figures to show here yet." />
        ) : kinds.length === 1 ? (
          tabs[0]?.content
        ) : (
          <RecordTabs tabs={tabs} label="Dashboards" param="dashboard" />
        )}
      </div>
    </>
  );
}
```

- [ ] **Step 6: Route it, retire Today, move the agent landing**

In `frontend/src/screens.tsx`:

1. Replace the `today` entry in `STAFF_SCREENS`:

```tsx
  { path: 'dashboard', element: <DashboardPage realm="staff" />, reach: { group: 'work', label: 'Dashboard', icon: LayoutDashboard } },
  // Retired 2026-10-14 for the dashboard. Kept as a redirect, with its query, for bookmarks and the old landing URL.
  { path: 'today', element: <RedirectPreservingQuery to="../dashboard" />, reach: 'drill-in' },
```

2. In `homeFor`, replace `return 'today';` with `return 'dashboard';` and update the comment above it to read: "Anyone with a dashboard lands on it (2026-10-14, replacing Today)." Change the condition so it reads the dashboards rather than the badges:

```tsx
  if (dashboardsFor('staff', identity).length > 0) return 'dashboard';
```

3. Set `REALM_HOME.agents` to `'dashboard'`, and add as the FIRST entry of the agents screens (before `me`):

```tsx
  { path: 'dashboard', element: <DashboardPage realm="agents" />, reach: { group: 'my-business', label: 'Dashboard', icon: LayoutDashboard } },
```

4. Imports: `DashboardPage` from `@/features/dashboard/DashboardPage`, `dashboardsFor` from `@/features/dashboard/dashboards`, `LayoutDashboard` from `lucide-react`. Remove the `TodayPage` import and the `Inbox` icon import if nothing else uses it (`grep -n "Inbox" frontend/src/screens.tsx`).

Every destination needs its own icon (a tested rule): `LayoutDashboard` must not already be used by another screen — `grep -n "LayoutDashboard" frontend/src/screens.tsx` before adding.

- [ ] **Step 7: Delete Today and prune what only it used**

```bash
git rm frontend/src/features/home/TodayPage.tsx frontend/src/features/home/TodayPage.test.tsx
grep -rn "useWorkWaiting\|WorkWaiting\|BADGE_TARGETS" frontend/src
```

Delete from `frontend/src/navBadges.ts` every export the grep shows no remaining caller for (expected: `useWorkWaiting`, the `WorkWaiting` interface, and `BADGE_TARGETS`). Keep `useNavBadges` and `LOADERS` — the sidebar badges still use them.

- [ ] **Step 8: Update the routing tests**

In `frontend/src/screens.test.ts`, change the `homeFor` expectations from `'today'` to `'dashboard'`, and the agents expectation to `'dashboard'`. Run:

`npx vitest run src/screens.test.ts src/features/dashboard src/test/designGuards.test.ts && npm run typecheck && npm run lint`
Expected: all passed; typecheck and lint clean. If `screens.test.ts` has a test asserting a role with badges lands on `'today'` for reasons of badges, rewrite it to assert the dashboard rule above — the landing is now "has a dashboard", not "has a badge".

- [ ] **Step 9: Commit**

```bash
git add frontend/src/features/dashboard frontend/src/screens.tsx frontend/src/screens.test.ts frontend/src/navBadges.ts
git commit -m "feat(dashboard): the dashboard page replaces Today, and agents land on it

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: The underwriter's panel

**Files:**
- Modify (replace the stub): `frontend/src/features/dashboard/UnderwritingDashboardPanel.tsx`
- Test: `frontend/src/features/dashboard/UnderwritingDashboardPanel.test.tsx`

**Interfaces:**
- Consumes: `useDashboardStore`, `selectUnderwriting` (Task 6); `KpiRow`, `countValue` (Task 6); `DashboardRail` (Task 7); `PartyName`, `ProductName`, `StatusBadge`, `Panel`, `ErrorPanel`, `LoadingBlock`; `formatMoney` from `@/lib/money`.

- [ ] **Step 1: Write the failing test**

```tsx
import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getUnderwritingDashboard } from '@/api/dashboard';
import { useDashboardStore } from '@/store/dashboardStore';
import { renderScreen } from '@/test/renderScreen';
import { UnderwritingDashboardPanel } from './UnderwritingDashboardPanel';

vi.mock('@/api/dashboard');
vi.mock('@/components/PartyName', () => ({ PartyName: ({ partyId }: { partyId: string }) => <span>{partyId}</span> }));
vi.mock('@/components/ProductName', () => ({ ProductName: () => <span>Nuru-Mpya</span> }));

const VIEW = {
  openCases: 238,
  casesInReview: 41,
  casesAwaitingIssue: null,
  queue: [{ caseId: 'case-1', applicantPartyId: 'party-1', lifeAssuredPartyId: null, productId: 'prod-1',
    sumAssured: { amount: '25000000.00', currencyCode: 'TZS' }, status: 'OPEN' as const }],
};

beforeEach(() => {
  useDashboardStore.setState({ underwriting: { status: 'idle', data: null, error: null, loadedAt: null } } as never);
  vi.mocked(getUnderwritingDashboard).mockResolvedValue(VIEW);
});

describe('UnderwritingDashboardPanel', () => {
  it('links each count to its own filtered queue', async () => {
    renderScreen(<UnderwritingDashboardPanel />);

    expect(await screen.findByRole('link', { name: /^238 Open cases/ }))
      .toHaveAttribute('href', '/staff/underwriting?status=OPEN');
    expect(screen.getByRole('link', { name: /^41 In review/ }))
      .toHaveAttribute('href', '/staff/underwriting?status=IN_REVIEW');
  });

  /** Could not be read: Not available, and no link to a list that would contradict it. */
  it('shows a figure it could not read as not available', async () => {
    renderScreen(<UnderwritingDashboardPanel />);

    await screen.findByRole('link', { name: /^238 Open cases/ });
    expect(screen.getAllByText('Not available').length).toBeGreaterThan(0);
  });

  it('lists the oldest open cases with a way into each', async () => {
    renderScreen(<UnderwritingDashboardPanel />);

    expect(await screen.findByText('party-1')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Review case-1' })).toHaveAttribute('href', '/staff/underwriting/case-1');
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npx vitest run src/features/dashboard/UnderwritingDashboardPanel.test.tsx`
Expected: FAIL — no link named "238 Open cases" (the stub renders nothing).

- [ ] **Step 3: Implement**

```tsx
import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { KpiRow, countValue, type Kpi } from '@/components/KpiCard';
import { Panel } from '@/components/Panel';
import { PartyName } from '@/components/PartyName';
import { ProductName } from '@/components/ProductName';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectUnderwriting, useDashboardStore } from '@/store/dashboardStore';
import { DashboardRail } from './DashboardRail';

/** The underwriter's dashboard (2026-10-14). Counts link to their queue; "awaiting issue" has no list that shows exactly it. */
export function UnderwritingDashboardPanel() {
  const resource = useDashboardStore(selectUnderwriting);
  const load = useDashboardStore((s) => s.load);

  useEffect(() => {
    void load('underwriting');
  }, [load]);

  if (isInitialLoad(resource)) return <LoadingBlock label="Counting cases" />;
  if (resource.status === 'error' && resource.error && resource.data === null) {
    return <ErrorPanel error={resource.error} onRetry={() => void load('underwriting')} />;
  }
  const view = resource.data;
  if (!view) return null;

  const kpis: Kpi[] = [
    { label: 'Open cases', value: countValue(view.openCases), hint: 'awaiting first review', to: '/staff/underwriting?status=OPEN' },
    { label: 'In review', value: countValue(view.casesInReview), hint: 'assessed, not yet decided', to: '/staff/underwriting?status=IN_REVIEW' },
    { label: 'Accepted, awaiting issue', value: countValue(view.casesAwaitingIssue), hint: 'decided, no policy yet' },
  ];
  const next = view.queue[0];

  return (
    <div className="space-y-5">
      <KpiRow kpis={kpis} />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <Panel emphasis title="Next in your queue" subtitle="Open cases that have waited longest, oldest first.">
          {view.queue.length === 0 ? (
            <p className="px-4 pb-4 text-xs text-muted-foreground">No open cases.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-band text-left text-xs text-muted-foreground">
                  <tr>
                    <th className="px-4 py-2.5 font-medium">Applicant</th>
                    <th className="px-4 py-2.5 font-medium">Product</th>
                    <th className="px-4 py-2.5 text-right font-medium">Sum assured</th>
                    <th className="px-4 py-2.5 font-medium">Status</th>
                    <th className="px-4 py-2.5"><span className="sr-only">Open</span></th>
                  </tr>
                </thead>
                <tbody>
                  {view.queue.map((c) => (
                    <tr key={c.caseId} className="h-11 border-t border-border first:border-t-0">
                      <td className="px-4"><PartyName partyId={c.applicantPartyId} /></td>
                      <td className="px-4">{c.productId ? <ProductName productId={c.productId} /> : '—'}</td>
                      <td className="px-4 text-right whitespace-nowrap">{c.sumAssured ? formatMoney(c.sumAssured) : '—'}</td>
                      <td className="px-4"><StatusBadge kind="underwritingCase" value={c.status} /></td>
                      <td className="px-4 text-right">
                        <Link to={`/staff/underwriting/${c.caseId}`} aria-label={`Review ${c.caseId}`}
                          className="rounded-md px-2 py-1 text-sm font-medium hover:bg-hover">Review</Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Panel>
        <DashboardRail
          alerts={view.casesAwaitingIssue ? [{ tone: 'warning', text: <><b>{countValue(view.casesAwaitingIssue)} accepted cases</b> have no policy yet. Issuing is the next step.</> }] : []}
          actions={[
            ...(next ? [{ label: 'Review the next case', description: 'The open case that has waited longest', to: `/staff/underwriting/${next.caseId}` }] : []),
            { label: 'Open a case', description: 'For a registered client', to: '/staff/underwriting/new' },
            { label: 'Issue a policy', description: 'From an accepted case', to: '/staff/policies/new' },
            { label: 'All open cases', description: 'The full queue', to: '/staff/underwriting?status=OPEN' },
          ]}
          absent={['SLA deadlines on cases', 'Accepted vs declined, and turnaround — phase 2', 'A risk-level field to filter by']}
        />
      </div>
    </div>
  );
}
```

`PartyName` takes `partyId`, `ProductName` takes `productId`, and `StatusBadge` takes `kind` and `value`; `kind="underwritingCase"` is the one `UnderwritingCaseDetailPage` already uses.

- [ ] **Step 4: Run the test and checks**

Run: `npx vitest run src/features/dashboard/UnderwritingDashboardPanel.test.tsx src/test/designGuards.test.ts && npm run typecheck && npm run lint`
Expected: 3 passed; guards, typecheck and lint clean.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/dashboard/UnderwritingDashboardPanel.tsx frontend/src/features/dashboard/UnderwritingDashboardPanel.test.tsx
git commit -m "feat(dashboard): the underwriter's panel

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: The claims panel

**Files:**
- Modify (replace the stub): `frontend/src/features/dashboard/ClaimsDashboardPanel.tsx`
- Test: `frontend/src/features/dashboard/ClaimsDashboardPanel.test.tsx`

**Interfaces:**
- Consumes: `selectClaims`, `KpiRow`, `countValue`, `DashboardRail`, `PartyName`, `StatusBadge`, `formatDate` (`@/lib/dates`).

- [ ] **Step 1: Write the failing test**

```tsx
import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getClaimsDashboard } from '@/api/dashboard';
import { useDashboardStore } from '@/store/dashboardStore';
import { renderScreen } from '@/test/renderScreen';
import { ClaimsDashboardPanel } from './ClaimsDashboardPanel';

vi.mock('@/api/dashboard');
vi.mock('@/components/PartyName', () => ({ PartyName: ({ partyId }: { partyId: string }) => <span>{partyId}</span> }));

const VIEW = {
  registered: 17, underAssessment: 9, settlementRequested: 6, approved: 4, reopened: 2, settled: 128,
  queueStatus: 'SETTLEMENT_REQUESTED' as const,
  queue: [{ claimId: 'claim-1', policyNumber: 'POL-00A4B7A1', claimantPartyId: 'party-1', claimType: 'DEATH' as const,
    dateOfEvent: '2026-09-29', status: 'SETTLEMENT_REQUESTED' as const }],
};

beforeEach(() => {
  useDashboardStore.setState({ claims: { status: 'idle', data: null, error: null, loadedAt: null } } as never);
  vi.mocked(getClaimsDashboard).mockResolvedValue(VIEW);
});

describe('ClaimsDashboardPanel', () => {
  it('links every stage to the claims list filtered to it', async () => {
    renderScreen(<ClaimsDashboardPanel />);

    expect(await screen.findByRole('link', { name: /^6 Awaiting settlement decision/ }))
      .toHaveAttribute('href', '/staff/claims?status=SETTLEMENT_REQUESTED');
    expect(screen.getByRole('link', { name: /^17 Awaiting assessment/ }))
      .toHaveAttribute('href', '/staff/claims?status=REGISTERED');
    expect(screen.getByRole('link', { name: /^4 Approved, not yet paid/ }))
      .toHaveAttribute('href', '/staff/claims?status=APPROVED');
    // All time: the card must say so, because there is no date filter to make it "this month".
    expect(screen.getByRole('link', { name: /^128 Settled, all time/ })).toBeInTheDocument();
  });

  /** A manager's queue is titled as the settlement decisions it is. */
  it('titles the queue by whose work it is', async () => {
    renderScreen(<ClaimsDashboardPanel />);

    expect(await screen.findByRole('heading', { name: 'Awaiting your settlement decision' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open claim-1' })).toHaveAttribute('href', '/staff/claims/claim-1');
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npx vitest run src/features/dashboard/ClaimsDashboardPanel.test.tsx`
Expected: FAIL — no link named "6 Awaiting settlement decision".

- [ ] **Step 3: Implement**

```tsx
import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { KpiRow, countValue, type Kpi } from '@/components/KpiCard';
import { Panel } from '@/components/Panel';
import { PartyName } from '@/components/PartyName';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { formatDate } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectClaims, useDashboardStore } from '@/store/dashboardStore';
import { DashboardRail } from './DashboardRail';

const CLAIM_TYPE: Record<string, string> = {
  DEATH: 'Death', DISABILITY: 'Disability', CRITICAL_ILLNESS: 'Critical illness', MATURITY: 'Maturity',
};

/** The claims dashboard (2026-10-14). Every stage is a count of one status; the queue is the reader's own work. */
export function ClaimsDashboardPanel() {
  const resource = useDashboardStore(selectClaims);
  const load = useDashboardStore((s) => s.load);

  useEffect(() => {
    void load('claims');
  }, [load]);

  if (isInitialLoad(resource)) return <LoadingBlock label="Counting claims" />;
  if (resource.status === 'error' && resource.error && resource.data === null) {
    return <ErrorPanel error={resource.error} onRetry={() => void load('claims')} />;
  }
  const view = resource.data;
  if (!view) return null;

  const manager = view.queueStatus === 'SETTLEMENT_REQUESTED';
  const to = (status: string) => `/staff/claims?status=${status}`;
  const kpis: Kpi[] = [
    { label: 'Awaiting settlement decision', value: countValue(view.settlementRequested), hint: 'assessed, recommended', to: to('SETTLEMENT_REQUESTED') },
    { label: 'Awaiting assessment', value: countValue(view.registered), hint: 'registered, not yet assessed', to: to('REGISTERED') },
    { label: 'Approved, not yet paid', value: countValue(view.approved), hint: 'payout not made', to: to('APPROVED') },
    { label: 'Settled, all time', value: countValue(view.settled), hint: 'no date filter yet', to: to('SETTLED') },
  ];
  const next = view.queue[0];

  return (
    <div className="space-y-5">
      <KpiRow kpis={kpis} />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <div className="space-y-5">
          <Panel
            emphasis
            title={manager ? 'Awaiting your settlement decision' : 'Awaiting assessment'}
            subtitle={manager
              ? 'Oldest first. Approving pays; you cannot decide a claim you assessed.'
              : 'Oldest first. Registered claims nobody has assessed yet.'}
          >
            {view.queue.length === 0 ? (
              <p className="px-4 pb-4 text-xs text-muted-foreground">Nothing waiting.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-band text-left text-xs text-muted-foreground">
                    <tr>
                      <th className="px-4 py-2.5 font-medium">Claim</th>
                      <th className="px-4 py-2.5 font-medium">Policy</th>
                      <th className="px-4 py-2.5 font-medium">Claimant</th>
                      <th className="px-4 py-2.5 font-medium">Status</th>
                      <th className="px-4 py-2.5"><span className="sr-only">Open</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    {view.queue.map((c) => (
                      <tr key={c.claimId} className="h-11 border-t border-border first:border-t-0">
                        <td className="px-4">
                          {CLAIM_TYPE[c.claimType] ?? c.claimType}
                          {c.dateOfEvent && <span className="block text-xs text-muted-foreground">event {formatDate(c.dateOfEvent)}</span>}
                        </td>
                        <td className="px-4 font-mono text-xs">{c.policyNumber}</td>
                        <td className="px-4"><PartyName partyId={c.claimantPartyId} /></td>
                        <td className="px-4"><StatusBadge kind="claim" value={c.status} /></td>
                        <td className="px-4 text-right">
                          <Link to={`/staff/claims/${c.claimId}`} aria-label={`Open ${c.claimId}`}
                            className="rounded-md px-2 py-1 text-sm font-medium hover:bg-hover">{manager ? 'Decide' : 'Assess'}</Link>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
          <Panel title="Where claims are" subtitle="Every open claim, by its stage.">
            <ul className="divide-y divide-border">
              {[
                ['registered, awaiting an assessor', view.registered, 'REGISTERED'],
                ['under assessment', view.underAssessment, 'UNDER_ASSESSMENT'],
                ['awaiting a settlement decision', view.settlementRequested, 'SETTLEMENT_REQUESTED'],
                ['approved, payout not made', view.approved, 'APPROVED'],
                ['reopened', view.reopened, 'REOPENED'],
              ].map(([text, n, status]) => (
                <li key={status as string} className="flex items-center gap-4 px-4 py-2.5">
                  <span className="w-12 shrink-0 text-right text-title">{countValue(n as number | null) ?? '—'}</span>
                  <span className="min-w-0 flex-1 text-sm">{text as string}</span>
                  {n !== null && (
                    <Link to={to(status as string)} className="rounded-md px-2 py-1 text-sm font-medium hover:bg-hover"
                      aria-label={`Open the ${text as string} list`}>Open</Link>
                  )}
                </li>
              ))}
            </ul>
          </Panel>
        </div>
        <DashboardRail
          alerts={view.approved ? [{ tone: 'warning', text: <><b>{countValue(view.approved)} approved claims</b> have not been paid.</> }] : []}
          actions={[
            { label: 'Register a claim', description: 'Identify the claimant, then the policy', to: '/staff/claims/new' },
            ...(next ? [{ label: manager ? 'Decide the next settlement' : 'Assess the next claim', description: 'The one that has waited longest', to: `/staff/claims/${next.claimId}` }] : []),
            { label: 'Unassessed claims', description: 'Registered, no assessor yet', to: '/staff/claims?status=REGISTERED' },
            { label: 'Approved, not yet paid', description: 'Payouts still to make', to: '/staff/claims?status=APPROVED' },
          ]}
          absent={['SLA deadlines on claims', 'Assigning a claim to an assessor', 'Benefit amounts paid — phase 2']}
        />
      </div>
    </div>
  );
}
```

The "Where claims are" title uses the Title tier (`text-title`) for the count; it is the same tier as the panel headings, so it adds no new size.

- [ ] **Step 4: Run the test and checks**

Run: `npx vitest run src/features/dashboard/ClaimsDashboardPanel.test.tsx src/test/designGuards.test.ts && npm run typecheck && npm run lint`
Expected: 2 passed; guards, typecheck and lint clean.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/dashboard/ClaimsDashboardPanel.tsx frontend/src/features/dashboard/ClaimsDashboardPanel.test.tsx
git commit -m "feat(dashboard): the claims panel

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: The finance panel and its chart

**Files:**
- Create: `frontend/src/features/dashboard/PremiumsChart.tsx`
- Test: `frontend/src/features/dashboard/PremiumsChart.test.tsx`
- Modify (replace the stub): `frontend/src/features/dashboard/FinanceDashboardPanel.tsx`
- Test: `frontend/src/features/dashboard/FinanceDashboardPanel.test.tsx`

**Interfaces:**
- Consumes: `selectFinance`, `KpiRow`, `countValue`, `DashboardRail`, `formatMoney`.
- Produces: `PremiumsChart({ thisYear, lastYear, currentMonth }: { thisYear: MonthPoint[]; lastYear: MonthPoint[]; currentMonth: string })` where `MonthPoint = { month: string; amount: Money | null }`; `reportLink(key: string, params: Record<string, string>): string`; `lastDayOf(month: string): string`.

- [ ] **Step 1: Write the failing chart test**

```tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PremiumsChart } from './PremiumsChart';

const p = (month: string, amount: string) => ({ month, amount: { amount, currencyCode: 'TZS' } });

describe('PremiumsChart', () => {
  /** Every bar is a report value; the month in progress is drawn as an outline, never as a complete month. */
  it('marks the month in progress as to date', () => {
    render(<PremiumsChart thisYear={[p('2026-09', '50204310.00'), p('2026-10', '22600000.00')]}
      lastYear={[p('2025-09', '45000000.00')]} currentMonth="2026-10" />);

    expect(screen.getByTitle('Oct 2026 to date: TZS 22,600,000.00').closest('rect')).toHaveAttribute('data-partial', 'true');
    expect(screen.getByTitle('Sep 2026: TZS 50,204,310.00').closest('rect')).not.toHaveAttribute('data-partial');
    expect(screen.getByTitle('Sep 2025: TZS 45,000,000.00')).toBeInTheDocument();
  });

  /** The described image says the same as the bars, for a reader who cannot see them. */
  it('describes itself', () => {
    render(<PremiumsChart thisYear={[p('2026-09', '50204310.00')]} lastYear={[]} currentMonth="2026-10" />);

    expect(screen.getByRole('img', { name: /Premiums collected by month/ })).toBeInTheDocument();
  });

  it('says so when there is nothing to draw', () => {
    render(<PremiumsChart thisYear={[]} lastYear={[]} currentMonth="2026-10" />);

    expect(screen.getByText('No premiums collected in either year yet.')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npx vitest run src/features/dashboard/PremiumsChart.test.tsx`
Expected: FAIL — `Failed to resolve import "./PremiumsChart"`.

- [ ] **Step 3: Implement the chart**

```tsx
import { formatMoney, type Money } from '@/lib/money';

export interface MonthPoint {
  month: string;
  amount: Money | null;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/**
 * Premiums collected by month, this year against last (2026-10-14).
 *
 * Every bar is a value the reporting module returned (`monthlyPremiumCollections`); this draws, it does not add.
 * Achromatic by rule: this year in ink, last year in the strong rule grey -- a series is not a state, so it takes no
 * status hue. The month in progress is an ink OUTLINE: a half-month drawn solid beside complete months would read as
 * a collapse that has not happened.
 *
 * Heights are proportions of the largest value for display only. Parsing the decimal strings to numbers here is
 * drawing, not arithmetic on money: no total, average or difference is produced and nothing parsed is ever shown --
 * the labels and titles show the strings as the server sent them.
 */
export function PremiumsChart({ thisYear, lastYear, currentMonth }: {
  thisYear: MonthPoint[];
  lastYear: MonthPoint[];
  currentMonth: string;
}) {
  if (thisYear.length === 0 && lastYear.length === 0) {
    return <p className="px-4 pb-4 text-xs text-muted-foreground">No premiums collected in either year yet.</p>;
  }

  const byMonth = (points: MonthPoint[]) => new Map(points.map((pt) => [Number(pt.month.slice(5, 7)), pt]));
  const cur = byMonth(thisYear);
  const last = byMonth(lastYear);
  const max = Math.max(1, ...[...thisYear, ...lastYear].map((pt) => Number(pt.amount?.amount ?? 0)));
  const year = Number(currentMonth.slice(0, 4));

  const W = 640, H = 220, L = 8, R = 8, T = 10, B = 26;
  const slot = (W - L - R) / 12;
  const bw = Math.min(14, (slot - 6) / 2);
  const h = (pt: MonthPoint | undefined) => (pt?.amount ? (Number(pt.amount.amount) / max) * (H - T - B) : 0);

  const label = (pt: MonthPoint | undefined) => (pt?.amount ? formatMoney(pt.amount) : 'nothing collected');
  const latest = thisYear.filter((pt) => pt.month !== currentMonth).at(-1);
  const description = `Premiums collected by month, ${year} against ${year - 1}.`
    + (latest ? ` ${MONTHS[Number(latest.month.slice(5, 7)) - 1]} ${year}: ${label(latest)}.` : '')
    + (cur.get(Number(currentMonth.slice(5, 7))) ? ` ${MONTHS[Number(currentMonth.slice(5, 7)) - 1]} is to date.` : '');

  return (
    <div className="px-4 pb-2">
      <svg viewBox={`0 0 ${W} ${H}`} className="block h-auto w-full" role="img" aria-label={description}>
        <line x1={L} x2={W - R} y1={H - B} y2={H - B} className="stroke-border" />
        {MONTHS.map((name, i) => {
          const m = i + 1;
          const cx = L + slot * i + slot / 2;
          const prev = last.get(m);
          const now = cur.get(m);
          const partial = `${year}-${String(m).padStart(2, '0')}` === currentMonth;
          return (
            <g key={name}>
              {prev && (
                <rect x={cx - bw - 1} y={H - B - h(prev)} width={bw} height={h(prev)} rx={2} className="fill-border-strong">
                  <title>{`${name} ${year - 1}: ${label(prev)}`}</title>
                </rect>
              )}
              {now && (
                <rect
                  x={cx + 1} y={H - B - h(now)} width={bw} height={h(now)} rx={2}
                  className={partial ? 'fill-none stroke-foreground stroke-[1.5]' : 'fill-foreground'}
                  {...(partial ? { 'data-partial': 'true' } : {})}
                >
                  <title>{`${name} ${year}${partial ? ' to date' : ''}: ${label(now)}`}</title>
                </rect>
              )}
              <text x={cx} y={H - 8} textAnchor="middle" className="fill-muted-foreground text-xs">{name}</text>
            </g>
          );
        })}
      </svg>
      <div className="flex flex-wrap gap-4 pt-1 text-xs text-muted-foreground">
        <span className="inline-flex items-center gap-1.5"><i className="inline-block size-2.5 rounded-sm bg-foreground" />{year}</span>
        <span className="inline-flex items-center gap-1.5"><i className="inline-block size-2.5 rounded-sm bg-border-strong" />{year - 1}</span>
        <span className="inline-flex items-center gap-1.5"><i className="inline-block size-2.5 rounded-sm border-[1.5px] border-foreground" />this month, to date</span>
      </div>
    </div>
  );
}
```

`getByTitle` returns the SVG `<title>` element itself, which is why the test reads `data-partial` from its `closest('rect')`. Axis labels are `text-xs`: the design guards refuse any text below 12px, inside a drawing or not.

- [ ] **Step 4: Run the chart test**

Run: `npx vitest run src/features/dashboard/PremiumsChart.test.tsx src/test/designGuards.test.ts`
Expected: 3 passed, guards clean.

- [ ] **Step 5: Write the failing panel test**

```tsx
import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getFinanceDashboard } from '@/api/dashboard';
import { useDashboardStore } from '@/store/dashboardStore';
import { renderScreen } from '@/test/renderScreen';
import { FinanceDashboardPanel, lastDayOf, reportLink } from './FinanceDashboardPanel';

vi.mock('@/api/dashboard');

const TZS = (amount: string) => ({ amount, currencyCode: 'TZS' });
const VIEW = {
  premiumsMonth: '2026-09', premiumsCollected: TZS('50204310.00'), premiumsChangePercent: '2.2',
  balanceSheetMonth: '2026-10', totalAssets: TZS('742580120.00'), totalLiabilities: TZS('512340210.00'),
  resultFrom: '2026-01', resultTo: '2026-10', netResult: TZS('31204880.00'),
  journalsAwaitingApproval: 4, receiptsOverdue: 7, transfersAwaiting: 3, arrearsAtLapse: null,
  premiumsThisYear: [], premiumsLastYear: [], currentMonth: '2026-10',
};

beforeEach(() => {
  useDashboardStore.setState({ finance: { status: 'idle', data: null, error: null, loadedAt: null } } as never);
  vi.mocked(getFinanceDashboard).mockResolvedValue(VIEW);
});

describe('FinanceDashboardPanel', () => {
  it('opens each money figure as its report, with the period it was read for', async () => {
    renderScreen(<FinanceDashboardPanel />);

    expect(await screen.findByRole('link', { name: /Premiums collected, Sep/ })).toHaveAttribute('href',
      '/staff/reports/premium-collections?from=2026-09-01&to=2026-09-30');
    expect(screen.getByRole('link', { name: /Total assets/ })).toHaveAttribute('href',
      '/staff/reports/balance-sheet?monthFrom=2026-10');
    expect(screen.getByRole('link', { name: /Net result, year to date/ })).toHaveAttribute('href',
      '/staff/reports/income-statement?monthFrom=2026-01&monthTo=2026-10');
  });

  it('shows the delta in words, against the month it compares to', async () => {
    renderScreen(<FinanceDashboardPanel />);

    expect(await screen.findByText('2.2% vs Aug')).toBeInTheDocument();
  });

  /** Journals cannot link: the journals list does not read a status from its URL, so it would show every journal. */
  it('does not link a count its list cannot reproduce', async () => {
    renderScreen(<FinanceDashboardPanel />);

    await screen.findByText('2.2% vs Aug');
    expect(screen.queryByRole('link', { name: /journals submitted, awaiting approval/ })).not.toBeInTheDocument();
    expect(screen.getByText('journals submitted, awaiting approval')).toBeInTheDocument();
  });

  it('builds links and month ends correctly', () => {
    expect(reportLink('balance-sheet', { monthFrom: '2026-10' })).toBe('/staff/reports/balance-sheet?monthFrom=2026-10');
    expect(lastDayOf('2026-02')).toBe('2026-02-28');
    expect(lastDayOf('2028-02')).toBe('2028-02-29');
    expect(lastDayOf('2026-09')).toBe('2026-09-30');
  });
});
```

- [ ] **Step 6: Run it to verify it fails**

Run: `npx vitest run src/features/dashboard/FinanceDashboardPanel.test.tsx`
Expected: FAIL — `reportLink` is not exported from the stub.

- [ ] **Step 7: Implement the panel**

```tsx
import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { KpiRow, countValue, type Kpi } from '@/components/KpiCard';
import { Panel } from '@/components/Panel';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectFinance, useDashboardStore } from '@/store/dashboardStore';
import { DashboardRail } from './DashboardRail';
import { PremiumsChart } from './PremiumsChart';

const MONTH_NAME = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const monthName = (yyyymm: string) => MONTH_NAME[Number(yyyymm.slice(5, 7)) - 1] ?? yyyymm;

/** A report's URL with the filter it was read with -- the names `reportParams.stateFromSearch` reads back. */
export function reportLink(key: string, params: Record<string, string>): string {
  return `/staff/reports/${key}?${new URLSearchParams(params).toString()}`;
}

/** The last civil day of a "YYYY-MM" month, as "YYYY-MM-DD". Calendar arithmetic, not money. */
export function lastDayOf(yyyymm: string): string {
  const [y, m] = yyyymm.split('-').map(Number) as [number, number];
  const day = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return `${yyyymm}-${String(day).padStart(2, '0')}`;
}

const previousMonthName = (yyyymm: string) => {
  const m = Number(yyyymm.slice(5, 7));
  return MONTH_NAME[(m + 10) % 12] ?? '';
};

/** The finance dashboard (2026-10-14). Every money figure is a report's own number; every count is one status. */
export function FinanceDashboardPanel() {
  const resource = useDashboardStore(selectFinance);
  const load = useDashboardStore((s) => s.load);

  useEffect(() => {
    void load('finance');
  }, [load]);

  if (isInitialLoad(resource)) return <LoadingBlock label="Reading the ledger" />;
  if (resource.status === 'error' && resource.error && resource.data === null) {
    return <ErrorPanel error={resource.error} onRetry={() => void load('finance')} />;
  }
  const v = resource.data;
  if (!v) return null;

  const money = (m: typeof v.totalAssets) => (m ? formatMoney(m) : null);
  const change = v.premiumsChangePercent;
  const kpis: Kpi[] = [
    {
      label: `Premiums collected, ${monthName(v.premiumsMonth)}`,
      value: money(v.premiumsCollected),
      hint: 'last complete month — this month is still running',
      to: reportLink('premium-collections', { from: `${v.premiumsMonth}-01`, to: lastDayOf(v.premiumsMonth) }),
      ...(change !== null && change !== undefined
        ? { delta: { direction: change.startsWith('-') ? 'down' as const : 'up' as const,
            text: `${change.replace(/^-/, '')}% vs ${previousMonthName(v.premiumsMonth)}` } }
        : {}),
    },
    { label: 'Total assets', value: money(v.totalAssets), hint: `balance sheet through ${monthName(v.balanceSheetMonth)}`,
      to: reportLink('balance-sheet', { monthFrom: v.balanceSheetMonth }) },
    { label: 'Total liabilities', value: money(v.totalLiabilities), hint: `balance sheet through ${monthName(v.balanceSheetMonth)}`,
      to: reportLink('balance-sheet', { monthFrom: v.balanceSheetMonth }) },
    { label: 'Net result, year to date', value: money(v.netResult), hint: `income statement, ${monthName(v.resultFrom)}–${monthName(v.resultTo)}`,
      to: reportLink('income-statement', { monthFrom: v.resultFrom, monthTo: v.resultTo }) },
  ];

  const waiting: { n: number | null | undefined; text: string; to?: string }[] = [
    // No `to`: ManualJournalsPage does not read a status from its URL, so it would list every journal.
    { n: v.journalsAwaitingApproval, text: 'journals submitted, awaiting approval' },
    { n: v.receiptsOverdue, text: 'field receipts past their reconciliation deadline', to: '/staff/field-receipts?status=RECONCILIATION_OVERDUE' },
    { n: v.transfersAwaiting, text: 'bank transfers nobody has made yet', to: '/staff/bank-transfers' },
    { n: v.arrearsAtLapse, text: 'policies in arrears at the lapse level', to: '/staff/arrears?minDunningLevel=5' },
  ];

  return (
    <div className="space-y-5">
      <KpiRow kpis={kpis} />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <div className="space-y-5">
          <Panel emphasis title="Waiting on finance" subtitle="Each opens its list, already filtered.">
            <ul className="divide-y divide-border">
              {waiting.map((row) => (
                <li key={row.text} className="flex items-center gap-4 px-4 py-2.5">
                  <span className="w-12 shrink-0 text-right text-title">{countValue(row.n) ?? '—'}</span>
                  <span className="min-w-0 flex-1 text-sm">{row.text}</span>
                  {row.to && row.n !== null && row.n !== undefined && (
                    <Link to={row.to} aria-label={`Open the list of ${row.text}`}
                      className="rounded-md px-2 py-1 text-sm font-medium hover:bg-hover">Open</Link>
                  )}
                </li>
              ))}
            </ul>
          </Panel>
          <Panel
            title="Premiums collected"
            subtitle="By month, this year against last. Every bar is a report value."
            actions={<Link to={reportLink('premium-collections', { from: `${Number(v.currentMonth.slice(0, 4)) - 1}-01-01`, to: lastDayOf(v.currentMonth) })}
              className="text-xs text-muted-foreground hover:text-foreground hover:underline">Open the report</Link>}
          >
            <PremiumsChart thisYear={v.premiumsThisYear} lastYear={v.premiumsLastYear} currentMonth={v.currentMonth} />
          </Panel>
        </div>
        <DashboardRail
          alerts={[
            ...(v.receiptsOverdue ? [{ tone: 'danger' as const, text: <><b>{countValue(v.receiptsOverdue)} field receipts</b> are past their reconciliation deadline.</> }] : []),
            ...(v.journalsAwaitingApproval ? [{ tone: 'warning' as const, text: <><b>{countValue(v.journalsAwaitingApproval)} journals</b> wait for approval. You may not approve your own.</> }] : []),
          ]}
          actions={[
            { label: 'Match field receipts', description: 'Agent-collected cash to payments', to: '/staff/field-receipts' },
            { label: 'Make bank transfers', description: 'Approved payouts awaiting EFT', to: '/staff/bank-transfers' },
            { label: 'Prepare a journal', description: 'Approval follows segregation of duties', to: '/staff/manual-journals/new' },
            { label: 'Run a statement', description: 'Trial balance, P&L, balance sheet', to: '/staff/reports' },
          ]}
          absent={['A cash-flow statement', 'Receivables total and aging — phase 2']}
        />
      </div>
    </div>
  );
}
```

- [ ] **Step 8: Run the tests and checks**

Run: `npx vitest run src/features/dashboard src/test/designGuards.test.ts && npm run typecheck && npm run lint`
Expected: every dashboard spec passed; guards, typecheck and lint clean.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/features/dashboard/PremiumsChart.tsx frontend/src/features/dashboard/PremiumsChart.test.tsx frontend/src/features/dashboard/FinanceDashboardPanel.tsx frontend/src/features/dashboard/FinanceDashboardPanel.test.tsx
git commit -m "feat(dashboard): the finance panel and its achromatic premiums chart

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: The agent panel

**Files:**
- Modify (replace the stub): `frontend/src/features/dashboard/AgentDashboardPanel.tsx`
- Test: `frontend/src/features/dashboard/AgentDashboardPanel.test.tsx`

**Interfaces:**
- Consumes: `selectAgent`, `KpiRow`, `countValue`, `DashboardRail`, `formatMoney`, `StatusBadge`.

- [ ] **Step 1: Write the failing test**

```tsx
import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getAgentDashboard } from '@/api/dashboard';
import { useDashboardStore } from '@/store/dashboardStore';
import { renderScreen } from '@/test/renderScreen';
import { AgentDashboardPanel } from './AgentDashboardPanel';

vi.mock('@/api/dashboard');

const VIEW = {
  clients: 309, clientsAwaitingKyc: 23, clientsKycRejected: 1, policiesInBook: 412,
  commissionPeriod: '2026-09', commissionEarned: { amount: '1284500.00', currencyCode: 'TZS' }, commissionStatus: 'CLOSED',
  awaitingKyc: [{ partyId: 'party-1', displayName: 'Amina Hassan', kycStatus: 'PENDING' as const }],
};

beforeEach(() => {
  useDashboardStore.setState({ agent: { status: 'idle', data: null, error: null, loadedAt: null } } as never);
  vi.mocked(getAgentDashboard).mockResolvedValue(VIEW);
});

describe('AgentDashboardPanel', () => {
  it('links each count to the agent own filtered list', async () => {
    renderScreen(<AgentDashboardPanel />);

    expect(await screen.findByRole('link', { name: /^309 My clients/ })).toHaveAttribute('href', '/agents/clients');
    expect(screen.getByRole('link', { name: /^23 Clients awaiting KYC/ }))
      .toHaveAttribute('href', '/agents/clients?kycStatus=PENDING');
    expect(screen.getByRole('link', { name: /^412 Policies in my book/ })).toHaveAttribute('href', '/agents/policies');
  });

  /** The agent's job here is sending the papers: each pending client opens straight on its KYC tab. */
  it('sends each pending client to their KYC tab', async () => {
    renderScreen(<AgentDashboardPanel />);

    expect(await screen.findByRole('link', { name: 'Send documents for Amina Hassan' }))
      .toHaveAttribute('href', '/agents/parties/party-1?tab=kyc');
  });

  /** Commission in full, never abbreviated: an agent checks it against their statement to the shilling. */
  it('shows commission in full', async () => {
    renderScreen(<AgentDashboardPanel />);

    expect(await screen.findByText(/1,284,500\.00/)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npx vitest run src/features/dashboard/AgentDashboardPanel.test.tsx`
Expected: FAIL — no link named "309 My clients".

- [ ] **Step 3: Implement**

```tsx
import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { KpiRow, countValue, type Kpi } from '@/components/KpiCard';
import { Panel } from '@/components/Panel';
import { StatusBadge } from '@/components/StatusBadge';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectAgent, useDashboardStore } from '@/store/dashboardStore';
import { DashboardRail } from './DashboardRail';

const MONTH = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

/**
 * The agent's dashboard (2026-10-14), and their landing page. Scoped by the server to the clients they registered and
 * their team's book. Leads with KYC because a client whose identity is unverified cannot hold a policy, and the
 * documents are the one part of that the agent can move.
 */
export function AgentDashboardPanel() {
  const resource = useDashboardStore(selectAgent);
  const load = useDashboardStore((s) => s.load);

  useEffect(() => {
    void load('agent');
  }, [load]);

  if (isInitialLoad(resource)) return <LoadingBlock label="Counting your clients" />;
  if (resource.status === 'error' && resource.error && resource.data === null) {
    return <ErrorPanel error={resource.error} onRetry={() => void load('agent')} />;
  }
  const v = resource.data;
  if (!v) return null;

  const period = MONTH[Number(v.commissionPeriod.slice(5, 7)) - 1] ?? v.commissionPeriod;
  const kpis: Kpi[] = [
    { label: 'My clients', value: countValue(v.clients), hint: 'registered by you', to: '/agents/clients' },
    { label: 'Clients awaiting KYC', value: countValue(v.clientsAwaitingKyc), hint: 'documents not yet verified', to: '/agents/clients?kycStatus=PENDING' },
    { label: 'Policies in my book', value: countValue(v.policiesInBook), hint: 'you and your downline', to: '/agents/policies' },
    { label: `Commission, ${period}`, value: v.commissionEarned ? formatMoney(v.commissionEarned) : null, hint: 'last closed statement', to: '/agents/commission' },
  ];

  return (
    <div className="space-y-5">
      <KpiRow kpis={kpis} />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <Panel emphasis title="Clients still needing KYC documents" subtitle="Send the ID scan; the office decides. Newest first.">
          {v.awaitingKyc.length === 0 ? (
            <p className="px-4 pb-4 text-xs text-muted-foreground">Every client you registered has been verified.</p>
          ) : (
            <ul className="divide-y divide-border">
              {v.awaitingKyc.map((c) => (
                <li key={c.partyId} className="flex items-center gap-3 px-4 py-2.5">
                  <span className="min-w-0 flex-1 truncate text-sm">{c.displayName}</span>
                  <StatusBadge kind="kyc" value={c.kycStatus} />
                  <Link to={`/agents/parties/${c.partyId}?tab=kyc`} aria-label={`Send documents for ${c.displayName}`}
                    className="rounded-md px-2 py-1 text-sm font-medium hover:bg-hover">Send documents</Link>
                </li>
              ))}
            </ul>
          )}
        </Panel>
        <DashboardRail
          alerts={[
            ...(v.clientsAwaitingKyc ? [{ tone: 'warning' as const, text: <><b>{countValue(v.clientsAwaitingKyc)} clients</b> cannot hold a policy until their KYC is verified.</> }] : []),
            ...(v.clientsKycRejected ? [{ tone: 'danger' as const, text: <><b>{countValue(v.clientsKycRejected)} {v.clientsKycRejected === 1 ? 'client has' : 'clients have'}</b> had documents rejected. <Link to="/agents/clients?kycStatus=REJECTED" className="underline">Send clearer ones.</Link></> }] : []),
          ]}
          actions={[
            { label: 'Register a client', description: 'Then send their ID documents', to: '/agents/customers/new' },
            { label: 'My clients', description: 'Everyone you have registered', to: '/agents/clients' },
            { label: 'My policies', description: 'Your book and your downline’s', to: '/agents/policies' },
            { label: 'My commission', description: 'Statements and payouts', to: '/agents/commission' },
          ]}
          absent={['Quotes', 'Leads and follow-up reminders', 'Applications in underwriting — phase 2']}
        />
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Run the tests and checks**

Run: `npx vitest run src/features/dashboard src/test/designGuards.test.ts && npm run typecheck && npm run lint`
Expected: every dashboard spec passed; guards, typecheck and lint clean.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/dashboard/AgentDashboardPanel.tsx frontend/src/features/dashboard/AgentDashboardPanel.test.tsx
git commit -m "feat(dashboard): the agent panel, leading with the clients still needing KYC

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11a: The activity feed in the three staff panels

**Files:**
- Create: `frontend/src/features/dashboard/ActivityFeed.tsx`
- Test: `frontend/src/features/dashboard/ActivityFeed.test.tsx`
- Modify: `UnderwritingDashboardPanel.tsx`, `ClaimsDashboardPanel.tsx`, `FinanceDashboardPanel.tsx` and their three tests

**Interfaces:**
- Consumes: `recentActivity` on the three views (Task 5a), regenerated into the client types by `npm run generate:api`; `formatInstant` (`@/lib/dates`), `formatMoney`, `Panel`.
- Produces: `ActivityFeed({ items }: { items: ActivityItem[] })`; `describeActivity(item: ActivityItem): { text: string; to?: string }`.

- [ ] **Step 1: Regenerate the client types**

Run (from `frontend/`): `npm run generate:api`
Expected: `openapi-dashboard.yaml -> src/types/api/dashboard.ts` in the output. Then add to `frontend/src/api/dashboard.ts`:

```ts
export type ActivityItem = components['schemas']['ActivityItem'];
```

- [ ] **Step 2: Write the failing test**

```tsx
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { ActivityFeed, describeActivity } from './ActivityFeed';

const item = (eventType: string, refs: Record<string, string> = {}, amount: { amount: string; currencyCode: string } | null = null) =>
  ({ eventType, occurredAt: '2026-10-14T08:00:00Z', refs, amount });

describe('describeActivity', () => {
  it('words a known event and links it by its reference', () => {
    expect(describeActivity(item('claims.ClaimSettled', { claimId: 'c-1' })))
      .toEqual({ text: 'Claim settled', to: '/staff/claims/c-1' });
    expect(describeActivity(item('underwriting.UnderwritingDecisionMade', { caseId: 'u-1', outcome: 'LOADED' })))
      .toEqual({ text: 'Case decided: accepted with a loading', to: '/staff/underwriting/u-1' });
    expect(describeActivity(item('billing.PremiumCollected', { policyNumber: 'POL-1' })))
      .toEqual({ text: 'Premium collected', to: '/staff/policies/POL-1' });
  });

  /** An event the console has no words for is shown by its own name, never dropped. */
  it('humanises an event it does not know', () => {
    expect(describeActivity(item('payment.SomethingNewHappened'))).toEqual({ text: 'Something new happened' });
  });

  it('does not link without the reference it needs', () => {
    expect(describeActivity(item('claims.ClaimSettled'))).toEqual({ text: 'Claim settled' });
  });
});

describe('ActivityFeed', () => {
  /** The audit log records no actor, so the feed must say what happened -- and that it does not say who. */
  it('says it records what happened, not who', () => {
    render(<MemoryRouter><ActivityFeed items={[item('claims.ClaimSettled', { claimId: 'c-1' },
      { amount: '10000000.00', currencyCode: 'TZS' })]} /></MemoryRouter>);

    expect(screen.getByText(/not who/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Claim settled' })).toHaveAttribute('href', '/staff/claims/c-1');
    expect(screen.getByText('TZS 10,000,000.00')).toBeInTheDocument();
  });

  it('says so when nothing has happened', () => {
    render(<MemoryRouter><ActivityFeed items={[]} /></MemoryRouter>);

    expect(screen.getByText('Nothing recorded yet.')).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Run it to verify it fails**

Run: `npx vitest run src/features/dashboard/ActivityFeed.test.tsx`
Expected: FAIL — `Failed to resolve import "./ActivityFeed"`.

- [ ] **Step 4: Implement**

```tsx
import { Link } from 'react-router-dom';
import type { ActivityItem } from '@/api/dashboard';
import { Panel } from '@/components/Panel';
import { formatInstant } from '@/lib/dates';
import { formatMoney } from '@/lib/money';

/** Underwriting decision outcomes, in the decision panel's words. An outcome not listed reads plainly "Case decided". */
const OUTCOME: Record<string, string> = {
  ACCEPTED: 'accepted', LOADED: 'accepted with a loading', POSTPONED: 'postponed', DECLINED: 'declined',
};

/**
 * Words per event type, each with the reference it links by. Only events a dashboard actually shows are here; any
 * other type is humanised from its own name rather than dropped, so a new backend event appears instead of vanishing.
 */
const WORDS: Record<string, { text: (refs: Record<string, string>) => string; link?: (refs: Record<string, string>) => string | undefined }> = {
  'underwriting.UnderwritingDecisionMade': {
    text: (r) => (r.outcome && OUTCOME[r.outcome] ? `Case decided: ${OUTCOME[r.outcome]}` : 'Case decided'),
    link: (r) => r.caseId && `/staff/underwriting/${r.caseId}`,
  },
  'claims.ClaimRegistered': { text: () => 'Claim registered', link: (r) => r.claimId && `/staff/claims/${r.claimId}` },
  'claims.ClaimAssessed': { text: () => 'Claim assessed', link: (r) => r.claimId && `/staff/claims/${r.claimId}` },
  'claims.ClaimSettlementRequested': { text: () => 'Settlement requested', link: (r) => r.claimId && `/staff/claims/${r.claimId}` },
  'claims.ClaimApproved': { text: () => 'Claim approved', link: (r) => r.claimId && `/staff/claims/${r.claimId}` },
  'claims.ClaimRejected': { text: () => 'Claim rejected', link: (r) => r.claimId && `/staff/claims/${r.claimId}` },
  'claims.ClaimSettled': { text: () => 'Claim settled', link: (r) => r.claimId && `/staff/claims/${r.claimId}` },
  'billing.PremiumCollected': { text: () => 'Premium collected', link: (r) => r.policyNumber && `/staff/policies/${r.policyNumber}` },
  'billing.FieldReceiptCaptured': { text: () => 'Field receipt captured', link: () => '/staff/field-receipts' },
  'billing.FieldReceiptReconciled': { text: () => 'Field receipt reconciled', link: () => '/staff/field-receipts' },
  'billing.FieldReceiptReconciliationOverdue': { text: () => 'Field receipt past its deadline', link: () => '/staff/field-receipts?status=RECONCILIATION_OVERDUE' },
  'billing.InvoiceWaived': { text: () => 'Premium waived', link: (r) => r.policyNumber && `/staff/policies/${r.policyNumber}` },
  'billing.PremiumOverdue': { text: () => 'Premium overdue', link: (r) => r.policyNumber && `/staff/policies/${r.policyNumber}` },
  'billing.PolicyLapseRecommended': { text: () => 'Lapse recommended', link: (r) => r.policyNumber && `/staff/policies/${r.policyNumber}` },
  'billing.PremiumRefundDue': { text: () => 'Refund due', link: (r) => r.policyNumber && `/staff/policies/${r.policyNumber}` },
  'payment.PaymentConfirmed': { text: () => 'Payment confirmed' },
  'payment.PaymentFailed': { text: () => 'Payment failed' },
  'payment.DisbursementCompleted': { text: () => 'Payout completed' },
  'payment.DisbursementFailed': { text: () => 'Payout failed' },
  'payment.EftDisbursementAwaitingExecution': { text: () => 'Bank transfer waiting to be made', link: () => '/staff/bank-transfers' },
  'payment.EftDisbursementExecuted': { text: () => 'Bank transfer made', link: () => '/staff/bank-transfers' },
  'finaccounting.ManualJournal': { text: () => 'Manual journal changed', link: () => '/staff/manual-journals' },
  'finaccounting.AutoReversal': { text: () => 'Journal reversed automatically', link: () => '/staff/manual-journals' },
};

/** "payment.SomethingNewHappened" -> "Something new happened". */
function humanise(eventType: string): string {
  const name = eventType.slice(eventType.indexOf('.') + 1).replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase();
  return name.charAt(0).toUpperCase() + name.slice(1);
}

export function describeActivity(item: ActivityItem): { text: string; to?: string } {
  const words = WORDS[item.eventType];
  if (!words) return { text: humanise(item.eventType) };
  const to = words.link?.(item.refs);
  return to ? { text: words.text(item.refs), to } : { text: words.text(item.refs) };
}

/**
 * This module's latest events (2026-10-14), from the audit log. It records what happened and when -- not who: the log
 * carries no actor, and the subtitle says so rather than letting the feed pass for an audit trail of people.
 */
export function ActivityFeed({ items }: { items: ActivityItem[] }) {
  return (
    <Panel title="Recent activity" subtitle="Newest first. The log records what happened, not who did it.">
      {items.length === 0 ? (
        <p className="px-4 pb-4 text-xs text-muted-foreground">Nothing recorded yet.</p>
      ) : (
        <ul className="divide-y divide-border">
          {items.map((item, i) => {
            const { text, to } = describeActivity(item);
            return (
              <li key={`${item.occurredAt}-${i}`} className="flex items-baseline gap-3 px-4 py-2.5 text-sm">
                <time dateTime={item.occurredAt} className="w-36 shrink-0 text-xs text-subtle-foreground">
                  {formatInstant(item.occurredAt)}
                </time>
                <span className="min-w-0 flex-1">
                  {to ? <Link to={to} className="hover:underline">{text}</Link> : text}
                  {item.refs.policyNumber && <span className="ml-2 font-mono text-xs text-muted-foreground">{item.refs.policyNumber}</span>}
                </span>
                {item.amount && <span className="shrink-0 whitespace-nowrap text-right">{formatMoney(item.amount)}</span>}
              </li>
            );
          })}
        </ul>
      )}
    </Panel>
  );
}
```

`formatInstant` is the helper `KycPanel` and `PartyDetailPage` already use for an `Instant`.

- [ ] **Step 5: Put it in the three panels**

In each of `UnderwritingDashboardPanel.tsx`, `ClaimsDashboardPanel.tsx` and `FinanceDashboardPanel.tsx`, import `ActivityFeed` from `./ActivityFeed` and add, as the LAST child of the work column (after the emphasised panel, and in finance after the chart):

```tsx
<ActivityFeed items={view.recentActivity} />
```

(`v.recentActivity` in the finance panel, which names its view `v`.) The underwriting panel's work column is currently the bare emphasised `Panel`; wrap it and the feed in `<div className="space-y-5">…</div>` so the grid still sees one column.

In each of the three panel tests, add `recentActivity: []` to the `VIEW` fixture.

- [ ] **Step 6: Run the tests and checks**

Run: `npx vitest run src/features/dashboard src/test/designGuards.test.ts && npm run typecheck && npm run lint`
Expected: every dashboard spec passed (`ActivityFeed.test.tsx` 5); guards, typecheck and lint clean.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api/dashboard.ts frontend/src/features/dashboard
git commit -m "feat(dashboard): recent activity in the staff panels, worded per event

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: E2E sign-in, DESIGN.md, and the real stack

Every e2e sign-in waits for a landing heading. Renaming "Today" to "Dashboard" and moving the agent landing breaks every one of them, and unit tests cannot see it (memory: e2e couples to accessible names).

**Files:**
- Modify: `frontend/e2e/auth.setup.ts`, `auth-admin.setup.ts`, `auth-assessor.setup.ts`, `auth-finance-approver.setup.ts`, `auth-finance.setup.ts`, `auth-manager.setup.ts`, `auth-senior.setup.ts`, `approverSession.ts`, `agentSession.ts`
- Modify: `DESIGN.md`
- Create: `frontend/e2e/staff-dashboard.spec.ts`

- [ ] **Step 1: Point the sign-ins at the new landing**

In each of the seven `auth*.setup.ts` files and `approverSession.ts`, replace `{ name: 'Today', exact: true }` with `{ name: 'Dashboard', exact: true }`, and any comment saying "lands on Today" with "lands on the Dashboard (2026-10-14)". In `agentSession.ts`, replace `page.getByRole('heading', { name: 'My profile' })` with `page.getByRole('heading', { name: 'Dashboard', exact: true })`.

Then check the specs that may have relied on agents landing on "My profile":

```bash
grep -n "goto\|getByRole('heading', { name: 'My profile'" frontend/e2e/agents-my-profile.spec.ts frontend/e2e/agents-my-commission.spec.ts frontend/e2e/agents-commission-team-picker.spec.ts
```

Any spec that asserts the "My profile" heading WITHOUT first navigating to `/agents/me` must gain `await page.goto('/agents/me');` before that assertion.

Run: `grep -rn "name: 'Today'" frontend/e2e/` — Expected: no output.

- [ ] **Step 2: Write the staff dashboard e2e**

`frontend/e2e/staff-dashboard.spec.ts`:

```ts
import { expect, test } from '@playwright/test';

/**
 * The dashboard against the real stack: the figures arrive from the real endpoint, and a card opens the list it
 * counted. Runs as the default staff user (auth.setup.ts), who holds underwriting and finance.
 */
test.describe('staff dashboard', () => {
  test('lands on the dashboard and a count opens its own filtered list', async ({ page }) => {
    await page.goto('/staff');
    await expect(page.getByRole('heading', { name: 'Dashboard', exact: true })).toBeVisible();

    const openCases = page.getByRole('link', { name: /Open cases, awaiting first review/ });
    await expect(openCases).toBeVisible();
    await openCases.click();
    await expect(page).toHaveURL(/\/staff\/underwriting\?status=OPEN/);
  });

  test('the old Today address still arrives at the dashboard', async ({ page }) => {
    await page.goto('/staff/today');
    await expect(page).toHaveURL(/\/staff\/dashboard/);
    await expect(page.getByRole('heading', { name: 'Dashboard', exact: true })).toBeVisible();
  });
});
```

- [ ] **Step 3: Record the dashboard in DESIGN.md**

Add a new subsection to DESIGN.md, under `## Components`, directly after `### Counts`:

```markdown
### Dashboard (2026-10-14)

Where a working role lands: underwriting, claims, finance, and the agent. Replaces "Today", whose work-waiting counts
became each dashboard's work panel. One tab per dashboard a person holds; an inactive tab is not mounted.

- **`KpiCard`** — a figure, its label, a hint, and optionally a delta. A **link** to exactly the list or report it was
  read from, already filtered, or **not a link** when that destination cannot reproduce its filter (the journals list
  reads no status from its URL, so "journals awaiting approval" is not clickable). A failed figure is "Not available"
  with an em dash, never 0, and never a link.
- **A figure never wraps.** It is `whitespace-nowrap`; the card is a size container and the figure steps from the
  Display tier to the Headline tier when the card is narrow — both tiers already on the page, so no third size. The row
  is `auto-fill` with a 16rem floor, so a narrow screen gets fewer cards across rather than a squeezed number. Money is
  never abbreviated ("1.28M"): a finance reader reconciles to the cent.
- **The work panel** leads the work column with `emphasis` — the one act the dashboard exists for. Then the rest.
- **The right column** holds alerts, quick actions and "Not here yet". It is not a record rail: quick actions are links
  into the screens that own each act, every one with its own confirmation, not acts in their own right.
- **"Not here yet"** names what is deliberately missing because the platform has no concept behind it (SLA deadlines,
  quotes, leads). An honest list beats an empty widget.
- **The chart** is ink (this year) and Rule Strong grey (last year). The month in progress is an ink outline, never a
  solid bar beside complete months.
```

- [ ] **Step 4: Run the whole frontend suite once, then the backend**

Run (from `frontend/`): `npm run typecheck && npm run lint && npx vitest run`
Expected: typecheck and lint clean; every test file passed. Compare the file count with the run before Task 6: it is higher by the new test files and lower by the deleted `TodayPage.test.tsx`.

Run (from `backend/`, dev backend stopped): `./mvnw -o -q clean test-compile && ./mvnw -o test -Dtest='ReportQueriesIntegrationTest,UnderwritingDashboardTest,ClaimsDashboardTest,ReportFiguresTest,FinanceDashboardTest,AgentDashboardTest,DashboardContractTest,ModularityTests,NoCrossModuleJoinTest' -DfailIfNoSpecifiedTests=false`
Expected: every class `Failures: 0, Errors: 0`.

- [ ] **Step 5: Run against the real stack**

The dev backend does not pick up new code or endpoints on its own (memory: dev backend/migrations are decoupled). There is no migration in this feature. Restart the dev backend (stop the `java` process listening on 8080 AND its Maven parent, then `./mvnw -B -o spring-boot:run -Dspring-boot.run.profiles=local` from `backend/`), wait for "Started Application", and confirm `curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/dashboards/finance` prints `401` (mapped and secured), not `404`.

Then run the sign-in setups, the new spec and the agent specs (memory: re-run with deps, never `--no-deps`, before diagnosing an auth failure):

Run (from `frontend/`): `npx playwright test e2e/staff-dashboard.spec.ts e2e/agents-my-profile.spec.ts e2e/agents-my-commission.spec.ts e2e/agents-commission-team-picker.spec.ts`
Expected: all passed. A timeout in a long run: re-run that one spec alone before diagnosing (memory: e2e budget failures mimic regressions).

Open `http://localhost:5173/staff` as a finance user and an underwriter, and `http://localhost:5173/agents` as `agent.senior`, and look at each dashboard at a desktop width and at ~400px. Check: no figure wraps; every clickable card lands on a list whose count matches the card; the October bar is an outline.

- [ ] **Step 6: Commit**

```bash
git add frontend/e2e DESIGN.md
git commit -m "test(dashboard): sign-ins land on the dashboard, and a card opens its own list

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

## Final whole-branch review

Required before merge (memory: the final whole-branch review catches systemic bugs — bugs live in the seams between correct tasks). Read the whole diff `git diff main...role-dashboards`, not task by task, and check specifically:

- [ ] Every `@PreAuthorize` in `DashboardController` matches its row in `dashboardsFor` exactly.
- [ ] Every card's `to` lands on a list or report whose own count equals the card. Open each one on the real stack.
- [ ] No component sums, averages or differences money. `grep -rn "reduce\|\.amount) *[-+*/]" frontend/src/features/dashboard` returns nothing that combines money.
- [ ] Every figure path handles null: kill one module's data (or mock one composer read to throw) and confirm one card says "Not available" and the rest still render.
- [ ] Dates: no `LocalDate.now()` without the `Africa/Dar_es_Salaam` zone anywhere in `dashboard/`.
- [ ] `ModularityTests` and `NoCrossModuleJoinTest` pass; `dashboard` imports only `<module>.api` packages.
- [ ] The doctrine: no delta coloured green or red, no chart series in a status hue.
- [ ] The activity feed: no field outside `RecentActivity.REF_KEYS` reaches the wire (check one real response in the browser's network tab); nothing on screen names who did an event; the agent dashboard has no feed; every event type seen on the real stack is either worded or humanised, none dropped.
- [ ] Then merge as the repository does: `git checkout main && git merge --no-ff role-dashboards`, push `main`, and push the branch as a backup.
