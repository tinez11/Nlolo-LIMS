# Analytics Plan 3 — Agent Sales in Money Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An agent sees what they sold in money — premium sold a year, cover sold, savings deposits taken, premium
their customers paid — on their dashboard, and over any dates in their own downloadable "My sales" report; a
supervisor can switch between Me and My team.

**Architecture:** One new reporting query sums the reporting copy's `report_policy` rows the way the New business
report already does (`ANNUALISED_AT_SALE`, `COVER`, `SINGLE_PREMIUM`), scoped to agent ids. The dashboard adds four
figures from it; a new agent-only endpoint serves the same numbers bucketed by month, quarter or year as a
`ReportResult`, so the existing Excel/PDF exporter prints it unchanged. Scope is always resolved from the token
(`DistributionApi.resolveAgentTeam`), never trusted from a parameter.

**Tech Stack:** Spring Boot 3.3 / Java 21 / JdbcTemplate (reporting, dashboard), React 19 + TypeScript + Vitest,
OpenAPI 3.1 (`npm run generate:api`), Playwright.

**Source decision:** `backend/docs/superpowers/specs/2026-11-11-analytics-prd-assessment.md`, "Agents see what they
sold, in money (2026-11-11, the user)": "premium sold = the yearly premium of the policies they sold (monthly TZS
100,000 counts TZS 1.2M), plus cover sold (sum assured) and premium collected from their customers; savings and
deposits shown apart as 'savings deposits taken'. On the agent dashboard (this month / quarter, against the period
before) and in the agent's own 'My sales' report with a wider timeline — any dates, by month, quarter or year,
compared periods, opens the policies behind each figure, downloadable. Supervisors: 'My team'." Attribution A1:
"sales count for the agent who sold (frozen at issue, already)". Mockup screen 6 (AgentDashboard),
https://claude.ai/artifact/NCgMMcFxs4Xj1D2xsrVbXf: "Premium you sold this month TZS 18.4M a year ▲ 22% on October ·
Cover sold: TZS 214M · savings deposits taken: TZS 6.0M (shown apart)", "Show: Me | My team", "Your customers'
premiums paid 81% · Of premiums due this month", "My sales report — premium sold a year, quarter by quarter … show by
month, quarter or year · Download".

## Decisions (the user, 2026-11-12)

- **D1 yes:** a sale counts on the day cover starts, everywhere an agent sees "sold".
- **D2 yes:** single premiums that are not savings shown apart as "Single premiums sold".
- **D3: the opening deposit only** (the user chose (b) over (c) on the recommendation): "Savings deposits taken" =
  the first money in on each savings or fixed-deposit policy sold — its single deposit, or its first regular deposit
  (one instalment, not a year of them). Later deposits are not sales. Never added into premium.
- **D4 yes:** "Your customers' premiums paid" on the dashboard, this month.

The SQL in Task 1 follows D3: `savings_deposits` = `SUM(CASE WHEN portfolio IN ('SAV','DEP') THEN COALESCE(<SINGLE_PREMIUM>, p.premium) END)`
-- for a SINGLE policy its single deposit, otherwise one regular deposit (`p.premium` is one instalment).
The test's SAV MONTHLY TZS 20,000 policy therefore gives savingsDeposits 20,000.00, not 240,000.00.

## Decisions as first asked (kept for the record)

| # | Question | Facts found (2026-11-12) | Recommended |
|---|---|---|---|
| D1 | When does a sale count? | The agent dashboard counts "Policies sold" by **issue date** (`ReportQueries.issuedByMonth`), which includes offers never paid; the New business report counts by **date on cover** (`activated_on`). Two "sold" numbers for one month disagree today. | Count by **date on cover** everywhere an agent sees "sold", and change the existing count to match. An offer that is never paid was not a sale. |
| D2 | Single premiums that are not savings | Dev holds 12 credit-life (CRL) and 1 immediate-annuity (IANN) policies paid by one SINGLE premium. "A year" has no meaning for them. The New business report already shows single premiums apart. | Show **"Single premiums sold"** apart, as the New business report does; never add them into "a year". |
| D3 | What "savings deposits taken" counts | Savings (SAV) and fixed deposits (DEP) are sold with a SINGLE opening deposit or a MONTHLY deposit. | The deposits agreed on savings and fixed-deposit policies sold: the single deposit, plus the regular deposit for a year (monthly × 12). Not added into premium. |
| D4 | The mockup's "Your customers' premiums paid 81%" | Not in the written decision; it is in the mockup. Plan 1's collection rate (C1) can be scoped to the agent's team with the follow-up list's own scoping. | Include it, on the dashboard only, for this month. |

## Global Constraints

- Simple English on every label and sentence (the user, 2026-11-11). Money in full where an agent checks it ("in full,
  never abbreviated: an agent checks it against their statement to the shilling" — `AgentDashboardPanel.tsx`).
- Render only what the platform can prove: every figure is the server's; the browser adds nothing up.
- An agent's scope comes from the token (`party_id` → `resolveAgentTeam`); an `agentId` parameter is honoured only if
  it is in that team, otherwise 403 — the rule `MyCommissionPage` follows ("An id outside the team falls back to
  self" on the console; the server refuses).
- Host Maven only; `clean test-compile` first; dev backend stopped for backend tests and restarted after (`.env` by
  absolute path); one heavy run at a time; Playwright `--workers=1`, MCP browser closed; never Prettier; Edit/Write
  tools only.
- Branch `analytics-agent-sales`, `git merge --no-ff`, push `origin main`; trailer
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Not in this plan

- Quotes that became customers, quotes ending this week (mockup screen 6, lower half): Plan 4 (sales funnel).
- Changing the agent on an individual policy (A1: left out).
- Commission (already on the dashboard).

## File Structure

| File | Responsibility |
|---|---|
| `reporting/api/SalesTotals.java` (new) | one bucket of an agent's sales in money |
| `reporting/api/ReportingApi.java` (modify) | `salesByPeriod`, `agentSalesReport`, `agentCollectionFigures` |
| `reporting/infrastructure/ReportQueries.java` (modify) | `salesTotals` (one SQL), `policiesSold` (the list behind a figure) |
| `reporting/application/AgentSales.java` (new) | buckets (month/quarter/year), the report, the policy list |
| `reporting/application/FollowUpQueue.java` (modify) | `figures(...)` scoped to an agent team (D4) |
| `reporting/infrastructure/AgentSalesController.java` (new) | `/agents/me/sales`, `/export`, `/policies` |
| `dashboard/application/AgentDashboard.java`, `dashboard/api/AgentDashboardView.java` (modify) | the money figures, D1 |
| `dashboard/infrastructure/DashboardController.java` (modify) | `agentId` (Me) honoured inside the team |
| OpenAPI: `openapi-reporting.yaml`, `openapi-dashboard.yaml` | the new paths and fields |
| `frontend/src/api/agentSales.ts`, `frontend/src/features/distribution/MySalesPage.tsx` (new) | the My sales report |
| `frontend/src/features/dashboard/AgentDashboardPanel.tsx` (modify) | the money tiles, Me / My team, the report card |
| `frontend/src/screens.tsx`, `lazyPages.ts` | the agent route `sales` |

---

### Task 1: reporting — sales totals in one query

**Interfaces — produces:**

```java
/** One bucket of sales on activation date (D1), in one currency. Every figure the server's (D2, D3). */
public record SalesTotals(String bucket /* "2026-11", "2026-Q4" or "2026" */, String currency, long policies,
                          BigDecimal premiumAYear, BigDecimal singlePremiums, BigDecimal cover,
                          BigDecimal savingsDeposits) {}
// ReportQueries
public List<SalesTotals> salesTotals(UUID tenantId, LocalDate from, LocalDate to, Set<UUID> agentIds, String by /* MONTH|QUARTER|YEAR */)
```

SQL (one statement; `by` chooses the bucket expression from a closed list, never request text):

```sql
SELECT <bucket> AS bucket, p.currency, COUNT(*) AS policies,
       COALESCE(SUM(CASE WHEN COALESCE(p.portfolio_code,'') NOT IN ('SAV','DEP') THEN <ANNUALISED_AT_SALE> END), 0) AS premium_a_year,
       COALESCE(SUM(CASE WHEN COALESCE(p.portfolio_code,'') NOT IN ('SAV','DEP') THEN <SINGLE_PREMIUM> END), 0) AS single_premiums,
       COALESCE(SUM(<COVER>), 0) AS cover,
       COALESCE(SUM(CASE WHEN COALESCE(p.portfolio_code,'') IN ('SAV','DEP')
                         THEN COALESCE(<ANNUALISED_AT_SALE>, 0) + COALESCE(<SINGLE_PREMIUM>, 0) END), 0) AS savings_deposits
  FROM reporting.report_policy p
 WHERE p.tenant_id = ? AND p.activated_on BETWEEN ? AND ?  [+ appendAgents]
 GROUP BY bucket, p.currency ORDER BY bucket, p.currency
```

  `<bucket>`: MONTH `to_char(p.activated_on,'YYYY-MM')`; QUARTER `to_char(p.activated_on,'YYYY') || '-Q' ||
  to_char(p.activated_on,'Q')`; YEAR `to_char(p.activated_on,'YYYY')`. `COVER`, `ANNUALISED_AT_SALE`,
  `SINGLE_PREMIUM` are `ReportSql`'s own fragments; `appendAgents` is the helper `issuedByMonth` uses; an EMPTY
  agent set returns `List.of()` (never every policy — the rule at `ReportQueries.java:314`).

- [ ] **Step 1: Failing test** in `ReportQueriesIntegrationTest` (sale fixtures: `Sale.of(...).agent(a).premium(...)`
  and `.portfolio("SAV")`): one MONTHLY TZS 100,000 term policy, one SINGLE TZS 500,000 credit-life policy, one SAV
  MONTHLY TZS 20,000 policy, all agent A, activated in 2026-10; one more term policy for agent B. `salesTotals(...,
  {A}, "MONTH")` for October → policies 3, premiumAYear 1,200,000.00, singlePremiums 500,000.00, savingsDeposits
  240,000.00, cover = the term + credit-life sum assured only (`COVER` drops SAV/DEP); agent B's policy absent;
  `"QUARTER"` → bucket `2026-Q4`; an empty agent set → empty list.
- [ ] **Step 2–4:** run (FAIL), implement, run (PASS).
- [ ] **Step 5: Commit** `feat(reporting): an agent's sales in money, by month, quarter or year`.

### Task 2: reporting — the My sales report, the policies behind a figure, the agent's collection rate

**Interfaces — produces:**

```java
// ReportingApi
List<SalesTotals> salesByPeriod(UUID agentPartyId, UUID onlyAgentId, LocalDate from, LocalDate to, String by);
ReportResult agentSalesReport(UUID agentPartyId, UUID onlyAgentId, LocalDate from, LocalDate to, String by, CompareWith compareWith);
List<PolicySold> policiesSold(UUID agentPartyId, UUID onlyAgentId, LocalDate from, LocalDate to);   // the list behind a figure
CollectionFigures agentCollectionFigures(UUID agentPartyId, LocalDate from, LocalDate to, LocalDate today); // D4
record PolicySold(String policyNumber, String productName, String clientName, LocalDate onCoverFrom, String frequency,
                  BigDecimal premium, BigDecimal premiumAYear, BigDecimal cover, String currency, String agentName) {}
```

  `onlyAgentId` (Me): must be in `resolveAgentTeam(agentPartyId)` or `AccessDeniedException`; null = the whole team.
  `agentSalesReport`: one `ReportSection` "Sales by <month|quarter|year>" with columns Period (TEXT), Policies sold
  (COUNT), Premium a year (MONEY), Single premiums (MONEY), Cover sold (MONEY), Savings deposits taken (MONEY), a
  totals row, `ReportFigure`s for the four money totals compared with the period before or the same period last year
  (`ReportFilter.comparisonSpan` rules — reuse `ClaimsReporting.money`), and a TREND chart of premium a year.
  `agentCollectionFigures`: `FollowUpQueue.figures` with the scope the follow-up list uses for an agent party
  (rows whose `report_policy.agent_id` is in the team) — add an `agentPartyId` parameter to `figures`, null for staff.

- [ ] Tests (`AgentSalesIntegrationTest`, copy `FollowUpQueueIntegrationTest`'s setup): the report's totals equal
  `salesByPeriod`'s; Me with an agent outside the team → AccessDeniedException; `policiesSold` lists exactly the
  October policies of the team with premium a year 1,200,000.00 for the monthly one and null for the single one.
- [ ] Implement, PASS, commit `feat(reporting): my sales report and the policies behind it`.

### Task 3: endpoints and OpenAPI

```java
@GetMapping("/agents/me/sales")          // ?from&to&by=MONTH|QUARTER|YEAR&agentId? -> List<SalesTotals>
@GetMapping("/agents/me/sales/report")   // + compareWith -> ReportResult (the screen renders it like any report)
@GetMapping("/agents/me/sales/export")   // + format -> file, via ReportExporter; recorded with reporting.listDownloaded("My sales", ...)
@GetMapping("/agents/me/sales/policies") // ?from&to&agentId? -> List<PolicySold>
// all @PreAuthorize("hasRole('REALM_AGENTS')"); party from jwt party_id, 403 when absent (FollowUpController.agentParty)
```

- [ ] `ReportingContractTest`: each path valid against the spec as an agent with a random party (empty but valid);
  403 without `party_id`; 403 for a staff token. Commit `feat(reporting): my sales endpoints`.

### Task 4: dashboard — the money figures (and D1)

**Files:** `AgentDashboardView` (append `Money premiumSold, Money premiumSoldBefore, String premiumSoldChangePercent,
Money singlePremiumsSold, Money coverSold, Money savingsDepositsTaken, CollectionFigures customersPaid` — appended so
the JSON order of existing fields is unchanged), `AgentDashboard.build` (+ `UUID onlyAgentId`), `DashboardController`
(`@RequestParam(required = false) UUID agentId`), `openapi-dashboard.yaml`, `DashboardContractTest`,
`RangedDashboardsTest`.

- [ ] D1: `sold`/`soldBefore`/`soldSeries` read `salesByPeriod(..., "MONTH")` (date on cover) instead of
  `policiesIssuedByMonth` — the test asserting the old count changes with a comment citing D1.
- [ ] Money figures from the same call (TZS — the agent's working currency, as `WORKING_CURRENCY` on the finance
  dashboard); change percent with `DashboardPeriod.changePercent`; `customersPaid` from `agentCollectionFigures` for
  the current month.
- [ ] Tests, commit `feat(dashboard): agents see what they sold in money`.

### Task 5: frontend — the dashboard tiles and Me / My team

**Design:** invoke `impeccable` ("agent dashboard money tiles and the Me / My team switch — mockup screen 6's top
row; DESIGN.md; money in full").

- [ ] Tiles: **Premium you sold** (`TZS … a year`, change vs the period before), under it `Cover sold: TZS … ·
  Single premiums: TZS … · Savings deposits taken: TZS … (shown apart)`; **Your customers' premiums paid** (`81%`,
  `Of premiums due this month`); a **My sales report** card linking to `/agents/sales`.
- [ ] Me / My team: shown only when `GET /agents/me/team` returns more than the agent; the choice in `?agent=` (the
  MyCommissionPage rule), passed as `agentId`.
- [ ] Tests (`AgentDashboardPanel.test.tsx`), commit `feat(agents): what you sold, in money, on your dashboard`.

### Task 6: frontend — My sales

**Design:** invoke `impeccable` ("the agent's My sales report — mockup screen 6's 'My sales report' block: From/To,
Quarter·Month·Year, Download; a chart of premium a year; the table; each figure opens the policies behind it").

**Files:** `frontend/src/api/agentSales.ts`, `frontend/src/features/distribution/MySalesPage.tsx` (+ test),
`screens.tsx` (agent route `sales`, group `my-business`, label `My sales`, an unused icon), `lazyPages.ts`.

- [ ] From / To (default January of last year to today, as the mockup), Show by Month · Quarter · Year, Compare with
  (the period before / the same period last year / nothing), Me / My team; render the `ReportResult` with
  `ReportFigures` and `ReportSectionTable` (the same components the staff report page uses); Download Excel / PDF via
  `exportReport`-style blob download from `/agents/me/sales/export`.
- [ ] "Opens the policies behind each figure": each table row's period opens a drawer listing `policiesSold` for that
  bucket (policy links to `/agents/policies/{n}`).
- [ ] Tests, `tsc`, eslint, commit `feat(agents): my sales report, any dates, downloadable`.

### Task 7: end to end and merge

- [ ] `frontend/e2e/agents-my-sales.spec.ts` (auth `e2e/.auth/agent.json`, copy `agents-my-book.spec.ts`): the
  dashboard shows "Premium you sold"; My sales opens, Quarter shows `Q` buckets, Download Excel returns a file.
- [ ] Backend affected classes (ReportQueries, AgentSales, ReportingContract, DashboardContract, RangedDashboards,
  ModularityTests); vitest on reporting, dashboard, distribution, screens; Playwright `--workers=1`:
  `agents-my-sales`, `agents-my-book`, `agents-my-profile`, `staff-dashboard`, `staff-reports-foundation`.
- [ ] Merge `--no-ff`, push, update memory.

## Self-review

- Decision coverage: premium a year (T1, T4–6), cover sold (T1, T4–6), premium collected from customers (D4, T2, T4–5),
  savings apart (D3), dashboard this month/quarter vs before (T4–5, existing range picker), My sales any dates by
  month/quarter/year compared and downloadable (T2–3, T6), opens the policies (T2, T6), Me / My team (T2, T4–6).
- Open points are stated as D1–D4 with facts and a recommendation, not assumed silently.
- Names consistent: `SalesTotals`, `salesTotals`, `salesByPeriod`, `agentSalesReport`, `policiesSold`, `PolicySold`,
  `agentCollectionFigures`, `onlyAgentId`.
