# IFRS 17 I6: Year-End Close Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An approved year-end close that clears classes 4-8 to 3310, posts M-07 (3310 -> 3210) and M-11 (3320 -> 3210) as one SYSTEM journal in December, and without which December cannot lock.

**Architecture:** A pure journal builder (`YearEndCloseJournal`) in finaccounting.domain; a `YearEndCloses` service in finaccounting.application (JDBC reads, JPA posting through `FinaccountingApiImpl.postEntry`); `AccountingPeriods.lock` split into a reusable `blockers(...)` dry run plus a new JDBC-only `YearEndLockGate`; a controller, OpenAPI, and a console page.

**Tech Stack:** Spring Boot 3 / Java 21, JdbcTemplate + JPA, Postgres 16 (RLS), Testcontainers, React + zustand + Playwright.

Spec: `backend/docs/superpowers/specs/2026-10-07-ifrs17-i6-year-end-close-design.md`. Branch `ifrs17-i6` from the I5b tip; merge main into it once I5b lands, before the gate.

## Global Constraints

- Worktree `.worktrees/ifrs17-i6`. Host `./mvnw -B -o`, `-Dtest=` with fully qualified names; never Maven in Docker; stop the dev backend before `clean test` in a worktree it runs from; no Maven while Playwright runs. Never run Prettier. Edit with Write/Edit.
- Migration `finaccounting/V17__year_end_close.sql`, appended to test lists with `node scripts/dev/append-test-migration.mjs finaccounting/V17__year_end_close.sql` (from `backend/`).
- Money BigDecimal scale 2; currency TZS.
- Roles as the engine endpoints: FINANCE = `hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))`; APPROVER = `hasRole('REALM_STAFF') and hasRole('FINANCE_APPROVER')`.
- Errors: 409 `YEAR_END_STATE`; 404 `YEAR_END_NOT_FOUND`; 422 `FINACCOUNTING_VALIDATION_FAILED`.
- Journal: events `ifrs17.YearEndClose` / `ifrs17.YearEndCloseReversal`, source SYSTEM, `year_end_close_id` set; period `Y-12`.
- Classes closed: posting accounts whose code starts 4, 5, 6, 7 or 8. Dividends: 3320. Profit = minus the net Dr - Cr of classes 4-8 (a credit balance is a profit).
- Close journals (`year_end_close_id IS NOT NULL`) are excluded from: extract cash flows, the expense pool, the year's balances and the stale check.
- After JPA posting, `entityManager.flush()` before JDBC reads of the posted lines.
- **Clarification of spec §3 (lock):** December's lock requires a close only when the year has class 4-8 or 3320 postings outside close journals -- an empty year needs no close (as an empty month needs no closing). Preparing a close of a year with nothing to close is refused ("Nothing to close in Y").

---

### Task 1: The closing journal (pure)

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/YearEndCloseJournal.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/YearEndCloseJournalTest.java`

**Interfaces:**
- Produces: `YearEndCloseJournal.Balance(String account, BigDecimal net)` (net Dr - Cr for the year); `YearEndCloseJournal.Line(String account, String side, BigDecimal amount)` (side "DR"/"CR"); `YearEndCloseJournal.Result(Map<String, BigDecimal> classTotals, BigDecimal profit, BigDecimal dividends, List<Line> lines)`; `static Result build(List<Balance> classAccounts, BigDecimal dividendsNetDr)`; constants `CURRENT_YEAR = "3310"`, `RETAINED = "3210"`, `DIVIDENDS = "3320"`.

- [ ] **Step 1: Failing test**

```java
package tz.co.nlolo.lifeplatform.finaccounting;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.finaccounting.domain.YearEndCloseJournal;
import tz.co.nlolo.lifeplatform.finaccounting.domain.YearEndCloseJournal.Balance;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** IFRS 17 I6: classes 4-8 closed to 3310, M-07 to 3210, M-11 for dividends -- the guide's 5.7, balanced. */
class YearEndCloseJournalTest {

    private static String text(YearEndCloseJournal.Line l) {
        return l.side() + " " + l.account() + " " + l.amount();
    }

    @Test
    void aProfitClosesToRetainedEarningsThroughCurrentYearProfit() {
        var r = YearEndCloseJournal.build(List.of(new Balance("4110", new BigDecimal("-1000.00")),
            new Balance("5110", new BigDecimal("300.00")), new Balance("8110", new BigDecimal("100.00"))),
            BigDecimal.ZERO);
        assertThat(r.profit()).isEqualByComparingTo("600.00");
        assertThat(r.classTotals()).containsEntry("4", new BigDecimal("-1000.00")).containsEntry("8", new BigDecimal("100.00"));
        assertThat(r.lines()).extracting(YearEndCloseJournalTest::text).containsExactly(
            "DR 4110 1000.00", "CR 5110 300.00", "CR 8110 100.00", "CR 3310 600.00",
            "DR 3310 600.00", "CR 3210 600.00");
    }

    @Test
    void aLossMovesRetainedEarningsTheOtherWay() {
        var r = YearEndCloseJournal.build(List.of(new Balance("8110", new BigDecimal("250.00"))), BigDecimal.ZERO);
        assertThat(r.profit()).isEqualByComparingTo("-250.00");
        assertThat(r.lines()).extracting(YearEndCloseJournalTest::text).containsExactly(
            "CR 8110 250.00", "DR 3310 250.00", "DR 3210 250.00", "CR 3310 250.00");
    }

    @Test
    void dividendsDeclaredCloseToRetainedEarningsAndZeroAccountsAreSkipped() {
        var r = YearEndCloseJournal.build(List.of(new Balance("4110", new BigDecimal("-500.00")),
            new Balance("7110", BigDecimal.ZERO)), new BigDecimal("200.00"));
        assertThat(r.dividends()).isEqualByComparingTo("200.00");
        assertThat(r.lines()).extracting(YearEndCloseJournalTest::text).containsExactly(
            "DR 4110 500.00", "CR 3310 500.00", "DR 3310 500.00", "CR 3210 500.00", "DR 3210 200.00", "CR 3320 200.00");
        BigDecimal dr = r.lines().stream().filter(l -> l.side().equals("DR")).map(YearEndCloseJournal.Line::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cr = r.lines().stream().filter(l -> l.side().equals("CR")).map(YearEndCloseJournal.Line::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(dr).isEqualByComparingTo(cr);
    }

    @Test
    void nothingToCloseIsNoLines() {
        assertThat(YearEndCloseJournal.build(List.of(), BigDecimal.ZERO).lines()).isEmpty();
    }
}
```

- [ ] **Step 2: Run** `./mvnw -B -o test "-Dtest=tz.co.nlolo.lifeplatform.finaccounting.YearEndCloseJournalTest"` -- fails to compile.

- [ ] **Step 3: Implement**

```java
package tz.co.nlolo.lifeplatform.finaccounting.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The year-end close's journal (IFRS 17 I6, guide 5.7): every class 4-8 account with a balance for the year closed to
 * 3310 Current year profit or loss, then 3310 to 3210 Retained earnings (M-07), then dividends declared 3320 to 3210
 * (M-11). Balances are net Dr - Cr; a credit balance of classes 4-8 is a profit. Zero amounts write no line. Pure.
 */
public final class YearEndCloseJournal {

    public static final String CURRENT_YEAR = "3310";
    public static final String RETAINED = "3210";
    public static final String DIVIDENDS = "3320";

    public record Balance(String account, BigDecimal net) {}

    public record Line(String account, String side, BigDecimal amount) {}

    public record Result(Map<String, BigDecimal> classTotals, BigDecimal profit, BigDecimal dividends, List<Line> lines) {}

    private YearEndCloseJournal() {}

    public static Result build(List<Balance> classAccounts, BigDecimal dividendsNetDr) {
        List<Line> lines = new ArrayList<>();
        Map<String, BigDecimal> totals = new TreeMap<>();
        BigDecimal net = BigDecimal.ZERO;
        for (Balance b : classAccounts.stream().sorted(Comparator.comparing(Balance::account)).toList()) {
            if (b.net() == null || b.net().signum() == 0) {
                continue;
            }
            totals.merge(b.account().substring(0, 1), b.net(), BigDecimal::add);
            net = net.add(b.net());
            lines.add(new Line(b.account(), b.net().signum() > 0 ? "CR" : "DR", b.net().abs()));
        }
        BigDecimal profit = net.negate();
        if (net.signum() != 0) {
            // The accounts' opposite sides net to -net; 3310 takes the year's result.
            lines.add(new Line(CURRENT_YEAR, net.signum() > 0 ? "DR" : "CR", net.abs()));
            // M-07: the result moves on to retained earnings.
            if (profit.signum() > 0) {
                lines.add(new Line(CURRENT_YEAR, "DR", profit));
                lines.add(new Line(RETAINED, "CR", profit));
            } else {
                lines.add(new Line(RETAINED, "DR", profit.abs()));
                lines.add(new Line(CURRENT_YEAR, "CR", profit.abs()));
            }
        }
        BigDecimal dividends = dividendsNetDr == null ? BigDecimal.ZERO : dividendsNetDr;
        if (dividends.signum() > 0) {
            lines.add(new Line(RETAINED, "DR", dividends));   // M-11
            lines.add(new Line(DIVIDENDS, "CR", dividends));
        }
        return new Result(totals, profit, dividends.max(BigDecimal.ZERO), lines);
    }
}
```

- [ ] **Step 4: Run** -- 4 pass. **Step 5: Commit** `feat(ifrs17): I6 -- the year-end closing journal (classes 4-8 to 3310, M-07, M-11)`.

---

### Task 2: Data, journal link, API types and errors

**Files:**
- Create: `backend/db-migrations/finaccounting/V17__year_end_close.sql`
- Modify: `JournalEntry.java` (column, getter, `fromYearEndClose(UUID)`), `FinaccountingExceptionHandler.java`
- Create (api): `YearEndCloseView.java`, `YearEndCloseStateException.java`, `YearEndCloseNotFoundException.java`

- [ ] **Step 1: Migration**

```sql
-- IFRS 17 I6: the year-end close (guide 5.7, M-07, M-11). A FINANCE_OFFICER prepares the close of a calendar year; a
-- FINANCE_APPROVER who did not prepare it approves; one SYSTEM journal in Y-12 clears classes 4-8 to 3310, moves the
-- result to 3210 and closes dividends declared (3320). December cannot lock without it (when the year has anything to
-- close); a later replacement reverses it.

CREATE TABLE finaccounting.year_end_close (
    close_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL,
    year                 SMALLINT NOT NULL CHECK (year BETWEEN 1900 AND 2999),
    status               VARCHAR(8) NOT NULL CHECK (status IN ('PREPARED','POSTED','REJECTED','REPLACED')),
    class_totals         JSONB,
    profit               NUMERIC(19,2),
    dividends            NUMERIC(19,2),
    prepared_by          VARCHAR(100) NOT NULL,
    prepared_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by           VARCHAR(100),
    decided_at           TIMESTAMPTZ,
    decision_reason      VARCHAR(500),
    replaces_id          UUID REFERENCES finaccounting.year_end_close (close_id),
    journal_entry_id     UUID,
    reversal_journal_id  UUID,
    version              BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT year_end_close_decider_not_preparer CHECK (decided_by IS NULL OR decided_by <> prepared_by)
);
CREATE UNIQUE INDEX ux_year_end_close_one_prepared ON finaccounting.year_end_close (tenant_id, year)
    WHERE status = 'PREPARED';
CREATE UNIQUE INDEX ux_year_end_close_one_posted ON finaccounting.year_end_close (tenant_id, year)
    WHERE status = 'POSTED';

ALTER TABLE finaccounting.journal_entry
    ADD COLUMN year_end_close_id UUID REFERENCES finaccounting.year_end_close (close_id);

ALTER TABLE finaccounting.year_end_close ENABLE ROW LEVEL SECURITY;
CREATE POLICY year_end_close_tenant_isolation ON finaccounting.year_end_close
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON finaccounting.year_end_close TO app_role;
```
Then `node scripts/dev/append-test-migration.mjs finaccounting/V17__year_end_close.sql`.

- [ ] **Step 2: JournalEntry** -- after `expenseAllocationId`:

```java
    /** The year-end close the journal posts or reverses (IFRS 17 I6, finaccounting V17). */
    @Column(name = "year_end_close_id")
    private UUID yearEndCloseId;
```
getter `getYearEndCloseId()`, and

```java
    /** A year-end close's journal (IFRS 17 I6): the platform's own approved run, so SYSTEM. */
    public JournalEntry fromYearEndClose(UUID closeId) {
        this.sourceType = JournalSource.SYSTEM;
        this.yearEndCloseId = closeId;
        return this;
    }
```

- [ ] **Step 3: API types**

```java
/**
 * A year's close (IFRS 17 I6): PREPARED (its figures computed on read), POSTED as one SYSTEM journal in Y-12, REJECTED,
 * or REPLACED. Balances net Dr - Cr; profit positive for a profit. {@code stale}: classes 4-8 or 3320 posted in the year
 * since it was approved.
 */
public record YearEndCloseView(UUID closeId, int year, String status, Map<String, BigDecimal> classTotals,
                               BigDecimal profit, BigDecimal dividends, List<Account> accounts, List<Line> lines,
                               boolean stale, String preparedBy, Instant preparedAt, String decidedBy, Instant decidedAt,
                               String decisionReason, UUID replacesId, UUID journalEntryId, UUID reversalJournalId) {
    public record Account(String code, String name, String accountClass, BigDecimal balance) {}
    public record Line(String account, String side, BigDecimal amount) {}
}
```
(`closeId`, `status`, `preparedBy`, `preparedAt` are null for a preview.) Exceptions `YearEndCloseStateException` / `YearEndCloseNotFoundException` (RuntimeException, message constructor), handler entries mapping to 409 `YEAR_END_STATE` and 404 `YEAR_END_NOT_FOUND` next to the allocation ones.

- [ ] **Step 4:** `./mvnw -B -o clean test-compile`, then `-Dtest=tz.co.nlolo.lifeplatform.finaccounting.LedgerGuardsIntegrationTest` (applies V17). **Step 5: Commit** `feat(ifrs17): I6 -- year-end close table (V17), its journal link, API types and errors`.

---

### Task 3: The lock's dry run, the year-end gate, the service

**Files:**
- Modify: `AccountingPeriods.java` (`blockers`, `lock`)
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/application/YearEndLockGate.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/application/YearEndCloses.java`
- Modify: `EngineLedger.cashFlows`, `ExpenseAllocations.pool` (exclusions)
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/application/YearEndCloseIntegrationTest.java`

**Interfaces:**
- Produces: `AccountingPeriods.blockers(UUID tenantId, String period): List<String>` (the lock's checks but the year-end gate, in the lock's order; the engine gate's lines collapsed to one with "(and N more)"); `YearEndLockGate.blocking(UUID tenantId, String period): Optional<String>`; `YearEndCloses` public `preview(int year)`, `prepare(int year, String by)`, `approve(UUID id, String by)`, `reject(UUID id, String reason, String by)`, `get(UUID id)`, `list(int year)` -- each returning `YearEndCloseView` (list: `List<YearEndCloseView>`).

- [ ] **Step 1: `AccountingPeriods` split** -- move the four checks of `lock` into

```java
    /**
     * Why {@code period} cannot lock now, in the order the lock checks -- earlier periods with postings, clearing
     * accounts, unposted events, the engine and the expense allocation -- but not the year-end close (its own gate, which
     * the close's preparation runs this to check everything else). Empty when nothing else blocks it.
     */
    List<String> blockers(UUID tenantId, String period) {
        List<String> blockers = new java.util.ArrayList<>();
        for (String earlier : postings.periodsWithPostingsBefore(tenantId, period)) {
            boolean locked = periods.findByTenantIdAndPeriod(tenantId, earlier)
                .map(e -> e.getStatus() == PeriodStatus.LOCKED).orElse(false);
            if (!locked) {
                blockers.add("Period " + earlier + " must be locked first");
                break;
            }
        }
        List<Object[]> clearing = postings.nonZeroNetByAccountPrefix(tenantId, period, "9");
        if (!clearing.isEmpty()) {
            Object[] first = clearing.get(0);
            blockers.add("Clearing account " + first[0] + " holds "
                + String.format("%,.2f", ((BigDecimal) first[1]).abs()) + " TZS in " + period
                + "; clearing accounts must return to zero before the period locks");
        }
        int waiting = unposted.openUpTo(tenantId, period);
        if (waiting > 0) {
            blockers.add(waiting + (waiting == 1 ? " event is" : " events are") + " not posted;"
                + " post or dismiss " + (waiting == 1 ? "it" : "them") + " before the period locks");
        }
        List<String> engine = engineGate.blocking(tenantId, period);
        if (!engine.isEmpty()) {
            blockers.add(engine.get(0) + (engine.size() > 1 ? " (and " + (engine.size() - 1) + " more)" : ""));
        }
        return blockers;
    }
```
and `lock` becomes: load; `List<String> blockers = blockers(tenantId, period); yearEndGate.blocking(tenantId, period).ifPresent(blockers::add); if (!blockers.isEmpty()) throw new PeriodStateException(blockers.get(0));` then lock and save. Inject `YearEndLockGate yearEndGate` in the constructor. Keep the existing comments on each check.

- [ ] **Step 2: `YearEndLockGate`**

```java
/**
 * The year-end close's hold on December (IFRS 17 I6): a December whose year has anything to close -- class 4-8 or 3320
 * postings outside close journals -- locks only with a POSTED close of that year that is not stale. JDBC only, so
 * {@link AccountingPeriods} need not depend on {@link YearEndCloses} (which depends on it).
 */
@Component
class YearEndLockGate {

    static final String CLOSED_ACCOUNTS = "(substr(p.account_code, 1, 1) IN ('4','5','6','7','8') OR p.account_code = '3320')";

    private final JdbcTemplate jdbc;

    YearEndLockGate(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<String> blocking(UUID tenantId, String period) {
        if (!period.endsWith("-12")) {
            return Optional.empty();
        }
        int year = Integer.parseInt(period.substring(0, 4));
        Integer open = jdbc.queryForObject("SELECT count(*) FROM finaccounting.gl_posting p JOIN finaccounting.journal_entry j"
            + " ON j.journal_entry_id = p.journal_entry_id WHERE p.tenant_id = ? AND p.period BETWEEN ? AND ?"
            + " AND j.year_end_close_id IS NULL AND " + CLOSED_ACCOUNTS, Integer.class, tenantId, year + "-01", year + "-12");
        if (open == null || open == 0) {
            return Optional.empty();
        }
        List<java.sql.Timestamp> decided = jdbc.queryForList("SELECT decided_at FROM finaccounting.year_end_close"
            + " WHERE tenant_id = ? AND year = ? AND status = 'POSTED'", java.sql.Timestamp.class, tenantId, year);
        if (decided.isEmpty()) {
            return Optional.of("Year-end close required: prepare and approve the close of " + year);
        }
        return stale(tenantId, year, decided.get(0))
            ? Optional.of("The close of " + year + " is stale: postings to classes 4-8 since it was approved;"
                + " prepare a new close")
            : Optional.empty();
    }

    /** Class 4-8 or 3320 postings in the year, outside close journals, made after the close was approved. */
    boolean stale(UUID tenantId, int year, java.sql.Timestamp decidedAt) {
        Integer later = jdbc.queryForObject("SELECT count(*) FROM finaccounting.gl_posting p JOIN finaccounting.journal_entry j"
            + " ON j.journal_entry_id = p.journal_entry_id WHERE p.tenant_id = ? AND p.period BETWEEN ? AND ?"
            + " AND j.year_end_close_id IS NULL AND p.created_at > ? AND " + CLOSED_ACCOUNTS,
            Integer.class, tenantId, year + "-01", year + "-12", decidedAt);
        return later != null && later > 0;
    }
}
```

- [ ] **Step 3: Exclusions** -- `EngineLedger.cashFlows`: add `AND j.year_end_close_id IS NULL` beside `j.source_type <> 'ENGINE_RUN'`. `ExpenseAllocations.pool`: `AND j.expense_allocation_id IS NULL AND j.year_end_close_id IS NULL`.

- [ ] **Step 4: Failing integration test** (header copied from `ExpenseAllocationIntegrationTest`: Testcontainers, the same migration list through V17, `APP_ROLE_PASSWORD = "year_end_close_password"`, `@MockBean DocumentApi`, `post(...)` helper posting a SYSTEM journal into a given period). Fixtures: `post(tenant, period, ref, dr, cr, amount)`; `ledgerApi.startClosing/lockPeriod`. Tests:

```java
    private static final int YEAR = 2025;

    /** A year with a premium earned (4110 Cr 1000), a claim (5110 Dr 300), payroll (8110 Dr 100) and a dividend (3320 Dr 50). */
    private UUID aYear() {
        UUID tenant = UUID.randomUUID();
        post(tenant, "2025-03", "rev", "2121", "4110", "1000.00");
        post(tenant, "2025-06", "clm", "5110", "2210", "300.00");
        post(tenant, "2025-12", "pay", "8110", "1110", "100.00");
        post(tenant, "2025-12", "div", "3320", "2740", "50.00");
        TenantContext.set(tenant);
        ledgerApi.startClosing("2025-03", "f"); ledgerApi.lockPeriod("2025-03", "f");
        ledgerApi.startClosing("2025-06", "f"); ledgerApi.lockPeriod("2025-06", "f");
        ledgerApi.startClosing("2025-12", "f");
        return tenant;
    }

    @Test
    void closedByASecondPersonItClearsTheYearIntoRetainedEarnings() {
        UUID tenant = aYear();
        var preview = closes.preview(YEAR);
        assertThat(preview.profit()).isEqualByComparingTo("600.00");
        assertThat(preview.dividends()).isEqualByComparingTo("50.00");
        var prepared = closes.prepare(YEAR, "finance-one");
        assertThatThrownBy(() -> closes.approve(prepared.closeId(), "finance-one"))
            .isInstanceOf(YearEndCloseStateException.class).hasMessageContaining("You prepared");
        var posted = closes.approve(prepared.closeId(), "finance-approver");
        assertThat(posted.status()).isEqualTo("POSTED");
        TenantContext.set(tenant);
        assertThat(net(tenant, "4110")).isZero();
        assertThat(net(tenant, "8110")).isZero();
        assertThat(net(tenant, "3310")).isZero();
        assertThat(net(tenant, "3320")).isZero();
        assertThat(net(tenant, "3210")).isEqualByComparingTo("-550.00");   // 600 profit less 50 dividends, a credit
        ledgerApi.lockPeriod("2025-12", "f");
        assertThat(ledgerApi.period("2025-12").status()).isEqualTo(PeriodStatus.LOCKED);
    }

    @Test
    void decemberDoesNotLockWithoutTheClose() {
        aYear();
        assertThatThrownBy(() -> ledgerApi.lockPeriod("2025-12", "f")).hasMessageContaining("Year-end close required");
    }

    @Test
    void preparationNeedsTheRestOfTheYearLockedAndDecemberClosing() {
        UUID tenant = UUID.randomUUID();
        post(tenant, "2025-05", "rev", "2121", "4110", "10.00");
        TenantContext.set(tenant);
        assertThatThrownBy(() -> closes.prepare(YEAR, "f")).isInstanceOf(YearEndCloseStateException.class)
            .hasMessageContaining("is OPEN");
        ledgerApi.startClosing("2025-12", "f");
        assertThatThrownBy(() -> closes.prepare(YEAR, "f")).hasMessageContaining("Period 2025-05 must be locked first");
    }

    @Test
    void aLaterPostingMakesTheCloseStaleAndAReplacementReversesIt() {
        UUID tenant = aYear();
        var first = closes.approve(closes.prepare(YEAR, "f").closeId(), "a");
        post(tenant, "2025-12", "late", "8110", "1110", "20.00");
        assertThat(closes.get(first.closeId()).stale()).isTrue();
        assertThatThrownBy(() -> ledgerApi.lockPeriod("2025-12", "f")).hasMessageContaining("stale");
        var second = closes.approve(closes.prepare(YEAR, "f").closeId(), "a");
        assertThat(second.replacesId()).isEqualTo(first.closeId());
        assertThat(closes.get(first.closeId()).status()).isEqualTo("REPLACED");
        TenantContext.set(tenant);
        assertThat(net(tenant, "8110")).isZero();
        assertThat(net(tenant, "3210")).isEqualByComparingTo("-530.00");
    }

    @Test
    void closeJournalsAreNotInTheExpensePool() throws Exception {
        UUID tenant = aYear();
        closes.approve(closes.prepare(YEAR, "f").closeId(), "a");
        TenantContext.set(tenant);
        assertThat(allocations.preview("2025-12", BigDecimal.ONE, null, null).pool()).isEqualByComparingTo("100.00");
    }

    @Test
    void anEmptyYearNeedsNoCloseAndCannotPrepareOne() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        ledgerApi.startClosing("2025-12", "f");
        assertThatThrownBy(() -> closes.prepare(YEAR, "f")).hasMessageContaining("Nothing to close in 2025");
        ledgerApi.lockPeriod("2025-12", "f");
    }
```
`net(tenant, account)` sums Dr - Cr over all postings of the tenant on that account. Use the real names of `FinaccountingApiImpl`'s period methods (`startClosing`, `lockPeriod`, and the view getter -- check the class) and the real 2740 posting account (or another MAN liability) for the dividend fixture.

- [ ] **Step 5: `YearEndCloses`** -- same shape as `ExpenseAllocations`:
  - constructor: `JdbcTemplate`, `AccountingPeriods`, `YearEndLockGate`, `FinaccountingApiImpl postings`, `EntityManager`, `ObjectMapper`.
  - `balances(tenant, year)`: `SELECT p.account_code, sum(CASE WHEN p.direction='DR' THEN p.amount ELSE -p.amount END) AS net FROM gl_posting p JOIN journal_entry j ... WHERE p.tenant_id=? AND p.period BETWEEN 'Y-01' AND 'Y-12' AND j.year_end_close_id IS NULL AND substr(p.account_code,1,1) IN ('4','5','6','7','8') GROUP BY p.account_code HAVING sum(...) <> 0 ORDER BY p.account_code`; `dividends(tenant, year)` the same on `3320`.
  - `compute(tenant, year)` -> `YearEndCloseJournal.build(balances, dividends)`.
  - `requireCloseable(year)`: year 1900-2999 else 422; `periods.view(Y-12).status() == CLOSING` else 409 "Period Y-12 is STATUS; the year closes once December is closing"; `periods.blockers(tenant, "Y-12")` first entry -> 409 with that message.
  - `preview(year)`: validates the year, returns the view of `compute` (no record fields), `accounts` named from `ChartOfAccountBlueprint`.
  - `prepare(year, by)`: `requireCloseable`; `compute`; no lines -> 409 "Nothing to close in Y: no class 4-8 or dividend postings"; insert PREPARED (DuplicateKeyException -> 409 "A close of Y is already awaiting a decision"); return `get`.
  - `approve(id, by)`: load (404); decidable (PREPARED, not the preparer -- "You prepared this close; a FINANCE_APPROVER other than you approves it"); `requireCloseable`; `compute`; no lines -> 409; a POSTED close of the year -> reverse its journal (`ifrs17.YearEndCloseReversal`, source ref = old journal id, legs swapped, `fromYearEndClose(newId).reversing(oldJournal)`), mark it REPLACED; post the new journal (`ifrs17.YearEndClose`, source ref = close id, period `Y-12`, `fromYearEndClose(id)`, legs from the result's lines in order, no dimensions); `entityManager.flush()`; update POSTED with class_totals (JSON), profit, dividends, journal ids, decided_by/at, replaces_id.
  - `reject(id, reason, by)`: decidable; reason required (422); REJECTED.
  - `get` / `list(year)` (newest first) -> view: PREPARED -> figures from `compute` now; decided -> the stored totals, profit, dividends, and `lines` read from the close journal's postings (ordered by posting id/creation), `accounts` from those lines (every line not on 3310/3210/3320: balance = amount for a CR line, -amount for a DR line); `stale` = POSTED and `yearEndGate.stale(tenant, year, decidedAt)`.
  - Transactions: all `@Transactional` (not read-only).

- [ ] **Step 6: Run** `YearEndCloseIntegrationTest`, `AccountingPeriodAndPolicyRegisterIntegrationTest`, `EngineCycleIntegrationTest`, `ExpenseAllocationIntegrationTest` -- all pass. **Commit** `feat(ifrs17): I6 -- the year-end close, December's lock waiting on it, close journals out of the extract and the pool`.

---

### Task 4: HTTP, OpenAPI, contract test

**Files:** create `YearEndCloseController.java` (infrastructure); modify `backend/api/openapi/openapi-finaccounting.yaml`, `FinaccountingContractTest.java`.

- [ ] Controller (as `ExpenseAllocationController`): `GET /ifrs17/year-end/{year}/preview` (FINANCE or APPROVER), `POST /ifrs17/year-end/{year}/closes` (FINANCE, 201, no body), `GET /ifrs17/year-end/{year}/closes` (FINANCE or APPROVER), `GET /ifrs17/year-end-closes/{id}`, `POST /ifrs17/year-end-closes/{id}/approval` (APPROVER, no body), `POST /ifrs17/year-end-closes/{id}/rejection` (APPROVER, `{reason}`). `{year}` an `int` path variable.
- [ ] OpenAPI: the six paths (year `type: integer, minimum: 1900, maximum: 2999`), schema `YearEndCloseView` (closeId/status/preparedBy/preparedAt/decided* nullable via `type: [x, "null"]`; `classTotals` an object of numbers; `accounts` items {code, name, accountClass, balance}; `lines` items {account, side enum DR/CR, amount}; `stale` boolean); responses 401/403/404/409/422 as the allocation paths.
- [ ] Contract test `theYearEndCloseOverHttp`: prepare on an OPEN December -> 409 `YEAR_END_STATE` (validated); post a payroll line into `2026-12` through `ledgerApi` with a SYSTEM journal (tenant context set), start closing December over HTTP; preview 200 (validated) with profit -100; prepare 201 (validated); approval by a plain finance officer 403; by the approver 200 POSTED (validated); list 200 length 1; get from another tenant 404 `YEAR_END_NOT_FOUND`.
- [ ] Run the contract + spec-parse tests; commit `feat(ifrs17): I6 -- the year-end close over HTTP, in the OpenAPI, contract-tested`.

---

### Task 5: Console

**Files:** `frontend/src/api/types.ts` (re-export `YearEndCloseView` after `npm run generate:api`), `frontend/src/api/yearEnd.ts`, `frontend/src/store/yearEndStore.ts`, `frontend/src/features/finance/yearEnd.ts` + `yearEnd.test.ts`, `YearEndPage.tsx`, `YearEndClosePage.tsx`, `lazyPages.ts`, `screens.tsx`.

- [ ] `yearEnd.ts` helpers with vitest: `CLOSE_STATUS_LABEL` (PREPARED "Awaiting approval", POSTED "Posted", REJECTED "Rejected", REPLACED "Replaced"); `lastYear(now = new Date())` -> `now.getFullYear() - 1`; `canDecideClose(close, viewer, approver)` (as `canDecideAllocation`); `resultLabel(profit)` -> "Profit 600.00" / "Loss 250.00".
- [ ] Store (pattern of `expenseAllocationStore`): `preview`, `closes`, `current`, `acting`; `loadYear(year)` loads preview + list; `prepare(year)`; `load(id)`; `approve(id)`; `reject(id, reason)`.
- [ ] `YearEndPage` (route `year-end`, Finance menu "Year-end close", icon `CalendarClock`): `PageHeader` "Year-end close" ("Classes 4-8 closed to 3310, the result to retained earnings (M-07), dividends declared too (M-11). December locks only once its year is closed."); a "Year" field (default `lastYear()`); the preview: a table `aria-label="Accounts closed"` (code, name, class, balance), totals per class, `resultLabel`, dividends, and a table `aria-label="Closing journal"` (Dr/Cr, account, amount); preview errors through `InlineError` (e.g. "Period … is OPEN"); a "Prepare close" button; a table `aria-label="Year-end closes"` with a link per close `Close of ${year} · ${status label}`.
- [ ] `YearEndClosePage` (route `year-end/closes/:closeId`, drill-in): header `Close of ${year}`, description `${status} · ${resultLabel} · dividends ${money}`; stale notice `role="status"` "Postings to classes 4-8 since this close was approved: prepare a new close"; the two tables as above; Approve and post / Reject with "Reason to reject" when `canDecideClose`; "A finance approver other than you will approve or reject it." for the preparer.
- [ ] `npx tsc -b --noEmit`, `npx eslint src`, `npx vitest run yearEnd screens`; commit `feat(console): I6 -- the year-end close page and its approval page`.

---

### Task 6: e2e, dev stack, gate, merge

- [ ] `frontend/e2e/periods.ts`: replace `untouchedPeriod(page)` with `untouchedPeriod(page, month: '11' | '12' = '11')` -- the year before the earliest known period's year (at most 2000), that year's `month`; it still checks the row offers "Start closing". The engine and allocation specs keep calling `untouchedPeriod(page)`
  and so work in November: they post class 4/5 lines there and lock November only, and a November lock never needs a
  year-end close (only December's does). Each spec run still has a year to itself, because the next run's year is
  before the earliest known period.
- [ ] `frontend/e2e/staff-ifrs17-year-end.spec.ts`: `untouchedPeriod(page, '12')`; while December is OPEN, a payroll manual journal (O-01 template, period set to the December, lines 1000/100/100/800, saved, a document attached, submitted) approved by the approver; start closing December; record "No allocation this month" and have it approved; on `/staff/year-end` set the year, see "Loss 1,000.00" (payroll only) and the "Closing journal" table with 8110, 3310 and 3210; "Prepare close"; open the close; approver approves; "Posted"; lock December with `lockPeriod`.
- [ ] Merge main into `ifrs17-i6` once I5b is merged; apply V17 to dev (`docker exec -i infra-postgres-1 psql ... < V17`); restart backend and Vite from `.worktrees/ifrs17-i6`; run the three IFRS 17 specs; verify the close journal in the DB.
- [ ] Gate: dev backend stopped; `clean test` of every finaccounting and reinsurance class, ModularityTests, and every class whose migration list gained V17 (FQNs; count reports); console tsc/eslint/vitest; full e2e.
- [ ] Merge `--no-ff` into main ("Merge IFRS 17 I6: the year-end close"), push main and `ifrs17-i6`, update memory: IFRS 17 is complete for the agreed scope; the roadmap moves to testing, then production.
