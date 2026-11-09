# Reports batch 1: the new report page and the Accounting statements

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the generic report page into the redesigned one, and upgrade the four Accounting reports. The page has an in-page searchable report list with collapsed areas, a figures band and stat tiles, a bridge chart, prior-period comparison, and a computation tree that opens any statement line down to its postings.

**Architecture:**
- `ReportResult` gains optional `figures`, `chart` and per-row `lineId`/`hasParts`.
- A new `GET /reports/{key}/parts` endpoint returns a line's parts, with both periods' figures and a server-side sum check.
- The comparison is a `compareWith` filter that the server resolves into a second period range.
- The income statement and balance sheet are rebuilt on a small `Statement` tree: lines of accounts, subtotals and totals.
- The ledger gains a paged, period-bounded postings query.
- Frontend: one `ReportsPage` (list + report), `ReportFigures`, `BridgeChart`, and `ReportTree`.

**Tech Stack:** Spring Boot / Spring Modulith (Java 21), Postgres, OpenAPI contract tests; React + TypeScript, zustand, vitest, in-house SVG charts.

**Spec:** `backend/docs/superpowers/specs/2026-11-09-reports-redesign-design.md` (sections 2–4, 5.1, 6, 7, build step 1).

## Global Constraints

- **Every figure, sum, change and % comes from the server.** The browser never adds anything up (DESIGN.md).
- **The dashboard reads report sections by title.** The income statement must keep a `Result` section with columns `outcome` and `amount`. The balance sheet must keep sections titled `Assets` and `Liabilities` with a `balance` total. See `dashboard.application.ReportFigures` and `FinanceDashboard`.
- **Module boundaries:** `reporting` reads the ledger only through `FinaccountingApi`. ModularityTests must stay green.
- **No browser sums, a 12px floor, the chart palette,** and the figures band only as the `hero-scope` gradient (DESIGN.md). `designGuards.test.ts` must stay green.
- **Compare-with values** are `PRIOR_PERIOD`, `SAME_PERIOD_LAST_YEAR` and `NONE`. Accounting statements default to `PRIOR_PERIOD`.
- **Good or bad colouring** is decided per line by the server (`goodWhen: UP|DOWN`): revenue up is good, expenses up is bad.
- **Never run Prettier.** Edit files with the Edit/Write tools. Run Maven on the host with `JAVA_HOME=~/.jdks/jdk-21.0.12.1+1`. Stop the dev backend before a `clean` build.

---

### Task 1: Ledger, paged postings for an account across periods

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/api/FinaccountingApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/application/FinaccountingApiImpl.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/infrastructure/GlPostingRepository.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/api/AccountPostingsView.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/FinaccountingApiIntegrationTest.java` (add a test)

**Interfaces:**
- Produces:
  ```java
  public record AccountPostingsView(String accountCode, String fromPeriod, String toPeriod,
      BigDecimal debitTotal, BigDecimal creditTotal, long count, List<Posting> postings) {
    public record Posting(UUID postingId, UUID journalEntryId, String period, java.time.Instant postedAt,
        PostingDirection direction, BigDecimal amount, String currency, String policyNumber,
        String sourceEvent, String sourceRef, String description) {}
  }
  AccountPostingsView accountPostings(String accountCode, String fromPeriod, String toPeriod, int page, int size);
  ```
  - `accountCode` may be a summary account. Postings of every descendant leaf are included, ordered by period, posted time and id.
  - The totals cover the whole range, not just the page.

- [ ] **Step 1:** Write the failing test. Post two journal entries to 4110 in 2026-09 and one in 2026-10, using the test's existing posting helper. Assert that `accountPostings("4100","2026-09","2026-10",0,2)` returns `count`=3, 2 postings on the page, `creditTotal` = the sum of all three, and that a period outside the range is excluded.
- [ ] **Step 2:** Run it: `./mvnw -o -q test -Dtest=FinaccountingApiIntegrationTest` → FAIL (method missing).
- [ ] **Step 3:** Add the repository queries. Page: `SELECT p FROM GlPosting p WHERE p.tenantId=:t AND p.accountCode IN :codes AND p.period BETWEEN :from AND :to ORDER BY p.period, p.createdAt, p.postingId`. Totals: `SELECT p.direction, SUM(p.amount), COUNT(p) … GROUP BY p.direction`. Resolve descendant leaf codes from the chart (the same walk `trialBalance` uses). Join the journal entry for `postedAt`, `sourceEvent`, `sourceRef` and description.
- [ ] **Step 4:** Run → PASS. Run `ModularityTests`.
- [ ] **Step 5:** Commit: `feat(finaccounting): paged postings for an account across periods`.

### Task 2: The report result grows figures, a chart and line ids; the filter grows compare-with

**Files:**
- Modify: `reporting/api/ReportResult.java`, `ReportSection.java`, `ReportFilter.java`, `ReportDefinitionView.java`
- Create: `reporting/api/ReportFigure.java`, `reporting/api/ReportChart.java`, `reporting/api/CompareWith.java`, `reporting/api/ReportParts.java`
- Modify: `reporting/application/ReportFilters.java`, `reporting/infrastructure/ReportFilterParams.java`
- Modify: `backend/api/openapi/openapi-reporting.yaml`
- Test: `reporting/application/ReportFiltersTest.java`, `reporting/ReportingContractTest.java`

**Interfaces:**
- Produces:
  ```java
  public enum CompareWith { PRIOR_PERIOD, SAME_PERIOD_LAST_YEAR, NONE }
  public record ReportFigure(String key, String label, String value, String prior, String change,
      String changePercent, String goodWhen /* UP|DOWN|null */, boolean lead /* figures band vs tile */) {}
  public record ReportChart(String kind /* BRIDGE */, String description, List<Bar> bars) {
    public record Bar(String label, String value, String kind /* START|UP|DOWN|END */) {}
  }
  public record ReportResult(String key, String title, String filterSummary, Instant generatedAt,
      List<ReportSection> sections, List<ReportFigure> figures, ReportChart chart, String comparisonLabel) {
    public ReportResult(String key, String title, String filterSummary, Instant generatedAt,
        List<ReportSection> sections) { this(key, title, filterSummary, generatedAt, sections, List.of(), null, null); }
  }
  ```
  - A `ReportSection` row may carry the reserved keys `_lineId`, `_hasParts` (`"true"`), `_level` (`"0"|"1"|"2"`), `_kind` (`LINE|SUBTOTAL|TOTAL`) and `_goodWhen`. The table renders them; they are never columns. This keeps `ReportSection` unchanged and every existing report valid.
  - `ReportFilter` gains `CompareWith compareWith` (default `NONE`), plus `withCompareWith(..)` and `comparisonMonths()`, which returns the prior `YearMonth` range:
    - `PRIOR_PERIOD` on a MONTH_RANGE of n months is the n months immediately before;
    - `SAME_PERIOD_LAST_YEAR` is both ends minus 12 months;
    - a MONTH filter is the single prior month or the same month last year.
  - `ReportDefinitionView` gains `boolean comparesPeriods` (accounting: true).
  - `ReportParts`:
    ```java
    public record ReportParts(String lineId, String label, List<Map<String,String>> rows, List<ReportColumn> columns,
        long total, int page, String check, boolean balanced) {}
    ```

- [ ] **Step 1:** Write failing tests in `ReportFiltersTest`:
  - `compareWith=PRIOR_PERIOD` on months 2026-10..2026-10 gives 2026-09..2026-09;
  - on 2026-07..2026-09 it gives 2026-04..2026-06;
  - `SAME_PERIOD_LAST_YEAR` gives 2025-10..2025-10;
  - an unknown value is a `ReportParameterException`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement the records, the canonical constructor (all existing callers compile through the 5-argument overload), the params parsing (`compareWith` query parameter), and `comparisonMonths()`.
- [ ] **Step 4:** In `openapi-reporting.yaml`:
  - add `figures`, `chart` and `comparisonLabel` to `ReportResult` (optional, nullable);
  - add the `ReportFigure`, `ReportChart` and `ReportParts` schemas;
  - add the `compareWith` query parameter to `/reports/{reportKey}` and `/export`;
  - add `comparesPeriods` to `ReportDefinition`.
- [ ] **Step 5:** Run `ReportFiltersTest`, `ReportingContractTest` and `ReportingSpecParsesTest` → PASS.
- [ ] **Step 6:** Commit: `feat(reporting): figures, chart and compare-with on a report`.

### Task 3: The statement tree

**Files:**
- Create: `reporting/application/Statement.java`
- Test: `reporting/application/StatementTest.java`

**Interfaces:**
- Produces:
  ```java
  final class Statement {
    enum Kind { LINE, SUBTOTAL, TOTAL }
    /** A line made of chart accounts (their rolled balances), or of other lines (a subtotal or total). */
    record Line(String id, String label, Kind kind, List<String> accountRoots, List<Term> terms, String goodWhen) {}
    record Term(String lineId, int sign) {}
    record Figures(BigDecimal current, BigDecimal prior) { BigDecimal change(); String percent(); }
    /** Evaluates every line on two ledgers (current, prior), each a list of AccountBalanceView. */
    static Map<String, Figures> evaluate(List<Line> lines, List<AccountBalanceView> current, List<AccountBalanceView> prior);
    /** "1,102.00 + 96.30 + 42.20 = 1,240.50": the sum statement for a line's parts, from the same figures. */
    static String check(List<BigDecimal> parts, List<Integer> signs, BigDecimal total);
  }
  ```
  - An account line's figure is the sum of `LedgerAccounts.classTotal` over its roots, in each class's natural direction.
  - A subtotal is the signed sum of its terms.
  - `percent()` is the change ÷ |prior| × 100 to 1 decimal place, or `null` when prior is 0.

- [ ] **Step 1:** Write failing tests:
  - revenue 4000 = 1,240.50 and expenses 5000 = 720.20 give an insurance service result of 520.30;
  - the prior figures come from the prior ledger;
  - the change and % are 72.40 and 6.2;
  - `check(...)` gives `1,102.00 + 96.30 + 42.20 = 1,240.50`;
  - a negative term is shown with `−`;
  - a prior of 0 gives a `null` percent.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit: `feat(reporting): a statement tree evaluated on two periods`.

### Task 4: The income statement on the statement tree, with comparison, figures and a bridge

**Files:**
- Modify: `reporting/application/IncomeStatementReport.java`
- Test: `reporting/application/AccountingReportsTest.java`

**Interfaces:**
- Consumes: `Statement`, `ReportFilter.comparisonMonths()`, `FinaccountingApi.trialBalanceBetween`.
- Produces: `ReportResult` with these parts:
  - **The main section `Income statement`**, with columns `line` (TEXT), `current` (MONEY), `prior` (MONEY), `change` (MONEY) and `changePercent` (PERCENT). Its rows are, in order:
    - `revenue` = 4000, goodWhen UP;
    - `service-expenses` = 5000, DOWN;
    - `service-result` = revenue − service-expenses, SUBTOTAL, UP;
    - `reinsurance` = 6000, signed as the ledger states, UP;
    - `finance` = 7000, UP;
    - `other` = 8000, DOWN;
    - `profit` = service-result + reinsurance + finance − other, TOTAL, UP.
  - **The `Result` section, unchanged.** The dashboard reads it.
  - **figures:** revenue, service-result and profit (lead=true).
  - **chart BRIDGE:** prior profit (START), then one bar per line's change (UP/DOWN), then current profit (END).
  - **comparisonLabel:** for example "compared with September 2026".
  - When `compareWith=NONE`, the prior, change and % columns are left out and the chart is null.
  - Line rows carry `_lineId` = the line id and `_hasParts=true`.

- [ ] **Step 1:** Write failing tests:
  - two mocked ledgers for 2026-10 and 2026-09 give the rows, the `profit` figure, the bridge bars (START 381.70 … END 412.60) and an unchanged `Result` section;
  - with `compareWith=NONE` there is no `prior` column.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run `AccountingReportsTest` and `FinanceDashboard`'s test → PASS.
- [ ] **Step 5:** Commit: `feat(reporting): income statement with subtotals, comparison and a bridge`.

### Task 5: The balance sheet on the statement tree

**Files:**
- Modify: `reporting/application/BalanceSheetReport.java`
- Test: `AccountingReportsTest.java`

**Interfaces:**
- Produces:
  - **Sections `Assets` and `Liabilities`,** whose titles and `balance` total are kept for the dashboard.
  - **The `Assets` section** has lines: cash (1100), investments (1200), receivables (1300), reinsurance assets (1400), and other assets (1000 minus those).
  - **The `Liabilities` section** has lines: insurance contract liabilities (2100), payables and other (2000 minus 2100).
  - **The `Equity` section** is 3000, plus the current-year result where the existing report already adds it.
  - **The `Check` section** confirms that assets equal liabilities plus equity.
  - **Columns:** current, prior, change. With `compareWith=PRIOR_PERIOD` the prior is through the prior month; with `SAME_PERIOD_LAST_YEAR`, through the same month last year.
  - **Figures:** total assets, total liabilities and net assets.
  - **No chart.**
- [ ] **Step 1:** Write a failing test with mocked `trialBalanceThrough` for both months. **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS, with `FinanceDashboard` tests still green.
- [ ] **Step 5:** Commit: `feat(reporting): balance sheet with comparison and lines`.

### Task 6: The parts endpoint, the computation tree on the server

**Files:**
- Modify: `reporting/api/ReportingApi.java`, `reporting/application/ReportingApiImpl.java`, `reporting/application/Report.java`
- Modify: `reporting/infrastructure/ReportController.java`, `openapi-reporting.yaml`
- Test: `reporting/application/AccountingReportsTest.java`, `reporting/ReportingContractTest.java`

**Interfaces:**
- Produces:
  - `ReportParts parts(String key, String lineId, ReportFilter f, int page)`;
  - `GET /reports/{key}/parts?lineId=…&page=0&<same filters>`.
- `Report` gets a default method `parts(...)` that throws `ReportNotFoundException`; the statement reports override it.
- Line id grammar:
  - `<statementLineId>`: the line's accounts (or, for a subtotal, its terms), each as a row with `_lineId=acct:<code>` and `_hasParts=true`, plus columns current, prior, change and %;
  - `acct:<code>`: if the account has child accounts, they are the rows; for a leaf, its postings come from `accountPostings`, 20 to a page, with columns date (period), reference (sourceRef), description, policy and amount (signed in the account's natural direction), and each row carries `_source=<sourceEvent>:<sourceRef>`.
- `check` is the server's sum statement over **all** parts (not just the page), and `balanced` is whether they equal the line. For postings, the check states the period total from `AccountPostingsView` totals.

- [ ] **Step 1:** Write failing tests:
  - `parts("income-statement","revenue",…)` returns 4100/4200/4300 rows whose check reads `1,102.00 + 96.30 + 42.20 = 1,240.50` and `balanced=true`;
  - `acct:4110` returns a postings page with `total`;
  - an unknown line gives a 404 through the controller;
  - the contract test validates the response against the spec.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS, with ModularityTests green.
- [ ] **Step 5:** Commit: `feat(reporting): the computation tree -- a line's parts, down to postings`.

### Task 7: The trial balance and general ledger upgrades; exports carry comparisons

**Files:**
- Modify: `TrialBalanceReport.java`, `GeneralLedgerReport.java`, `reporting/infrastructure/ReportXlsx.java`, `ReportPdf.java`
- Test: `AccountingReportsTest.java`, `reporting/infrastructure/ReportExportTest.java`

- **Trial balance:**
  - columns opening, debit, credit, closing for the month (opening = through the prior month), plus `prior closing` when comparing;
  - the ledger check section is unchanged;
  - account rows carry `_lineId=acct:<code>`, `_hasParts=true`.
- **General ledger:** switches to `accountPostings` (no 2000-entry in-memory filter), keeps the running balance, and adds a `reference` column (sourceRef) and `_source`.
- **Exports:** XLSX and PDF write the reserved `_*` keys nowhere; they indent by `_level` and bold SUBTOTAL/TOTAL rows; the figures are written as a small block above the first section.

- [ ] **Step 1:** Write failing tests:
  - trial balance opening + debit − credit = closing for a DR account;
  - the general ledger calls `accountPostings`, not `listJournalEntries`;
  - an export of the income statement has no `_lineId` header and bolds `Profit`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5:** Commit: `feat(reporting): trial balance movement, ledger by query, exports with comparisons`.

### Task 8: Frontend, types and the reports page with the in-page list

**Files:**
- Modify: `frontend/src/api/reporting.ts` (types from `npm run generate:api`; add `getReportParts`)
- Create: `frontend/src/features/reporting/ReportsPage.tsx`, `frontend/src/features/reporting/ReportList.tsx`, `frontend/src/features/reporting/reportAreas.ts`
- Modify: `frontend/src/screens.tsx` (`reports` and `reports/:reportKey` both render `ReportsPage`), `frontend/src/lazyPages.ts`
- Delete: `frontend/src/features/reporting/ReportsCataloguePage.tsx`, after moving its rebuild button into `ReportsPage` for ADMIN
- Test: `frontend/src/features/reporting/ReportList.test.tsx`

**Interfaces:**
- `reportAreas.ts` maps the catalogue category to areas, in order:
  - Accounting: the ACCOUNTING category, plus ↗ `IFRS 17 statements` → `/staff/ifrs17-engine`;
  - Policies: the MANAGEMENT reports except clients;
  - Clients;
  - Reinsurance: ↗ `Treaty statements` → `/staff/reinsurance-statements`;
  - Commissions: ↗ `Agent commission` → `/staff/commission`;
  - Regulatory: ↗ `TIRA returns` → `/staff/regulatory-returns`.
  - Each ↗ entry is shown only if the user `can()` reach that screen.
- `ReportList`:
  - a search box, then areas collapsed by default, each with its count;
  - the area containing the current `reportKey` opens itself;
  - typing in search opens every area that has a match and hides the rest;
  - a `<nav aria-label="Reports">`, with each area a `<button aria-expanded>`.

- [ ] **Step 1:** Write a failing vitest:
  - areas start collapsed;
  - the open report's area is expanded;
  - search "balance" shows Balance sheet and Trial balance only;
  - a ↗ entry without permission is absent.
- [ ] **Step 2:** `npx vitest run src/features/reporting` → FAIL. **Step 3:** Implement. **Step 4:** PASS, then `npx tsc -b --noEmit` and `npx eslint src/features/reporting`.
- [ ] **Step 5:** Commit: `feat(console): reports page with a searchable list of collapsed areas`.

### Task 9: Frontend, figures band, tiles, compare-with and the bridge chart

**Files:**
- Create: `frontend/src/features/reporting/ReportFigures.tsx`, `frontend/src/components/charts/BridgeChart.tsx`
- Modify: `frontend/src/features/reporting/ReportFilterBar.tsx`, `reportParams.ts`
- Test: `ReportFigures.test.tsx`, `BridgeChart.test.tsx`, `reportParams.test.ts`

- **ReportFigures:**
  - `lead` figures go in a `hero-scope bg-hero` band: up to three, each with its label above and "up X (Y%) from Z" below, using the server's `change` and `changePercent` and the arrow word "up" or "down";
  - the others are `StatTile`s, with `goodWhen` mapped to the tile's `goodWhen`.
- **BridgeChart:**
  - an in-house SVG in the style of `ColumnChart`;
  - START and END bars in `chart-1`, UP and DOWN bars as floating bars in the good and bad change hues;
  - every bar has a text label with its server value;
  - a `description` for screen readers;
  - it draws only and computes no totals: bar positions are geometry from the server values.
- **The filter bar** gains "Compare with" (Prior period / Same period last year / None) when `definition.comparesPeriods`, kept in the URL as `compareWith`, default `PRIOR_PERIOD`.

- [ ] **Step 1:** Write failing tests:
  - the band shows three lead figures, with "up 30.90 (8.1%) from 381.70";
  - a tile's pill is green for an UP-good rise;
  - the bridge renders one labelled bar per server bar, with no extra total;
  - the URL round-trips `compareWith`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS, plus `designGuards.test.ts`.
- [ ] **Step 5:** Commit: `feat(console): report figures band, tiles, compare-with and a bridge chart`.

### Task 10: Frontend, the computation tree table

**Files:**
- Create: `frontend/src/features/reporting/ReportTree.tsx`
- Modify: `frontend/src/features/reporting/ReportPage.tsx` (renders figures, chart, and `ReportTree` for sections whose rows carry `_lineId`, otherwise the existing `ReportSectionTable`)
- Test: `ReportTree.test.tsx`

**ReportTree:**
- Hidden columns: the reserved `_*` keys.
- Indentation by `_level`. SUBTOTAL rows are bold; the TOTAL row is on the Band ground and bold.
- A row with `_hasParts` gets a ▸ `<button aria-expanded aria-label="Show what makes up {line}">`. Opening it calls `getReportParts(key, lineId, params)` once and caches the result by lineId and params.
- The parts rows are inserted beneath the line, one level deeper, and are themselves expandable.
- After the parts comes a check row: "✓ {check}". When `balanced=false`, it shows "Does not add up: {check}" in the danger hue.
- Postings show the page, then "Show all n" (it fetches the following pages) and "Download".
- **Expand all** opens the statement lines only, not postings. **Collapse all** closes everything.
- The change column shows the server's change, plus a % pill coloured by `_goodWhen`.

- [ ] **Step 1:** Write a failing test with mocked `getReportParts`:
  - opening `Insurance revenue` shows three accounts and the check row;
  - opening `4100` shows postings and "Show all 812";
  - `balanced=false` shows "Does not add up";
  - the toggles have accessible names.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. Run `npx vitest run src/features/reporting src/components/charts` and `designGuards`.
- [ ] **Step 5:** Commit: `feat(console): the computation tree in report tables`.

### Task 11: Verify end to end and merge

- [ ] **Step 1:** Stop the dev backend. Run the affected backend tests: `./mvnw -o -q test -Dtest='Reporting*,AccountingReportsTest,ReportFiltersTest,ReportExportTest,FinaccountingApiIntegrationTest,FinanceDashboard*,ModularityTests'`. Expected: all pass.
- [ ] **Step 2:** Restart the backend (with SMS live per memory) and open `/staff/reports/income-statement` in the browser as a finance user. Check:
  - the list is collapsed with Accounting open;
  - the band, tiles and bridge show;
  - opening Insurance revenue → 4100 → postings works;
  - the check row reads true against the trial balance for the same month.
  Take a screenshot.
- [ ] **Step 3:** Update the reports e2e spec (`frontend/e2e/reports*.spec.ts`) to the new accessible names (the list, the toggles), and run it alone with `--workers=2`.
- [ ] **Step 4:** Commit the e2e changes, then merge `reports-redesign` to main with `--no-ff` and push.

## Self-review

- **Spec coverage:**
  - §2 D3 → Task 8; D4 → Tasks 6 and 10; D1 → Task 2; D6 → Tasks 9 and 10; D7 → Task 8.
  - §3.1 accuracy → Task 3 (check) and Task 6 (balanced).
  - §5.1 → Tasks 4, 5 and 7.
  - Claims, solvency and the other areas are batches 2–5: out of scope here by design.
- **Placeholders:** none. Defaults are stated.
- **Type consistency:** `ReportFigure`, `ReportChart.Bar.kind`, `CompareWith`, `ReportParts` and the reserved row keys are named identically in Tasks 2, 4, 6, 9 and 10.
