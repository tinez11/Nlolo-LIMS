# Role Dashboards (Phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the backend gaps that made eight dashboard figures unprovable, and light up the cards phase 1 drew dashed.

**Architecture:** Each missing figure is added to the module that **owns the data** — underwriting counts and times its own cases, claims sums its own settlements, billing ages its own receivables — never to the dashboard and never to the browser. Every aggregate returns a **monthly series**, not a single total, so phase 3 draws a chart from the same call with no second endpoint. The `dashboard` module only reads and forwards.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith, JUnit 5 + Mockito, Testcontainers Postgres 16, Flyway-style numbered SQL under `backend/db-migrations/<module>/`, OpenAPI 3.1; React + TypeScript, Zustand, Tailwind v4, Vitest + RTL, Playwright.

**Depends on:** `2026-10-14-role-dashboards-phase-1.md`, fully merged. Every task here fills a card that plan drew dashed, or a filter it recorded as missing.

## Global Constraints

Everything in phase 1's Global Constraints still binds (money as a decimal string, TZS only, deltas between complete months, deltas in ink, achromatic series, a figure never wraps, civil dates in `Africa/Dar_es_Salaam`, no new `Clock` bean, host `./mvnw`, no Prettier, no fabricated JWTs). In addition:

- **An aggregate belongs to the module that owns the rows.** Not to `dashboard`, which composes; not to `reporting`, whose projections cover policies, collections and clients and know nothing of claims, cases or arrears.
- **Every aggregate is a monthly series.** `List<...Point>` keyed by `YYYY-MM`, oldest first, a month with nothing **absent** rather than zero. One shape serves a card (read the last complete month) and a chart (read them all).
- **A money aggregate is per currency.** Never add TZS to USD. A dashboard shows the TZS figure and nothing else; a second currency is a row in a report, not a silent addition.
- **A new column is backfilled only where the answer is known exactly**, and left null otherwise, with the console saying "not recorded" — the rule party V5 and V7 set.
- **Each new filter is refused where it does not apply**, never ignored: a date filter on a search that cannot honour it is a 400.

## The doctrine amendment this phase needs

PRODUCT.md currently says a figure has **two** sources: a paged search's `totalElements`, or a value the reporting module computed. Phase 2 adds a third, and Task 0 writes it down before any code:

> **a figure a module computed about its own rows** — an average turnaround from underwriting, a settled total from claims, an aging bucket from billing.

The rule that does not move: the **browser** never sums, averages or differences anything, and a figure is only shown where a person can open the list or report that proves it. This amendment widens *who may compute*, not *whether it must be provable*.

**Why not extend the reporting module instead.** It was the alternative, and it is the heavier, better-for-later answer: new `report_claim` and `report_underwriting_case` projections fed from the audit journal, rebuildable, giving real claims and underwriting *reports* as well as dashboard figures. It is a batch of its own and it belongs after phase 3, when the shape of what people actually read is known. Recorded here so the choice is visible rather than silently foreclosed.

## What phase 1 got wrong, and this plan corrects

**An agent's applications in underwriting were never a gap.** Phase 1 drew that card dashed, saying "agents cannot search underwriting cases today". They can: `GET /underwriting/cases` is `hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')`, and `UnderwritingController.listCases` already scopes an agents token to `partyApi.partyIdsRegisteredBy(jwt.getSubject())`. The card needs no backend work at all — only the composer reading it. Task 6 does that, and it is the cheapest task in this plan.

## The eight figures, and where each one comes from

| # | Figure | Dashboard | Owner | What is missing today |
|---|---|---|---|---|
| 1 | Accepted vs declined, by month | Underwriting | `underwriting` | `search` filters on status only; the outcome and the decision date are stored and unfilterable |
| 2 | Average turnaround, opened → decided | Underwriting | `underwriting` | both instants stored; nothing averages them |
| 3 | Cases referred to a senior | Underwriting | `underwriting` | `referralStatus` stored, not filterable |
| 4 | Claims received / settled in a period | Claims | `claims` | `search` has no date filter, and **no settlement date is stored at all** |
| 5 | Benefits paid, by month | Claims | `claims` | nothing sums `approvedAmount`, and (4) blocks "when" |
| 6 | Open document requests across claims | Claims | `claims` | requests are read per claim; nothing counts them tenant-wide |
| 7 | Receivables total and aging | Finance | `billing` | the arrears list exists; nothing totals or buckets it |
| 8 | Applications awaiting underwriting | Agent | — | **nothing** — already provable, see above |

## File Structure

**`underwriting`** — counts and timing for its own cases:
- Modify `infrastructure/UnderwritingCaseRepository.java` — outcome, referral and decided-date filters on `search`; one aggregate query.
- Modify `api/UnderwritingApi.java`, `application/UnderwritingApiImpl.java` — the widened `listCases` and a new `decisionsByMonth`.
- Create `api/DecisionMonth.java` — one month's decisions by outcome, with the average turnaround.
- Modify `infrastructure/UnderwritingController.java`, `backend/api/openapi/openapi-underwriting.yaml`.

**`claims`** — when a claim settled, and what it paid:
- Create `backend/db-migrations/claims/V10__claim_settled_at.sql`.
- Modify `domain/Claim.java` — `settledAt`, set by both settle paths.
- Modify `infrastructure/ClaimRepository.java` — type and date filters, one aggregate query, one open-request count.
- Modify `api/ClaimsApi.java`, `application/ClaimsApiImpl.java`.
- Create `api/SettlementMonth.java`.
- Modify `infrastructure/ClaimController.java`, `backend/api/openapi/openapi-claims.yaml`.

**`billing`** — aging its own receivables:
- Modify `infrastructure/ArrearsCaseRepository.java` — one bucketing query.
- Create `api/ReceivablesAging.java`, `api/ReceivablesBucket.java`.
- Modify `api/BillingApi.java`, `application/BillingApiImpl.java`.
- Modify `infrastructure/BillingController.java` (or wherever `/arrears` lives), `backend/api/openapi/openapi-billing.yaml`.

**`dashboard`** — read and forward only:
- Modify the four views and four composers, `infrastructure/DashboardController.java`, `backend/api/openapi/openapi-dashboard.yaml`.

**Frontend:**
- Modify `src/features/dashboard/UnderwritingDashboardPanel.tsx`, `ClaimsDashboardPanel.tsx`, `FinanceDashboardPanel.tsx`, `AgentDashboardPanel.tsx` — the dashed cards become real.
- Create `src/features/dashboard/ReceivablesAging.tsx`.
- Modify `PRODUCT.md`.

---

### Task 0: The doctrine amendment

**Files:**
- Modify: `PRODUCT.md`

- [ ] **Step 1: Widen the sources of a figure**

In `PRODUCT.md`, find the paragraph beginning `**A figure is a count, or a report's own number.**` and replace its "Two sources only" sentence with:

```markdown
Three sources only: a paged search's `totalElements` (work waiting, by one status), a
value the reporting module computed (`GET /reports/{key}`), and an aggregate a module
computed about its OWN rows — underwriting's turnaround, claims' settled totals,
billing's receivables aging (2026-10-15). What does not move: the browser never sums,
averages or differences anything, and a figure appears only where a person can open the
list or report that proves it.
```

- [ ] **Step 2: Commit**

```bash
git checkout main && git pull && git checkout -b role-dashboards-phase-2
git add PRODUCT.md backend/docs/superpowers/plans/2026-10-15-role-dashboards-phase-2.md
git commit -m "docs: a module may compute an aggregate about its own rows

Phase 2 needs figures no search total and no report can give: an average
turnaround, a settled total, an aging bucket. They go to the module that owns
the rows, never to the dashboard and never to the browser.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 1: Underwriting — outcome, referral and decided-date filters

Three cards (accepted, declined, referred) are counts the search cannot express. Each is one more null-safe clause in a query that already has three.

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/infrastructure/UnderwritingCaseRepository.java:35-43`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/api/UnderwritingApi.java:218`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/application/UnderwritingApiImpl.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/infrastructure/UnderwritingController.java:65-81`
- Modify: `backend/api/openapi/openapi-underwriting.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting/UnderwritingApiIntegrationTest.java`

**Interfaces:**
- Produces: `record CaseFilter(UnderwritingCaseStatus status, DecisionOutcome outcome, ReferralStatus referral, Instant decidedFrom, Instant decidedTo, UUID applicantPartyId, Set<UUID> applicantPartyIds)` in `underwriting.api`, with `static CaseFilter status(UnderwritingCaseStatus s)` and `withOutcome`, `withReferral`, `withDecided(Instant from, Instant to)`, `withApplicant(UUID)`, `withApplicants(Set<UUID>)`.
- Produces: `Page<UnderwritingCaseView> UnderwritingApi.listCases(CaseFilter filter, Pageable pageable)`. **The existing four-argument `listCases` stays**, delegating — about thirty callers and fixtures use it, and the overload rule `registerIndividual` set applies.

- [ ] **Step 1: Write the failing test**

Append to `UnderwritingApiIntegrationTest` (reuse its existing fixture helpers for opening and deciding a case; read the class first for their names):

```java
    /**
     * The three dimensions a dashboard counts on and the search could not express: what was decided, whether it went
     * to a senior, and when. Each is one null-safe clause; together they are "accepted this month", "declined this
     * month" and "waiting on a senior".
     */
    @Test
    void filtersCasesByOutcomeReferralAndWhenTheyWereDecided() {
        UUID accepted = openCase();
        decide(accepted, DecisionOutcome.ACCEPTED);
        UUID declined = openCase();
        decide(declined, DecisionOutcome.DECLINED);
        UUID open = openCase();

        assertThat(underwritingApi.listCases(CaseFilter.none().withOutcome(DecisionOutcome.ACCEPTED), PAGE)
            .getContent()).extracting(UnderwritingCaseView::caseId).containsExactly(accepted);
        assertThat(underwritingApi.listCases(CaseFilter.none().withOutcome(DecisionOutcome.DECLINED), PAGE)
            .getTotalElements()).isEqualTo(1);

        // Decided inside the window; the undecided case is never in a decided-date range, whatever the bounds.
        Instant now = Instant.now();
        assertThat(underwritingApi.listCases(
            CaseFilter.none().withDecided(now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS)), PAGE)
            .getTotalElements()).isEqualTo(2);
        assertThat(underwritingApi.listCases(
            CaseFilter.none().withDecided(now.plus(1, ChronoUnit.DAYS), now.plus(2, ChronoUnit.DAYS)), PAGE)
            .getTotalElements()).isZero();
        assertThat(underwritingApi.listCases(CaseFilter.none(), PAGE).getContent())
            .extracting(UnderwritingCaseView::caseId).contains(open);
    }

    @Test
    void filtersCasesWaitingOnASeniorUnderwriter() {
        UUID referred = openCase();
        underwritingApi.referToSeniorUnderwriter(referred);
        openCase();

        assertThat(underwritingApi.listCases(
            CaseFilter.none().withReferral(ReferralStatus.REFERRED_TO_SENIOR), PAGE).getContent())
            .extracting(UnderwritingCaseView::caseId).containsExactly(referred);
    }

    /** The old four-argument call is unchanged for its ~30 callers: the same rows, through the new filter. */
    @Test
    void theFourArgumentListStillWorks() {
        UUID open = openCase();

        assertThat(underwritingApi.listCases(UnderwritingCaseStatus.OPEN, null, null, PAGE).getContent())
            .extracting(UnderwritingCaseView::caseId).contains(open);
    }
```

Add `private static final Pageable PAGE = PageRequest.of(0, 20);` to the class if it has no equivalent, and imports for `CaseFilter`, `DecisionOutcome`, `ReferralStatus`, `Instant`, `ChronoUnit`, `Pageable`, `PageRequest`.

- [ ] **Step 2: Run it to verify it fails**

Run (from `backend/`, dev backend stopped): `./mvnw -o -q test-compile`
Expected: FAIL — `cannot find symbol: class CaseFilter`.

- [ ] **Step 3: Create the filter**

`underwriting/api/CaseFilter.java`:

```java
package tz.co.nlolo.lifeplatform.underwriting.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * What a caller is asking of the case list. A record rather than more parameters: the search had four and grew three
 * more for the dashboards (2026-10-15), and a seven-argument method is one whose call sites no longer read.
 *
 * <p>Every field is optional and means "no filter on that dimension" when null. {@code applicantPartyIds} keeps its
 * three-way meaning exactly: NULL for staff (no scope), an EMPTY set for an agent who has registered nobody (scope to
 * nothing, zero rows), a populated set for the clients an agent registered.
 *
 * @param decidedFrom inclusive; {@code decidedTo} exclusive, so a month is [first of month, first of next)
 */
public record CaseFilter(UnderwritingCaseStatus status, DecisionOutcome outcome, ReferralStatus referral,
                         Instant decidedFrom, Instant decidedTo, UUID applicantPartyId, Set<UUID> applicantPartyIds) {

    public static CaseFilter none() {
        return new CaseFilter(null, null, null, null, null, null, null);
    }

    public static CaseFilter status(UnderwritingCaseStatus status) {
        return none().withStatus(status);
    }

    public CaseFilter withStatus(UnderwritingCaseStatus value) {
        return new CaseFilter(value, outcome, referral, decidedFrom, decidedTo, applicantPartyId, applicantPartyIds);
    }

    public CaseFilter withOutcome(DecisionOutcome value) {
        return new CaseFilter(status, value, referral, decidedFrom, decidedTo, applicantPartyId, applicantPartyIds);
    }

    public CaseFilter withReferral(ReferralStatus value) {
        return new CaseFilter(status, outcome, value, decidedFrom, decidedTo, applicantPartyId, applicantPartyIds);
    }

    public CaseFilter withDecided(Instant from, Instant to) {
        return new CaseFilter(status, outcome, referral, from, to, applicantPartyId, applicantPartyIds);
    }

    public CaseFilter withApplicant(UUID value) {
        return new CaseFilter(status, outcome, referral, decidedFrom, decidedTo, value, applicantPartyIds);
    }

    public CaseFilter withApplicants(Set<UUID> value) {
        return new CaseFilter(status, outcome, referral, decidedFrom, decidedTo, applicantPartyId, value);
    }
}
```

- [ ] **Step 4: Widen the query**

In `UnderwritingCaseRepository`, replace the `search` query and signature with:

```java
    @Query("SELECT c FROM UnderwritingCase c WHERE c.tenantId = :tenantId "
        + "AND (:status IS NULL OR c.status = :status) "
        + "AND (:outcome IS NULL OR c.decisionOutcome = :outcome) "
        + "AND (:referral IS NULL OR c.referralStatus = :referral) "
        // A decided-date bound also excludes the undecided: a null decidedAt matches no range.
        + "AND (:decidedFrom IS NULL OR (c.decisionDecidedAt IS NOT NULL AND c.decisionDecidedAt >= :decidedFrom)) "
        + "AND (:decidedTo IS NULL OR (c.decisionDecidedAt IS NOT NULL AND c.decisionDecidedAt < :decidedTo)) "
        + "AND (:applicantPartyId IS NULL OR c.applicantPartyId = :applicantPartyId) "
        + "AND (:applicantPartyIds IS NULL OR c.applicantPartyId IN :applicantPartyIds)")
    Page<UnderwritingCase> search(@Param("tenantId") UUID tenantId,
                                   @Param("status") String status,
                                   @Param("outcome") String outcome,
                                   @Param("referral") String referral,
                                   @Param("decidedFrom") Instant decidedFrom,
                                   @Param("decidedTo") Instant decidedTo,
                                   @Param("applicantPartyId") UUID applicantPartyId,
                                   @Param("applicantPartyIds") Set<UUID> applicantPartyIds,
                                   Pageable pageable);
```

Add `import java.time.Instant;` if absent. Check how `status` is stored before writing `outcome` and `referral`: the existing query binds `status` as a `String`, so the columns are `@Enumerated(EnumType.STRING)` and the new parameters are `String` too — pass `enum.name()`, not the enum.

- [ ] **Step 5: Widen the API and keep the old call**

In `UnderwritingApi`, below the existing `listCases`:

```java
    /**
     * The same list, by every dimension a caller can ask on (2026-10-15): status, decision outcome, referral status,
     * when it was decided, and the applicant scope.
     *
     * <p>An overload rather than a widened signature, for the reason {@code registerIndividual} records: about thirty
     * callers ask only "the open cases" and have no opinion about the rest.
     */
    Page<UnderwritingCaseView> listCases(CaseFilter filter, Pageable pageable);
```

In `UnderwritingApiImpl`, implement the new one against the widened query and make the old one delegate:

```java
    @Override
    public Page<UnderwritingCaseView> listCases(UnderwritingCaseStatus status, UUID applicantPartyId,
                                                 Set<UUID> applicantPartyIds, Pageable pageable) {
        return listCases(CaseFilter.status(status).withApplicant(applicantPartyId).withApplicants(applicantPartyIds),
            pageable);
    }

    @Override
    public Page<UnderwritingCaseView> listCases(CaseFilter filter, Pageable pageable) {
        return repository.search(TenantContext.get(),
            filter.status() == null ? null : filter.status().name(),
            filter.outcome() == null ? null : filter.outcome().name(),
            filter.referral() == null ? null : filter.referral().name(),
            filter.decidedFrom(), filter.decidedTo(),
            filter.applicantPartyId(), filter.applicantPartyIds(), pageable)
            .map(/* the existing mapper this class already uses */);
    }
```

Read the existing method body first and reuse its exact mapper and tenant call; do not invent a second mapping.

Both arms are reads; if the existing method carries `@Transactional(readOnly = true)`, put it on both — a delegating call is a self-invocation that never reaches the proxy.

- [ ] **Step 6: Run the tests**

Run: `./mvnw -o -q clean test-compile && ./mvnw -o test -Dtest=UnderwritingApiIntegrationTest -DfailIfNoSpecifiedTests=false`
Expected: `Failures: 0, Errors: 0`, with three more tests than before.

- [ ] **Step 7: Expose the filters on the endpoint**

In `UnderwritingController.listCases`, add `@RequestParam(required = false) DecisionOutcome outcome`, `@RequestParam(required = false) ReferralStatus referral`, and `@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate decidedFrom` / `decidedTo`. Convert the dates at the boundary and keep the existing agent scoping exactly:

```java
        // Civil days in Africa/Dar_es_Salaam, half-open: decidedFrom 00:00 inclusive to decidedTo+1 00:00 exclusive,
        // so "decided in October" is every instant of the 1st through the 31st and nothing of 1 November.
        Instant from = decidedFrom == null ? null : decidedFrom.atStartOfDay(CIVIL).toInstant();
        Instant to = decidedTo == null ? null : decidedTo.plusDays(1).atStartOfDay(CIVIL).toInstant();
        CaseFilter filter = CaseFilter.status(status).withOutcome(outcome).withReferral(referral)
            .withDecided(from, to).withApplicant(applicantPartyId).withApplicants(applicantPartyIds);
        Page<UnderwritingCaseView> result = underwritingApi.listCases(filter, pageable);
```

with `private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");` on the controller.

In `openapi-underwriting.yaml`, add the four query parameters to `GET /underwriting/cases`:

```yaml
        - { name: outcome, in: query, required: false, schema: { type: string, enum: [ACCEPTED, LOADED, POSTPONED, DECLINED] } }
        - { name: referral, in: query, required: false, schema: { type: string, enum: [NONE, REFERRED_TO_SENIOR, REFERRAL_RESOLVED] } }
        - { name: decidedFrom, in: query, required: false, schema: { type: string, format: date }, description: "Civil day in Africa/Dar_es_Salaam, inclusive. Excludes undecided cases." }
        - { name: decidedTo, in: query, required: false, schema: { type: string, format: date }, description: "Civil day, inclusive." }
```

Read the enum values off `DecisionOutcome.java` and `ReferralStatus.java` before writing them; do not copy the list above on trust.

- [ ] **Step 8: Run the contract test and commit**

Run: `./mvnw -o test -Dtest='UnderwritingApiIntegrationTest,UnderwritingContractTest' -DfailIfNoSpecifiedTests=false`
Expected: both `Failures: 0, Errors: 0`.

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting backend/api/openapi/openapi-underwriting.yaml backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting
git commit -m "feat(underwriting): cases filter by outcome, referral and decision date

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Underwriting — decisions and turnaround, by month

The one figure no filter can give: how long a case waits. Both instants are stored; nothing averages them. Returned per month so phase 3 charts it without a second endpoint.

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting/api/DecisionMonth.java`
- Modify: `underwriting/infrastructure/UnderwritingCaseRepository.java`
- Modify: `underwriting/api/UnderwritingApi.java`, `application/UnderwritingApiImpl.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting/UnderwritingApiIntegrationTest.java`

**Interfaces:**
- Produces: `record DecisionMonth(YearMonth month, DecisionOutcome outcome, long cases, BigDecimal averageDays)` in `underwriting.api`; `List<DecisionMonth> UnderwritingApi.decisionsByMonth(YearMonth from, YearMonth to)` — oldest first, one row per month and outcome, a month with no decisions absent, `averageDays` to one decimal place.

- [ ] **Step 1: Write the failing test**

```java
    /**
     * How many decisions of each kind, and how long each took. The average is the module's own arithmetic about its
     * own rows -- a dashboard that computed it would be adding up a page of cases and calling it the truth.
     *
     * <p>Per month and per outcome, because a card reads the last complete month and a chart reads them all.
     */
    @Test
    void averagesTurnaroundPerMonthAndOutcome() {
        UUID fast = openCaseCreatedAt(Instant.parse("2026-03-01T08:00:00Z"));
        decideAt(fast, DecisionOutcome.ACCEPTED, Instant.parse("2026-03-03T08:00:00Z"));
        UUID slow = openCaseCreatedAt(Instant.parse("2026-03-01T08:00:00Z"));
        decideAt(slow, DecisionOutcome.ACCEPTED, Instant.parse("2026-03-09T08:00:00Z"));
        UUID other = openCaseCreatedAt(Instant.parse("2026-03-02T08:00:00Z"));
        decideAt(other, DecisionOutcome.DECLINED, Instant.parse("2026-03-04T08:00:00Z"));
        UUID undecided = openCaseCreatedAt(Instant.parse("2026-03-02T08:00:00Z"));

        List<DecisionMonth> months = underwritingApi.decisionsByMonth(YearMonth.of(2026, 3), YearMonth.of(2026, 3));

        assertThat(months).extracting(m -> m.month() + " " + m.outcome() + " " + m.cases() + " " + m.averageDays())
            .containsExactlyInAnyOrder("2026-03 ACCEPTED 2 5.0", "2026-03 DECLINED 1 2.0");
        assertThat(undecided).isNotNull();
    }

    /** A month nobody decided anything in is ABSENT, not a zero: zero decisions and no data look different. */
    @Test
    void leavesOutAMonthWithNoDecisions() {
        assertThat(underwritingApi.decisionsByMonth(YearMonth.of(2020, 1), YearMonth.of(2020, 2))).isEmpty();
    }
```

`openCaseCreatedAt` and `decideAt` do not exist. Add them to the test class beside its existing helpers: open and decide through `underwritingApi` as the other tests do, then set the two instants directly with the test's `JdbcTemplate` (`UPDATE underwriting.underwriting_case SET created_at = ?, decision_decided_at = ? WHERE case_id = ?`). The entity sets both from the clock, and a turnaround test needs them at known points. If the class has no `JdbcTemplate`, add `@Autowired private JdbcTemplate jdbc;`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -o -q test-compile`
Expected: FAIL — `cannot find symbol: class DecisionMonth`.

- [ ] **Step 3: Create the view**

```java
package tz.co.nlolo.lifeplatform.underwriting.api;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * One month's decisions of one outcome, and how long they took (2026-10-15).
 *
 * @param averageDays mean days from a case being opened to being decided, one decimal place. Calendar days, not
 *     working days: the platform has no calendar of holidays, and a "4.2 working days" it cannot support would be a
 *     figure nobody could reproduce.
 */
public record DecisionMonth(YearMonth month, DecisionOutcome outcome, long cases, BigDecimal averageDays) {}
```

- [ ] **Step 4: Add the aggregate query**

In `UnderwritingCaseRepository`, a native query — the date arithmetic is Postgres's:

```java
    /**
     * Decisions per month and outcome, with the mean days from opening to decision. Native because the interval
     * arithmetic and {@code to_char} are Postgres's; JPQL has neither.
     *
     * <p>Months are civil months in Africa/Dar_es_Salaam: a case decided at 23:30 on 31 October UTC was decided on
     * 1 November here, and counting it in October would put it in a month the business had already closed.
     */
    @Query(value = """
        SELECT to_char(c.decision_decided_at AT TIME ZONE 'Africa/Dar_es_Salaam', 'YYYY-MM') AS month,
               c.decision_outcome AS outcome,
               COUNT(*) AS cases,
               ROUND(AVG(EXTRACT(EPOCH FROM (c.decision_decided_at - c.created_at)) / 86400.0)::numeric, 1) AS avg_days
          FROM underwriting.underwriting_case c
         WHERE c.tenant_id = :tenantId
           AND c.decision_decided_at IS NOT NULL
           AND c.decision_decided_at >= :from AND c.decision_decided_at < :to
         GROUP BY month, c.decision_outcome
         ORDER BY month, c.decision_outcome
        """, nativeQuery = true)
    List<Object[]> decisionsByMonth(@Param("tenantId") UUID tenantId, @Param("from") Instant from,
                                     @Param("to") Instant to);
```

Confirm the schema and table name against `db-migrations/underwriting/V1__*.sql` before running.

- [ ] **Step 5: Implement**

In `UnderwritingApi`:

```java
    /**
     * Decisions per month and outcome with their average turnaround, {@code from} through {@code to} inclusive,
     * oldest first. A month in which nothing was decided is absent rather than zero.
     */
    List<DecisionMonth> decisionsByMonth(YearMonth from, YearMonth to);
```

In `UnderwritingApiImpl`:

```java
    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");

    @Override
    public List<DecisionMonth> decisionsByMonth(YearMonth from, YearMonth to) {
        Instant start = from.atDay(1).atStartOfDay(CIVIL).toInstant();
        Instant end = to.plusMonths(1).atDay(1).atStartOfDay(CIVIL).toInstant();
        return repository.decisionsByMonth(TenantContext.get(), start, end).stream()
            .map(row -> new DecisionMonth(YearMonth.parse((String) row[0]),
                DecisionOutcome.valueOf((String) row[1]), ((Number) row[2]).longValue(), (BigDecimal) row[3]))
            .toList();
    }
```

- [ ] **Step 6: Run and commit**

Run: `./mvnw -o -q clean test-compile && ./mvnw -o test -Dtest=UnderwritingApiIntegrationTest -DfailIfNoSpecifiedTests=false`
Expected: `Failures: 0, Errors: 0`, two more tests than Task 1 left.

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/underwriting backend/src/test/java/tz/co/nlolo/lifeplatform/underwriting
git commit -m "feat(underwriting): decisions and average turnaround, by month and outcome

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Claims — record when a claim settled

The blocker behind two cards. `Claim` stores `createdAt`, `updatedAt` and `dateOfEvent` and **no settlement date**, so "settled this month" and "benefits paid this month" cannot be asked at all. `updatedAt` is not an answer: a reopened-then-corrected claim moves it long after the money left.

**Files:**
- Create: `backend/db-migrations/claims/V10__claim_settled_at.sql`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/claims/domain/Claim.java` (`markSettled`, `settleWithNothingToPay`)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/claims/api/ClaimView.java`
- Modify: `claims/application/ClaimsApiImpl.java` (wherever `ClaimView` is built)
- Modify: `backend/api/openapi/openapi-claims.yaml`
- Modify: every test class listing claims migrations (see Step 2)
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/claims/ClaimsApiIntegrationTest.java`

**Interfaces:**
- Produces: `Instant settledAt` as a new last component of `ClaimView`; null until the claim settles, and null for every claim settled before this migration.

- [ ] **Step 1: Write the failing test**

```java
    /**
     * When the money left, recorded at the moment it did. Nothing stored this before 2026-10-15, so "settled this
     * month" could not be asked; updatedAt is not an answer, since a later correction moves it.
     */
    @Test
    void recordsWhenAClaimSettled() {
        UUID claimId = /* register, assess, approve and settle a claim as this class already does */;

        ClaimView settled = claimsApi.getClaim(claimId);

        assertThat(settled.status()).isEqualTo(ClaimStatus.SETTLED);
        assertThat(settled.settledAt()).isNotNull();
        assertThat(settled.settledAt()).isBetween(Instant.now().minus(5, ChronoUnit.MINUTES), Instant.now());
    }

    @Test
    void anUnsettledClaimHasNoSettlementDate() {
        UUID claimId = /* register a claim only */;

        assertThat(claimsApi.getClaim(claimId).settledAt()).isNull();
    }
```

Read the class first and reuse its own fixture helpers for the claim lifecycle; `getClaim` may be named differently.

- [ ] **Step 2: Write the migration**

`backend/db-migrations/claims/V10__claim_settled_at.sql`:

```sql
-- db-migrations/claims/V10__claim_settled_at.sql
-- When a claim settled, recorded at the moment it does.
--
-- THE GAP THIS CLOSES. claim carried created_at, updated_at and date_of_event, and nothing for the one date a
-- finance officer and a claims manager both ask about: when the benefit was actually paid. "Claims settled this
-- month" and "benefits paid this month" were unanswerable, which is why the role dashboards (2026-10-14) drew both
-- cards dashed.
--
-- NOT updated_at. That moves whenever anything about the claim changes -- a reopened claim corrected in December
-- would report itself as settled in December although the money left in August.
--
-- NO BACKFILL. The claim table never recorded it, so the honest value for every existing settled claim is NULL, and
-- the console says "not recorded". claims.ClaimSettled events in the audit journal carry settledAt and could fill
-- some rows; that is a data-repair exercise with its own proof of correctness, not a column default. Deliberately
-- left for whoever needs the history, and recorded here so the absence is a decision rather than an oversight.
ALTER TABLE claims.claim
    ADD COLUMN settled_at TIMESTAMPTZ;

COMMENT ON COLUMN claims.claim.settled_at IS
    'When this claim settled, set by Claim.markSettled and Claim.settleWithNothingToPay. NULL while unsettled, and '
    'NULL for every claim settled before V10.';
```

Then add it to **every** test class that lists claims migrations:

```bash
grep -rln "db-migrations/claims/V9__zero_annuity_settlement.sql" backend/src/test/java | wc -l
```

Insert `"db-migrations/claims/V10__claim_settled_at.sql",` after the V9 line in each, preserving indentation and commas — the same sweep party V7 needed. Verify with:

```bash
echo "V9: $(grep -rl 'claims/V9__zero_annuity_settlement' backend/src/test/java | wc -l)  V10: $(grep -rl 'claims/V10__claim_settled_at' backend/src/test/java | wc -l)"
```
Both counts must match.

- [ ] **Step 3: Run it to verify it fails**

Run: `./mvnw -o -q test-compile`
Expected: FAIL — `cannot find symbol: method settledAt()`.

- [ ] **Step 4: Set it on both settle paths**

In `Claim.java`, add the field and set it in **both** methods — `markSettled` (the payment path) and `settleWithNothingToPay` (the zero-annuity path). Missing the second would make a settled-at-zero claim invisible to every count built on this column:

```java
    @Column(name = "settled_at")
    private Instant settledAt;

    public Instant getSettledAt() { return settledAt; }
```

and in each method, beside `this.status = ClaimStatus.SETTLED;`:

```java
        this.settledAt = Instant.now();
```

Add `settledAt` as the last component of `ClaimView`, and pass `claim.getSettledAt()` wherever the view is constructed (`grep -n "new ClaimView(" backend/src/main/java -r` — fix every site).

In `openapi-claims.yaml`, add to the claim schema:

```yaml
        settledAt: { type: [string, 'null'], format: date-time, description: "When the claim settled. Null while unsettled, and null for claims settled before 2026-10-15." }
```

- [ ] **Step 5: Run the claims tests**

Run: `./mvnw -o -q clean test-compile && ./mvnw -o test -Dtest='ClaimsApiIntegrationTest,ClaimsContractTest,ClaimSettlementEndToEndTest' -DfailIfNoSpecifiedTests=false`
Expected: all three `Failures: 0, Errors: 0`. A `column "settled_at" does not exist` here means a test class was missed in the migration sweep.

- [ ] **Step 6: Apply it to the dev database and commit**

The dev database does not run migrations on boot, and `scripts/migrate.sh local` cannot be used on it (it predates Flyway and has no history table, so Flyway starts at V1 and dies on "relation already exists", applying nothing). Apply the one file by hand, as this project always has:

```bash
docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 < backend/db-migrations/claims/V10__claim_settled_at.sql
docker exec infra-postgres-1 psql -U postgres -d lifeplatform -c "\d claims.claim" | grep settled_at
```

```bash
git add backend/db-migrations/claims backend/src/main/java/tz/co/nlolo/lifeplatform/claims backend/api/openapi/openapi-claims.yaml backend/src/test/java
git commit -m "feat(claims): record when a claim settled

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Claims — type and date filters, settlement totals, open document requests

Three figures in one module. The filters are counts; the totals are the module's own arithmetic; the request count is the one question `ClaimDocumentRequest` could not answer across claims.

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/claims/api/ClaimFilter.java`, `api/SettlementMonth.java`
- Modify: `claims/infrastructure/ClaimRepository.java:52-62`, `claims/infrastructure/ClaimDocumentRequestRepository.java`
- Modify: `claims/api/ClaimsApi.java`, `application/ClaimsApiImpl.java`
- Modify: `claims/infrastructure/ClaimController.java`, `backend/api/openapi/openapi-claims.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/claims/ClaimsApiIntegrationTest.java`

**Interfaces:**
- Produces: `record ClaimFilter(ClaimStatus status, ClaimType claimType, UUID claimantPartyId, Collection<String> policyNumbers, String q, Instant registeredFrom, Instant registeredTo, Instant settledFrom, Instant settledTo)` with `none()` and `with…` builders.
- Produces: `Page<ClaimView> ClaimsApi.searchClaims(ClaimFilter filter, Pageable pageable)`; the existing five-argument `searchClaims` stays and delegates.
- Produces: `record SettlementMonth(YearMonth month, String currency, long claims, BigDecimal totalApproved)`; `List<SettlementMonth> ClaimsApi.settlementsByMonth(YearMonth from, YearMonth to)` — per month **and currency**, never added across currencies.
- Produces: `long ClaimsApi.countOpenDocumentRequests()`.

- [ ] **Step 1: Write the failing tests**

```java
    /** A dashboard counts "death claims this month"; the search could express neither half. */
    @Test
    void filtersClaimsByTypeAndWhenTheyWereRegistered() {
        UUID death = registerClaim(ClaimType.DEATH);
        registerClaim(ClaimType.DISABILITY);
        Instant now = Instant.now();

        assertThat(claimsApi.searchClaims(ClaimFilter.none().withClaimType(ClaimType.DEATH), PAGE).getContent())
            .extracting(ClaimView::claimId).containsExactly(death);
        assertThat(claimsApi.searchClaims(
            ClaimFilter.none().withRegistered(now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS)), PAGE)
            .getTotalElements()).isEqualTo(2);
        assertThat(claimsApi.searchClaims(
            ClaimFilter.none().withRegistered(now.plus(1, ChronoUnit.DAYS), now.plus(2, ChronoUnit.DAYS)), PAGE)
            .getTotalElements()).isZero();
    }

    /**
     * What was actually paid, per month and PER CURRENCY. A single total across currencies would be a number in no
     * currency at all; the dashboard shows the TZS row and says nothing about the rest.
     */
    @Test
    void totalsSettledClaimsByMonthAndCurrency() {
        UUID first = settleClaimAt("4000000.00", "TZS", Instant.parse("2026-03-04T08:00:00Z"));
        UUID second = settleClaimAt("6000000.00", "TZS", Instant.parse("2026-03-20T08:00:00Z"));
        settleClaimAt("1000.00", "USD", Instant.parse("2026-03-21T08:00:00Z"));
        settleClaimAt("2000000.00", "TZS", Instant.parse("2026-04-02T08:00:00Z"));

        List<SettlementMonth> months = claimsApi.settlementsByMonth(YearMonth.of(2026, 3), YearMonth.of(2026, 4));

        assertThat(months).extracting(m -> m.month() + " " + m.currency() + " " + m.claims() + " "
                + m.totalApproved().toPlainString())
            .containsExactly("2026-03 TZS 2 10000000.00", "2026-03 USD 1 1000.00", "2026-04 TZS 1 2000000.00");
        assertThat(first).isNotEqualTo(second);
    }

    /** The question no screen could ask: how many claims are waiting on a document, across the whole desk. */
    @Test
    void countsOpenDocumentRequestsAcrossClaims() {
        UUID a = registerClaim(ClaimType.DEATH);
        UUID b = registerClaim(ClaimType.DEATH);
        claimsApi.requestDocument(a, "Death certificate", null);
        claimsApi.requestDocument(b, "Police report", null);
        claimsApi.withdrawDocumentRequest(/* the request on b */);

        assertThat(claimsApi.countOpenDocumentRequests()).isEqualTo(1);
    }
```

`settleClaimAt` does not exist: register, assess, approve and settle through the API as the class already does, then set `settled_at` and the approved amount with the test's `JdbcTemplate`. Read `ClaimsApi` for the real names of the document-request methods and their arguments before writing the third test; fix the call to match.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -o -q test-compile`
Expected: FAIL — `cannot find symbol: class ClaimFilter`.

- [ ] **Step 3: Implement the filter and the two aggregates**

`ClaimFilter` follows `CaseFilter` from Task 1 exactly in shape and in its javadoc about null meaning "no filter". Widen `ClaimRepository.search` with four more null-safe clauses (`claimType`, `createdAt >= :registeredFrom`, `createdAt < :registeredTo`, and the same pair on `settledAt`, each excluding nulls the way Task 1's decided-date clause does).

`SettlementMonth`:

```java
package tz.co.nlolo.lifeplatform.claims.api;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * One month's settled claims in one currency (2026-10-15).
 *
 * <p>Per currency, never summed across: a total of TZS and USD is a number in no currency. The approved amount is
 * what the platform approved, which is what it has; the amount a bank actually moved belongs to {@code payment}.
 */
public record SettlementMonth(YearMonth month, String currency, long claims, BigDecimal totalApproved) {}
```

The aggregate query mirrors Task 2's — native, grouping `to_char(settled_at AT TIME ZONE 'Africa/Dar_es_Salaam', 'YYYY-MM')` and `approved_currency`, summing `approved_amount`, `WHERE settled_at IS NOT NULL AND settled_at >= :from AND settled_at < :to`. Rows with a null `approved_currency` (an annuity settled at zero) are excluded by `AND approved_currency IS NOT NULL` — a currency-less amount cannot join a currency's total.

The open-request count is a derived method on `ClaimDocumentRequestRepository`. Read its entity for the field that marks a request answered or withdrawn — `ClaimDocumentRequest` carries `requestedAt`, `requestedBy` and a status-like field; count the ones still outstanding for the tenant, and name the method for exactly that.

- [ ] **Step 4: Run, expose, commit**

Run: `./mvnw -o -q clean test-compile && ./mvnw -o test -Dtest='ClaimsApiIntegrationTest,ClaimsContractTest' -DfailIfNoSpecifiedTests=false`
Expected: `Failures: 0, Errors: 0`.

Add `claimType`, `registeredFrom`, `registeredTo` as query parameters on `GET /claims` in the controller and `openapi-claims.yaml`, converting civil days to instants exactly as Task 1 Step 7 does. `settledFrom`/`settledTo` are not exposed on the endpoint — no screen filters on them, and an unused parameter is one more thing to keep true.

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/claims backend/api/openapi/openapi-claims.yaml backend/src/test/java/tz/co/nlolo/lifeplatform/claims
git commit -m "feat(claims): type and date filters, settled totals by month, open document requests

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Billing — receivables total and aging

The finance dashboard's one dashed card. `ArrearsCaseView` already carries `amount`, `currency`, `dueDate` and `invoiceStatus`; nothing totals or buckets them.

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/billing/api/ReceivablesAging.java`
- Modify: `billing/infrastructure/ArrearsCaseRepository.java`
- Modify: `billing/api/BillingApi.java`, `application/BillingApiImpl.java`
- Modify: the controller serving `/arrears`, `backend/api/openapi/openapi-billing.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/billing/BillingApiIntegrationTest.java`

**Interfaces:**
- Produces: `record ReceivablesAging(String currency, List<Bucket> buckets)` with `record Bucket(String name, long cases, BigDecimal outstanding)` nested, and `static final List<String> BUCKETS = List.of("NOT_DUE", "D1_30", "D31_60", "D61_90", "OVER_90")`.
- Produces: `List<ReceivablesAging> BillingApi.receivablesAging(LocalDate asOf)` — one entry per currency, every bucket present (zero is a real answer here: a bucket with nothing in it is "nothing is this old", which the reader needs).

- [ ] **Step 1: Write the failing test**

```java
    /**
     * What is owed, and how old it is. Every bucket is present even when empty -- unlike a monthly series, where an
     * absent month means "no data", an empty bucket here means "nothing is this overdue", which is the answer.
     *
     * <p>Outstanding is amount MINUS what has been paid: a part-paid invoice owes the remainder, and showing its full
     * value would overstate the book.
     */
    @Test
    void agesReceivablesIntoBucketsPerCurrency() {
        LocalDate asOf = LocalDate.of(2026, 10, 15);
        arrearsFor("POL-A", "100000.00", "TZS", asOf.plusDays(5), "0.00");    // not due
        arrearsFor("POL-B", "200000.00", "TZS", asOf.minusDays(10), "0.00");  // 1-30
        arrearsFor("POL-C", "300000.00", "TZS", asOf.minusDays(45), "50000.00"); // 31-60, part paid
        arrearsFor("POL-D", "400000.00", "TZS", asOf.minusDays(200), "0.00"); // over 90

        List<ReceivablesAging> aging = billingApi.receivablesAging(asOf);

        assertThat(aging).singleElement().satisfies(tzs -> {
            assertThat(tzs.currency()).isEqualTo("TZS");
            assertThat(tzs.buckets()).extracting(b -> b.name() + " " + b.cases() + " " + b.outstanding().toPlainString())
                .containsExactly("NOT_DUE 1 100000.00", "D1_30 1 200000.00", "D31_60 1 250000.00",
                    "D61_90 0 0.00", "OVER_90 1 400000.00");
        });
    }

    /** A resolved case is not a receivable: it has been paid or waived and owes nothing. */
    @Test
    void leavesOutResolvedArrears() {
        LocalDate asOf = LocalDate.of(2026, 10, 15);
        UUID resolved = arrearsFor("POL-E", "500000.00", "TZS", asOf.minusDays(10), "0.00");
        resolveArrears(resolved);

        assertThat(billingApi.receivablesAging(asOf)).isEmpty();
    }
```

`arrearsFor` and `resolveArrears` do not exist. Read the class for how it already creates an invoice and drives it into arrears, and write the two helpers from that; the invoice carries `amount`, `currency`, `dueDate` and `amountPaid`, and the arrears case carries `resolvedAt`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -o -q test-compile`
Expected: FAIL — `cannot find symbol: class ReceivablesAging`.

- [ ] **Step 3: Implement**

The query joins `arrears_case` to `premium_invoice` for the amount and due date, as the existing `ArrearsCaseView` mapping already does. Read that mapping first and reuse its join. The bucketing is a `CASE` on `:asOf - i.due_date`, summing `i.amount - COALESCE(i.amount_paid, 0)`, grouped by currency and bucket, `WHERE a.resolved_at IS NULL`.

Build every bucket in the implementation rather than in SQL: the query returns only buckets with rows, and `ReceivablesAging` promises all five in order. An absent bucket becomes `new Bucket(name, 0, BigDecimal.ZERO.setScale(2))`.

- [ ] **Step 4: Run, expose, commit**

Run: `./mvnw -o -q clean test-compile && ./mvnw -o test -Dtest='BillingApiIntegrationTest,BillingContractTest' -DfailIfNoSpecifiedTests=false`
Expected: `Failures: 0, Errors: 0`.

Expose `GET /arrears/aging` (`FINANCE_OFFICER` or `ADMIN`, matching every other finance endpoint), taking an optional `asOf` civil date defaulting to today in `Africa/Dar_es_Salaam`, and add it to `openapi-billing.yaml`.

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/billing backend/api/openapi/openapi-billing.yaml backend/src/test/java/tz/co/nlolo/lifeplatform/billing
git commit -m "feat(billing): receivables total and aging, per currency

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: The dashboards read the new figures

Every dashed card becomes real. The agent's "applications awaiting underwriting" needs no backend work — see "What phase 1 got wrong" above.

**Files:**
- Modify: `dashboard/api/UnderwritingDashboardView.java`, `ClaimsDashboardView.java`, `FinanceDashboardView.java`, `AgentDashboardView.java`
- Modify: `dashboard/application/UnderwritingDashboard.java`, `ClaimsDashboard.java`, `FinanceDashboard.java`, `AgentDashboard.java`
- Modify: `dashboard/package-info.java` — nothing new; `underwriting::api`, `claims::api` and `billing::api` are already allowed
- Modify: `backend/api/openapi/openapi-dashboard.yaml`
- Test: the four composer tests and `DashboardContractTest`

**Interfaces:**
- Consumes: `UnderwritingApi.listCases(CaseFilter, Pageable)` and `decisionsByMonth` (Tasks 1–2); `ClaimsApi.searchClaims(ClaimFilter, Pageable)`, `settlementsByMonth`, `countOpenDocumentRequests` (Tasks 3–4); `BillingApi.receivablesAging` (Task 5); `PartyApi.partyIdsRegisteredBy` (already there).
- Produces, added to the views: underwriting `Long casesReferred`, `List<DecisionMonth> decisions`; claims `List<SettlementMonth> settlements`, `Long openDocumentRequests`; finance `List<ReceivablesAging> receivables`; agent `Long applicationsInUnderwriting`.

- [ ] **Step 1: Write the failing composer tests**

Add to `UnderwritingDashboardTest`:

```java
    /** The three cards phase 1 drew dashed, now read from the filters and the aggregate that answer them. */
    @Test
    void countsReferralsAndReadsLastMonthsDecisions() {
        when(underwriting.listCases(any(CaseFilter.class), any())).thenReturn(total(0));
        when(underwriting.listCases(argThat((CaseFilter f) -> f != null
            && f.referral() == ReferralStatus.REFERRED_TO_SENIOR), any())).thenReturn(total(3));
        when(underwriting.decisionsByMonth(any(), any())).thenReturn(List.of(
            new DecisionMonth(YearMonth.of(2026, 9), DecisionOutcome.ACCEPTED, 42, new BigDecimal("3.4")),
            new DecisionMonth(YearMonth.of(2026, 9), DecisionOutcome.DECLINED, 7, new BigDecimal("2.1"))));

        UnderwritingDashboardView view = dashboard.build();

        assertThat(view.casesReferred()).isEqualTo(3L);
        assertThat(view.decisions()).hasSize(2);
    }
```

Add the matching tests to `ClaimsDashboardTest` (settlements and open requests), `FinanceDashboardTest` (receivables), and to `AgentDashboardTest`:

```java
    /**
     * Never a gap. GET /underwriting/cases admits an agents token and the controller already scopes it to
     * partyIdsRegisteredBy; phase 1 dashed this card on a wrong reading of the endpoint.
     */
    @Test
    void countsTheAgentsOwnApplicationsAwaitingUnderwriting() {
        Set<UUID> mine = Set.of(UUID.randomUUID());
        when(parties.partyIdsRegisteredBy(SUBJECT)).thenReturn(mine);
        when(underwriting.listCases(argThat((CaseFilter f) -> f != null
            && f.status() == UnderwritingCaseStatus.OPEN && mine.equals(f.applicantPartyIds())), any()))
            .thenReturn(total(5));

        assertThat(dashboard.build(SUBJECT, PARTY, TODAY).applicationsInUnderwriting()).isEqualTo(5L);
    }
```

`AgentDashboard` gains an `UnderwritingApi` constructor parameter; update its other tests for the new argument.

- [ ] **Step 2: Run to verify they fail, implement, run again**

Run: `./mvnw -o -q test-compile` — Expected: FAIL on the new view components.

Implement each composer's read through `Safely.read`, exactly as phase 1 does, so one broken source still costs one card. The underwriting composer asks `decisionsByMonth` for the **last complete month only** for its card (`YearMonth.from(today).minusMonths(1)` on both sides) — phase 3 widens that range for the chart and nothing else changes.

Run: `./mvnw -o -q clean test-compile && ./mvnw -o test -Dtest='UnderwritingDashboardTest,ClaimsDashboardTest,FinanceDashboardTest,AgentDashboardTest,RecentActivityTest,ReportFiguresTest,DashboardContractTest,ModularityTests' -DfailIfNoSpecifiedTests=false`
Expected: every class `Failures: 0, Errors: 0`.

- [ ] **Step 3: Extend the contract and commit**

Add the new properties to the four view schemas in `openapi-dashboard.yaml`, each in `required`, with `DecisionMonth`, `SettlementMonth` and `ReceivablesAging` as new component schemas (`additionalProperties: false`, money as the common `Money`, months as the existing `Month` pattern).

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/dashboard backend/api/openapi/openapi-dashboard.yaml backend/src/test/java/tz/co/nlolo/lifeplatform/dashboard
git commit -m "feat(dashboard): the dashed cards read their real figures

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: The dashed cards light up

**Files:**
- Modify: `frontend/src/features/dashboard/UnderwritingDashboardPanel.tsx`, `ClaimsDashboardPanel.tsx`, `FinanceDashboardPanel.tsx`, `AgentDashboardPanel.tsx` and their four tests
- Create: `frontend/src/features/dashboard/ReceivablesAging.tsx`, `ReceivablesAging.test.tsx`

**Interfaces:**
- Consumes: the widened views, regenerated by `npm run generate:api`.
- Produces: `ReceivablesAging({ aging }: { aging: ReceivablesAging[] })` — the TZS entry as a labelled list of five buckets with counts and outstanding amounts, and a line naming any other currency it is **not** showing.

- [ ] **Step 1: Regenerate the types**

Run (from `frontend/`): `npm run generate:api`
Expected: `openapi-dashboard.yaml -> src/types/api/dashboard.ts`.

- [ ] **Step 2: Write the failing tests**

For each panel, the dashed card becomes a real one. In `UnderwritingDashboardPanel.test.tsx`:

```tsx
  /** Phase 2: the card that read "Not available" now reads a number, and links to the list that proves it. */
  it('shows last month’s decisions and links them', async () => {
    renderScreen(<UnderwritingDashboardPanel />);

    expect(await screen.findByRole('link', { name: /^42 Accepted, September/ }))
      .toHaveAttribute('href', '/staff/underwriting?outcome=ACCEPTED&decidedFrom=2026-09-01&decidedTo=2026-09-30');
    expect(screen.getByText('3.4 days')).toBeInTheDocument();
  });
```

Set the fixture's `decisions` and `casesReferred` accordingly, and freeze the clock the panel reads (the month names come from the view's own `YYYY-MM` values, not from `Date.now()` — if a panel needs today's date, pass it from the view rather than reading the clock in a component).

In `ReceivablesAging.test.tsx`:

```tsx
  it('shows every bucket, including the empty ones', () => {
    render(<ReceivablesAging aging={[{ currency: 'TZS', buckets: [
      { name: 'NOT_DUE', cases: 1, outstanding: { amount: '100000.00', currencyCode: 'TZS' } },
      { name: 'D1_30', cases: 0, outstanding: { amount: '0.00', currencyCode: 'TZS' } },
      { name: 'D31_60', cases: 1, outstanding: { amount: '250000.00', currencyCode: 'TZS' } },
      { name: 'D61_90', cases: 0, outstanding: { amount: '0.00', currencyCode: 'TZS' } },
      { name: 'OVER_90', cases: 1, outstanding: { amount: '400000.00', currencyCode: 'TZS' } }] }]} />);

    expect(screen.getByText('Not due')).toBeInTheDocument();
    expect(screen.getByText('61–90 days')).toBeInTheDocument();
    expect(screen.getByText('TZS 400,000.00')).toBeInTheDocument();
  });

  /** Another currency is named, never added in: the reader must know the total is not the whole book. */
  it('names a currency it is not showing', () => {
    render(<ReceivablesAging aging={[
      { currency: 'TZS', buckets: [] }, { currency: 'USD', buckets: [] }]} />);

    expect(screen.getByText(/also owed in USD/)).toBeInTheDocument();
  });
```

- [ ] **Step 3: Implement, run, commit**

The aging panel is a list, not a chart — five labelled rows with a count and an amount, right-aligned, no colour (an age is not a status). Phase 3 adds the bar.

Run: `npx vitest run src/features/dashboard src/test/designGuards.test.ts && npm run typecheck && npm run lint`
Expected: all passed, clean.

```bash
git add frontend/src/features/dashboard frontend/src/api/dashboard.ts
git commit -m "feat(dashboard): the dashed cards show their figures

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: The real stack, and the docs

**Files:**
- Modify: `DESIGN.md` (the Dashboard section's "Not here yet" list), the phase 1 plan's figures table (mark the eight as done)
- Create: `frontend/e2e/staff-dashboard-phase2.spec.ts`

- [ ] **Step 1: Restart the dev stack with the new column and code**

The migration from Task 3 must be on the dev database (applied in that task's Step 6) and the backend restarted, or every claims figure 500s on a missing column. Stop the `java` process listening on 8080 **and its Maven parent**, then `./mvnw -B -o spring-boot:run -Dspring-boot.run.profiles=local` from `backend/`, wait for "Started Application", and confirm:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/arrears/aging   # 401, not 404
```

- [ ] **Step 2: An e2e for one real new figure**

```ts
import { expect, test } from '@playwright/test';

/** Phase 2 against the real stack: a figure that was "Not available" is a number, and its card opens its own list. */
test('the underwriting dashboard shows last month’s decisions', async ({ page }) => {
  await page.goto('/staff/dashboard?dashboard=underwriting');
  const card = page.getByRole('link', { name: /Accepted, / });
  await expect(card).toBeVisible();
  await card.click();
  await expect(page).toHaveURL(/outcome=ACCEPTED/);
});
```

Run: `npx playwright test e2e/staff-dashboard-phase2.spec.ts`

- [ ] **Step 3: Update the docs and commit**

In `DESIGN.md`'s Dashboard section, remove from "Not here yet" everything this phase delivered, leaving only what is still genuinely absent (SLA deadlines, a risk-level field, assigning an assessor, a cash-flow statement, quotes, leads). In the phase 1 plan's "Phase 2 (drawn dashed…)" line, mark it done with this plan's name.

```bash
git add DESIGN.md backend/docs/superpowers/plans frontend/e2e
git commit -m "docs(dashboard): phase 2 figures are provable now

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

## Final whole-branch review

Read `git diff main...role-dashboards-phase-2` whole, not task by task:

- [ ] Every new filter is null-safe and every date filter excludes rows whose date is null — a decided-date range must never return an undecided case.
- [ ] No aggregate adds two currencies. `grep -n "SUM(" backend/src/main/java --include=*.java -r` — every one groups by currency.
- [ ] `settled_at` is set on **both** settle paths; a claim settled at zero is counted.
- [ ] The claims migration is in every test class that lists claims migrations, and on the dev database.
- [ ] Monthly series are absent-not-zero; aging buckets are present-even-when-empty. The two rules differ on purpose and both are tested.
- [ ] Civil months throughout: no `to_char` without `AT TIME ZONE 'Africa/Dar_es_Salaam'`.
- [ ] `ModularityTests` and `NoCrossModuleJoinTest` pass.
- [ ] Merge as the repository does: `git checkout main && git merge --no-ff role-dashboards-phase-2`, push `main`, push the branch as a backup.
