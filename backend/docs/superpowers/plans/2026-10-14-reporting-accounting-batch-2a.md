# Accounting Reports (Batch 2a) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an Accounting category to the reporting module with the four core financial statements — Trial Balance, General Ledger, Balance Sheet and Income Statement — read live from the general ledger.

**Architecture:** Management reports (batch 1) read a projection of domain events; accounting reports must not. A projected copy of the ledger is a second set of books that can silently disagree with the first, which is the one thing an auditor rejects. So `reporting` gains `finaccounting::api` as an allowed dependency and reads the ledger directly, while ledger aggregation itself stays inside `finaccounting` where the roll-up rules already live.

**Tech Stack:** Java 21, Spring Boot, Spring Modulith, JPA/Hibernate, PostgreSQL with RLS, JUnit 5 + Testcontainers; React 19 + TypeScript + Zustand + Vitest/RTL on the console side.

## Global Constraints

- Package root `tz.co.nlolo.lifeplatform`; module `reporting`, reading `finaccounting::api`.
- Module boundaries are enforced by Spring Modulith. A new cross-module dependency MUST be declared in `reporting/package-info.java` as `"finaccounting::api"` — the `::api` named-interface suffix is required or `ModularityTest` fails.
- **No client-side arithmetic on money.** Every total is computed server-side and shipped as a string. `TrialBalanceView` already states this rule and the reason.
- Money is `BigDecimal` server-side, rendered through `Figures.money(...)`, and a decimal string on the wire. Working currency TZS.
- **Balances roll up the hierarchy.** A parent account reports itself plus every descendant. Totals MUST sum `own` figures only — summing rolled figures counts every posting once per ancestor and reports a wild imbalance on a sound ledger.
- Ledger periods are `YYYY-MM` strings. There is no posting date on `gl_posting` beyond the journal's `postedAt`, so every accounting report filters by MONTH, never by day.
- `gl_posting.amount` is always a positive magnitude; the sign lives in `PostingDirection` (DR/CR). A reversal is a new entry with directions swapped, never a negative amount.
- Reports render only what the ledger can prove. No estimates, no placeholder zeros, no invented rows.
- Run backend tests per-class (`./mvnw test -Dtest=ClassName`), never the full suite — it takes ~80 minutes on this machine.
- Stop the dev backend before any `clean test` run, or tests fail with `NoClassDefFoundError`/byte-buddy errors that look like assertion failures.

## Design decisions settled before this plan

| # | Decision |
|---|---|
| D1 | Accounting reports read `finaccounting::api` live. No new projection tables, no new events. |
| D2 | Ledger aggregation stays in `finaccounting`. Two new API methods supply cumulative and range balances. |
| D3 | Accounting filters are months (`YYYY-MM`). New `DateFilterKind.MONTH` and `MONTH_RANGE`, new `ReportFilter` fields `monthFrom`/`monthTo`/`accountCode`. |
| D4 | Balance Sheet and Income Statement are **straight class roll-ups from the chart** (user's decision, 2026-10-14). No statutory layout is imposed. |
| D5 | Cash Flow Statement is **parked** pending an operating/investing/financing classification per account, which Finance must approve. |
| D6 | Bank Reconciliation is **parked**: no bank statement exists anywhere on the platform, so there is nothing to reconcile against. It is a feature (statement import, matching rules, unmatched-item workflow), not a report. |

**Out of scope for 2a, planned next:** batch 2b — Journal Entries and Approvals, Financial Period Closing, Ledger Audit Trail. Batch 2c — Commission Payable, Claims Expense and Outstanding Claims, Premium Collection and Reconciliation, Reinsurance Balances, Insurance Contract Liability, Liability Reconciliation.

> **A finding 2b must not lose.** The Audit Trail report cannot be built on `audit::api`. That module's own javadoc is explicit: `audit_log` is a domain-event journal with **no actor column**, and of the six things a compliance screen wants — actor, action, record, before, after, reason — five do not exist anywhere on this platform. Presenting that feed as compliance evidence would be a lie the console tells on the platform's behalf.
>
> What **is** provable is the ledger's own accountability record, and it is rich: a manual journal carries its preparer, approver, reason and rejection reason; an accounting period carries `closingStartedBy`, `lockedBy`, `reopenRequestedBy`, `reopenReason` and `reopenedBy`; a year-end close carries `preparedBy` and `decidedBy`; an engine run carries `uploadedBy`, `decidedBy` and the actuary's `signOffReference`. So 2b's report is **"who moved the ledger, and when"** — scoped to finance actions, named as such, and genuinely what an auditor opens. It is not a platform-wide who-did-what trail, and it must not be titled as though it were.

## File Structure

**Backend — `finaccounting` (ledger aggregation):**
- Modify `finaccounting/infrastructure/GlPostingRepository.java` — add two aggregation queries.
- Modify `finaccounting/api/FinaccountingApi.java` — add `trialBalanceThrough`, `trialBalanceBetween`.
- Modify `finaccounting/application/FinaccountingApiImpl.java` — extract the roll-up into one private helper the three public methods share.

**Backend — `reporting` (the reports):**
- Modify `reporting/package-info.java` — add `finaccounting::api`.
- Modify `reporting/api/ReportCategory.java` — add `ACCOUNTING`.
- Modify `reporting/api/DateFilterKind.java` — add `MONTH`, `MONTH_RANGE`.
- Modify `reporting/api/ReportFilter.java` — add `monthFrom`, `monthTo`, `accountCode`.
- Modify `reporting/application/ReportFilters.java` — validate the two new kinds and `accountCode`.
- Modify `reporting/application/FilterSummary.java` — describe the new fields.
- Modify `reporting/infrastructure/ReportFilterParams.java` — parse them.
- Modify `reporting/application/ReportingApiImpl.java` — extend `ORDER`.
- Create `reporting/application/LedgerAccounts.java` — shared chart helpers (class of an account, filtering a trial balance to classes, row mapping).
- Create `reporting/application/TrialBalanceReport.java`
- Create `reporting/application/GeneralLedgerReport.java`
- Create `reporting/application/BalanceSheetReport.java`
- Create `reporting/application/IncomeStatementReport.java`

**Frontend:**
- Modify `frontend/src/features/reporting/ReportsCataloguePage.tsx` — label the new category.
- Modify `frontend/src/features/reporting/ReportFilterBar.tsx` — month and account inputs.
- Modify `frontend/src/features/reporting/reportParams.ts` — carry the new params.

---

### Task 1: Ledger aggregation for a cumulative balance and a period range

The single gap blocking three of the four reports. `sumByAccountAndDirection` filters `p.period = :period` — an equality match on a `YYYY-MM` string — so the ledger can answer "September's movement" and "everything ever", and nothing in between. A Balance Sheet needs everything **through** a month; an Income Statement needs the movement **between** two.

String comparison is correct here and not a hack: `YYYY-MM` is zero-padded and fixed-width, so lexicographic order is chronological order.

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/infrastructure/GlPostingRepository.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/api/FinaccountingApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/application/FinaccountingApiImpl.java:257`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/TrialBalanceRangeIntegrationTest.java`

**Interfaces:**
- Consumes: `TrialBalanceView`, `AccountBalanceView`, `PostingDirection`, `ChartOfAccount` (all existing).
- Produces:
  - `TrialBalanceView FinaccountingApi.trialBalanceThrough(String period)` — inception through and including `period`.
  - `TrialBalanceView FinaccountingApi.trialBalanceBetween(String fromPeriod, String toPeriod)` — inclusive both ends.
  - Both echo the period they were asked for in `TrialBalanceView.period()`: `"through 2026-09"` and `"2026-04..2026-09"` respectively, so a balance on screen always says what it covers.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/TrialBalanceRangeIntegrationTest.java`. Follow the existing finaccounting integration tests for the base class and tenant setup — read `FinaccountingApiIntegrationTest` first and copy its annotations and `@BeforeEach` verbatim.

```java
@Test
void cumulativeBalanceIncludesEveryPeriodUpToAndIncludingTheOneAsked() {
    postJournal("2026-07", "1100", "4100", new BigDecimal("100.00"));
    postJournal("2026-08", "1100", "4100", new BigDecimal("30.00"));
    postJournal("2026-09", "1100", "4100", new BigDecimal("7.00"));

    TrialBalanceView through August = finaccountingApi.trialBalanceThrough("2026-08");

    assertEquals("through 2026-08", through.period());
    assertEquals(0, balanceOf(through, "1100").compareTo(new BigDecimal("130.00")));
    assertTrue(through.balanced());
}

@Test
void rangeBalanceExcludesPeriodsOutsideBothEnds() {
    postJournal("2026-07", "1100", "4100", new BigDecimal("100.00"));
    postJournal("2026-08", "1100", "4100", new BigDecimal("30.00"));
    postJournal("2026-09", "1100", "4100", new BigDecimal("7.00"));

    TrialBalanceView range = finaccountingApi.trialBalanceBetween("2026-08", "2026-09");

    assertEquals("2026-08..2026-09", range.period());
    assertEquals(0, balanceOf(range, "1100").compareTo(new BigDecimal("37.00")));
}

@Test
void aRangeEndingBeforeItStartsIsRefused() {
    assertThrows(FinaccountingValidationException.class,
        () -> finaccountingApi.trialBalanceBetween("2026-09", "2026-08"));
}

/** The totals sum OWN figures: a rolled-up total would count 1100 again under 1000 and under the total. */
@Test
void totalsStayBalancedAcrossARangeDespiteTheHierarchy() {
    postJournal("2026-08", "1100", "4100", new BigDecimal("30.00"));

    TrialBalanceView range = finaccountingApi.trialBalanceBetween("2026-01", "2026-12");

    assertEquals(0, range.totalDebit().compareTo(range.totalCredit()));
    assertTrue(range.balanced());
}

private BigDecimal balanceOf(TrialBalanceView view, String accountCode) {
    return view.accounts().stream()
        .filter(a -> a.accountCode().equals(accountCode)).findFirst().orElseThrow().balance();
}
```

Write `postJournal(period, debitAccount, creditAccount, amount)` as a private helper that posts one balanced two-leg entry through whatever posting path the existing integration test uses. Do not invent a new one.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=TrialBalanceRangeIntegrationTest`
Expected: FAIL — `trialBalanceThrough` and `trialBalanceBetween` do not exist (compilation error).

- [ ] **Step 3: Add the repository queries**

In `GlPostingRepository.java`, beside `sumByAccountAndDirection`:

```java
/**
 * Inception through and including {@code period}. `YYYY-MM` is zero-padded and fixed-width, so a
 * string comparison is a chronological one -- no date parsing, and the existing index on period still serves.
 */
@Query("""
    select p.accountCode, p.direction, sum(p.amount)
      from GlPosting p
     where p.tenantId = :tenantId
       and p.period <= :period
     group by p.accountCode, p.direction
    """)
List<Object[]> sumByAccountAndDirectionThrough(@Param("tenantId") UUID tenantId,
                                               @Param("period") String period);

/** Both ends inclusive. See {@link #sumByAccountAndDirectionThrough} on why string bounds are chronological. */
@Query("""
    select p.accountCode, p.direction, sum(p.amount)
      from GlPosting p
     where p.tenantId = :tenantId
       and p.period >= :fromPeriod
       and p.period <= :toPeriod
     group by p.accountCode, p.direction
    """)
List<Object[]> sumByAccountAndDirectionBetween(@Param("tenantId") UUID tenantId,
                                               @Param("fromPeriod") String fromPeriod,
                                               @Param("toPeriod") String toPeriod);
```

- [ ] **Step 4: Extract the roll-up and add the two API methods**

In `FinaccountingApiImpl.java`, the body of `trialBalance(String period)` from line 257 already does: aggregate → roll up the hierarchy → total the own figures. Extract everything after the aggregation into one private method so all three entry points share it — this is the code whose javadoc warns that summing rolled balances is the easiest way to get a trial balance wrong, and it must not be duplicated three times.

```java
@Override
public TrialBalanceView trialBalance(String period) {
    UUID tenantId = TenantContext.get();
    return assemble(tenantId, period, glPostingRepository.sumByAccountAndDirection(tenantId, period));
}

@Override
public TrialBalanceView trialBalanceThrough(String period) {
    UUID tenantId = TenantContext.get();
    return assemble(tenantId, "through " + period,
        glPostingRepository.sumByAccountAndDirectionThrough(tenantId, period));
}

@Override
public TrialBalanceView trialBalanceBetween(String fromPeriod, String toPeriod) {
    if (fromPeriod.compareTo(toPeriod) > 0) {
        throw new FinaccountingValidationException(
            "The first period must not be after the last; got " + fromPeriod + " to " + toPeriod);
    }
    UUID tenantId = TenantContext.get();
    return assemble(tenantId, fromPeriod + ".." + toPeriod,
        glPostingRepository.sumByAccountAndDirectionBetween(tenantId, fromPeriod, toPeriod));
}

/**
 * The chart, the rows rolled up through the hierarchy, and the totals.
 *
 * <p>{@code label} is echoed as {@link TrialBalanceView#period()} -- it is what the balance COVERS, not
 * necessarily one period, so "through 2026-09" and "2026-04..2026-09" are both valid values. A balance with
 * no period beside it is a number nobody can reconcile against anything.
 *
 * <p>The totals sum OWN figures. See {@link TrialBalanceView}'s javadoc: adding rolled balances counts every
 * posting once for its own account and again for every ancestor above it.
 */
private TrialBalanceView assemble(UUID tenantId, String label, List<Object[]> sums) {
    // ... the existing body of trialBalance from line 259 onward, with `period` replaced by `label`
    // in the returned view and `sums` used in place of the inline repository call.
}
```

Then declare both on the interface, in `FinaccountingApi.java` directly below `trialBalance`:

```java
/**
 * Every posting from inception through and including {@code period} -- the cumulative position a balance
 * sheet states. {@link #trialBalance} answers one period's MOVEMENT; this answers the balance that movement
 * left behind.
 *
 * @param period {@code YYYY-MM}, required. Echoed back as {@code "through YYYY-MM"}.
 */
TrialBalanceView trialBalanceThrough(String period);

/**
 * The movement across a range of periods, both ends inclusive -- what an income statement reports for a
 * quarter or a year to date.
 *
 * @throws FinaccountingValidationException when {@code fromPeriod} is after {@code toPeriod}
 */
TrialBalanceView trialBalanceBetween(String fromPeriod, String toPeriod);
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=TrialBalanceRangeIntegrationTest`
Expected: PASS, 4 tests.

- [ ] **Step 6: Re-run the existing finaccounting trial-balance tests**

Run: `cd backend && ./mvnw test -Dtest=FinaccountingApiIntegrationTest`
Expected: PASS. The extraction must not have changed `trialBalance(period)`'s behaviour.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/TrialBalanceRangeIntegrationTest.java
git commit -m "feat(finaccounting): the ledger can state a cumulative balance and a period's movement

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Open the reporting module to the ledger, and teach its filters about months

Three small contract changes that every later task depends on. Done as one task because none of them is independently useful and a reviewer would accept or reject them together.

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/package-info.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/api/ReportCategory.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/api/DateFilterKind.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/api/ReportFilter.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/application/ReportFilters.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/application/FilterSummary.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/infrastructure/ReportFilterParams.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/application/ReportFiltersTest.java` (create)

**Interfaces:**
- Produces:
  - `ReportCategory.ACCOUNTING`
  - `DateFilterKind.MONTH` (needs `monthFrom`), `DateFilterKind.MONTH_RANGE` (needs `monthFrom` and `monthTo`)
  - `ReportFilter` gains three trailing components: `YearMonth monthFrom, YearMonth monthTo, String accountCode`
  - `ReportFilter.month(YearMonth)`, `ReportFilter.months(YearMonth, YearMonth)`, `ReportFilter.withAccountCode(String)`
  - Query parameters `monthFrom`, `monthTo`, `accountCode`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/application/ReportFiltersTest.java`. `ReportFilters` is package-private, so the test must sit in `tz.co.nlolo.lifeplatform.reporting.application`. No Spring context — this is a pure unit test.

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportFiltersTest {

    private static ReportDefinitionView definition(DateFilterKind kind) {
        return new ReportDefinitionView("trial-balance", "Trial balance", "", ReportCategory.ACCOUNTING,
            kind, List.of(), false);
    }

    @Test
    void monthIsRequiredForAMonthReport() {
        ReportParameterException e = assertThrows(ReportParameterException.class,
            () -> ReportFilters.validate(definition(DateFilterKind.MONTH), ReportFilter.none()));
        assertTrue(e.getMessage().contains("monthFrom"));
    }

    @Test
    void aDayDateIsRefusedOnAMonthReport() {
        ReportFilter f = ReportFilter.month(YearMonth.of(2026, 9)).withAsOf(LocalDate.of(2026, 9, 30));
        assertThrows(ReportParameterException.class,
            () -> ReportFilters.validate(definition(DateFilterKind.MONTH), f));
    }

    @Test
    void oneMonthIsEnoughForAMonthReport() {
        assertDoesNotThrow(() -> ReportFilters.validate(definition(DateFilterKind.MONTH),
            ReportFilter.month(YearMonth.of(2026, 9))));
    }

    @Test
    void aRangeNeedsBothEndsInOrder() {
        assertThrows(ReportParameterException.class, () -> ReportFilters.validate(
            definition(DateFilterKind.MONTH_RANGE), ReportFilter.month(YearMonth.of(2026, 9))));
        assertThrows(ReportParameterException.class, () -> ReportFilters.validate(
            definition(DateFilterKind.MONTH_RANGE),
            ReportFilter.months(YearMonth.of(2026, 9), YearMonth.of(2026, 4))));
        assertDoesNotThrow(() -> ReportFilters.validate(definition(DateFilterKind.MONTH_RANGE),
            ReportFilter.months(YearMonth.of(2026, 4), YearMonth.of(2026, 9))));
    }

    @Test
    void anAccountCodeMustLookLikeTheChartsCodes() {
        ReportFilter bad = ReportFilter.month(YearMonth.of(2026, 9)).withAccountCode("11OO");
        assertThrows(ReportParameterException.class,
            () -> ReportFilters.validate(definition(DateFilterKind.MONTH), bad));
        assertDoesNotThrow(() -> ReportFilters.validate(definition(DateFilterKind.MONTH),
            ReportFilter.month(YearMonth.of(2026, 9)).withAccountCode("1100")));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ReportFiltersTest`
Expected: FAIL — `ReportCategory.ACCOUNTING`, `DateFilterKind.MONTH` and `ReportFilter.month` do not exist (compilation error).

- [ ] **Step 3: Make the contract changes**

`reporting/package-info.java`:

```java
/**
 * Operational and management reports (spec 2026-10-11-reporting-management-design.md), and accounting reports
 * (batch 2a, 2026-10-14).
 *
 * <p>Two different relationships with the truth, deliberately. MANAGEMENT reports read a projection built from
 * domain events and the audit journal -- rebuildable, and the record of nothing. ACCOUNTING reports read the
 * general ledger LIVE through {@code finaccounting::api}: a projected copy of the ledger would be a second set
 * of books that can disagree with the first, which is exactly what a trial balance exists to rule out.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "audit::api", "party::api", "product::api", "distribution::api", "finaccounting::api" })
package tz.co.nlolo.lifeplatform.reporting;
```

`ReportCategory.java`:

```java
package tz.co.nlolo.lifeplatform.reporting.api;

/** The department that reads a report. */
public enum ReportCategory { MANAGEMENT, ACCOUNTING }
```

`DateFilterKind.java` — add to the enum and extend the comment:

```java
/** AS_OF: a snapshot on one date. PERIOD: what happened between two dates. COHORT: policies activated in a
 * range of months, followed forward. AS_OF_AND_PERIOD: both a snapshot date and a period (clients).
 * MONTH / MONTH_RANGE: one accounting period, or a span of them. The ledger is organised by YYYY-MM and
 * carries no posting date of its own, so an accounting report that accepted a day would be promising a
 * precision the ledger cannot deliver. */
public enum DateFilterKind { AS_OF, PERIOD, COHORT, AS_OF_AND_PERIOD, MONTH, MONTH_RANGE }
```

`ReportFilter.java` — append three components and the factories. Every existing `with*` method must be updated to carry the three new values through; the record has no builder, so each one re-lists every component.

```java
public record ReportFilter(LocalDate asOf, LocalDate compareAsOf, LocalDate from, LocalDate to, YearMonth cohortFrom,
                           YearMonth cohortTo, UUID productId, String productCategory, UUID agentId, String salesChannel,
                           String branchCode, String clientType, GroupBy groupBy,
                           /** The accounting period a MONTH report runs for, or the first of a MONTH_RANGE. */
                           YearMonth monthFrom,
                           /** The last period of a MONTH_RANGE; null on a MONTH report. */
                           YearMonth monthTo,
                           /** Restricts a ledger report to one chart account, four digits. Null for every account. */
                           String accountCode) {

    public static ReportFilter none() {
        return new ReportFilter(null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null);
    }

    public static ReportFilter month(YearMonth month) {
        return none().withMonths(month, null);
    }

    public static ReportFilter months(YearMonth from, YearMonth to) {
        return none().withMonths(from, to);
    }

    public ReportFilter withMonths(YearMonth from, YearMonth to) {
        return new ReportFilter(asOf, compareAsOf, this.from, this.to, cohortFrom, cohortTo, productId,
            productCategory, agentId, salesChannel, branchCode, clientType, groupBy, from, to, accountCode);
    }

    public ReportFilter withAccountCode(String code) {
        return new ReportFilter(asOf, compareAsOf, from, to, cohortFrom, cohortTo, productId, productCategory,
            agentId, salesChannel, branchCode, clientType, groupBy, monthFrom, monthTo, code);
    }
}
```

> Watch the shadowing in `withMonths`: the record's own components are also called `from` and `to`. The parameters shadow them, so the existing date components must be written `this.from` and `this.to`, and the new month components take the parameters. Getting this wrong compiles and silently swaps a report's dates for its months.

`ReportFilters.java` — add two branches to the switch and one rule:

```java
private static final java.util.regex.Pattern ACCOUNT_CODE = java.util.regex.Pattern.compile("^[1-9]\\d{3}$");

// inside validate(...)'s switch:
case MONTH -> {
    require(f.monthFrom() != null, "monthFrom is required for " + key);
    refuse(f.monthTo() != null, "monthTo does not apply to " + key + "; it takes one month");
    refuse(f.asOf() != null || f.from() != null || f.to() != null,
        "asOf/from/to do not apply to " + key + "; it takes monthFrom");
    refuse(f.cohortFrom() != null || f.cohortTo() != null, "cohortFrom/cohortTo do not apply to " + key);
}
case MONTH_RANGE -> {
    require(f.monthFrom() != null && f.monthTo() != null, "monthFrom and monthTo are required for " + key);
    refuse(f.monthFrom().isAfter(f.monthTo()), "monthFrom must not be after monthTo");
    refuse(f.asOf() != null || f.from() != null || f.to() != null,
        "asOf/from/to do not apply to " + key + "; it takes monthFrom and monthTo");
    refuse(f.cohortFrom() != null || f.cohortTo() != null, "cohortFrom/cohortTo do not apply to " + key);
}

// after the switch, beside the other cross-cutting rules:
refuse(f.accountCode() != null && !ACCOUNT_CODE.matcher(f.accountCode()).matches(),
    "accountCode must be four digits starting 1-9, as the chart of accounts writes them");
```

Also add `refuse(f.monthFrom() != null || f.monthTo() != null, ...)` to each of the four existing branches, so a month handed to a management report is refused rather than ignored — the rule the file's own javadoc states.

`FilterSummary.java` — add before the `groupByOptions` line:

```java
if (f.monthFrom() != null && f.monthTo() != null) parts.add("Periods " + f.monthFrom() + " to " + f.monthTo());
else if (f.monthFrom() != null) parts.add("Period " + f.monthFrom());
if (f.accountCode() != null) parts.add("Account: " + f.accountCode());
```

`ReportFilterParams.java` — three more parsers, in component order at the end of the constructor call:

```java
value(q, "monthFrom", YearMonth::parse, "a month, YYYY-MM"),
value(q, "monthTo", YearMonth::parse, "a month, YYYY-MM"),
blankToNull(q.get("accountCode")));
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ReportFiltersTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Verify the module boundary still holds**

Run: `cd backend && ./mvnw test -Dtest=ModularityTest`
Expected: PASS. A failure here means `finaccounting::api` was written without the `::api` suffix, or the named interface is missing.

- [ ] **Step 6: Re-compile the whole test tree**

Run: `cd backend && ./mvnw clean test-compile`
Expected: BUILD SUCCESS. `ReportFilter` gained components, so every existing construction site must still compile — incremental compilation hides exactly this class of break, which is why `clean` is required here.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/reporting backend/src/test/java/tz/co/nlolo/lifeplatform/reporting
git commit -m "feat(reporting): an Accounting category, month filters and a ledger dependency

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Shared chart helpers

Four reports all need the same two things: which class an account belongs to, and a trial balance narrowed to some of those classes. Written once, here, so a change to the chart's shape lands in one file.

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/application/LedgerAccounts.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/application/LedgerAccountsTest.java`

**Interfaces:**
- Consumes: `AccountBalanceView`, `AccountType` from `finaccounting::api`.
- Produces:
  - `static String classOf(String accountCode)` — `"1100"` → `"1000"`.
  - `static List<AccountBalanceView> inClass(List<AccountBalanceView> accounts, String classRoot)`
  - `static boolean posted(AccountBalanceView a)` — true when the account has any own or rolled movement.
  - `static final Map<String, String> CLASS_NAMES` — the nine class roots to their names.
  - `static final List<String> BALANCE_SHEET_CLASSES` — `["1000", "2000", "3000"]`, in statement order.
  - `static final List<String> INCOME_STATEMENT_CLASSES` — `["4000", "5000", "6000", "7000", "8000"]`.
  - `static boolean isIncomeClass(String classRoot)` — true for 4000 and 7000.

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerAccountsTest {

    private static AccountBalanceView account(String code, AccountType type, String own, String rolled) {
        return new AccountBalanceView(code, "Account " + code, type, PostingDirection.DR, null, (short) 1, true,
            AccountStatus.ACTIVE, "TZS", new BigDecimal(own), BigDecimal.ZERO, new BigDecimal(rolled),
            BigDecimal.ZERO, new BigDecimal(rolled));
    }

    @Test
    void anAccountsClassIsItsFirstDigitAndThreeZeroes() {
        assertEquals("1000", LedgerAccounts.classOf("1100"));
        assertEquals("8000", LedgerAccounts.classOf("8490"));
        assertEquals("9000", LedgerAccounts.classOf("9000"));
    }

    @Test
    void filteringKeepsOnlyTheClassAskedFor() {
        List<AccountBalanceView> all = List.of(
            account("1100", AccountType.ASSET, "10", "10"),
            account("1900", AccountType.ASSET, "15", "15"),
            account("2100", AccountType.LIABILITY, "20", "20"));

        List<AccountBalanceView> kept = LedgerAccounts.inClass(all, "1000");

        assertEquals(List.of("1100", "1900"), kept.stream().map(AccountBalanceView::accountCode).toList());
    }

    /**
     * Which classes are income decides the sign of the result. 7000 is a RESULT class -- finance and
     * investment -- and reads as income, not as an expense; 6000 is the reinsurance result and reads as an
     * expense. Getting either backwards flips a profit into a loss of the same size.
     */
    @Test
    void theIncomeClassesAreTheRevenueAndTheFinanceResult() {
        assertTrue(LedgerAccounts.isIncomeClass("4000"));
        assertTrue(LedgerAccounts.isIncomeClass("7000"));
        assertFalse(LedgerAccounts.isIncomeClass("5000"));
        assertFalse(LedgerAccounts.isIncomeClass("6000"));
        assertFalse(LedgerAccounts.isIncomeClass("8000"));
    }

    /** An untouched account is not a zero worth printing: a hundred of them would bury the ten that moved. */
    @Test
    void anAccountWithNoMovementIsNotPosted() {
        assertFalse(LedgerAccounts.posted(account("1100", AccountType.ASSET, "0", "0")));
        assertTrue(LedgerAccounts.posted(account("1100", AccountType.ASSET, "0", "5")));
        assertTrue(LedgerAccounts.posted(account("1100", AccountType.ASSET, "5", "5")));
    }

    @Test
    void everyClassRootIsNamed() {
        assertEquals("Assets", LedgerAccounts.CLASS_NAMES.get("1000"));
        assertEquals(9, LedgerAccounts.CLASS_NAMES.size());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=LedgerAccountsTest`
Expected: FAIL — `LedgerAccounts` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The chart of accounts as the accounting reports read it. The nine classes are the posting guide's
 * (IFRS 17 Chart of Accounts and Posting Guide 2.5), seeded by ChartOfAccountBlueprint, and an account code is
 * always four digits whose first is its class.
 */
final class LedgerAccounts {

    private LedgerAccounts() {}

    /** The nine class roots, in the order a statement reads them. */
    static final Map<String, String> CLASS_NAMES = Map.ofEntries(
        Map.entry("1000", "Assets"),
        Map.entry("2000", "Liabilities"),
        Map.entry("3000", "Equity"),
        Map.entry("4000", "Insurance revenue (IFRS 17)"),
        Map.entry("5000", "Insurance service expenses (IFRS 17)"),
        Map.entry("6000", "Net result from reinsurance contracts held"),
        Map.entry("7000", "Finance and investment result"),
        Map.entry("8000", "Other operating expenses and tax"),
        Map.entry("9000", "Clearing and suspense"));

    /** Balance-sheet classes: what the entity owns, owes and is worth. A List, not a Set: a statement has an order. */
    static final List<String> BALANCE_SHEET_CLASSES = List.of("1000", "2000", "3000");

    /** Profit-and-loss classes, in the order a statement reads them. */
    static final List<String> INCOME_STATEMENT_CLASSES = List.of("4000", "5000", "6000", "7000", "8000");

    /** The result classes that ADD to the result rather than subtract from it. */
    private static final Set<String> INCOME_CLASSES = Set.of("4000", "7000");

    static String classOf(String accountCode) {
        return accountCode.charAt(0) + "000";
    }

    static List<AccountBalanceView> inClass(List<AccountBalanceView> accounts, String classRoot) {
        return accounts.stream().filter(a -> classRoot.equals(classOf(a.accountCode()))).toList();
    }

    /**
     * Whether a class adds to the result or subtracts from it. Decided by the CLASS, never by sampling an
     * account's {@code AccountType}: a class can be empty in a period, and a contra account inside it keeps its
     * parent's type with the opposite normal balance, so the first account in a list is not a safe witness for
     * the class it sits in.
     */
    static boolean isIncomeClass(String classRoot) {
        return INCOME_CLASSES.contains(classRoot);
    }

    /**
     * Whether anything reached this account. An account the ledger never touched is not a zero worth printing:
     * a 246-account chart would bury the dozen that moved under two hundred that did not.
     */
    static boolean posted(AccountBalanceView a) {
        return signum(a.ownDebit()) != 0 || signum(a.ownCredit()) != 0
            || signum(a.debit()) != 0 || signum(a.credit()) != 0;
    }

    private static int signum(BigDecimal value) {
        return value == null ? 0 : value.signum();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=LedgerAccountsTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/application/LedgerAccounts.java backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/application/LedgerAccountsTest.java
git commit -m "feat(reporting): chart-of-accounts helpers the accounting reports share

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Trial Balance report

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/application/TrialBalanceReport.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/application/ReportingApiImpl.java:26` (the `ORDER` list)
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/application/TrialBalanceReportTest.java`

**Interfaces:**
- Consumes: `FinaccountingApi.trialBalanceThrough`, `LedgerAccounts.posted`, `Sections.col`, `Figures.money`.
- Produces: report key `"trial-balance"`, `DateFilterKind.MONTH`, no group-by, not comparable.

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.TrialBalanceView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrialBalanceReportTest {

    private final FinaccountingApi finaccounting = Mockito.mock(FinaccountingApi.class);
    private final TrialBalanceReport report = new TrialBalanceReport(finaccounting);
    private final Labels labels = Mockito.mock(Labels.class);

    private static AccountBalanceView account(String code, String name, AccountType type, String dr, String cr) {
        return new AccountBalanceView(code, name, type, PostingDirection.DR, null, (short) 1, true,
            AccountStatus.ACTIVE, "TZS", new BigDecimal(dr), new BigDecimal(cr), new BigDecimal(dr),
            new BigDecimal(cr), new BigDecimal(dr).subtract(new BigDecimal(cr)));
    }

    @Test
    void listsEveryPostedAccountAndStatesWhetherTheLedgerBalances() {
        Mockito.when(finaccounting.trialBalanceThrough("2026-09")).thenReturn(new TrialBalanceView(
            "through 2026-09",
            List.of(account("1100", "Premium receivables", AccountType.ASSET, "130.00", "0.00"),
                    account("4100", "Insurance revenue", AccountType.INCOME, "0.00", "130.00"),
                    account("1200", "Never used", AccountType.ASSET, "0.00", "0.00")),
            new BigDecimal("130.00"), new BigDecimal("130.00"), true));

        List<ReportSection> sections = report.run(UUID.randomUUID(),
            ReportFilter.month(YearMonth.of(2026, 9)), labels);

        ReportSection balances = sections.get(0);
        assertEquals(2, balances.rows().size(), "an account with no movement is not a row");
        assertEquals("1100", balances.rows().get(0).get("accountCode"));
        assertEquals("130.00", balances.totals().get("debit"));
        assertEquals("130.00", balances.totals().get("credit"));
        assertTrue(sections.get(1).rows().get(0).get("status").contains("balances"));
    }

    @Test
    void saysSoLoudlyWhenTheLedgerDoesNotBalance() {
        Mockito.when(finaccounting.trialBalanceThrough("2026-09")).thenReturn(new TrialBalanceView(
            "through 2026-09",
            List.of(account("1100", "Premium receivables", AccountType.ASSET, "130.00", "0.00")),
            new BigDecimal("130.00"), new BigDecimal("129.00"), false));

        List<ReportSection> sections = report.run(UUID.randomUUID(),
            ReportFilter.month(YearMonth.of(2026, 9)), labels);

        assertTrue(sections.get(1).rows().get(0).get("status").contains("does NOT balance"));
        assertEquals("1.00", sections.get(1).rows().get(0).get("difference"));
    }

    @Test
    void readsCumulativelyThroughTheMonth() {
        Mockito.when(finaccounting.trialBalanceThrough("2026-09")).thenReturn(
            new TrialBalanceView("through 2026-09", List.of(), BigDecimal.ZERO, BigDecimal.ZERO, true));

        report.run(UUID.randomUUID(), ReportFilter.month(YearMonth.of(2026, 9)), labels);

        // A trial balance is the position AT a date, not one month's movement in isolation.
        Mockito.verify(finaccounting).trialBalanceThrough("2026-09");
        Mockito.verify(finaccounting, Mockito.never()).trialBalance(Mockito.anyString());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=TrialBalanceReportTest`
Expected: FAIL — `TrialBalanceReport` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.TrialBalanceView;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/**
 * Every account's position through the chosen period, and the one assertion a ledger must be able to make.
 *
 * <p>Cumulative, not one month's movement: a trial balance states where the books stand at a date. The figures
 * are the OWN ones, not the rolled ones -- a trial balance lists the accounts that were posted to, and summing
 * rolled balances would count every posting again for each ancestor above it.
 *
 * <p>Whether the ledger balances is the ledger's own answer ({@code TrialBalanceView.balanced}), never a
 * comparison of two decimal strings made here.
 */
@Component
public class TrialBalanceReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("trial-balance", "Trial balance",
        "Every posted account's debits, credits and balance through the chosen period, and whether the ledger balances.",
        ReportCategory.ACCOUNTING, DateFilterKind.MONTH, List.of(), false);

    private final FinaccountingApi finaccounting;

    public TrialBalanceReport(FinaccountingApi finaccounting) {
        this.finaccounting = finaccounting;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        TrialBalanceView ledger = finaccounting.trialBalanceThrough(f.monthFrom().toString());

        List<ReportColumn> columns = List.of(col("accountCode", "Account", TEXT), col("name", "Name", TEXT),
            col("accountType", "Type", TEXT), col("currency", "Currency", TEXT),
            col("debit", "Debit", MONEY), col("credit", "Credit", MONEY), col("balance", "Balance", MONEY));

        List<Map<String, String>> rows = ledger.accounts().stream().filter(LedgerAccounts::posted).map(a -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("accountCode", a.accountCode());
            row.put("name", a.name());
            row.put("accountType", a.accountType().name());
            row.put("currency", a.currency());
            row.put("debit", Figures.money(a.ownDebit()));
            row.put("credit", Figures.money(a.ownCredit()));
            row.put("balance", Figures.money(a.balance()));
            return row;
        }).toList();

        // The ledger's totals, not a sum of the rows: the ledger has already excluded the double counting.
        Map<String, String> totals = new LinkedHashMap<>();
        totals.put("accountCode", "Total");
        totals.put("name", "");
        totals.put("accountType", "");
        totals.put("currency", rows.isEmpty() ? "" : rows.get(0).get("currency"));
        totals.put("debit", Figures.money(ledger.totalDebit()));
        totals.put("credit", Figures.money(ledger.totalCredit()));
        totals.put("balance", "");

        List<ReportColumn> check = List.of(col("status", "Check", TEXT), col("difference", "Difference", MONEY));
        BigDecimal difference = ledger.totalDebit().subtract(ledger.totalCredit()).abs();
        Map<String, String> checkRow = new LinkedHashMap<>();
        checkRow.put("status", ledger.balanced()
            ? "Debits equal credits: the ledger balances"
            : "Debits do NOT balance credits — investigate before closing the period");
        checkRow.put("difference", Figures.money(difference));

        return List.of(
            new ReportSection("Balances through " + f.monthFrom(), columns, rows, totals),
            new ReportSection("Ledger check", check, List.of(checkRow), Map.of()));
    }
}
```

- [ ] **Step 4: Register it in the catalogue order**

In `ReportingApiImpl.java`, extend `ORDER` so Accounting follows Management in the order a finance team reads it:

```java
/** Catalogue order: the order a manager reads the business in, then the order a ledger is read in. */
static final List<String> ORDER = List.of("policies-in-force", "products", "new-business", "lapse-rate",
    "persistency", "premium-collections", "clients",
    "trial-balance", "general-ledger", "balance-sheet", "income-statement");
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=TrialBalanceReportTest`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/reporting backend/src/test/java/tz/co/nlolo/lifeplatform/reporting
git commit -m "feat(reporting): a trial balance read live from the ledger

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: General Ledger report

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/application/GeneralLedgerReport.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/application/GeneralLedgerReportTest.java`

**Interfaces:**
- Consumes: `FinaccountingApi.listJournalEntries(String period, String policyNumber, String accountCode, Pageable)`, `FinaccountingApi.trialBalanceThrough`.
- Produces: report key `"general-ledger"`, `DateFilterKind.MONTH_RANGE`, requires `accountCode`.

An opening balance is the cumulative position through the month **before** the range starts. That is why Task 1 exists.

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.GlPostingView;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalEntryView;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalSource;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.TrialBalanceView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeneralLedgerReportTest {

    private final FinaccountingApi finaccounting = Mockito.mock(FinaccountingApi.class);
    private final GeneralLedgerReport report = new GeneralLedgerReport(finaccounting);
    private final Labels labels = Mockito.mock(Labels.class);

    private static JournalEntryView entry(String period, String account, PostingDirection direction, String amount) {
        UUID entryId = UUID.randomUUID();
        GlPostingView posting = new GlPostingView(UUID.randomUUID(), entryId, account, direction,
            new BigDecimal(amount), "TZS", period, "POL-1", "billing.PremiumCollected", "ref-1", null);
        return new JournalEntryView(entryId, "billing.PremiumCollected", "ref-1", period, "POL-1",
            Instant.parse(period + "-15T00:00:00Z"), List.of(posting), JournalSource.EVENT, 1, "v1");
    }

    private void openingBalanceOf(String period, String amount) {
        Mockito.when(finaccounting.trialBalanceThrough(period)).thenReturn(new TrialBalanceView("through " + period,
            List.of(new AccountBalanceView("1100", "Premium receivables", AccountType.ASSET, PostingDirection.DR,
                null, (short) 1, true, AccountStatus.ACTIVE, "TZS", new BigDecimal(amount), BigDecimal.ZERO,
                new BigDecimal(amount), BigDecimal.ZERO, new BigDecimal(amount))),
            new BigDecimal(amount), BigDecimal.ZERO, true));
    }

    @Test
    void opensWithThePositionBeforeTheRangeAndRunsABalanceForward() {
        openingBalanceOf("2026-07", "100.00");
        Mockito.when(finaccounting.listJournalEntries(Mockito.isNull(), Mockito.isNull(), Mockito.eq("1100"),
                Mockito.any(Pageable.class)))
            .thenReturn(page(List.of(entry("2026-08", "1100", PostingDirection.DR, "30.00"),
                                     entry("2026-09", "1100", PostingDirection.CR, "7.00"))));

        List<ReportSection> sections = report.run(UUID.randomUUID(),
            ReportFilter.months(YearMonth.of(2026, 8), YearMonth.of(2026, 9)).withAccountCode("1100"), labels);

        List<java.util.Map<String, String>> rows = sections.get(0).rows();
        assertEquals("Opening balance", rows.get(0).get("description"));
        assertEquals("100.00", rows.get(0).get("balance"));
        assertEquals("130.00", rows.get(1).get("balance"));
        assertEquals("123.00", rows.get(2).get("balance"));
        assertEquals("123.00", sections.get(0).totals().get("balance"));
    }

    @Test
    void leavesOutEntriesOutsideTheRange() {
        openingBalanceOf("2026-07", "0.00");
        Mockito.when(finaccounting.listJournalEntries(Mockito.isNull(), Mockito.isNull(), Mockito.eq("1100"),
                Mockito.any(Pageable.class)))
            .thenReturn(page(List.of(entry("2026-06", "1100", PostingDirection.DR, "5.00"),
                                     entry("2026-08", "1100", PostingDirection.DR, "30.00"),
                                     entry("2026-11", "1100", PostingDirection.DR, "9.00"))));

        List<ReportSection> sections = report.run(UUID.randomUUID(),
            ReportFilter.months(YearMonth.of(2026, 8), YearMonth.of(2026, 9)).withAccountCode("1100"), labels);

        assertEquals(2, sections.get(0).rows().size(), "opening balance plus the one entry inside the range");
    }

    @Test
    void refusesToRunWithoutAnAccount() {
        ReportParameterException e = assertThrows(ReportParameterException.class, () -> report.run(UUID.randomUUID(),
            ReportFilter.months(YearMonth.of(2026, 8), YearMonth.of(2026, 9)), labels));
        assertEquals(true, e.getMessage().contains("accountCode"));
    }

    private static Page<JournalEntryView> page(List<JournalEntryView> entries) {
        return new PageImpl<>(entries, Pageable.ofSize(500), entries.size());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=GeneralLedgerReportTest`
Expected: FAIL — `GeneralLedgerReport` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.GlPostingView;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalEntryView;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/**
 * One account's movements across a span of periods, opening and closing balances included.
 *
 * <p>The opening balance is the cumulative position through the month BEFORE the range — the figure that makes
 * the running balance mean anything. Without it a general ledger is a list of postings, not a ledger.
 *
 * <p>The running balance moves in the ACCOUNT's normal direction, so a positive figure always means "normal"
 * on both a receivable and a payable.
 */
@Component
public class GeneralLedgerReport implements Report {

    /** One page large enough for a month's postings on one account; the ledger is append-only and unbounded. */
    private static final int PAGE_SIZE = 2000;

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("general-ledger", "General ledger",
        "Every posting to one account across a span of periods, with its opening and closing balance.",
        ReportCategory.ACCOUNTING, DateFilterKind.MONTH_RANGE, List.of(), false);

    private final FinaccountingApi finaccounting;

    public GeneralLedgerReport(FinaccountingApi finaccounting) {
        this.finaccounting = finaccounting;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        if (f.accountCode() == null) {
            throw new ReportParameterException("accountCode is required for general-ledger; choose one account");
        }
        String account = f.accountCode();
        String openingThrough = f.monthFrom().minusMonths(1).toString();

        BigDecimal balance = finaccounting.trialBalanceThrough(openingThrough).accounts().stream()
            .filter(a -> a.accountCode().equals(account))
            .map(tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView::balance)
            .findFirst().orElse(BigDecimal.ZERO);

        List<ReportColumn> columns = List.of(col("period", "Period", TEXT), col("entry", "Entry", TEXT),
            col("description", "Description", TEXT), col("policyNumber", "Policy", TEXT),
            col("currency", "Currency", TEXT), col("debit", "Debit", MONEY), col("credit", "Credit", MONEY),
            col("balance", "Balance", MONEY));

        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(openingRow(openingThrough, balance));

        Page<JournalEntryView> entries = finaccounting.listJournalEntries(null, null, account,
            PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "postedAt")));

        String from = f.monthFrom().toString();
        String to = f.monthTo().toString();
        List<JournalEntryView> inRange = entries.getContent().stream()
            .filter(e -> e.period() != null && e.period().compareTo(from) >= 0 && e.period().compareTo(to) <= 0)
            .sorted(Comparator.comparing(JournalEntryView::period).thenComparing(JournalEntryView::postedAt))
            .toList();

        for (JournalEntryView entry : inRange) {
            for (GlPostingView posting : entry.postings()) {
                if (!posting.accountCode().equals(account)) {
                    continue;
                }
                balance = posting.direction() == PostingDirection.DR
                    ? balance.add(posting.amount()) : balance.subtract(posting.amount());
                Map<String, String> row = new LinkedHashMap<>();
                row.put("period", entry.period());
                row.put("entry", entry.journalEntryId().toString());
                row.put("description", entry.sourceEvent());
                row.put("policyNumber", entry.policyNumber() == null ? "" : entry.policyNumber());
                row.put("currency", posting.currency());
                row.put("debit", posting.direction() == PostingDirection.DR ? Figures.money(posting.amount()) : "0.00");
                row.put("credit", posting.direction() == PostingDirection.CR ? Figures.money(posting.amount()) : "0.00");
                row.put("balance", Figures.money(balance));
                rows.add(row);
            }
        }

        Map<String, String> totals = new LinkedHashMap<>();
        totals.put("period", "Closing balance");
        totals.put("entry", "");
        totals.put("description", "");
        totals.put("policyNumber", "");
        totals.put("currency", rows.size() > 1 ? rows.get(1).get("currency") : "");
        totals.put("debit", "");
        totals.put("credit", "");
        totals.put("balance", Figures.money(balance));

        return List.of(new ReportSection("Account " + account + ", " + from + " to " + to, columns, rows, totals));
    }

    private static Map<String, String> openingRow(String through, BigDecimal balance) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("period", through);
        row.put("entry", "");
        row.put("description", "Opening balance");
        row.put("policyNumber", "");
        row.put("currency", "");
        row.put("debit", "");
        row.put("credit", "");
        row.put("balance", Figures.money(balance));
        return row;
    }
}
```

> The balance arithmetic uses `PostingDirection` and the account's own balance from the ledger. Do **not** net debits and credits by subtracting a rendered string — `gl_posting.amount` is always a positive magnitude and the sign lives in the direction.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=GeneralLedgerReportTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/application/GeneralLedgerReport.java backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/application/GeneralLedgerReportTest.java
git commit -m "feat(reporting): a general ledger with opening and closing balances

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: Balance Sheet report

Straight class roll-ups from the chart (decision D4): classes 1000, 2000 and 3000, each as its own section, with the accounting equation stated at the end.

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/application/BalanceSheetReport.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/application/BalanceSheetReportTest.java`

**Interfaces:**
- Consumes: `FinaccountingApi.trialBalanceThrough`, `LedgerAccounts.BALANCE_SHEET_CLASSES`, `LedgerAccounts.inClasses`, `LedgerAccounts.posted`, `LedgerAccounts.CLASS_NAMES`.
- Produces: report key `"balance-sheet"`, `DateFilterKind.MONTH`, `comparable = true` — a second period to compare against is the first thing anybody asks a balance sheet for.

> `comparable` on `ReportDefinitionView` is wired to `compareAsOf`, a `LocalDate`. This report compares months, so it reads `compareAsOf` and takes the month that date falls in. Record that in the javadoc; do not widen `ReportFilter` again for it.

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.TrialBalanceView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BalanceSheetReportTest {

    private final FinaccountingApi finaccounting = Mockito.mock(FinaccountingApi.class);
    private final BalanceSheetReport report = new BalanceSheetReport(finaccounting);
    private final Labels labels = Mockito.mock(Labels.class);

    private static AccountBalanceView account(String code, AccountType type, PostingDirection normal, String balance) {
        return new AccountBalanceView(code, "Account " + code, type, normal, null, (short) 1, true,
            AccountStatus.ACTIVE, "TZS", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal(balance));
    }

    private void ledgerThrough(String period, List<AccountBalanceView> accounts) {
        Mockito.when(finaccounting.trialBalanceThrough(period)).thenReturn(
            new TrialBalanceView("through " + period, accounts, BigDecimal.ZERO, BigDecimal.ZERO, true));
    }

    @Test
    void sectionsTheChartIntoAssetsLiabilitiesAndEquityAndStatesTheEquation() {
        ledgerThrough("2026-09", List.of(
            account("1100", AccountType.ASSET, PostingDirection.DR, "500.00"),
            account("2100", AccountType.LIABILITY, PostingDirection.CR, "300.00"),
            account("3100", AccountType.EQUITY, PostingDirection.CR, "200.00"),
            account("4100", AccountType.INCOME, PostingDirection.CR, "900.00")));

        List<ReportSection> sections = report.run(UUID.randomUUID(),
            ReportFilter.month(YearMonth.of(2026, 9)), labels);

        assertEquals("Assets", sections.get(0).title());
        assertEquals("500.00", sections.get(0).totals().get("balance"));
        assertEquals("Liabilities", sections.get(1).title());
        assertEquals("Equity", sections.get(2).title());
        assertTrue(sections.get(3).rows().get(0).get("check").contains("balances"),
            "500 assets = 300 liabilities + 200 equity");
    }

    @Test
    void anIncomeAccountNeverAppearsOnABalanceSheet() {
        ledgerThrough("2026-09", List.of(
            account("1100", AccountType.ASSET, PostingDirection.DR, "500.00"),
            account("4100", AccountType.INCOME, PostingDirection.CR, "900.00")));

        List<ReportSection> sections = report.run(UUID.randomUUID(),
            ReportFilter.month(YearMonth.of(2026, 9)), labels);

        assertTrue(sections.stream().flatMap(s -> s.rows().stream())
            .noneMatch(r -> "4100".equals(r.get("accountCode"))));
    }

    @Test
    void saysSoWhenTheSheetDoesNotBalance() {
        ledgerThrough("2026-09", List.of(
            account("1100", AccountType.ASSET, PostingDirection.DR, "500.00"),
            account("2100", AccountType.LIABILITY, PostingDirection.CR, "300.00")));

        List<ReportSection> sections = report.run(UUID.randomUUID(),
            ReportFilter.month(YearMonth.of(2026, 9)), labels);

        assertTrue(sections.get(3).rows().get(0).get("check").contains("does NOT balance"));
        assertEquals("200.00", sections.get(3).rows().get(0).get("difference"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=BalanceSheetReportTest`
Expected: FAIL — `BalanceSheetReport` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.TrialBalanceView;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/**
 * What the entity owns, owes and is worth, through the chosen period.
 *
 * <p>A straight roll-up of the chart's own classes -- 1000 Assets, 2000 Liabilities, 3000 Equity -- not a
 * statutory presentation (decision D4, 2026-10-14). No account is reclassified, netted or renamed on its way
 * here: what the ledger calls a thing is what this report calls it.
 *
 * <p>Balances are the ledger's, signed in each account's own normal direction, so a positive figure means
 * "normal" on an asset and on a liability alike.
 *
 * <p>Comparison takes the MONTH that {@code compareAsOf} falls in. The filter's comparison field is a date
 * because every other comparable report compares dates; the ledger has only months, so the day is dropped
 * rather than a fourth month field added to a filter every report shares.
 */
@Component
public class BalanceSheetReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("balance-sheet", "Balance sheet",
        "Assets, liabilities and equity through the chosen period, rolled up from the chart of accounts.",
        ReportCategory.ACCOUNTING, DateFilterKind.MONTH, List.of(), true);

    private final FinaccountingApi finaccounting;

    public BalanceSheetReport(FinaccountingApi finaccounting) {
        this.finaccounting = finaccounting;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        TrialBalanceView ledger = finaccounting.trialBalanceThrough(f.monthFrom().toString());
        TrialBalanceView comparison = f.compareAsOf() == null ? null
            : finaccounting.trialBalanceThrough(YearMonth.from(f.compareAsOf()).toString());

        List<ReportSection> sections = new ArrayList<>();
        Map<String, BigDecimal> classTotals = new LinkedHashMap<>();

        for (String classRoot : LedgerAccounts.BALANCE_SHEET_CLASSES) {
            List<AccountBalanceView> accounts = LedgerAccounts.inClass(ledger.accounts(), classRoot)
                .stream().filter(LedgerAccounts::posted).toList();
            BigDecimal total = accounts.stream().map(AccountBalanceView::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            classTotals.put(classRoot, total);
            sections.add(section(LedgerAccounts.CLASS_NAMES.get(classRoot), accounts, total, comparison));
        }

        BigDecimal assets = classTotals.get("1000");
        BigDecimal liabilitiesAndEquity = classTotals.get("2000").add(classTotals.get("3000"));
        BigDecimal difference = assets.subtract(liabilitiesAndEquity).abs();
        boolean balances = difference.signum() == 0;

        Map<String, String> check = new LinkedHashMap<>();
        check.put("check", balances
            ? "Assets equal liabilities plus equity: the sheet balances"
            : "Assets do NOT balance liabilities plus equity — the ledger is out, or a period is unposted");
        check.put("difference", Figures.money(difference));
        sections.add(new ReportSection("Accounting equation",
            List.of(col("check", "Check", TEXT), col("difference", "Difference", MONEY)),
            List.of(check), Map.of()));

        return sections;
    }

    private static ReportSection section(String title, List<AccountBalanceView> accounts, BigDecimal total,
                                         TrialBalanceView comparison) {
        List<ReportColumn> columns = comparison == null
            ? List.of(col("accountCode", "Account", TEXT), col("name", "Name", TEXT),
                col("currency", "Currency", TEXT), col("balance", "Balance", MONEY))
            : List.of(col("accountCode", "Account", TEXT), col("name", "Name", TEXT),
                col("currency", "Currency", TEXT), col("balance", "Balance", MONEY),
                col("comparison", "Comparison", MONEY), col("movement", "Movement", MONEY));

        List<Map<String, String>> rows = accounts.stream().map(a -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("accountCode", a.accountCode());
            row.put("name", a.name());
            row.put("currency", a.currency());
            row.put("balance", Figures.money(a.balance()));
            if (comparison != null) {
                BigDecimal was = balanceOf(comparison, a.accountCode());
                row.put("comparison", Figures.money(was));
                row.put("movement", Figures.money(a.balance().subtract(was)));
            }
            return row;
        }).toList();

        Map<String, String> totals = new LinkedHashMap<>();
        totals.put("accountCode", "Total");
        totals.put("name", "");
        totals.put("currency", rows.isEmpty() ? "" : rows.get(0).get("currency"));
        totals.put("balance", Figures.money(total));
        if (comparison != null) {
            BigDecimal was = accounts.stream().map(a -> balanceOf(comparison, a.accountCode()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            totals.put("comparison", Figures.money(was));
            totals.put("movement", Figures.money(total.subtract(was)));
        }
        return new ReportSection(title, columns, rows, totals);
    }

    private static BigDecimal balanceOf(TrialBalanceView view, String accountCode) {
        return view.accounts().stream().filter(a -> a.accountCode().equals(accountCode))
            .map(AccountBalanceView::balance).findFirst().orElse(BigDecimal.ZERO);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=BalanceSheetReportTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/application/BalanceSheetReport.java backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/application/BalanceSheetReportTest.java
git commit -m "feat(reporting): a balance sheet rolled up from the chart's classes

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: Income Statement report

Classes 4000–8000 across a range of periods, each class a section, with the result stated at the end.

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/application/IncomeStatementReport.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/application/IncomeStatementReportTest.java`

**Interfaces:**
- Consumes: `FinaccountingApi.trialBalanceBetween`, `LedgerAccounts.INCOME_STATEMENT_CLASSES`.
- Produces: report key `"income-statement"`, `DateFilterKind.MONTH_RANGE`, not comparable.

Income and expense are **movement**, never a cumulative position — which is why this one calls `trialBalanceBetween` and the balance sheet calls `trialBalanceThrough`. Getting those two the wrong way round produces a plausible-looking statement that is completely wrong, so the test asserts which is called.

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.TrialBalanceView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncomeStatementReportTest {

    private final FinaccountingApi finaccounting = Mockito.mock(FinaccountingApi.class);
    private final IncomeStatementReport report = new IncomeStatementReport(finaccounting);
    private final Labels labels = Mockito.mock(Labels.class);

    private static AccountBalanceView account(String code, AccountType type, PostingDirection normal, String balance) {
        return new AccountBalanceView(code, "Account " + code, type, normal, null, (short) 1, true,
            AccountStatus.ACTIVE, "TZS", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal(balance));
    }

    @Test
    void readsTheMovementAcrossTheRangeNotACumulativePosition() {
        Mockito.when(finaccounting.trialBalanceBetween("2026-01", "2026-09")).thenReturn(new TrialBalanceView(
            "2026-01..2026-09", List.of(account("4100", AccountType.INCOME, PostingDirection.CR, "900.00")),
            BigDecimal.ZERO, BigDecimal.ZERO, true));

        report.run(UUID.randomUUID(),
            ReportFilter.months(YearMonth.of(2026, 1), YearMonth.of(2026, 9)), labels);

        Mockito.verify(finaccounting).trialBalanceBetween("2026-01", "2026-09");
        Mockito.verify(finaccounting, Mockito.never()).trialBalanceThrough(Mockito.anyString());
    }

    @Test
    void statesTheResultAsIncomeLessExpenses() {
        Mockito.when(finaccounting.trialBalanceBetween("2026-01", "2026-09")).thenReturn(new TrialBalanceView(
            "2026-01..2026-09",
            List.of(account("4100", AccountType.INCOME, PostingDirection.CR, "900.00"),
                    account("5100", AccountType.EXPENSE, PostingDirection.DR, "400.00"),
                    account("8100", AccountType.EXPENSE, PostingDirection.DR, "100.00"),
                    account("1100", AccountType.ASSET, PostingDirection.DR, "500.00")),
            BigDecimal.ZERO, BigDecimal.ZERO, true));

        List<ReportSection> sections = report.run(UUID.randomUUID(),
            ReportFilter.months(YearMonth.of(2026, 1), YearMonth.of(2026, 9)), labels);

        ReportSection result = sections.get(sections.size() - 1);
        assertEquals("Result", result.title());
        assertEquals("400.00", result.rows().get(0).get("amount"), "900 income less 500 expenses");
        assertTrue(result.rows().get(0).get("outcome").contains("Profit"));
    }

    @Test
    void aBalanceSheetAccountNeverAppears() {
        Mockito.when(finaccounting.trialBalanceBetween("2026-01", "2026-09")).thenReturn(new TrialBalanceView(
            "2026-01..2026-09",
            List.of(account("1100", AccountType.ASSET, PostingDirection.DR, "500.00"),
                    account("4100", AccountType.INCOME, PostingDirection.CR, "900.00")),
            BigDecimal.ZERO, BigDecimal.ZERO, true));

        List<ReportSection> sections = report.run(UUID.randomUUID(),
            ReportFilter.months(YearMonth.of(2026, 1), YearMonth.of(2026, 9)), labels);

        assertTrue(sections.stream().flatMap(s -> s.rows().stream())
            .noneMatch(r -> "1100".equals(r.get("accountCode"))));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=IncomeStatementReportTest`
Expected: FAIL — `IncomeStatementReport` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.TrialBalanceView;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/**
 * What moved through classes 4000-8000 across a span of periods, and the result it left.
 *
 * <p>MOVEMENT, not position: an income statement reports a span, so this reads {@code trialBalanceBetween}
 * where the balance sheet reads {@code trialBalanceThrough}. The two are easy to swap and the swap produces a
 * plausible statement that is entirely wrong, which is why a test asserts which one is called.
 *
 * <p>A straight roll-up of the chart's classes (decision D4): 4000 revenue, 5000 insurance service expenses,
 * 6000 reinsurance result, 7000 finance and investment result, 8000 other operating expenses and tax. Nothing
 * is reclassified on its way here.
 */
@Component
public class IncomeStatementReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("income-statement", "Income statement",
        "Revenue and expenses across a span of periods, rolled up from the chart of accounts, and the result.",
        ReportCategory.ACCOUNTING, DateFilterKind.MONTH_RANGE, List.of(), false);

    private final FinaccountingApi finaccounting;

    public IncomeStatementReport(FinaccountingApi finaccounting) {
        this.finaccounting = finaccounting;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        TrialBalanceView ledger = finaccounting.trialBalanceBetween(f.monthFrom().toString(), f.monthTo().toString());

        List<ReportSection> sections = new ArrayList<>();
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;

        for (String classRoot : LedgerAccounts.INCOME_STATEMENT_CLASSES) {
            List<AccountBalanceView> accounts = LedgerAccounts.inClass(ledger.accounts(), classRoot)
                .stream().filter(LedgerAccounts::posted).toList();
            BigDecimal total = accounts.stream().map(AccountBalanceView::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (LedgerAccounts.isIncomeClass(classRoot)) {
                income = income.add(total);
            } else {
                expense = expense.add(total);
            }
            sections.add(section(LedgerAccounts.CLASS_NAMES.get(classRoot), accounts, total));
        }

        BigDecimal result = income.subtract(expense);
        Map<String, String> row = new LinkedHashMap<>();
        row.put("outcome", result.signum() >= 0 ? "Profit for the period" : "Loss for the period");
        row.put("amount", Figures.money(result.abs()));
        sections.add(new ReportSection("Result",
            List.of(col("outcome", "Outcome", TEXT), col("amount", "Amount", MONEY)), List.of(row), Map.of()));

        return sections;
    }

    private static ReportSection section(String title, List<AccountBalanceView> accounts, BigDecimal total) {
        List<ReportColumn> columns = List.of(col("accountCode", "Account", TEXT), col("name", "Name", TEXT),
            col("currency", "Currency", TEXT), col("amount", "Amount", MONEY));
        List<Map<String, String>> rows = accounts.stream().map(a -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("accountCode", a.accountCode());
            row.put("name", a.name());
            row.put("currency", a.currency());
            row.put("amount", Figures.money(a.balance()));
            return row;
        }).toList();
        Map<String, String> totals = new LinkedHashMap<>();
        totals.put("accountCode", "Total");
        totals.put("name", "");
        totals.put("currency", rows.isEmpty() ? "" : rows.get(0).get("currency"));
        totals.put("amount", Figures.money(total));
        return new ReportSection(title, columns, rows, totals);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=IncomeStatementReportTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Run every reporting test together**

Run: `cd backend && ./mvnw test -Dtest='*Report*Test,ReportFiltersTest,LedgerAccountsTest'`
Expected: PASS. Confirm the run reports **more than 20 tests** — a glob that matches nothing still exits zero, so the count is the evidence, not the exit code.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/application/IncomeStatementReport.java backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/application/IncomeStatementReportTest.java
git commit -m "feat(reporting): an income statement across a span of periods

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: Contract test and OpenAPI

The four reports must appear in the catalogue over HTTP, with their filters enforced, before the console can use them.

**Files:**
- Modify: `backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/ReportingContractTest.java`
- Modify: `backend/api/openapi/reporting.yaml`

**Interfaces:**
- Consumes: the four `ReportDefinitionView`s from Tasks 4-7.
- Produces: `ReportCategory` enum value `ACCOUNTING`, `DateFilterKind` values `MONTH`/`MONTH_RANGE`, and query parameters `monthFrom`, `monthTo`, `accountCode` in the spec the console's types are generated from.

- [ ] **Step 1: Write the failing test**

Add to `ReportingContractTest`, following the class's existing authentication and MockMvc setup:

```java
@Test
void theCatalogueCarriesTheAccountingReports() throws Exception {
    mockMvc.perform(get("/reports").with(staff()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.key=='trial-balance')].category").value(hasItem("ACCOUNTING")))
        .andExpect(jsonPath("$[?(@.key=='balance-sheet')].dateFilterKind").value(hasItem("MONTH")))
        .andExpect(jsonPath("$[?(@.key=='income-statement')].dateFilterKind").value(hasItem("MONTH_RANGE")));
}

@Test
void aTrialBalanceNeedsAMonthAndRefusesADate() throws Exception {
    mockMvc.perform(get("/reports/trial-balance/run").param("asOf", "2026-09-30").with(staff()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("monthFrom")));

    mockMvc.perform(get("/reports/trial-balance/run").param("monthFrom", "2026-09").with(staff()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sections[1].rows[0].status").exists());
}

@Test
void aGeneralLedgerWithoutAnAccountIsRefusedByName() throws Exception {
    mockMvc.perform(get("/reports/general-ledger/run")
            .param("monthFrom", "2026-08").param("monthTo", "2026-09").with(staff()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("accountCode")));
}
```

> Use the class's own `staff()` request post-processor. **Do not mint a JWT by hand** — this project has twice shipped a green suite over a credential path nobody could actually use, and fabricated test identities are the reason.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ReportingContractTest`
Expected: FAIL — the catalogue has seven entries, none of them ACCOUNTING.

- [ ] **Step 3: Update the OpenAPI spec**

In `backend/api/openapi/reporting.yaml`:
- add `ACCOUNTING` to the `ReportCategory` enum
- add `MONTH` and `MONTH_RANGE` to the `DateFilterKind` enum
- add three query parameters to the run operation:

```yaml
- name: monthFrom
  in: query
  required: false
  schema: { type: string, pattern: '^\d{4}-\d{2}$' }
  description: The accounting period a MONTH report runs for, or the first of a MONTH_RANGE.
- name: monthTo
  in: query
  required: false
  schema: { type: string, pattern: '^\d{4}-\d{2}$' }
  description: The last period of a MONTH_RANGE.
- name: accountCode
  in: query
  required: false
  schema: { type: string, pattern: '^[1-9]\d{3}$' }
  description: Restricts a ledger report to one chart account. Required by general-ledger.
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ReportingContractTest`
Expected: PASS.

- [ ] **Step 5: Regenerate the console's types**

Run: `cd frontend && npm run generate:api`
Expected: `src/types/api/reporting.ts` now carries `ACCOUNTING`, `MONTH`, `MONTH_RANGE` and the three parameters.

> `openapi-typescript` makes any property with a `default:` required. If a generated field turns up unexpectedly required, that is why — remove the `default:` from the spec rather than widening the TypeScript.

- [ ] **Step 6: Commit**

```bash
git add backend/api/openapi/reporting.yaml backend/src/test/java/tz/co/nlolo/lifeplatform/reporting/ReportingContractTest.java frontend/src/types/api/reporting.ts
git commit -m "feat(reporting): accounting reports on the wire

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 9: The console

The catalogue already groups by category and maps labels, so the new section needs one entry. The filter bar needs month inputs and an account picker.

**Files:**
- Modify: `frontend/src/features/reporting/ReportsCataloguePage.tsx:12`
- Modify: `frontend/src/features/reporting/ReportFilterBar.tsx`
- Modify: `frontend/src/features/reporting/reportParams.ts`
- Test: `frontend/src/features/reporting/reportParams.test.ts`

**Interfaces:**
- Consumes: `ReportDefinition['category']`, `ReportDefinition['dateFilterKind']` from the regenerated types; `listChartOfAccounts()` from `@/api/finaccounting`.
- Produces: `ReportFilterState` gains `monthFrom`, `monthTo`, `accountCode`; `toParams` and `validateFilters` handle `MONTH`/`MONTH_RANGE`.

- [ ] **Step 1: Write the failing test**

Add to `frontend/src/features/reporting/reportParams.test.ts`, using the file's existing imports (`toParams`, `validateFilters`, `defaultFilters`) and its existing helper for building a `ReportDefinition`:

```ts
const monthState = (over: Partial<ReportFilterState>): ReportFilterState => ({
  asOf: '', compareAsOf: '', from: '', to: '', cohortFrom: '', cohortTo: '',
  groupBy: '', clientType: '', productId: '', monthFrom: '', monthTo: '', accountCode: '',
  ...over,
});

it('carries one month for a MONTH report and leaves the day dates out', () => {
  const params = toParams(
    { dateFilterKind: 'MONTH', groupByOptions: [], comparable: false } as unknown as ReportDefinition,
    monthState({ monthFrom: '2026-09', asOf: '2026-09-30' }),
  );
  expect(params.monthFrom).toBe('2026-09');
  expect(params.asOf).toBeUndefined();
  expect(params.monthTo).toBeUndefined();
});

it('carries both ends and the account for a ledger range', () => {
  const params = toParams(
    { dateFilterKind: 'MONTH_RANGE', groupByOptions: [], comparable: false } as unknown as ReportDefinition,
    monthState({ monthFrom: '2026-04', monthTo: '2026-09', accountCode: '1100' }),
  );
  expect(params.monthFrom).toBe('2026-04');
  expect(params.monthTo).toBe('2026-09');
  expect(params.accountCode).toBe('1100');
});

it('refuses a range whose first month is after its last, before sending it', () => {
  const definition = { dateFilterKind: 'MONTH_RANGE', groupByOptions: [], comparable: false } as unknown as ReportDefinition;
  expect(validateFilters(definition, monthState({ monthFrom: '2026-09', monthTo: '2026-04' })))
    .toMatch(/must not be after/);
  expect(validateFilters(definition, monthState({ monthFrom: '2026-04', monthTo: '2026-09' }))).toBeNull();
});

it('defaults a MONTH report to the current month', () => {
  const state = defaultFilters(
    { dateFilterKind: 'MONTH', groupByOptions: [], comparable: false } as unknown as ReportDefinition,
    new Date(2026, 8, 14),
  );
  expect(state.monthFrom).toBe('2026-09');
  expect(state.asOf).toBe('');
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/features/reporting/reportParams.test.ts`
Expected: FAIL — the month fields are not carried.

- [ ] **Step 3: Label the category**

In `ReportsCataloguePage.tsx`:

```tsx
const CATEGORY_LABELS: Record<ReportDefinition['category'], string> = {
  MANAGEMENT: 'Management',
  ACCOUNTING: 'Accounting',
};
```

And update the page description and the stale comment above the component:

```tsx
/** Every report this tenant can run, grouped by the department that reads it. Management reports read a
 * projection of domain events; accounting reports read the general ledger itself. */
```

```tsx
<PageHeader title="Reports" description="Management and accounting reports, on screen or as Excel, CSV or PDF." />
```

- [ ] **Step 4: Carry the new fields through `reportParams.ts`**

Four edits, in the order the file reads:

```ts
export interface ReportFilterState {
  asOf: string;
  compareAsOf: string;
  from: string;
  to: string;
  cohortFrom: string;
  cohortTo: string;
  groupBy: string;
  clientType: string;
  /** Narrows any report to one product; every report's query applies it. */
  productId: string;
  /** The accounting period a MONTH report runs for, or the first of a MONTH_RANGE. YYYY-MM. */
  monthFrom: string;
  /** The last period of a MONTH_RANGE. YYYY-MM. */
  monthTo: string;
  /** One chart account, four digits. Required by general-ledger. */
  accountCode: string;
}
```

In `defaultFilters`, add the three fields to the returned object. A ledger report opens on the current month, and a range on the year to date — the span a finance officer asks for most:

```ts
    monthFrom: kind === 'MONTH' ? isoMonth(today)
      : kind === 'MONTH_RANGE' ? `${today.getFullYear()}-01` : '',
    monthTo: kind === 'MONTH_RANGE' ? isoMonth(today) : '',
    accountCode: '',
```

Extend `FIELDS` so a filter survives a page reload:

```ts
const FIELDS: (keyof ReportFilterState)[] = ['asOf', 'compareAsOf', 'from', 'to', 'cohortFrom', 'cohortTo',
  'groupBy', 'clientType', 'productId', 'monthFrom', 'monthTo', 'accountCode'];
```

In `toParams`, add the two kinds. `compareAsOf` is deliberately allowed on `MONTH` as well, because the balance sheet is the comparable one:

```ts
  if (kind === 'MONTH') put('monthFrom');
  if (kind === 'MONTH' && definition.comparable) put('compareAsOf');
  if (kind === 'MONTH_RANGE') {
    put('monthFrom');
    put('monthTo');
  }
  if (kind === 'MONTH' || kind === 'MONTH_RANGE') put('accountCode');
```

In `validateFilters`, refuse before the request rather than after:

```ts
  if (kind === 'MONTH' && !state.monthFrom) return 'Choose a period.';
  if (kind === 'MONTH_RANGE') {
    if (!state.monthFrom || !state.monthTo) return 'Choose the first and last period.';
    if (state.monthFrom > state.monthTo) return 'The first period must not be after the last.';
  }
```

> The month comparison is a string comparison and that is correct: `YYYY-MM` is zero-padded and fixed-width, so lexicographic order is chronological — the same property Task 1 relies on in SQL. The existing `cohortFrom > cohortTo` check two lines above works the same way.

- [ ] **Step 5: Add the inputs to the filter bar**

`ReportFilterBar.tsx` already has a `field(name, label, type)` helper used by every existing branch. Add two lines beside lines 64-69:

```tsx
        {kind === 'MONTH' && field('monthFrom', 'Period', 'month')}
        {kind === 'MONTH' && definition.comparable && field('compareAsOf', 'Compare with', 'date')}
        {kind === 'MONTH_RANGE' && field('monthFrom', 'First period', 'month')}
        {kind === 'MONTH_RANGE' && field('monthTo', 'Last period', 'month')}
```

`type="month"` means the control itself refuses a day, so the ledger is never asked for a precision it does not have.

For the account, a free-text box would invite a code that is not in the chart. Load the real chart and offer it as a select, above the existing fields:

```tsx
const [accounts, setAccounts] = useState<ChartOfAccountView[]>([]);
const needsAccount = kind === 'MONTH' || kind === 'MONTH_RANGE';

useEffect(() => {
  if (!needsAccount) return;
  // A bare array of a few hundred rows, the same call the Chart of accounts screen makes.
  listChartOfAccounts().then(setAccounts).catch(() => setAccounts([]));
}, [needsAccount]);
```

```tsx
{needsAccount && (
  <label className="flex flex-col gap-1 text-xs">
    <span className="font-medium text-muted-foreground">Account</span>
    <Select inputSize="sm" value={state.accountCode} onChange={(e) => set('accountCode', e.target.value)}>
      <option value="">Every account</option>
      {accounts.filter((a) => a.postingAllowed).map((a) => (
        <option key={a.accountCode} value={a.accountCode}>{a.accountCode} — {a.name}</option>
      ))}
    </Select>
  </label>
)}
```

> Only `postingAllowed` accounts are offered. A summary account carries no postings of its own, so a general ledger of one would be empty and read as a bug.
>
> If the chart fails to load the select falls back to "Every account" alone, and the general ledger's own 400 ("accountCode is required") still names the problem. Do not block the whole filter bar on a failed reference load.

Match the `set(...)` state-updating helper and the `Select` import to whatever the file already uses — read the top of the file before writing this block.

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd frontend && npx vitest run src/features/reporting/reportParams.test.ts`
Expected: PASS, including the four new cases.

- [ ] **Step 7: Type-check, lint, and re-run the reporting suite**

Run: `cd frontend && npx tsc --noEmit && npx eslint src/features/reporting --max-warnings=0 && npx vitest run src/features/reporting`
Expected: all clean. `ReportFilterState` gained three required fields, so every existing construction of it must still compile — `tsc` is the check that it does.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/features/reporting
git commit -m "feat(reporting): accounting reports in the console catalogue and filter bar

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 10: End-to-end, against the real stack

**Files:**
- Modify: `frontend/e2e/staff-reports.spec.ts`

- [ ] **Step 1: Apply the migrations and restart the dev backend**

The dev backend does not pick up new migrations on its own, and this batch has none — but Task 1 changed the API and Task 8 changed the spec, so the running backend is stale. Rebuild and restart it before writing the spec, or every assertion below fails for the wrong reason.

- [ ] **Step 2: Write the spec**

Add to `staff-reports.spec.ts`, following the file's existing session helper:

```ts
test('a finance officer runs a trial balance and sees whether the ledger balances', async ({ page }) => {
  await page.goto('/staff/reports');
  await expect(page.getByRole('heading', { name: 'Accounting' })).toBeVisible();
  await page.getByRole('link', { name: 'Trial balance' }).click();
  await page.getByLabel('Period').fill('2026-09');
  await page.getByRole('button', { name: 'Run' }).click();
  await expect(page.getByRole('table').first()).toBeVisible();
  await expect(page.getByText(/the ledger balances|does NOT balance/)).toBeVisible();
});
```

> Accessible names are what this spec couples to. If the filter bar labels the month control anything other than "Period", use that name — do not rename the control to suit the test.

- [ ] **Step 3: Run it**

Run: `cd frontend && npx playwright test e2e/staff-reports.spec.ts`
Expected: PASS.

> If it fails on authentication, re-run **with** dependencies — `--no-deps` reuses a stale saved session and the failure looks like a bug in the page. If it times out inside a full-suite run, re-run that spec alone before diagnosing: a budget timeout mimics a regression.

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e/staff-reports.spec.ts
git commit -m "test(reporting): a trial balance end to end

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Final whole-branch review

Before merging, review the whole branch as one change rather than ten. Systemic bugs on this project live in the seams between individually-correct tasks, and a final review has found them every time. Specifically check:

- [ ] `ReportFilter`'s new components are carried through **every** `with*` method. The `withMonths` shadowing trap (Task 2) is the likeliest place for a silent swap of dates for months.
- [ ] No accounting report calls `trialBalance(period)` where it meant `trialBalanceThrough(period)`. One is a month's movement, the other a cumulative position; both compile and both return plausible numbers.
- [ ] Totals anywhere in the four reports sum `own` figures or the ledger's own totals, never rolled balances.
- [ ] `./mvnw clean test-compile` passes — `ReportFilter` gained components, and incremental compilation hides exactly that break.
- [ ] `./mvnw test -Dtest=ModularityTest` passes, so the new `finaccounting::api` dependency is declared properly.
- [ ] The management reports still run: `./mvnw test -Dtest=ReportingContractTest` and the console's `src/features/reporting` vitest files.
