# Analytics Plan 2 — Reports Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every report row and dashboard card opens the records behind it; every report offers quarters; every report
says how fresh its figures are; and a "What the figures mean" page explains each figure from the code.

**Architecture:** Additive changes to the reporting module's existing shapes (`ReportColumn`, `ReportResult`,
`ReportDefinitionView`) — no new report engine. Links ride in the row map under hidden `_` keys, the convention rows
already use (`_lineId`, `_kind`, `_source`). Quarters are period presets in the browser over the dates and months every
report already takes — no server change. Freshness is the newest `reporting.applied_event.applied_at`. The catalogue is
read-only, generated from the report definitions plus definitions declared in code beside the figures (decision K1,
like posting rules D10).

**Tech Stack:** Spring Boot 3.3 / Java 21 / JdbcTemplate (reporting), React 19 + TypeScript + Vitest, OpenAPI 3.1
specs in `backend/api/openapi/` (types regenerated with `npm run generate:api`), Playwright e2e.

**Source decisions:** `backend/docs/superpowers/specs/2026-11-11-analytics-prd-assessment.md` — §3.5 drill-down
("Add a `link` column type to `ReportColumn` (policy, claim, client, agent, invoice) and carry filters into queue
URLs"), §3.4/PRD 14 "last updated" ("Make it explicit on each page ('live' vs 'as of last refresh')"), S1 ("more
filters on reports, including quarters"), K1 (catalogue read-only from code), PRD 19 (catalogue fields), Phase 0
items 1–2. Mockup screen 3 (Reports home) at https://claude.ai/artifact/NCgMMcFxs4Xj1D2xsrVbXf: period choices
"This month · Last month · This quarter (Oct–Dec 2026) · Last quarter (Jul–Sep 2026) · This year · Pick dates…" and
the catalogue entry shape "<meaning>. Counts: … Leaves out: … Comes from: …".

## Global Constraints

- Simple English on every label and sentence (the user, 2026-11-11).
- Render only what the platform can prove (PRODUCT.md): no figure summed, averaged or differenced in the browser.
- Host Maven only: `cd backend; export JAVA_HOME=~/.jdks/jdk-21.0.12.1+1; ./mvnw -B -o …`; never Maven in Docker.
- `./mvnw -B -o -q clean test-compile` before trusting a test run (stale IDE classes pass vacuously); compare test
  counts with the previous run.
- Stop the dev backend before a backend test run; restart it after (`backend/.env` sourced by ABSOLUTE path).
- One heavy run at a time (Maven, vitest, Playwright never together); Playwright `--workers=1`, MCP browser closed.
- Never run Prettier. Edit with the Edit/Write tools, not shell.
- Frontend UI tasks invoke the `impeccable` skill and follow DESIGN.md ("The Lit Figure").
- Work on a branch (`analytics-reports-foundation`), `git merge --no-ff` to main, push `origin main`.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Not in this plan (said so, so nothing is assumed)

- **"Show by month / quarter / week"** (mockup screen 3): grouping a report's series by quarter is a server change to
  every report with a series. Not built here; the period presets are.
- **INVOICE links**: an invoice has no page of its own (it lives on the policy's billing tab). Links go to the policy.
- **Figure definitions for the 25 reports' own headline figures** (53 distinct figures, list in Appendix A): each
  needs its code read to be stated truthfully. This plan builds the catalogue, generates every report's entry, and
  writes the Collections figures in full; **Plan 2b** writes the rest, report by report, into the same structure.
- Dashboard cards that are averages or rates (Days to decide, Accepted %, Days to settle) open no list: no list holds
  "an average". Accepted counts ACCEPT + LOADED, which one `outcome` filter cannot express.

## File Structure

Backend (`backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/`):

| File | Responsibility |
|---|---|
| `api/ReportColumn.java` (modify) | optional `link` (POLICY, CLAIM, CLIENT, AGENT) |
| `api/ReportResult.java` (modify) | optional `dataUpTo` (Instant) |
| `api/ReportDefinitionView.java` (modify) | `sources` (what the report reads) and `figures` (definitions) |
| `api/FigureDefinition.java` (new) | one figure explained |
| `api/FigureGroup.java` (new) | a titled group of definitions not tied to one report |
| `api/ReportingApi.java` (modify) | `List<FigureGroup> figureGroups()` |
| `application/Sections.java` (modify) | `link(...)` column helper |
| `application/ReportingApiImpl.java` (modify) | `dataUpTo` on a run; `figureGroups()` |
| `application/CollectionsFigureDefinitions.java` (new) | the Collections definitions, beside their code |
| `application/ProductsReport.java`, `PremiumCollectionsReport.java`, `ClaimsNetOfReinsuranceReport.java`, `ClientsReport.java`, `CommissionsReport.java`, `GeneralLedgerReport.java` (modify) | link columns |
| every `application/*Report.java` (modify) | `.from(...)` sources on its DEFINITION |
| `infrastructure/ReportQueries.java` (modify) | `holderPartyId` on the policy list; `lastAppliedAt` |
| `infrastructure/ReportController.java` (modify) | `GET /reports/figure-definitions` |
| `infrastructure/ReportPdf.java`, `ReportXlsx.java` (modify) | print "Last change taken in" |
| `backend/api/openapi/openapi-reporting.yaml` (modify) | the new fields and path |

Frontend (`frontend/src/`):

| File | Responsibility |
|---|---|
| `features/reporting/reportLinks.ts` (new) | link kind → console path |
| `features/reporting/ReportSectionTable.tsx` (modify) | render link cells |
| `features/reporting/periodPresets.ts` (new) | the period choices, pure |
| `features/reporting/ReportFilterBar.tsx` (modify) | preset chips |
| `features/reporting/ReportPage.tsx` (modify) | the freshness line |
| `features/reporting/FiguresGuidePage.tsx` (new) | "What the figures mean" |
| `features/reporting/ReportsPage.tsx` (modify) | link to the guide |
| `api/reporting.ts` (modify) | `getFigureDefinitions()` and types |
| `api/underwriting.ts`, `store/underwritingStore.ts`, `features/underwriting/UnderwritingQueuePage.tsx` (modify) | decided-date and outcome filters from the URL |
| `api/claims.ts`, `features/claims/ClaimsPage.tsx` (modify) | registered-date filter from the URL |
| `features/dashboard/ClaimsDashboardPanel.tsx`, `FinanceDashboardPanel.tsx` (modify) | the two unlinked cards that have a list |
| `screens.tsx`, `lazyPages.ts` (modify) | the guide's route |

---

### Task 1: backend — link columns

**Files:**
- Modify: `reporting/api/ReportColumn.java`, `reporting/application/Sections.java`,
  `reporting/infrastructure/ReportQueries.java` (`PolicyListRow` + `policyList`),
  `reporting/application/ProductsReport.java:103-125`, `PremiumCollectionsReport.java:99-105`,
  `ClaimsNetOfReinsuranceReport.java:81-91`, `ClientsReport.java:143-150`, `CommissionsReport.java:210-226`,
  `GeneralLedgerReport.java:86,112`, `backend/api/openapi/openapi-reporting.yaml` (`ReportColumn`)
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/ReportQueriesIntegrationTest.java`,
  `ReportingContractTest.java`

**Interfaces:**
- Produces:

```java
// reporting/api/ReportColumn.java
/**
 * @param link what a cell opens (2026-11-11, drill-down): POLICY, CLAIM, CLIENT or AGENT; null for none. The id is the
 *             row's hidden "_" + key + "Id" value, or -- for POLICY only -- the cell's own text (a policy number).
 */
public record ReportColumn(String key, String label, ColumnType type, String link) {
    public static final java.util.Set<String> LINKS = java.util.Set.of("POLICY", "CLAIM", "CLIENT", "AGENT");
    public ReportColumn {
        if (link != null && !LINKS.contains(link)) throw new IllegalArgumentException("Unknown link " + link);
    }
    public ReportColumn(String key, String label, ColumnType type) { this(key, label, type, null); }
}
// reporting/application/Sections.java
static ReportColumn link(String key, String label, String link) { return new ReportColumn(key, label, ColumnType.TEXT, link); }
```

- [ ] **Step 1: Failing test** in `ReportQueriesIntegrationTest` (copy the `products` register tests' setup — find
  them with `grep -n '"products"' ReportQueriesIntegrationTest.java`):

```java
@Test
void thePolicyRegisterLinksEachPolicyItsClientAndItsAgent() {
    UUID person = UUID.randomUUID();
    UUID agent = UUID.randomUUID();
    events.client(tenant, person, "INDIVIDUAL", "VERIFIED", "Asha", d("2025-12-01"), d("1990-01-01"), "FEMALE");
    events.sell(tenant, Sale.of("L-1", product, d("2026-01-05")).holder(person).agent(agent));

    ReportResult result = run("products", ReportFilter.asOf(d("2026-01-31")).withProduct(product));
    ReportSection register = result.sections().stream()
        .filter(s -> s.columns().get(0).key().equals("policyNumber")).findFirst().orElseThrow();

    assertThat(register.columns()).extracting(ReportColumn::key, ReportColumn::link).contains(
        tuple("policyNumber", "POLICY"), tuple("client", "CLIENT"), tuple("agent", "AGENT"));
    assertThat(register.rows()).singleElement().satisfies(row -> {
        assertThat(row.get("policyNumber")).isEqualTo("L-1");
        assertThat(row.get("_clientId")).isEqualTo(person.toString());
        assertThat(row.get("_agentId")).isEqualTo(agent.toString());
    });
}
```

  If `ReportFilter` has no `withProduct`, build the filter the way the existing products-register test does (read it
  first; do not add API to `ReportFilter` for a test).

- [ ] **Step 2: Run** `./mvnw -B -o test -Dtest=ReportQueriesIntegrationTest` — expect a compile failure.

- [ ] **Step 3: Implement.**
  - `ReportColumn` and `Sections.link` as above.
  - `ReportQueries.PolicyListRow`: add `UUID holderPartyId` after `holder`; `policyList` selects
    `p.holder_party_id` and reads `rs.getObject("holder_party_id", UUID.class)`. Fix every constructor call
    (`grep -rn "new PolicyListRow" backend/src`).
  - `ProductsReport.register`: `link("policyNumber", "Policy", "POLICY")`, `link("client", "Client", "CLIENT")`,
    `link("agent", "Agent", "AGENT")`; in the row put `"_clientId"` = `p.holderPartyId()` (when not null) and
    `"_agentId"` = `p.agentId()` (when not null). A row with no id shows plain text (the frontend's rule, Task 2).
  - `PremiumCollectionsReport` top policies: `link("policyNumber", "Policy", "POLICY")`.
  - `ClaimsNetOfReinsuranceReport`: `link("policy", "Policy", "POLICY")`; the total row's `"policy"` is `"Total"`
    and has no `_policyId` — Task 2 leaves a total row unlinked.
  - `ClientsReport` schemes: `link("policyNumber", "Scheme", "POLICY")`.
  - `CommissionsReport.agents`: `link("agent", "Agent", "AGENT")`; put `"_agentId"` = `agentId.toString()` when the
    agent id is not null.
  - `GeneralLedgerReport`: `link("policyNumber", "Policy", "POLICY")` (an empty cell stays plain).
  - Exports need no change: `ReportCsv`/`ReportXlsx`/`ReportPdf` print by column and never print `_` keys — confirm
    with `grep -n '"_' backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/infrastructure/Report*.java`
    (expect nothing); if one iterates row keys instead of columns, filter keys starting with `_`.
  - `openapi-reporting.yaml` `ReportColumn`: add `link: { type: [string, "null"], enum: [POLICY, CLAIM, CLIENT, AGENT, null] }`
    (not required — older clients ignore it).
- [ ] **Step 4: Run** `ReportQueriesIntegrationTest,ReportingContractTest,ReportingSpecParsesTest` — PASS; the
  contract test's existing `isValid(SPEC)` checks now cover `link`.
- [ ] **Step 5: Commit** `feat(reporting): report tables link each policy, client and agent to its record`.

---

### Task 2: frontend — link cells

**Files:**
- Create: `frontend/src/features/reporting/reportLinks.ts`, `reportLinks.test.ts`
- Modify: `frontend/src/features/reporting/ReportSectionTable.tsx` (render), its test if one exists
  (`ls frontend/src/features/reporting/*.test.tsx`)

**Interfaces:**
- Consumes: `ReportColumn.link`, hidden row keys `_<key>Id` (Task 1). Regenerate types first: `npm run generate:api`.
- Produces:

```ts
// reportLinks.ts
export type LinkKind = 'POLICY' | 'CLAIM' | 'CLIENT' | 'AGENT';
/** Where a report cell opens, or null when the row names nothing to open (a total row, an empty cell). */
export function linkFor(kind: LinkKind | null | undefined, key: string, row: Record<string, string>): string | null {
  if (!kind) return null;
  const id = row[`_${key}Id`] ?? (kind === 'POLICY' ? row[key] : undefined);
  if (!id || id === 'Total') return null;
  const base = { POLICY: '/staff/policies/', CLAIM: '/staff/claims/', CLIENT: '/staff/parties/', AGENT: '/staff/agents/' }[kind];
  return base + encodeURIComponent(id);
}
```

- [ ] **Step 1: Failing test** `reportLinks.test.ts`: POLICY with the cell text → `/staff/policies/POL-1`; CLIENT with
  `_clientId` → `/staff/parties/<id>`; CLIENT without `_clientId` → null; POLICY on the `Total` row → null; no kind →
  null.
- [ ] **Step 2: Run** `npx vitest run src/features/reporting/reportLinks.test.ts` — FAIL.
- [ ] **Step 3: Implement** `reportLinks.ts`; in `ReportSectionTable.render`, when `!line.total` and
  `linkFor(c.link, c.key, line.cells)` is a path, wrap the formatted text in
  `<Link to={path} className="font-medium underline-offset-2 hover:underline">` (react-router `Link`); otherwise the
  text as today. Add a test in a `ReportSectionTable.test.tsx` (create if absent, render with `renderScreen` from
  `@/test/renderScreen`): a section with a POLICY column renders `getByRole('link', { name: 'POL-1' })` with
  `href="/staff/policies/POL-1"`, and the total row's cell is not a link.
- [ ] **Step 4: Run** the two tests, `npx tsc --noEmit -p .`, `npx eslint src/features/reporting` — PASS.
- [ ] **Step 5: Commit** `feat(reports): policy, client and agent cells open their records`.

---

### Task 3: frontend — dashboard cards keep their filter

**Files:**
- Modify: `frontend/src/api/underwriting.ts` (`UnderwritingListParams`, `listCases`),
  `frontend/src/store/underwritingStore.ts` (if it narrows params), `frontend/src/features/underwriting/UnderwritingQueuePage.tsx`,
  `frontend/src/api/claims.ts` (`ClaimSearchParams`, `searchClaims`), `frontend/src/features/claims/ClaimsPage.tsx`,
  `frontend/src/features/dashboard/ClaimsDashboardPanel.tsx` (Registered card),
  `frontend/src/features/dashboard/FinanceDashboardPanel.tsx` (New policies card)
- Test: `UnderwritingQueuePage` and `ClaimsPage` tests (find with `ls frontend/src/features/{underwriting,claims}/*.test.tsx`;
  create `UnderwritingQueuePage.test.tsx` / `ClaimsPage.test.tsx` if absent), `ClaimsDashboardPanel.test.tsx`,
  `FinanceDashboardPanel.test.tsx`

Facts (checked 2026-11-11):
- `GET /underwriting/cases` already takes `outcome` (ACCEPT, LOADED, DECLINED, POSTPONED), `decidedFrom`, `decidedTo`
  (`UnderwritingController.java:95-117`); the dashboard already links `?decidedFrom=…&decidedTo=…`
  (`UnderwritingDashboardPanel.tsx:38`) but `UnderwritingQueuePage` reads neither, so the filter is lost.
- `GET /claims` already takes `registeredFrom`, `registeredTo` (`ClaimController.java:138-139`); the frontend never
  sends them.
- `new-business` is a PERIOD report (`from`, `to`); `reportLink` and `lastDayOf` are in
  `features/dashboard/reportLinks.ts`.

- [ ] **Step 1: Failing tests.**
  - Underwriting queue rendered at `/staff/underwriting?decidedFrom=2026-07-01&decidedTo=2026-09-30&outcome=DECLINED`
    calls `listCases` with those three, and shows a removable chip reading `Decided Jul 1, 2026 to Sep 30, 2026 ·
    Declined` (dates with `formatDate`); clicking the chip's remove button reloads without them.
  - Claims list at `/staff/claims?registeredFrom=2026-07-01&registeredTo=2026-09-30` calls `searchClaims` with both
    and shows `Registered Jul 1, 2026 to Sep 30, 2026` with a remove button.
  - Claims dashboard: the Registered card links to
    `/staff/claims?registeredFrom=2026-07-01&registeredTo=2026-09-30` for RANGE `2026-07`..`2026-09`.
  - Finance dashboard: the New policies card links to
    `/staff/reports/new-business?from=2026-07-01&to=2026-09-30`.
- [ ] **Step 2: Run** the four test files — FAIL.
- [ ] **Step 3: Implement.**
  - `UnderwritingListParams` += `decidedFrom?: string; decidedTo?: string; outcome?: string`; `listCases` sends each
    when set (the same spread shape as `status`).
  - `UnderwritingQueuePage`: read the three from `params`; pass to `loadList` (and to the retry call at line ~221);
    add them to the effect's dependency list; render the chip above the table with the copy above, its remove button
    labelled `Clear the decided dates` deleting the three params and `page`.
  - `ClaimSearchParams` += `registeredFrom?: string; registeredTo?: string`; `searchClaims` sends them; `ClaimsPage`
    reads them, passes them, renders the chip (remove button `Clear the registered dates`).
  - `ClaimsDashboardPanel` Registered stat: `to: \`/staff/claims?registeredFrom=${p.from}-01&registeredTo=${lastDayOf(p.to)}\``.
  - `FinanceDashboardPanel` New policies stat: `to: reportLink('new-business', { from: \`${p.from}-01\`, to: lastDayOf(p.to) })`.
- [ ] **Step 4: Run** the tests, `tsc`, eslint on the touched files — PASS.
- [ ] **Step 5: Commit** `feat(dashboards): cards open their list with the same dates`.

---

### Task 4: frontend — quarters on every report

**Files:**
- Create: `frontend/src/features/reporting/periodPresets.ts`, `periodPresets.test.ts`
- Modify: `frontend/src/features/reporting/ReportFilterBar.tsx` (the date row at ~line 82)

**Design:** invoke `impeccable` ("add the period choices to the report filter bar — mockup screen 3's 'Period: This
month · Last month · This quarter (Oct–Dec 2026) · Last quarter (Jul–Sep 2026) · This year · Pick dates…'; reuse
FilterChip; the date fields stay as 'Pick dates'").

**Interfaces:**
- Consumes: `ReportFilterState` (`reportParams.ts`), `DateFilterKind`.
- Produces:

```ts
// periodPresets.ts
export interface PeriodPreset { id: string; label: string; set: Partial<ReportFilterState> }
/**
 * The period choices a report of this kind offers, ending no later than today (a report cannot show tomorrow).
 * PERIOD / AS_OF_AND_PERIOD: from/to dates. MONTH_RANGE: monthFrom/monthTo. MONTH: monthFrom. AS_OF: asOf.
 * COHORT: none (activation months are not a calendar period).
 */
export function periodPresets(kind: ReportDefinition['dateFilterKind'], today: Date): PeriodPreset[];
/** The preset the state currently matches, or null ("Pick dates"). */
export function activePreset(presets: PeriodPreset[], state: ReportFilterState): string | null;
```

  The choices, exactly (quarters are calendar quarters Jan–Mar, Apr–Jun, Jul–Sep, Oct–Dec; month names short English):

| kind | ids and labels | values |
|---|---|---|
| PERIOD, AS_OF_AND_PERIOD | `month` This month · `lastMonth` Last month · `quarter` This quarter (Oct–Dec 2026) · `lastQuarter` Last quarter (Jul–Sep 2026) · `year` This year | from = first day; to = last day, or today when that is later than today |
| MONTH_RANGE | the same five | monthFrom/monthTo = first and last month, the last capped at this month |
| MONTH | `month` This month · `lastMonth` Last month | monthFrom |
| AS_OF | `today` Today · `endLastMonth` End of last month · `endLastQuarter` End of last quarter · `endLastYear` End of last year | asOf |

- [ ] **Step 1: Failing tests** (`today = new Date(2026, 10, 11)` — 11 Nov 2026): PERIOD `quarter` → from
  `2026-10-01`, to `2026-11-11`, label `This quarter (Oct–Dec 2026)`; `lastQuarter` → `2026-07-01`..`2026-09-30`,
  label `Last quarter (Jul–Sep 2026)`; `year` → `2026-01-01`..`2026-11-11`; MONTH_RANGE `lastQuarter` → `2026-07`..
  `2026-09`; AS_OF `endLastQuarter` → `2026-09-30`; in January (`new Date(2027, 0, 5)`) `lastQuarter` is
  Oct–Dec 2026 and `lastMonth` is December 2026; COHORT → `[]`; `activePreset` finds `quarter` for its own values and
  null for other dates.
- [ ] **Step 2: Run** `npx vitest run src/features/reporting/periodPresets.test.ts` — FAIL.
- [ ] **Step 3: Implement** with local-date arithmetic (the `isoDate`/`isoMonth` helpers in `reportParams.ts`: never
  `toISOString`, which is UTC). In `ReportFilterBar`, above the date fields, a labelled row `Period` of `FilterChip`s
  (one per preset, `active` = `activePreset(...) === id`) and a last chip `Pick dates…` active when none match;
  choosing a preset merges `set` into the state (it does not run the report — Run does, as today). The date fields
  stay visible and editable.
- [ ] **Step 4: Run** the test, `ReportList.test.tsx`, `reportParams.test.ts`, `tsc`, eslint — PASS. Check in the
  browser: Premium collections → This quarter → Run shows `Oct 1, 2026 to Nov 11, 2026` (or today) in the summary.
- [ ] **Step 5: Commit** `feat(reports): this quarter, last quarter and this year on every report`.

---

### Task 5: backend — what each report reads, and how fresh it is

**Files:**
- Modify: `reporting/api/ReportDefinitionView.java`, `reporting/api/ReportResult.java`,
  `reporting/application/ReportingApiImpl.java` (`run`), `reporting/infrastructure/ReportQueries.java`
  (`lastAppliedAt`), every `reporting/application/*Report.java` DEFINITION (`.from(...)`),
  `reporting/infrastructure/ReportPdf.java:36-39`, `ReportXlsx.java:86`, `openapi-reporting.yaml`
  (`ReportDefinition`, `ReportResult`)
- Test: `ReportQueriesIntegrationTest`, `ReportingContractTest`, the PDF/XLSX tests
  (`ls backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/infrastructure`)

**Interfaces:**
- Produces:

```java
// ReportDefinitionView: two new components, appended after comparesPeriods
//   List<String> sources     -- REPORTING_COPY, LEDGER, BILLING, DISTRIBUTION, REINSURANCE
//   List<FigureDefinition> figures  -- Task 7; empty until written
// every existing constructor keeps working and passes List.of(), List.of(); plus:
public ReportDefinitionView from(String... sources)   // a copy with these sources
public ReportDefinitionView explaining(FigureDefinition... figures)   // Task 7
// ReportResult: one new component, appended last: Instant dataUpTo -- the newest change the reporting copy has
//   taken in for this tenant; null when the report does not read the copy. Existing constructors pass null.
// ReportQueries
public Instant lastAppliedAt(UUID tenantId)   // max(applied_at) from reporting.applied_event, null when none
```

  Sources per report — from each report's constructor (checked 2026-11-11). `ReportQueries` = REPORTING_COPY;
  `FinaccountingApi` and `SolvencyReports` (which reads `FinaccountingApi`) = LEDGER:

| report key | sources |
|---|---|
| policies-in-force, products, new-business, lapse-rate, persistency, free-look-and-not-taken-up, clients, claims-incurred, loss-ratio, claim-outcomes, claims-approved-vs-paid, claims-turnaround, early-claims, claims-outstanding | REPORTING_COPY |
| premium-collections | REPORTING_COPY, BILLING |
| commissions | DISTRIBUTION, REPORTING_COPY |
| claims-net-of-reinsurance | REPORTING_COPY, REINSURANCE |
| reinsurance | REINSURANCE |
| trial-balance, general-ledger, balance-sheet, income-statement, capital-adequacy, stress-tests, solvency-forecast | LEDGER |

- [ ] **Step 1: Failing tests.**
  - `ReportQueriesIntegrationTest`: after `events.sell(...)`, `reportingApi.run("policies-in-force", …).dataUpTo()` is
    not null and not after `Instant.now()`; `run("trial-balance", ReportFilter.month(YearMonth.of(2026, 9))).dataUpTo()`
    is null.
  - A test that every catalogue entry declares at least one source:
    `assertThat(reportingApi.catalogue()).allSatisfy(d -> assertThat(d.sources()).isNotEmpty())`.
  - PDF test: the subtitle holds `Last change taken in` when `dataUpTo` is set (copy the existing preparedBy test).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.**
  - `lastAppliedAt`: `SELECT max(applied_at) FROM reporting.applied_event WHERE tenant_id = ?` →
    `jdbc.queryForObject(..., java.sql.Timestamp.class, tenantId)`, null-safe to `Instant`.
  - `ReportingApiImpl.run`: `dataUpTo = definition.sources().contains("REPORTING_COPY") ? queries.lastAppliedAt(tenant) : null`.
  - `.from(...)` on each DEFINITION per the table.
  - `ReportPdf.document`: after the Prepared line, when `dataUpTo` is set,
    `subtitle.add("Last change taken in " + ReportFormat.stamp(result.dataUpTo()))`; `ReportXlsx.summary`: the same
    line as a cell in the next row.
  - OpenAPI: `ReportDefinition.sources` (array of the five enums, required), `ReportDefinition.figures` (array of
    `FigureDefinition`, required — Task 7 adds the schema; add it now with Task 7's fields), `ReportResult.dataUpTo`
    (`[string, "null"]`, date-time, not required).
- [ ] **Step 4: Run** `ReportQueriesIntegrationTest,ReportingContractTest,ReportingSpecParsesTest` and the export
  tests — PASS.
- [ ] **Step 5: Commit** `feat(reporting): every report says what it reads and the last change it took in`.

---

### Task 6: frontend — the freshness line

**Files:** Modify `frontend/src/features/reporting/ReportPage.tsx` (below the PageHeader description, ~line 84);
test in the report page test (find with `grep -ln "ReportPage" frontend/src/features/reporting/*.test.tsx`; create
`ReportPage.test.tsx` if absent, mocking `@/api/reporting`).

Copy, exactly (Micro, Subtle Ink; `formatInstant` from `@/lib/dates`):
- reads the copy: `Last change taken in: <formatInstant(dataUpTo)>` and, when it also reads a live source,
  ` · <live words> read live`;
- reads only live sources: `Read live from <live words>`;
- live words: LEDGER `the ledger`, BILLING `billing`, DISTRIBUTION `agents and commission`, REINSURANCE `reinsurance`,
  joined with `and`.

- [ ] **Step 1: Failing test:** a result with `dataUpTo` and definition sources `['REPORTING_COPY', 'BILLING']` shows
  `Last change taken in:` and `billing read live`; a LEDGER report shows `Read live from the ledger`.
- [ ] **Step 2–4:** implement, run the test, `tsc`, eslint.
- [ ] **Step 5: Commit** `feat(reports): each report says how fresh its figures are`.

---

### Task 7: backend — figure definitions and the Collections group

**Files:**
- Create: `reporting/api/FigureDefinition.java`, `reporting/api/FigureGroup.java`,
  `reporting/application/CollectionsFigureDefinitions.java`
- Modify: `reporting/api/ReportingApi.java`, `reporting/application/ReportingApiImpl.java`,
  `reporting/infrastructure/ReportController.java`, `openapi-reporting.yaml`
- Test: `ReportingContractTest` (path + schema), a unit test `CollectionsFigureDefinitionsTest`

**Interfaces:**

```java
/**
 * One figure explained (PRD 19, decision K1): read-only, written beside the code that works the figure out, so a
 * change to the formula is a code change that must change these words too.
 */
public record FigureDefinition(String key, String name, String meaning, String counts, String leavesOut,
                               String comesFrom, String dateBasis) {}
/** Definitions not tied to one report: the Collections figures shared by the follow-up list and the dashboard. */
public record FigureGroup(String title, List<FigureDefinition> figures) {}
// ReportingApi
List<FigureGroup> figureGroups();
// GET /reports/figure-definitions -> List<FigureGroup>, @PreAuthorize("@access.can('reports.view')")
```

The Collections group, word for word — each sentence checked against `FollowUpQueue.figures` /
`FollowUpQueue.scoped` / `BillingApiImpl.invoicesDueBetween` / `BillingApiImpl.receivablesAging` (2026-11-11):

| key | name | meaning | counts | leavesOut | comesFrom | dateBasis |
|---|---|---|---|---|---|---|
| due | Premiums due | Every premium that fell due in the period, paid or not. | The premium charged on each one, added up. | Waived premiums; savings and deposit accounts (they take deposits, not premiums); amounts not in TZS. | Billing. | The day each premium fell due, from the period's first day to its last, never later than today. |
| paid | Premiums paid | Of the premiums that fell due in the period, how much is paid. | Premiums due, less what is still owed on them (charged, less paid, less credited back). Money paid early counts when the premium falls due. | The same as Premiums due. | Billing. | As Premiums due. |
| rate | Collection rate | Of the premiums that fell due in the period, the share that has been paid. | Premiums paid ÷ premiums due, to one decimal. None when nothing fell due. | The same as Premiums due. | Billing. | As Premiums due. |
| overdueNow | Overdue now | Premiums past their due day that are still owed, today. | Each premium still owed whose due day is before today, and what is owed on it. | Offers never accepted; premiums an agent's cash already covers; amounts not in TZS. | Billing, with product and agent from the reports' own copy. | Today. |
| dueToday | Due today | Premiums falling due today that are still owed. | As Overdue now, for premiums due today. | As Overdue now. | As Overdue now. | Today. |
| owedToday | Owed today | Every unpaid premium due by today, by how late it is. | Each open premium due by today (due, in grace, overdue or part paid), charged less paid less credited back, and any other with an open arrears case. Bands: not yet overdue, 1–7, 8–14, 15–30, 31–60, 61–90, over 90 days. | Offers never accepted; premiums paid or waived. Cash an agent holds is still owed until it reaches the company. | Billing. | Today. |

- [ ] **Step 1: Failing tests:** `CollectionsFigureDefinitionsTest` — the group is titled `Collections`, holds the six
  keys in that order, and no field is blank; `ReportingContractTest` — `GET /reports/figure-definitions` as
  FINANCE_OFFICER is 200 and valid against the spec; as UNDERWRITER 403.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** the records, `CollectionsFigureDefinitions.GROUP` (a `static final FigureGroup` holding the
  table above), `ReportingApiImpl.figureGroups()` returning `List.of(CollectionsFigureDefinitions.GROUP)`, the
  controller method, and `ReportDefinitionView.explaining(...)` (Task 5's signature) — no report calls it yet (Plan 2b).
  Add a comment above `FollowUpQueue.figures` and `BillingApiImpl.receivablesAging`: "Explained in
  CollectionsFigureDefinitions -- change the words with the formula."
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(reporting): the Collections figures explained, read-only from the code`.

---

### Task 8: frontend — "What the figures mean"

**Design:** invoke `impeccable` ("build the 'What the figures mean' page — mockup screen 3's example card: name,
one-line meaning, then Counts / Leaves out / Comes from; follow DESIGN.md; simple English; Read mode"). The page is
read-only.

**Files:**
- Create: `frontend/src/features/reporting/FiguresGuidePage.tsx` (+ `.test.tsx`)
- Modify: `frontend/src/api/reporting.ts` (`FigureDefinition`, `FigureGroup` types; `getFigureDefinitions()`),
  `frontend/src/features/reporting/ReportsPage.tsx` (a `What the figures mean` link in the PageHeader actions),
  `frontend/src/screens.tsx` (`{ path: 'reports/what-the-figures-mean', element: <FiguresGuidePage />, reach: 'drill-in' }`
  placed BEFORE `reports/:reportKey`), `STAFF_PERMISSION` (`'reports/what-the-figures-mean': ['reports.view']`),
  `frontend/src/lazyPages.ts`.

Content (nothing more):
- Header `What the figures mean`; description `How each figure is worked out, read from the platform's own code.
  A change to a formula changes these words with it.`
- Section `Collections`: one card per definition — name (Title), meaning, then three labelled lines `Counts`,
  `Leaves out`, `Comes from`, and `Dates: <dateBasis>`.
- Section `Reports`: one entry per report from `GET /reports/catalogue`: title (links to the report), description,
  `Period: <words for its dateFilterKind>` (AS_OF `On one day`, PERIOD `Between two days`, AS_OF_AND_PERIOD `On one
  day, and between two days`, MONTH `One month`, MONTH_RANGE `A run of months`, COHORT `Policies that started in a
  run of months, followed forward`), `Comes from: <sources in words>` (Task 6's words; REPORTING_COPY `the reports'
  own copy, updated as things happen`), `Compares with: the period before, or the same period last year` when
  `comparesPeriods`, and the report's `figures` when it has any (none until Plan 2b — then no figure list is drawn).

- [ ] **Step 1: Failing test:** with both APIs mocked, the page shows the Collections card `Collection rate` with its
  `Counts` line, and a report entry `Premium collections` with `Comes from: the reports' own copy, updated as things
  happen, and billing` and a link to `/staff/reports/premium-collections`.
- [ ] **Step 2–4:** build (impeccable), run the test, `screens.test.ts`, `tsc`, eslint.
- [ ] **Step 5: Commit** `feat(reports): what the figures mean`.

---

### Task 9: end to end and merge

- [ ] **Step 1: e2e** `frontend/e2e/staff-reports-foundation.spec.ts` (auth as `e2e/.auth/staff-finance.json`, copy
  `staff-follow-up.spec.ts`): Reports → Policies by product shows a policy link that opens `/staff/policies/…`;
  Premium collections → `This quarter` → Run → the summary names the quarter's first day; the report shows `Last
  change taken in`; Reports → `What the figures mean` shows `Collection rate`; Dashboard (finance) → New policies
  opens New business with the range's dates.
- [ ] **Step 2: Backend** (dev backend stopped): `clean test-compile`; `ReportQueriesIntegrationTest,
  ReportingContractTest, ReportingSpecParsesTest, FollowUpQueueIntegrationTest, ModularityTests` plus the export
  tests; restart the dev backend.
- [ ] **Step 3: Frontend:** vitest on `src/features/reporting src/features/dashboard src/features/underwriting
  src/features/claims src/screens.test.ts src/test/designGuards.test.ts`; then Playwright `--workers=1`:
  `staff-reports-foundation`, the existing reports spec(s) (`ls frontend/e2e | grep -i report`), `staff-dashboard`,
  `staff-dashboard-charts`, the underwriting queue and claims list specs (`ls frontend/e2e | grep -iE "underwriting|claims"`).
  A failure: re-run that spec alone before treating it as a regression.
- [ ] **Step 4:** merge `--no-ff` to main, push, update memory.

---

## Appendix A — figures for Plan 2b (from `grep` of the reporting code, 2026-11-11)

Counts: Number of claims, Open at the end, Declined, Lapsed, Large claims (20m or more), New policies, Still open at the
end, Policies in force, Claims registered, Registered, Reinstated, Surrendered. Money: Annualised premium, Available
capital, Average claim, Commission booked, Claims incurred, Clawed back, Credit risk, Claims approved (gross),
Insurance risk, Market risk, Kept (net of reinsurance), Netted at settlement, Operational risk, Overrides, Paid on
statements, Paid, Premium collected, Premium next 12 months, Recoverable from reinsurers, Required capital, Result next
12 months, Sum assured, Approved not yet paid, Approved still unpaid, Booked not yet paid. Rates and days: Declined of
those decided, Paid within N days, Capital adequacy ratio (now / in 12 months / high / low), Commission ÷ premium
collected, Loss ratio, Share of claims registered, Share recovered, Days to a decision, Days from approval to payment,
Worst case. Plus the reports whose figures come from their first total row (`Sections.figuresFromTotals`) and the
dashboards' own cards. Each is written from its report's code into `DEFINITION.explaining(...)`.

## Self-review

- Spec coverage: drill-down links (Tasks 1–2), cards carry filters (3), quarters (4), last updated / live vs copy
  (5–6), catalogue read-only from code (7–8), Phase 0 items 1–2. INVOICE links, "Show by", and per-report figure
  words are stated as not in this plan, with the reason.
- No placeholders: every copy string is given; every fact cites the file it was checked in.
- Names used consistently: `ReportColumn.link`, `_<key>Id`, `linkFor`, `periodPresets`, `activePreset`,
  `ReportDefinitionView.sources/figures/from/explaining`, `ReportResult.dataUpTo`, `lastAppliedAt`,
  `FigureDefinition`, `FigureGroup`, `figureGroups`, `/reports/figure-definitions`.
