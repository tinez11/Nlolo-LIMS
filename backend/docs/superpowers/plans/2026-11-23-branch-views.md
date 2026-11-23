# Branch Views Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A branch choice on the policy-and-claims reports and the management, underwriting and claims dashboards, starting on a non-head-office home branch; company-wide reports say so.

**Architecture:** Reports take the existing `branchCode` filter (already applied by `ReportSql`); company-wide reports refuse it. Dashboards take `branchCode`: underwriting figures filter on `underwriting_case.branch_code`; claims figures pass the branch's policy numbers (from a new reporting API method over `report_policy`) into the claims API, which already filters `searchClaims` by policy numbers; management figures come from the reports with the branch. `/me/access` gains `homeBranch`; the console reads branches and the head office from `GET /organisation/branches`. The choice lives in the page address as `branch`.

**Tech Stack:** Spring Boot 3 / Spring Modulith, JdbcTemplate/JPA, React + react-router + zustand, Vitest, Playwright.

**Spec:** `backend/docs/superpowers/specs/2026-11-23-branch-views-design.md` (with the claims refinement recorded in Task 0).

## Global Constraints

- One parameter name end to end on the server: `branchCode` (reports already parse it); in the console's address: `branch` (`branch=ARU`, `branch=all`, absent = the starting rule).
- Starting rule: home branch if set and not the head office (`headOffice: true` in `/organisation/branches`), else all branches.
- "Yes" reports: new-business, policies-in-force, persistency, lapse-rate, free-look, products, clients, premium-collections, commissions, claim-outcomes, claims-incurred, claims-in-progress, claim-turnaround, outstanding-claims, early-claims, approved-vs-paid, claims-net-of-reinsurance, loss-ratio. Company-wide: balance-sheet, trial-balance, general-ledger, income-statement, capital-adequacy, solvency-forecast, stress-test, reinsurance. A "Yes" report found to read the ledger moves to company-wide.
- Screen words: "Arusha branch" (name); exports: "Branch: Arusha (ARU)"; company-wide: "Company-wide"; own branch option: "Arusha (your branch)" (none for head-office staff); unbranched figure: "All branches".
- Existing components and tokens only (DESIGN.md); read impeccable's `reference/craft-floor.md` before any UI edit.
- No dashboard figure changes when no branch is chosen (all existing tests stay green unchanged).
- Backend: run affected test classes only (memory: full suite is a merge gate); stop the dev backend before `clean`. Vitest from PowerShell with an uppercase `C:` cwd. E2E the documented way (`npm run test:e2e`, one worker) and only at the end.
- Edit with Edit/Write; commit with explicit paths.

---

### Task 0: Record the claims refinement in the spec

- [ ] In the spec's Backend → Dashboards → **Claims** bullet, replace "from `reporting.report_claim` joined to `report_policy`… through a new reporting API method" with: "the branch's policy numbers come from a new reporting API method over `report_policy`; the claims API's own queries take them (`searchClaims` already does; the by-month figures and the open-document-request count gain an overload), so claim statuses stay the claims module's own rules."
- [ ] Commit: `docs(spec): claims branch figures keep the claims module's own status rules`.

### Task 1: Reports — company-wide flag, branch names, export summary, branch policy numbers

**Files:**
- Modify: `reporting/api/ReportDefinitionView.java` (add `companyWide`), the 8 company-wide `*Report.java` definitions, `reporting/application/ReportFilters.java`, `FilterSummary.java`, `Labels.java`, `reporting/api/ReportResult.java` (add `exportSummary`), `ReportingApiImpl.java`, `infrastructure/ReportPdf.java`, `ReportXlsx.java` (and the CSV writer if it prints the summary), `reporting/api/ReportingApi.java` (+ `policyNumbersInBranch`), `infrastructure/ProjectionStore.java`
- Modify: `backend/api/openapi/openapi-reporting.yaml` (`companyWide`, `exportSummary`)
- Test: `reporting/ReportingTestEvents.java` (+ `Sale.branch(String)`), new `reporting/BranchCutReportsIntegrationTest.java`, `ReportingContractTest` (company-wide refusal over HTTP)

**Interfaces — produces:**
- `ReportDefinitionView.companyWide()` (boolean); builder `companyWide()` returning a copy with it true.
- `ReportResult.exportSummary()` (String): the summary with `Branch: <name> (<code>)`; `filterSummary()` carries `<name> branch`.
- `ReportingApi.policyNumbersInBranch(String branchCode)`: `Set<String>` of `report_policy.policy_number` for the current tenant and branch.
- `Labels.branch(String code)`: the register's name, or the code when unknown.

- [ ] **Step 1: Failing tests.** `BranchCutReportsIntegrationTest` (copy `ReportQueriesIntegrationTest`'s setup): seed two sales in `DSM` and two in `ARU` with different premiums and sums assured, a collection on one policy of each, and a claim (registered, approved, settled) on one policy of each, through `ReportingTestEvents`. For every "Yes" key, build the filter its `dateFilterKind` needs (a period covering the seeded dates), run it with `branchCode` `ARU` and `DSM`, and assert the two results' sections differ and the ARU result never names a DSM policy number. For every company-wide key, assert `run` with `branchCode` throws `ReportParameterException` whose message contains "is company-wide". Assert `filterSummary` of an ARU run contains "Arusha branch" and `exportSummary` contains "Branch: Arusha (ARU)" (seed the branch register name through `OrganisationApi`'s test path, or assert against whatever name the register returns for ARU). Assert `policyNumbersInBranch("ARU")` returns exactly the two ARU numbers.
- [ ] **Step 2: Run, see them fail** (`./mvnw -B -o test -Dtest=BranchCutReportsIntegrationTest`).
- [ ] **Step 3: Implement.**
  - `ReportDefinitionView`: add component `boolean companyWide` (last), thread it through every constructor and `comparingPeriods/from/explaining`; add `companyWide()` copier.
  - Mark the 8 company-wide definitions `.companyWide()`.
  - `ReportFilters.validate`: `refuse(f.branchCode() != null && d.companyWide(), d.title() + " is company-wide; it cannot be cut by branch")`.
  - `Labels.branch(code)` via `OrganisationApi.branches()` (cached per `Labels` instance like products).
  - `FilterSummary.describe(d, f, labels, boolean forExport)`: branch part `forExport ? "Branch: " + name + " (" + code + ")" : name + " branch"`; company-wide reports add `"Company-wide"` when `forExport`.
  - `ReportResult`: add `exportSummary` component; existing constructors pass `filterSummary` for it. `ReportingApiImpl.run` sets both. `ReportPdf`/`ReportXlsx`/CSV print `exportSummary`.
  - `ProjectionStore.policyNumbersInBranch` (`SELECT policy_number FROM reporting.report_policy WHERE tenant_id = ? AND branch_code = ?`) behind `ReportingApi.policyNumbersInBranch`.
  - For any "Yes" report whose test still fails: give its query the `ReportSql` filter (it joins `report_policy p`); if it reads the ledger, move it to company-wide and record that in the spec's table.
- [ ] **Step 4: Run** the new test, `ReportingContractTest`, `ReportQueriesIntegrationTest`, the exporter tests and `ModularityTests`; all green.
- [ ] **Step 5: Commit** `feat(reporting): branch cuts for the policy-and-claims reports; company-wide reports say so`.

### Task 2: Underwriting and claims APIs take a branch

**Files:**
- Modify: `underwriting/api/CaseFilter.java` (+ `branchCode`, `withBranch`), `UnderwritingApi.java` (overloads `decisionsByMonth(from, to, branchCode)`, `decisionTurnaroundByMonth(from, to, branchCode)`, `listCasesAwaitingIssue(..., branchCode)` if the awaiting-issue count is shown per branch), the implementation and its queries (filter `branch_code = ?` when not null)
- Modify: `claims/api/ClaimsApi.java` (overloads taking `Set<String> policyNumbers` for `settlementsByMonth`, `settlementsByType`, `settlementTurnaroundByMonth`, `registrationsByMonth`, `countOpenDocumentRequests`), `ClaimsApiImpl` (`AND policy_number = ANY(?)` when not null; an EMPTY set returns nothing)
- Test: the modules' existing API integration tests gain one case each: two branches (underwriting) / two policy-number sets (claims), filtered vs unfiltered.

- [ ] **Step 1:** failing tests as above.
- [ ] **Step 2:** implement; existing signatures delegate with `null` so callers are untouched.
- [ ] **Step 3:** run the two modules' API tests + `ModularityTests`; green.
- [ ] **Step 4:** commit `feat(underwriting,claims): the dashboard figures can be cut by branch`.

### Task 3: Dashboards take `branchCode`

**Files:**
- Modify: `dashboard/infrastructure/DashboardController.java` (`@RequestParam(required = false) String branchCode` on overview, underwriting, claims; finance unchanged), `UnderwritingDashboard`, `ClaimsDashboard`, `ManagementDashboard` (`build(period, today, branchCode)`; the old signatures delegate with null)
- Modify: `dashboard/api/*DashboardView.java`: add `BranchRef branch` (code, name; null = all) and `List<String> allBranches` (keys of figures with no branch source)
- Modify: `backend/api/openapi/openapi-dashboard.yaml` (or wherever the dashboards are described)
- Test: `dashboard/*DashboardTest` (existing unit tests with stubbed APIs): with a branch, each API is called with it; `allBranches` lists exactly the unbranched figures.

- [ ] **Step 1: failing tests.**
- [ ] **Step 2: implement.**
  - Underwriting: `CaseFilter...withBranch(b)` for open, in-review, referred and the queue; branch overloads for decisions and turnaround. `recent` (activity) goes into `allBranches`.
  - Claims: `Set<String> pn = branchCode == null ? null : reporting.policyNumbersInBranch(branchCode)`; `searchClaims(status, null, pn, null, ...)` for every count and the queue; by-month and document requests through the new overloads. `recent` into `allBranches`.
  - Management: with a branch, premiums collected and new policies from `premium-collections` and `new-business` run with `branchCode` (the figures its cards open), compare months as the finance dashboard does; in force/lapsed and open claims from their reports with `branchCode`; claims paid from the branch claims dashboard; waiting = branch underwriting + branch claims. Without a branch: exactly today's code path.
  - `branch` filled from `OrganisationApi` names through `reporting`'s `Labels`-equivalent: the dashboard module may not read organisation directly, so `ReportingApi` exposes `branchName(String code)` (Task 1's `Labels.branch`).
- [ ] **Step 3:** run `dashboard` tests + `ModularityTests`; green.
- [ ] **Step 4:** commit `feat(dashboard): the management, underwriting and claims dashboards can be cut by branch`.

### Task 4: `/me/access` carries the home branch

**Files:** `iam/api/AccessApi.java` (`AccessView` + `String homeBranch`), `iam/application/AccessService.java` (`load` selects `home_branch`; `fromToken` uses the token's `home_branch` claim if `StaffIdentity` has it, else null), `iam/infrastructure/AccessController.java` (`MyAccessDto.homeBranch`), `backend/api/openapi/openapi-iam.yaml` (`MyAccess.homeBranch: [string, "null"]`), test `iam/AccessIntegrationTest` (a staff user with `home_branch = 'ARU'` gets it back).

- [ ] Failing test → implement → run `AccessIntegrationTest`, `AccessAdministrationIntegrationTest` → commit `feat(iam): /me/access says where the staff member is based`.

### Task 5: The console's data layer

**Files:**
- `frontend/src/api/types.ts` / generated types (`npm run generate:api`; memory: openapi-typescript makes `default:` fields required)
- Create: `frontend/src/features/reporting/branchChoice.ts` + `branchChoice.test.ts`
- Modify: `frontend/src/api/dashboard.ts` (`params(range, branch)`), `features/reporting/reportParams.ts` (+ `branch` in state and FIELDS, sent as `branchCode`), `features/dashboard/reportLinks.ts` and `features/reporting/reportLinks.ts` (carry `branch`)

**Interfaces — produces (`branchChoice.ts`):**
```ts
export type BranchOption = { code: string; name: string; yours: boolean };
/** 'all' or a branch code: the address's `branch`, else the starting rule. */
export function chosenBranch(param: string | null, homeBranch: string | null, headOffice: string | null): string;
/** The select's options: "All branches" first is the caller's; these are the register's, head office first, own marked. */
export function branchOptions(branches: { code: string; name: string; active: boolean; headOffice: boolean }[],
  homeBranch: string | null): BranchOption[];
/** "Arusha branch", or null for all branches. */
export function branchWords(chosen: string, options: BranchOption[]): string | null;
```
Rules: `chosenBranch`: a param of `all` → `'all'`; a param naming a code → that code; no param → `homeBranch` when set and `!== headOffice`, else `'all'`. `branchOptions`: active only, head office first then register order, `yours` only when `homeBranch === code && code !== headOffice` (head-office staff: no marker). Option label in the select: `yours ? \`${name} (your branch)\` : name`.

- [ ] **Step 1:** `branchChoice.test.ts` covering: no param + home ARU → ARU; no param + home = head office → all; no param + no home → all; `branch=all` with home ARU → all; `branch=MOS` → MOS; options order and marker; no marker for head-office staff; `branchWords`.
- [ ] **Step 2:** fail → implement → pass. Add `branch` to `reportParams` (tests: round-trip `branch` ↔ `branchCode`; absent stays absent) and to both `reportLinks` (tests: a link carries `branch=ARU` and `branch=all`).
- [ ] **Step 3:** commit `feat(console): the branch choice -- starting rule, options, words, and links that carry it`.

### Task 6: The screens (impeccable)

Before editing: read `C:\Users\USER\.claude\skills\impeccable\reference\craft-floor.md`; run `impeccable surface-brief read frontend/src/features/dashboard/DashboardPage.tsx` if one exists.

**Files:** `features/dashboard/DashboardPage.tsx` (+ test), the three panels (`ManagementDashboardPanel`, `UnderwritingDashboardPanel`, `ClaimsDashboardPanel`: pass `branch`, show "All branches" under a figure whose key is in `allBranches`), `FinanceDashboardPanel` (untouched; the page note says "Company-wide · …" on its tab), `features/reporting/ReportFilterBar.tsx` (+ test), `features/reporting/freshness.ts` (+ test: `companyWide` → `Company-wide · Read live from the ledger`), `ReportPage.tsx` (passes `companyWide`).

- [ ] **Step 1: tests first** (React Testing Library, existing test files):
  - Dashboard: a labelled **Branch** select on the period row; starts on ARU for an ARU-based person (`useAccessStore` seeded via `frontend/src/test/access.ts` with `homeBranch`), on "All branches" for head office; choosing "All branches" writes `branch=all` to the address; the note reads "Arusha branch · Compared with …"; on the Finance tab the note reads "Company-wide · …".
  - A panel figure listed in `allBranches` shows "All branches" under its label; one not listed does not.
  - ReportFilterBar: a branch report shows a **Branch** select after Client type with "All branches" and the names; a company-wide report shows no Branch select.
  - freshness: `Company-wide · Read live from the ledger` for a company-wide ledger report.
- [ ] **Step 2: implement**, using the existing `Select` (`components/ui/input`) and label pattern of `ReportFilterBar`; the dashboard select sits after the period chips, before the comparison note; no new tokens or colours; "All branches" in the label tier, muted.
- [ ] **Step 3: run** the affected Vitest files from PowerShell (`npx vitest run <files>`), and `npm run typecheck` + `npm run lint`.
- [ ] **Step 4: look at it.** Screenshots signed in (the Playwright storage-state script used for shaping): dashboard Overview and Underwriting with a branch and with all; New business and Balance sheet; at 1440×900 and 390×844, light and dark. Check against the craft floor; fix what one look shows before looking again.
- [ ] **Step 5: commit** `feat(console): a branch choice on the dashboards and reports`.

### Task 7: The journey, the docs, and the gate

- [ ] **e2e:** `frontend/e2e/staff-branch-views.spec.ts` as `staff.management`: before, set its home branch to `ARU` through `PUT /access/users/{id}/contact` (admin token via `apiToken`), restore after. Assert: the dashboard's Branch select shows "Arusha (your branch)"; a card opens its report with `branch=ARU` in the address and the Branch filter on Arusha; choosing "All branches" changes the report's first figure; Balance sheet shows "Company-wide".
- [ ] **Docs:** `PRODUCT.md` or the reports help text if it lists filters; DESIGN.md only if a new pattern was introduced (expected: none).
- [ ] **Gate (memory: affected suites now, full suites at the milestone):** backend affected classes; full Vitest; the new e2e spec alone, then `npm run test:e2e` once at the end.
- [ ] **Commit** and report; merge only when the user says.
