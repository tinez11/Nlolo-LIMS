# Analytics Plan 5 — Management Overview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Senior management opens one dashboard tab that shows how the business is doing: what came in, what is on
the books, what is owed, what claims cost and what work is waiting. Each figure is said once and opens the report or
list behind it. A built-in Management role sees it and every area, and can change nothing. Claims reports gain the
stage the platform already records but does not report: assessment.

**Architecture:** No new figure is computed for the overview. The new `ManagementDashboard` reads each figure from the
report or dashboard that already owns it: `policies-in-force`, `claims-outstanding`, the finance dashboard's
collections, and the underwriting and claims queue counts. So the overview can never disagree with the screen its card
opens. A new permission `overview.view` guards the tab. A ninth built-in role, `MANAGEMENT`, holds every `*.view`
permission plus `reports.view` and `overview.view`, and nothing else. The reporting copy starts recording
`claims.ClaimAssessed`. A rebuild replays the audit journal, so past assessments fill in too. Claims turnaround gains
the steps registration → assessment → decision, and a new "Claims in progress" report shows where open claims stand
on a date.

**Tech Stack:** Spring Boot 3.3 / Java 21 / JdbcTemplate (reporting, dashboard, iam), Flyway-style SQL files run by
hand on dev (`db-migrations/`), React 19 + TypeScript + Vitest, OpenAPI 3.1 (`npm run generate:api`), Playwright.

**Source:** `backend/docs/superpowers/plans/2026-11-11-analytics-plan1-collections.md` ("Plan 5 — Management role and
Management overview dashboard; policy movement; claims by stage") and
`backend/docs/superpowers/specs/2026-11-11-analytics-prd-assessment.md`, PRD 5 ("a *Management overview* dashboard
built from existing report figures + four live queue counts, each card with period, definition and a link"),
PRD 9 (movement) and PRD 10 ("awaiting documents", "under investigation" stages "not projected").

## Facts found in the code (2026-11-12)

- **Policy movement already exists.** `PoliciesInForceReport` (the reports redesign batch 4, 2026-11-09) shows
  "opening + new + reinstated − lapsed − surrendered − matured − ended by a claim − cancelled in free-look − ended =
  closing", with figures `policies`, `new`, `reinstated`, `lapsed`, `surrendered`. This plan builds no second one; the
  overview links to it.
- **There is no management role.** `BuiltInRoles.ALL` has eight roles: ADMIN, SENIOR_UNDERWRITER, UNDERWRITER,
  CLAIMS_MANAGER, CLAIMS_ASSESSOR, FINANCE_APPROVER, FINANCE_OFFICER, CUSTOMER_SERVICE_REP. `staff.manager` (Keycloak)
  is the CLAIMS_MANAGER test login.
- **A new built-in role reaches existing tenants by itself.** `AccessService.seedBuiltInRoles` inserts any missing
  built-in role, with its permissions, when the tenant has fewer built-in roles than `BuiltInRoles.ALL`. A new
  permission does **not** reach existing ADMIN roles by itself; `db-migrations/iam/V4__organisation_manage.sql` shows
  the one-line grant to use.
- **Dashboards are one tab per permission.** `dashboards.ts` `dashboardsFor` and each `@PreAuthorize` in
  `DashboardController` must name the same permission ("Change one and the other must change with it").
- **Claims stages in reporting.** `report_claim` holds registered, approved, rejected, settlement requested, settled
  and reopened dates. The claims module also publishes `claims.ClaimAssessed` (`ClaimsApiImpl:608`, payload `claimId`,
  `fraudIndicator`), which reporting ignores. Document requests (`claims.claim_document_request`, requested_at /
  received_at) publish no event. The claims dashboard already counts open document requests live.
- **Dashboards read report figures already.** `dashboard/application/ReportFigures` reads a report's own number out
  of its result "so it can never disagree with the report a finance officer opens from the card".

## Decisions to confirm (the user)

| # | Question | Facts | Recommended |
|---|---|---|---|
| D1 | Who sees the overview? | No role reads every area today without also changing things. | A new built-in **Management** role: every "see" permission, reports, the overview, nothing else. **Administrators** also get the overview. Others only if an administrator gives them `overview.view`. |
| D2 | What is on it? | All of these exist today as report or dashboard figures. | A lit band of three: **Premiums collected** (period, against the year before), **New policies** (period, against the year before), **Policies in force** (today). Then four cards: **Collection rate** + owed overdue; **Lapsed** (period); **Claims paid** (period) + open claims; **Work waiting**: cases to decide, claims to decide, approvals waiting. Each opens its report or list. |
| D3 | Claims "waiting for documents" | Document requests send no event, so reporting cannot replay their history; the table keeps requested/received times. | Leave documents as the live count the claims dashboard already shows. Report the stages reporting can replay: registered → assessed → decided → payment requested → paid. Adding a document event is a later step if wanted. |
| D4 | Policy movement | Already built (Policies in force). | Do not rebuild it; the overview's "Policies in force" and "Lapsed" open it. |

## Global Constraints

- Simple English; each figure said once (the user, 2026-11-12: "too much explanation and info"). Money in full.
- Every figure is the server's and comes from the report that owns it; the browser adds nothing up.
- A dashboard never shows a role figures its own screens would refuse: the Management role holds every view
  permission the overview's figures come from.
- Host Maven only (`JAVA_HOME=~/.jdks/jdk-21.0.12.1+1`); `clean test-compile` first; dev backend stopped for backend
  tests and restarted after (`.env` by absolute path, `SMS_GATEWAY_LIVE=true SMS_GATEWAY_ALLOWED_RECIPIENTS=255755418369`);
  one heavy run at a time; Playwright `--workers=1`, MCP browser closed; never Prettier; Edit/Write tools only.
- New migrations are run by hand on dev with psql, then the backend restarted (dev backend and migrations are
  decoupled).
- Frontend tasks invoke impeccable; one batched desktop + phone check, one confirm round.
- Branch `analytics-management-overview`, `git merge --no-ff`, push `origin main`; trailer
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Not in this plan

- The sales funnel and underwriting stage history (Plan 4, on hold).
- Branch, product or agent filters on the overview (branch access does not exist yet, B1).
- Targets, alerts and scheduled reports.
- Document-request history in reports (D3).

## File Structure

| File | Responsibility |
|---|---|
| `iam/api/Permission.java` (modify) | `OVERVIEW_VIEW("overview.view", Area.REPORTS, ...)` |
| `iam/application/BuiltInRoles.java` (modify) | the `MANAGEMENT` role |
| `db-migrations/iam/V5__overview_view.sql` (new) | `overview.view` for every existing built-in ADMIN role |
| `db-migrations/reporting/V5__claim_assessed.sql` (new) | `report_claim.assessed_on` |
| `reporting/application/ReportingEventHandlers.java` (modify) | apply `claims.ClaimAssessed` |
| `reporting/infrastructure/ProjectionStore.java` (modify) | `claimAssessed` |
| `reporting/infrastructure/ReportQueries.java` (modify) | `assessedOn` on `ClaimFact` |
| `reporting/application/ClaimTurnaroundReport.java` (modify) | two stage rows |
| `reporting/application/ClaimsInProgressReport.java` (new) | open claims on a date, by stage, with their age |
| `dashboard/api/ManagementDashboardView.java` (new) | the overview's wire shape |
| `dashboard/application/ManagementDashboard.java` (new) | reads each figure from its owner |
| `dashboard/application/ReportFigures.java` (modify) | `figure(result, key)` |
| `dashboard/infrastructure/DashboardController.java` (modify) | `GET /dashboards/overview` |
| `api/openapi/openapi-dashboard.yaml` (modify) | path + schema |
| `frontend/src/api/dashboard.ts`, `store/dashboardStore.ts` (modify) | `overview` slot |
| `frontend/src/features/dashboard/dashboards.ts` (modify) | the `overview` kind, first in the list |
| `frontend/src/features/dashboard/ManagementDashboardPanel.tsx` (new) | the tab |
| `frontend/src/features/dashboard/DashboardPage.tsx` (modify) | register the panel |
| `backend/keycloak/lifeplatform-realm.json` (modify) | realm role `MANAGEMENT`, user `staff.management` |
| `frontend/e2e/auth-management.setup.ts`, `frontend/e2e/staff-management-overview.spec.ts` (new) | e2e |

---

### Task 1: The Management role and the overview permission

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/iam/api/Permission.java` (after `REPORTS_SOLVENCY_FACTORS`)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/iam/application/BuiltInRoles.java`
- Create: `backend/db-migrations/iam/V5__overview_view.sql`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/iam/application/BuiltInRolesTest.java` (create if absent; otherwise add to the existing roles test found by `grep -rl "BuiltInRoles" src/test`)

**Interfaces:**
- Produces: `Permission.OVERVIEW_VIEW` (code `overview.view`); built-in role code `MANAGEMENT`, name "Management".

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform.iam.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.iam.api.Permission;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInRolesTest {

    /** Management sees every area and the overview, and changes nothing (Plan 5, D1). */
    @Test
    void managementSeesEverythingAndChangesNothing() {
        var management = BuiltInRoles.byCode("MANAGEMENT");
        assertThat(management).isNotNull();
        assertThat(management.name()).isEqualTo("Management");
        for (Permission p : Permission.values()) {
            boolean seeing = p.code().endsWith(".view");
            assertThat(management.permissions().contains(p)).as(p.code()).isEqualTo(seeing);
        }
        assertThat(management.permissions()).contains(Permission.OVERVIEW_VIEW, Permission.REPORTS_VIEW);
    }

    @Test
    void administratorsSeeTheOverview() {
        assertThat(BuiltInRoles.byCode("ADMIN").permissions()).contains(Permission.OVERVIEW_VIEW);
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd backend && JAVA_HOME=~/.jdks/jdk-21.0.12.1+1 ./mvnw -B -o test -Dtest=BuiltInRolesTest`
Expected: compilation failure, `OVERVIEW_VIEW` not found.

- [ ] **Step 3: Add the permission**

In `Permission.java`, after `REPORTS_SOLVENCY_FACTORS(...)`:

```java
    OVERVIEW_VIEW("overview.view", Area.REPORTS, "See the management overview",
        "The dashboard of how the business is doing: premiums, policies, claims and work waiting."),
```

`.view` ends the code, so the role test above counts it as a "see" permission.

- [ ] **Step 4: Add the role**

In `BuiltInRoles.ALL`, after `ADMIN` (precedence: a person who is both shows "Administrator"):

```java
        // Plan 5, D1 (2026-11-12): sees every area and the overview, and changes nothing.
        new Definition("MANAGEMENT", "Management",
            "Sees every area, the reports and the management overview. Changes nothing.",
            seeingOnly()),
```

and the helper:

```java
    /** Every "see" permission: the codes that end in ".view". */
    private static Set<Permission> seeingOnly() {
        Set<Permission> all = EnumSet.noneOf(Permission.class);
        for (Permission p : Permission.values()) {
            if (p.code().endsWith(".view")) all.add(p);
        }
        return all;
    }
```

ADMIN gets `OVERVIEW_VIEW` already: `admin()` is every permission less the named exclusions. Update the class comment's
"The eight roles" to "The nine roles", and `seedBuiltInRoles`'s "The eight built-in roles" likewise.

- [ ] **Step 5: The grant for existing administrators**

`backend/db-migrations/iam/V5__overview_view.sql`:

```sql
-- db-migrations/iam/V5__overview_view.sql
-- overview.view (2026-11-12, analytics plan 5, D1): the management overview. New tenants get it from BuiltInRoles; this
-- gives it to the built-in administrator role every existing tenant already holds. The Management role itself is
-- seeded by AccessService on the tenant's next request. Only this one permission is added.
INSERT INTO iam.role_permission (tenant_id, role_id, permission)
SELECT r.tenant_id, r.role_id, 'overview.view'
  FROM iam.role r
 WHERE r.built_in AND r.code = 'ADMIN'
ON CONFLICT (role_id, permission) DO NOTHING;
```

Add it to whichever test-migration list applies iam migrations (`grep -rn "iam/V4__organisation_manage" src/test`)
right after V4.

- [ ] **Step 6: Run the role test and the iam tests**

Run: `./mvnw -B -o test -Dtest='BuiltInRolesTest,*Access*Test'`
Expected: PASS. A test that asserts "eight" roles must be changed to nine; do not loosen it to "at least".

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/iam backend/db-migrations/iam/V5__overview_view.sql backend/src/test
git commit -m "feat(iam): a Management role that sees everything and changes nothing; overview.view"
```

---

### Task 2: Reporting records the assessment

**Files:**
- Create: `backend/db-migrations/reporting/V5__claim_assessed.sql`
- Modify: `reporting/application/ReportingEventHandlers.java` (the handled list at line ~60 and the switch at ~135)
- Modify: `reporting/infrastructure/ProjectionStore.java` (beside `claimSettlementRequested`)
- Modify: `reporting/infrastructure/ReportQueries.java` (`ClaimFact` and `claimFacts`)
- Modify: `src/test/java/.../reporting/ReportingTestMigrations.java` (add V5 after V4)
- Test: `src/test/java/.../reporting/ReportQueriesIntegrationTest.java`

**Interfaces:**
- Produces: `ClaimFact.assessedOn()` (`LocalDate`, null until assessed); `ProjectionStore.claimAssessed(UUID tenantId, UUID claimId, LocalDate on)`.

- [ ] **Step 1: The column**

```sql
-- db-migrations/reporting/V5__claim_assessed.sql
-- The day a claim was first assessed (2026-11-12, analytics plan 5): claims.ClaimAssessed, which reporting used to
-- ignore. The first assessment only -- a second one does not move it -- so "days to assess" means time to the first
-- look. A rebuild replays the audit journal, so claims assessed before this column existed fill in too.
ALTER TABLE reporting.report_claim ADD COLUMN IF NOT EXISTS assessed_on DATE;
```

- [ ] **Step 2: Write the failing test** (in `ReportQueriesIntegrationTest`, using the claim fixtures the class's
  existing claims tests use: `grep -n "claimRegistered\|claimApproved" ReportQueriesIntegrationTest.java`)

```java
    /** Plan 5: the first assessment is kept; a second one does not move it. */
    @Test
    void aClaimKeepsTheDayItWasFirstAssessed() {
        UUID claim = UUID.randomUUID();
        store.claimRegistered(tenant, claim, "C-ASSESS", "DEATH", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2));
        store.claimAssessed(tenant, claim, LocalDate.of(2026, 10, 5));
        store.claimAssessed(tenant, claim, LocalDate.of(2026, 10, 9));

        ClaimFact fact = queries.claimFacts(tenant, LocalDate.of(2026, 10, 31), ReportFilter.none()).stream()
            .filter(c -> "C-ASSESS".equals(c.policyNumber())).findFirst().orElseThrow();
        assertThat(fact.assessedOn()).isEqualTo(LocalDate.of(2026, 10, 5));
    }
```

- [ ] **Step 3: Run it to see it fail** — `./mvnw -B -o test -Dtest=ReportQueriesIntegrationTest#aClaimKeepsTheDayItWasFirstAssessed`
  Expected: compilation failure (`claimAssessed`, `assessedOn`).

- [ ] **Step 4: Store, handler, query**

`ProjectionStore`:

```java
    /** The first assessment only (plan 5): COALESCE keeps the day already recorded. */
    public void claimAssessed(UUID tenantId, UUID claimId, LocalDate on) {
        jdbc.update("INSERT INTO reporting.report_claim (tenant_id, claim_id, assessed_on) VALUES (?, ?, ?)"
            + " ON CONFLICT (tenant_id, claim_id) DO UPDATE SET"
            + " assessed_on = COALESCE(report_claim.assessed_on, EXCLUDED.assessed_on)",
            tenantId, claimId, on);
    }
```

`ReportingEventHandlers`: add `"claims.ClaimAssessed"` to the handled list after `"claims.ClaimRegistered"`, and in the
switch:

```java
            // The day of the first assessment (plan 5). The event carries no date of its own: when it happened.
            case "claims.ClaimAssessed" -> store.claimAssessed(tenantId, uuid(p, "claimId"),
                onInstant(p, "assessedAt", occurredAt));
```

`ReportQueries`: add `assessed_on` to the `claimFacts` SELECT and `LocalDate assessedOn` to `ClaimFact` right after
`registeredOn`; fix every `new ClaimFact(` in tests (`grep -rn "new ClaimFact(" src`).

- [ ] **Step 5: Run** the new test and every claims report test:
  `./mvnw -B -o test -Dtest='ReportQueriesIntegrationTest,*Claim*Report*Test,ReportingContractTest'` → PASS.

- [ ] **Step 6: Commit** — `git commit -m "feat(reporting): the day a claim was first assessed"`

---

### Task 3: Claims turnaround by stage, and Claims in progress

**Files:**
- Modify: `reporting/application/ClaimTurnaroundReport.java`
- Create: `reporting/application/ClaimsInProgressReport.java`
- Test: the turnaround report's existing test (`grep -rl "claims-turnaround" src/test`) and a new `ClaimsInProgressReportTest`

**Interfaces:**
- Consumes: `ClaimFact.assessedOn()` (Task 2).
- Produces: report key `claims-in-progress` (category CLAIMS, `DateFilterKind.AS_OF`), section "Open claims by stage"
  with columns `stage`, `claims` (COUNT), `averageDays` (COUNT), `oldestDays` (COUNT); figure keys `open`,
  `notAssessed`, `awaitingPayment`.

- [ ] **Step 1: Turnaround — failing test.** In the existing turnaround test, add a claim registered 1 Oct, assessed
  4 Oct, approved 10 Oct, settled 15 Oct (Oct period) and assert the "Time taken" section has rows
  `"Average days from registration to assessment"` = `3.0` and `"Average days from assessment to a decision"` = `6.0`.

- [ ] **Step 2: Turnaround — implement.** In `Times` add `toAssess` and `assessToDecide`; in `times(...)`:

```java
            if (span.holds(c.assessedOn()) && c.registeredOn() != null) {
                assess.add(ChronoUnit.DAYS.between(c.registeredOn(), c.assessedOn()));
            }
            if (span.holds(decided) && c.assessedOn() != null) {
                assessToDecide.add(ChronoUnit.DAYS.between(c.assessedOn(), decided));
            }
```

and the two rows first in "Time taken", before "Average days from registration to a decision". Update the class
comment and `DEFINITION` description: "Average days to assess, to decide and to pay …".

- [ ] **Step 3: Claims in progress — failing test.**

```java
    /** Each open claim counts once, in the stage it has reached on the day asked about. */
    @Test
    void openClaimsAreCountedOnceInTheStageTheyReached() {
        LocalDate on = LocalDate.of(2026, 10, 31);
        List<ClaimFact> claims = List.of(
            fact("A", LocalDate.of(2026, 10, 1), null, null, null, null, null),            // registered, not assessed
            fact("B", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3), null, null, null, null), // assessed
            fact("C", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 5), null, null, null), // approved
            fact("D", LocalDate.of(2026, 9, 1), null, LocalDate.of(2026, 9, 5), null, LocalDate.of(2026, 9, 6), null), // payment asked
            fact("E", LocalDate.of(2026, 9, 1), null, LocalDate.of(2026, 9, 5), null, null, LocalDate.of(2026, 9, 9)), // paid: closed
            fact("F", LocalDate.of(2026, 9, 1), null, null, LocalDate.of(2026, 9, 4), null, null)); // declined: closed

        var stages = ClaimsInProgressReport.stages(claims, on);

        assertThat(stages).extracting(ClaimsInProgressReport.Stage::name, ClaimsInProgressReport.Stage::claims)
            .containsExactly(tuple("Waiting for assessment", 1L), tuple("Assessed, waiting for a decision", 1L),
                tuple("Approved, payment not yet asked for", 1L), tuple("Payment asked for, not yet paid", 1L));
        assertThat(stages.get(0).oldestDays()).isEqualTo(30L);
    }
```

(`fact(policy, registered, assessed, approved, rejected, settlementRequested, settled)` is a local helper building a
`ClaimFact` with the remaining fields null.) A claim reopened after it was paid or declined is open again from
`reopenedOn` and counts in "Waiting for assessment" unless assessed after reopening — state this in the class comment.

- [ ] **Step 4: Claims in progress — implement** `ClaimsInProgressReport implements Report` following
  `OutstandingClaimsReport`'s shape: `DEFINITION = new ReportDefinitionView("claims-in-progress", "Claims in
  progress", "Open claims on a date, by the stage each has reached, and how long they have waited.",
  ReportCategory.CLAIMS, DateFilterKind.AS_OF, List.of(), false).from("REPORTING_COPY")`;
  `static List<Stage> stages(List<ClaimFact>, LocalDate on)`; `record Stage(String name, long claims, BigDecimal
  averageDays, Long oldestDays)`; days waited are counted from the day the claim entered its stage (registered,
  assessed, approved, settlement requested) to `on`. Figures: `open` (all four stages), `notAssessed`,
  `awaitingPayment` (the last two stages). Register it the way the other reports are found (`@Component` implementing
  `Report`; check `grep -rn "implements Report" | head` and the catalogue test that counts reports, and raise its count).

- [ ] **Step 5: Run** `./mvnw -B -o test -Dtest='*Claim*Test,ReportingContractTest,ReportingSpecParsesTest'` → PASS.
  Add `claims-in-progress` wherever the figures guide or report-link tests list every report key.

- [ ] **Step 6: Commit** — `git commit -m "feat(reporting): claims turnaround by stage; Claims in progress"`

---

### Task 4: The overview endpoint

**Files:**
- Create: `dashboard/api/ManagementDashboardView.java`, `dashboard/application/ManagementDashboard.java`
- Modify: `dashboard/application/ReportFigures.java`, `dashboard/infrastructure/DashboardController.java`,
  `api/openapi/openapi-dashboard.yaml`
- Test: `dashboard/application/ManagementDashboardTest.java` (Mockito, built directly — never `@MockBean` of a module
  API, which breaks beans injecting the impl), `DashboardContractTest`

**Interfaces:**
- Consumes: `ReportingApi.run(String key, ReportFilter)`, `ReportingApi.collectionFigures(from, to, today)`,
  `FinanceDashboard.build(DashboardPeriod, LocalDate)` (premiums collected and new policies with their comparison),
  `UnderwritingDashboard` / `ClaimsDashboard` queue counts (read the existing builders for the exact accessors; take the
  counts, not a second query).
- Produces:

```java
public record ManagementDashboardView(
    Period period,
    Money collected, Money collectedBefore, String collectedChangePercent,
    Long newPolicies, Long newPoliciesBefore, String newPoliciesChangePercent,
    Long policiesInForce,
    tz.co.nlolo.lifeplatform.reporting.api.CollectionFigures collections,
    Long lapsed,
    Money claimsPaid, Long openClaims,
    Long casesToDecide, Long claimsToDecide, Long approvalsWaiting) {}
```

Every field may be null: a source that fails shows "Not available", never zero (`Safely`, as the other builders do).

- [ ] **Step 1: `ReportFigures.figure`** — failing unit test, then:

```java
    /** A report's figure by key, as the report states it; null when the report or the figure is not there. */
    static BigDecimal figure(ReportResult result, String key) {
        if (result == null) return null;
        return result.figures().stream().filter(f -> key.equals(f.key())).findFirst()
            .map(f -> f.value() == null || f.value().isBlank() ? null : new BigDecimal(f.value())).orElse(null);
    }
```

Coupled to `PoliciesInForceReport`'s keys `policies`, `lapsed` and `OutstandingClaimsReport`'s `closing`: add a comment
in each report naming `ManagementDashboard`, as the balance sheet names `ReportFigures`.

- [ ] **Step 2: Failing builder test** — mock `ReportingApi.run("policies-in-force", …)` returning figures
  `policies` = 412, `lapsed` = 7; `run("claims-outstanding", …)` with `closing` = 9; the finance dashboard returning
  collected TZS 5,000,000.00 vs 4,000,000.00 and new policies 30 vs 20; assert the view carries each unchanged, and
  that `policies-in-force` was asked with `asOf = today` and `compareAsOf = period start − 1 day` (so "Lapsed" is the
  period's).

- [ ] **Step 3: Implement `ManagementDashboard.build(DashboardPeriod period, LocalDate today)`**, each read inside
  `Safely`. Approvals waiting = the finance dashboard's `journalsAwaitingApproval` + nothing else unless the action bell
  exposes a count through an api (`grep -rn "interface .*Api" dashboard`'s allowed modules) — if it does not, leave
  approvals out and say so in the plan's notes, rather than adding a cross-module query.

- [ ] **Step 4: Endpoint**

```java
    /** Plan 5: the management overview. The Management role holds every view its figures come from (D1). */
    @GetMapping("/dashboards/overview")
    @PreAuthorize("@access.can('overview.view')")
    public ResponseEntity<ManagementDashboardView> overview(@RequestParam(required = false) String from,
                                                            @RequestParam(required = false) String to) {
        LocalDate today = LocalDate.now(CIVIL);
        return ResponseEntity.ok(management.build(DashboardPeriod.of(from, to, today), today));
    }
```

Add the path and `ManagementDashboardView` schema to `openapi-dashboard.yaml` (nullable fields as
`type: [integer, 'null']`, money as `anyOf: [ {type: 'null'}, {$ref: Money} ]` — `oneOf` with null matches twice).

- [ ] **Step 5: Contract test** — a token with role `MANAGEMENT` gets 200 and a valid body; `CUSTOMER_SERVICE_REP`
  gets 403 (never a bare `REALM_STAFF` token: in token mode it holds every permission).

- [ ] **Step 6: Run** `./mvnw -B -o test -Dtest='ManagementDashboardTest,DashboardContractTest,RangedDashboardsTest,FinanceDashboardTest,ModularityTests'` → PASS.

- [ ] **Step 7: Commit** — `git commit -m "feat(dashboard): the management overview, every figure read from its report"`

---

### Task 5: The overview tab (invoke impeccable)

**Files:**
- Modify: `frontend/src/api/dashboard.ts` (`ManagementDashboard` type, `getManagementDashboard`)
- Modify: `frontend/src/store/dashboardStore.ts` (`overview` slot; `DashboardKind` gains `'overview'`)
- Modify: `frontend/src/features/dashboard/dashboards.ts` (`if (can('overview.view')) kinds.unshift('overview')`;
  label "Overview"; comment table gains the line)
- Create: `frontend/src/features/dashboard/ManagementDashboardPanel.tsx`
- Modify: `frontend/src/features/dashboard/DashboardPage.tsx` (`PANEL.overview`)
- Test: `ManagementDashboardPanel.test.tsx`, `dashboards.test.ts`

Layout (DESIGN.md "The Lit Figure", the agent panel's 2026-11-12 shape):
- Lit band (`hero-scope bg-hero`), three figures: **Premiums collected**, **New policies**, **Policies in force**. The
  first two carry the change against the year before with an arrow and words; the third says "today". The period is
  said once, in the panel subtitle.
- `StatRow` of four tiles, each linking to exactly what it counted:
  Collection rate (note: "TZS … owed overdue") → `/staff/collections/follow-up`;
  Lapsed (period) → `/staff/reports/policies-in-force`;
  Claims paid (note: "N open") → `/staff/reports/claims-outstanding`;
  Work waiting (cases + claims to decide) → `/staff/underwriting`.
- No hint line repeats the period; "Not available" for a null figure, never 0.

- [ ] **Step 1:** Run `npm run generate:api`; add the api function and store slot (copy the `finance` slot).
- [ ] **Step 2: Failing tests** — the panel shows `TZS 5,000,000.00` once, `412`, the collection rate, and each tile's
  link `href`; `dashboardsFor` puts `overview` first for a person holding `overview.view` and ledger.view.
- [ ] **Step 3: Implement** the panel; run `npx tsc --noEmit -p .`, eslint on the changed files, and
  `npx vitest run src/features/dashboard` → PASS.
- [ ] **Step 4: impeccable check** — dev login with the Management role, desktop 1280 and phone 390 in one round; fix
  in one batch; one confirm round.
- [ ] **Step 5: Commit** — `git commit -m "feat(dashboard): the Overview tab"`

---

### Task 6: A Management login, e2e, merge

**Files:**
- Modify: `backend/keycloak/lifeplatform-realm.json` (realm role `MANAGEMENT`; user `staff.management`, password
  `devpassword`, tenant attribute as `staff.manager` has)
- Create: `frontend/e2e/auth-management.setup.ts` (copy `auth-manager.setup.ts`, user `staff.management`, state
  `e2e/.auth/staff-management.json`); register a `staff-management` project in `playwright.config.ts` matching
  `staff-management-*.spec.ts`
- Create: `frontend/e2e/staff-management-overview.spec.ts`

- [ ] **Step 1: Dev login.** The dev realm is already imported, so the JSON change reaches only fresh installs. On dev,
  create the role and user through the Keycloak admin API the way `backend/scripts/apply-staff-user-admin.mjs` does,
  or as an administrator on the platform's Users screen (Administration → Users → New, role Management).
- [ ] **Step 2: e2e** — signs in as `staff.management`; the Dashboard opens on "Overview"; the three band figures and
  four tiles show; "Policies in force" opens the Policies in force report; no "Issue policy" or "Record payment"
  button is reachable (the role changes nothing). A CLAIMS_MANAGER (`staff.manager`) sees no Overview tab.
- [ ] **Step 3: Apply migrations on dev** with psql (`iam/V5`, `reporting/V5`), restart the backend, start a reporting
  rebuild (Reports → Rebuild, or `POST` the rebuild endpoint as admin) so past assessments fill in, and confirm a claim
  assessed before today shows its day in Claims turnaround.
- [ ] **Step 4: Run** the affected backend classes (Tasks 1–4), full `src/features/dashboard` vitest, and Playwright
  `staff-management-overview`, `staff-dashboard`, `staff-reports-foundation` with `--workers=1`.
- [ ] **Step 5:** `git merge --no-ff analytics-management-overview`, push `origin main`, update memory.

---

## Self-review (2026-11-12)

- Spec coverage: management role (Task 1), overview dashboard (Tasks 4–5), policy movement (already built — D4,
  linked from the overview), claims by stage (Tasks 2–3; documents stay live, D3).
- Types: `ClaimFact.assessedOn` (Task 2) is used by Task 3; `ReportFigures.figure` and `ManagementDashboardView`
  (Task 4) by Task 5; `overview.view` is the same string in `Permission`, the migration, `@PreAuthorize` and
  `dashboards.ts`.
- Open in the plan by design: whether an "approvals waiting" count can be read through an existing api (Task 4,
  Step 3) — checked in code at that step, not guessed here.
