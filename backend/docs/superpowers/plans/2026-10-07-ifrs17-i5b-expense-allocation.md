# IFRS 17 I5b: Expense Allocation (P-19) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Month-end step 5: finance types three expense totals, the platform spreads them over IFRS 17 groups by driver, a second person approves, one SYSTEM journal posts P-19, and the extract waits for it.

**Architecture:** A pure splitter (`ExpenseAllocationSplit`) in finaccounting.domain; an `ExpenseAllocations` service in finaccounting.application reading drivers with JDBC and posting through `FinaccountingApiImpl.postEntry`; gates added to `EngineExtracts.create`, `EngineLockGate.blocking` and `EngineRuns` (latest extract); a REST controller, OpenAPI, and a console section + page.

**Tech Stack:** Spring Boot 3 / Java 21, JdbcTemplate + JPA, Postgres 16 (RLS), Testcontainers, React + zustand + Playwright.

Spec: `backend/docs/superpowers/specs/2026-10-07-ifrs17-i5b-expense-allocation-design.md`.

## Global Constraints

- Worktree `.worktrees/ifrs17-i5b`, branch `ifrs17-i5b`. Never run Maven in Docker; host `./mvnw -B -o` with `-Dtest=<FQN>` (fully qualified names only).
- Stop the dev backend before `clean test` in a worktree it runs from; never run Maven while Playwright runs.
- Never run Prettier. Edit files with Write/Edit, not shell strings.
- New migration files are appended to every test's migration list with `node scripts/dev/append-test-migration.mjs finaccounting/V16__expense_allocation.sql` (from `backend/`).
- Money: BigDecimal, scale 2, HALF_UP; currency `TZS`.
- Roles: FINANCE = `hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))`; APPROVER = `hasRole('REALM_STAFF') and hasRole('FINANCE_APPROVER')`.
- Errors: 409 `ALLOCATION_STATE`; 404 `ALLOCATION_NOT_FOUND`; 422 `FINACCOUNTING_VALIDATION_FAILED` (`FinaccountingValidationException`); extract refusal 409 `ENGINE_STATE`.
- Journal: event `ifrs17.ExpenseAllocation`, reversal `ifrs17.ExpenseAllocationReversal`, source `SYSTEM`, `expense_allocation_id` set.
- Accounts: maintenance 5210, claims handling 5215, acquisition 2123 (GMM, VFA; PAA when not EXPENSE_WHEN_INCURRED) or 5310 (PAA when EXPENSE_WHEN_INCURRED), credit 8490. 2123 lines movement `EXP_ACQ`.
- Pool: period net Dr - Cr on 8100-8499 except 8490, excluding journals with `expense_allocation_id`.
- After JPA posting and before any JDBC read of the posted lines in the same transaction: `entityManager.flush()` (I5a lesson).

---

### Task 1: The split (pure) and the EXP_ACQ movement

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/ExpenseAllocationSplit.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/MovementTypes.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/ExpenseAllocationSplitTest.java`

**Interfaces:**
- Produces: `ExpenseAllocationSplit.Group(String key, String model, long inForce, long claims, long issued)`; `ExpenseAllocationSplit.Line(String group, String model, String category, String account, String driver, long driverCount, BigDecimal amount)`; `static List<Line> split(BigDecimal maintenance, BigDecimal claimsHandling, BigDecimal acquisition, List<Group> groups, Set<String> paaExpensedModels)`; `static String acquisitionAccount(String model, boolean expensedWhenIncurred)`; `static List<BigDecimal> share(BigDecimal total, List<Long> weights)`; constants `MAINTENANCE`, `CLAIMS_HANDLING`, `ACQUISITION`, `MODELS = Set.of("GMM","VFA","PAA")`.

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform.finaccounting;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ExpenseAllocationSplit;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ExpenseAllocationSplit.Group;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ExpenseAllocationSplit.Line;
import tz.co.nlolo.lifeplatform.finaccounting.domain.MovementTypes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** IFRS 17 I5b: P-19's totals spread over the groups by driver, to the cent. */
class ExpenseAllocationSplitTest {

    private static final Group TERM = new Group("TERM-GMM-2026-REM", "GMM", 2, 1, 0);
    private static final Group FUN = new Group("FUN-PAA-2026-REM", "PAA", 1, 0, 1);
    private static final Group SAV = new Group("SAV-IFRS9-2026-REM", "IFRS9", 5, 5, 5);

    private static BigDecimal sum(List<Line> lines, String category) {
        return lines.stream().filter(l -> l.category().equals(category)).map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void eachTotalIsSharedByItsDriverToTheCentAndOnlyOverInsuranceGroups() {
        List<Line> lines = ExpenseAllocationSplit.split(new BigDecimal("100.00"), new BigDecimal("50.00"),
            new BigDecimal("30.00"), List.of(TERM, FUN, SAV), Set.of("PAA"));
        assertThat(lines).noneMatch(l -> l.group().equals(SAV.key()));
        assertThat(lines).filteredOn(l -> l.category().equals("MAINTENANCE"))
            .extracting(l -> l.group() + " " + l.account() + " " + l.amount() + " " + l.driver() + " " + l.driverCount())
            .containsExactly("FUN-PAA-2026-REM 5210 33.33 IN_FORCE 1", "TERM-GMM-2026-REM 5210 66.67 IN_FORCE 2");
        assertThat(lines).filteredOn(l -> l.category().equals("CLAIMS_HANDLING"))
            .extracting(l -> l.group() + " " + l.account() + " " + l.amount())
            .containsExactly("TERM-GMM-2026-REM 5215 50.00");
        assertThat(lines).filteredOn(l -> l.category().equals("ACQUISITION"))
            .extracting(l -> l.group() + " " + l.account() + " " + l.amount() + " " + l.driver())
            .containsExactly("FUN-PAA-2026-REM 5310 30.00 ISSUED");
        assertThat(sum(lines, "MAINTENANCE")).isEqualByComparingTo("100.00");
    }

    @Test
    void theLeftoverCentsGoToTheLargestRemaindersThenByGroupOrder() {
        assertThat(ExpenseAllocationSplit.share(new BigDecimal("0.10"), List.of(1L, 1L, 1L)))
            .extracting(BigDecimal::toPlainString).containsExactly("0.04", "0.03", "0.03");
        assertThat(ExpenseAllocationSplit.share(new BigDecimal("10.00"), List.of(1L, 2L)))
            .extracting(BigDecimal::toPlainString).containsExactly("3.33", "6.67");
    }

    @Test
    void aDriverAtZeroEverywhereFallsBackToInForceThenToEqualShares() {
        Group a = new Group("A-GMM-2026-REM", "GMM", 0, 0, 0);
        Group b = new Group("B-GMM-2026-REM", "GMM", 0, 0, 0);
        List<Line> lines = ExpenseAllocationSplit.split(BigDecimal.ZERO, new BigDecimal("1.00"), BigDecimal.ZERO,
            List.of(a, b), Set.of());
        assertThat(lines).extracting(l -> l.driver() + " " + l.amount()).containsExactly("EQUAL 0.50", "EQUAL 0.50");
        List<Line> fallback = ExpenseAllocationSplit.split(BigDecimal.ZERO, new BigDecimal("4.00"), BigDecimal.ZERO,
            List.of(TERM), Set.of());
        assertThat(fallback).extracting(Line::driver).containsExactly("IN_FORCE");
    }

    @Test
    void acquisitionGoesTo5310OnlyForPaaExpensedWhenIncurred() {
        assertThat(ExpenseAllocationSplit.acquisitionAccount("PAA", true)).isEqualTo("5310");
        assertThat(ExpenseAllocationSplit.acquisitionAccount("PAA", false)).isEqualTo("2123");
        assertThat(ExpenseAllocationSplit.acquisitionAccount("GMM", true)).isEqualTo("2123");
        assertThat(ExpenseAllocationSplit.acquisitionAccount("VFA", false)).isEqualTo("2123");
    }

    @Test
    void zeroTotalsWriteNoLinesAndExpAcqIsAKnownMovement() {
        assertThat(ExpenseAllocationSplit.split(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(TERM), Set.of()))
            .isEmpty();
        assertThat(MovementTypes.CODES).contains("EXP_ACQ");
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run (from `backend/`): `./mvnw -B -o test "-Dtest=tz.co.nlolo.lifeplatform.finaccounting.ExpenseAllocationSplitTest"`
Expected: compilation failure, `ExpenseAllocationSplit` not found.

- [ ] **Step 3: Implement**

`MovementTypes.CODES`: add `"EXP_ACQ"` to the IACF line, and extend the class comment: "EXP_ACQ (IFRS 17 I5b, a deviation from the guide's list): attributable acquisition overhead allocated into 2123 by P-19 -- deliberately not IACF_*, which the guide's commission report reads."

```java
package tz.co.nlolo.lifeplatform.finaccounting.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * P-19's split (IFRS 17 I5b, spec section 4): each of the month's three totals shared over the groups of insurance
 * contracts by a driver -- maintenance by policies in force, claims handling by claims notified in the month, acquisition
 * by policies issued in it -- to the cent, the leftover cents to the largest remainders (ties by group order). A driver
 * at zero in every group falls back to in force, then to equal shares. IFRS 9 and reinsurance groups get nothing. Pure.
 */
public final class ExpenseAllocationSplit {

    public static final String MAINTENANCE = "MAINTENANCE";
    public static final String CLAIMS_HANDLING = "CLAIMS_HANDLING";
    public static final String ACQUISITION = "ACQUISITION";
    public static final Set<String> MODELS = Set.of("GMM", "VFA", "PAA");

    /** One group and its three driver counts. */
    public record Group(String key, String model, long inForce, long claims, long issued) {}

    /** One posted line: a group's share of one category, the account it goes to and the driver it was shared by. */
    public record Line(String group, String model, String category, String account, String driver, long driverCount,
                       BigDecimal amount) {}

    private ExpenseAllocationSplit() {}

    /**
     * The lines, groups in key order, zero amounts left out. {@code paaExpensedModels} holds "PAA" when the register
     * says PAA acquisition cash flows are expensed when incurred (para 59(a)).
     */
    public static List<Line> split(BigDecimal maintenance, BigDecimal claimsHandling, BigDecimal acquisition,
                                   List<Group> groups, Set<String> paaExpensedModels) {
        List<Group> insurance = groups.stream().filter(g -> MODELS.contains(g.model()))
            .sorted(Comparator.comparing(Group::key)).toList();
        List<Line> lines = new ArrayList<>();
        category(lines, insurance, MAINTENANCE, maintenance, "IN_FORCE", Group::inForce, g -> "5210");
        category(lines, insurance, CLAIMS_HANDLING, claimsHandling, "CLAIMS", Group::claims, g -> "5215");
        category(lines, insurance, ACQUISITION, acquisition, "ISSUED", Group::issued,
            g -> acquisitionAccount(g.model(), paaExpensedModels.contains(g.model())));
        return lines;
    }

    public static String acquisitionAccount(String model, boolean expensedWhenIncurred) {
        return "PAA".equals(model) && expensedWhenIncurred ? "5310" : "2123";
    }

    /** {@code total} shared in proportion to {@code weights}, to the cent; the cents left go to the largest remainders. */
    public static List<BigDecimal> share(BigDecimal total, List<Long> weights) {
        BigInteger cents = total.setScale(2, RoundingMode.HALF_UP).movePointRight(2).toBigIntegerExact();
        BigInteger sum = weights.stream().map(BigInteger::valueOf).reduce(BigInteger.ZERO, BigInteger::add);
        List<BigInteger> base = new ArrayList<>();
        List<BigInteger> remainder = new ArrayList<>();
        BigInteger given = BigInteger.ZERO;
        for (Long w : weights) {
            BigInteger[] qr = cents.multiply(BigInteger.valueOf(w)).divideAndRemainder(sum);
            base.add(qr[0]);
            remainder.add(qr[1]);
            given = given.add(qr[0]);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < weights.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing((Integer i) -> remainder.get(i)).reversed().thenComparing(i -> i));
        int left = cents.subtract(given).intValueExact();
        for (int k = 0; k < left; k++) {
            int i = order.get(k);
            base.set(i, base.get(i).add(BigInteger.ONE));
        }
        return base.stream().map(c -> new BigDecimal(c, 2)).toList();
    }

    private static void category(List<Line> lines, List<Group> groups, String category, BigDecimal total, String driver,
                                 ToLongFunction<Group> count, java.util.function.Function<Group, String> account) {
        if (total == null || total.signum() == 0 || groups.isEmpty()) {
            return;
        }
        String used = driver;
        ToLongFunction<Group> weight = count;
        if (groups.stream().mapToLong(count).sum() == 0) {
            used = "IN_FORCE";
            weight = Group::inForce;
            if (groups.stream().mapToLong(Group::inForce).sum() == 0) {
                used = "EQUAL";
                weight = g -> 1L;
            }
        }
        ToLongFunction<Group> w = weight;
        List<BigDecimal> shares = share(total, groups.stream().map(g -> w.applyAsLong(g)).toList());
        for (int i = 0; i < groups.size(); i++) {
            if (shares.get(i).signum() > 0) {
                Group g = groups.get(i);
                lines.add(new Line(g.key(), g.model(), category, account.apply(g), used, w.applyAsLong(g), shares.get(i)));
            }
        }
    }
}
```

- [ ] **Step 4: Run it to see it pass**

Same command. Expected: `Tests run: 5, Failures: 0, Errors: 0`. Also run `tz.co.nlolo.lifeplatform.finaccounting.application.PostingRulesTest` (uses `MovementTypes`): passes.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/ExpenseAllocationSplit.java backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/MovementTypes.java backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/ExpenseAllocationSplitTest.java
git commit -m "feat(ifrs17): I5b -- P-19's split by driver to the cent, and the EXP_ACQ movement"
```

---

### Task 2: Data, journal link, API types and errors

**Files:**
- Create: `backend/db-migrations/finaccounting/V16__expense_allocation.sql`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/JournalEntry.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/api/ExpenseAllocationView.java`, `ExpenseAllocationPreview.java`, `ExpenseAllocationInput.java`, `ExpenseAllocationStateException.java`, `ExpenseAllocationNotFoundException.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/infrastructure/FinaccountingExceptionHandler.java`
- Modify (script): every test migration list.

**Interfaces:**
- Produces: `JournalEntry.fromExpenseAllocation(UUID allocationId)` (sets `sourceType = SYSTEM`, `expenseAllocationId`); the three api records below; the two exceptions.

- [ ] **Step 1: The migration**

```sql
-- IFRS 17 I5b: expense allocation (guide P-19, month-end step 5). Finance types the month's three totals; the platform
-- spreads them over the groups of insurance contracts by driver; a FINANCE_APPROVER who did not prepare it approves, and
-- one SYSTEM journal posts Dr 5210 / 5215 / 2123 or 5310, Cr 8490. A nil allocation ("none this month") posts nothing
-- but is decided like any other: the extract waits for a POSTED allocation.

CREATE TABLE finaccounting.expense_allocation (
    allocation_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL,
    period               VARCHAR(7) NOT NULL CHECK (period ~ '^\d{4}-(0[1-9]|1[0-2])$'),
    status               VARCHAR(8) NOT NULL CHECK (status IN ('PREPARED','POSTED','REJECTED','REPLACED')),
    maintenance          NUMERIC(19,2) NOT NULL CHECK (maintenance >= 0),
    claims_handling      NUMERIC(19,2) NOT NULL CHECK (claims_handling >= 0),
    acquisition          NUMERIC(19,2) NOT NULL CHECK (acquisition >= 0),
    currency             CHAR(3) NOT NULL DEFAULT 'TZS',
    study_reference      VARCHAR(200),
    note                 VARCHAR(500),
    nil_reason           VARCHAR(500),
    prepared_by          VARCHAR(100) NOT NULL,
    prepared_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by           VARCHAR(100),
    decided_at           TIMESTAMPTZ,
    decision_reason      VARCHAR(500),
    pool_at_approval     NUMERIC(19,2),
    over_pool            BOOLEAN,
    replaces_id          UUID REFERENCES finaccounting.expense_allocation (allocation_id),
    journal_entry_id     UUID,
    reversal_journal_id  UUID,
    version              BIGINT NOT NULL DEFAULT 0,
    CHECK (decided_by IS NULL OR decided_by <> prepared_by),
    CHECK (maintenance + claims_handling + acquisition > 0 OR nil_reason IS NOT NULL),
    CHECK (maintenance + claims_handling + acquisition = 0 OR study_reference IS NOT NULL)
);
CREATE UNIQUE INDEX ux_expense_allocation_one_prepared ON finaccounting.expense_allocation (tenant_id, period)
    WHERE status = 'PREPARED';
CREATE UNIQUE INDEX ux_expense_allocation_one_posted ON finaccounting.expense_allocation (tenant_id, period)
    WHERE status = 'POSTED';

CREATE TABLE finaccounting.expense_allocation_line (
    allocation_id      UUID NOT NULL REFERENCES finaccounting.expense_allocation (allocation_id),
    tenant_id          UUID NOT NULL,
    group_key          VARCHAR(40) NOT NULL,
    measurement_model  VARCHAR(5) NOT NULL,
    category           VARCHAR(15) NOT NULL CHECK (category IN ('MAINTENANCE','CLAIMS_HANDLING','ACQUISITION')),
    account_code       VARCHAR(10) NOT NULL,
    driver             VARCHAR(10) NOT NULL CHECK (driver IN ('IN_FORCE','CLAIMS','ISSUED','EQUAL')),
    driver_count       BIGINT NOT NULL,
    amount             NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    PRIMARY KEY (allocation_id, group_key, category)
);

ALTER TABLE finaccounting.journal_entry
    ADD COLUMN expense_allocation_id UUID REFERENCES finaccounting.expense_allocation (allocation_id);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['expense_allocation','expense_allocation_line'] LOOP
        EXECUTE format('ALTER TABLE finaccounting.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON finaccounting.%I USING (tenant_id = NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)',
                       t || '_tenant_isolation', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE ON finaccounting.expense_allocation TO app_role;
GRANT SELECT, INSERT ON finaccounting.expense_allocation_line TO app_role;
```

Then (from `backend/`): `node scripts/dev/append-test-migration.mjs finaccounting/V16__expense_allocation.sql` -- check it reports the files it appended to (the classes listing V15).

- [ ] **Step 2: JournalEntry link** -- after `engineRunId`:

```java
    @Column(name = "expense_allocation_id")
    private UUID expenseAllocationId;
```
getter `public UUID getExpenseAllocationId() { return expenseAllocationId; }` and, after `fromEngineRun`:

```java
    /** A P-19 expense allocation's journal (IFRS 17 I5b): the platform's own approved run, so SYSTEM. */
    public JournalEntry fromExpenseAllocation(UUID allocationId) {
        this.sourceType = JournalSource.SYSTEM;
        this.expenseAllocationId = allocationId;
        return this;
    }
```

- [ ] **Step 3: API types and errors** (package `tz.co.nlolo.lifeplatform.finaccounting.api`)

```java
/** What finance types for a month (IFRS 17 I5b): three totals and the study they come from, or why there are none. */
public record ExpenseAllocationInput(BigDecimal maintenance, BigDecimal claimsHandling, BigDecimal acquisition,
                                     String studyReference, String note, String nilReason) {}
```
```java
/** The split as it would post now: the month's pool beside the total, and the lines per group and category. */
public record ExpenseAllocationPreview(String period, BigDecimal pool, BigDecimal total, boolean overPool,
                                       List<ExpenseAllocationView.Line> lines) {}
```
```java
/**
 * One month's expense allocation (IFRS 17 I5b). While PREPARED the pool and lines are computed on read; once decided
 * they are as posted. {@code staleExtract} is the latest extract's number when it predates this POSTED allocation.
 */
public record ExpenseAllocationView(UUID allocationId, String period, String status, BigDecimal maintenance,
                                    BigDecimal claimsHandling, BigDecimal acquisition, BigDecimal total, String currency,
                                    String studyReference, String note, String nilReason, BigDecimal pool,
                                    boolean overPool, String preparedBy, Instant preparedAt, String decidedBy,
                                    Instant decidedAt, String decisionReason, UUID replacesId, UUID journalEntryId,
                                    UUID reversalJournalId, Integer staleExtract, List<Line> lines) {
    public record Line(String group, String measurementModel, String category, String account, String driver,
                       long driverCount, BigDecimal amount) {}
}
```
```java
/** A step of the expense allocation the period, its state or its people refuse (409, IFRS 17 I5b). */
public class ExpenseAllocationStateException extends RuntimeException {
    public ExpenseAllocationStateException(String message) { super(message); }
}
```
```java
public class ExpenseAllocationNotFoundException extends RuntimeException {
    public ExpenseAllocationNotFoundException(String message) { super(message); }
}
```
In `FinaccountingExceptionHandler`, next to the engine handlers:

```java
    @ExceptionHandler(tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationStateException.class)
    public ProblemDetail handleAllocationState(tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationStateException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "ALLOCATION_STATE");
    }

    @ExceptionHandler(tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationNotFoundException.class)
    public ProblemDetail handleAllocationNotFound(tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "ALLOCATION_NOT_FOUND");
    }
```

- [ ] **Step 4: Compile and prove the migration applies**

Run: `./mvnw -B -o clean test-compile` then `./mvnw -B -o test "-Dtest=tz.co.nlolo.lifeplatform.finaccounting.LedgerGuardsIntegrationTest"` (applies the finaccounting migrations through V16). Expected: BUILD SUCCESS, its tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A backend
git commit -m "feat(ifrs17): I5b -- expense allocation tables (V16), its journal link, API types and errors"
```

---

### Task 3: The service: preview, prepare, approve, reject, read

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/application/ExpenseAllocations.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/application/ExpenseAllocationIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 `ExpenseAllocationSplit`; Task 2 types; `AccountingPeriods.view(period).status()`; `FinaccountingApiImpl.postEntry(JournalEntry)`, `FinaccountingApiImpl.policyElectionInForce(String key, String scope, LocalDate on)`.
- Produces (all `public`, `@Transactional`): `ExpenseAllocationPreview preview(String period, BigDecimal maintenance, BigDecimal claimsHandling, BigDecimal acquisition)`; `ExpenseAllocationView prepare(String period, ExpenseAllocationInput input, String by)`; `ExpenseAllocationView approve(UUID id, boolean aboveThePool, String by)`; `ExpenseAllocationView reject(UUID id, String reason, String by)`; `ExpenseAllocationView get(UUID id)`; `List<ExpenseAllocationView> list(String period)`.

- [ ] **Step 1: Write the failing integration test**

Copy `EngineCycleIntegrationTest`'s class header verbatim (Testcontainers, `@SpringBootTest(classes = Application.class)`, `APP_ROLE_PASSWORD = "expense_allocation_password"`, the same migration list -- which now ends with V16 after Task 2's script -- the `@MockBean DocumentApi` with `storeDocumentsAnywhere`, `publish`, `money`, `ledgerApi`, `seeder`, `transactionManager`, `clearTenant`). Then:

```java
    @Autowired private ExpenseAllocations allocations;
    @Autowired private EngineExtracts extracts;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static final String PERIOD = "2026-08";

    /** A group and a policy classified into it, issued on {@code issued}. */
    private static void classify(UUID tenant, String group, String model, String policyNumber, String issued) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("INSERT INTO finaccounting.group_of_contracts (group_id, tenant_id, cohort_year, measurement_model,"
                + " group_key, portfolio_code, profitability_bucket) SELECT gen_random_uuid(), '" + tenant + "', 2026, '" + model
                + "', '" + group + "', 'TERM', 'REMAINING' WHERE NOT EXISTS (SELECT 1 FROM finaccounting.group_of_contracts"
                + " WHERE tenant_id = '" + tenant + "' AND group_key = '" + group + "')");
            s.execute("INSERT INTO finaccounting.policy_classification (tenant_id, policy_number, reason, effective_from,"
                + " group_id, group_key, measurement_model, model_basis, register_version, portfolio_code, cohort_year,"
                + " profitability_bucket) SELECT '" + tenant + "', '" + policyNumber + "', 'ISSUE', '" + issued + "', group_id,"
                + " group_key, '" + model + "', 'REGISTER', 1, 'TERM', 2026, 'REMAINING' FROM finaccounting.group_of_contracts"
                + " WHERE tenant_id = '" + tenant + "' AND group_key = '" + group + "'");
        }
    }

    private void activate(UUID tenant, String policyNumber) {
        publish(tenant, "policy.PolicyActivated", Map.of("policyNumber", policyNumber, "issueDate", "2026-03-01",
            "premiumFrequency", "MONTHLY", "sumAssured", money("1000000.00"), "premium", money("10000.00")));
    }

    /** One balanced journal of {@code source}: Dr {@code dr} / Cr {@code cr}. */
    private void post(UUID tenant, String ref, String dr, String cr, String amount,
                      tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions dims) {
        TenantContext.set(tenant);
        seeder.seedIfAbsent(tenant, "test");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var entry = new tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry(tenant, "test.Posting", ref, PERIOD,
                null, "test").withSource(tz.co.nlolo.lifeplatform.finaccounting.domain.JournalSource.SYSTEM);
            entry.addLeg(dr, tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection.DR, new java.math.BigDecimal(amount), "TZS", dims);
            entry.addLeg(cr, tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection.CR, new java.math.BigDecimal(amount), "TZS", dims);
            ledgerApi.postEntry(entry);
        });
    }

    /**
     * A month with a GMM group (two policies in force, one claim notified in August), a PAA group (one policy issued in
     * August), an IFRS 9 group (ignored) and a 1,000 expense pool (salaries), closing.
     */
    private UUID closingMonth() throws Exception {
        UUID tenant = UUID.randomUUID();
        classify(tenant, "TERM-GMM-2026-REM", "GMM", "POL-A", "2026-03-01");
        classify(tenant, "TERM-GMM-2026-REM", "GMM", "POL-B", "2026-03-01");
        classify(tenant, "FUN-PAA-2026-REM", "PAA", "POL-C", "2026-08-10");
        classify(tenant, "SAV-IFRS9-2026-REM", "IFRS9", "POL-D", "2026-08-10");
        for (String p : List.of("POL-A", "POL-B", "POL-C", "POL-D")) {
            activate(tenant, p);
        }
        post(tenant, "claim-1", "5110", "2210", "500.00", new tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions(
            "TERM-GMM-2026-REM", "GMM", null, null, "TERM", null, null, null, "CLAIM", "CLM-1"));
        post(tenant, "payroll-1", "8110", "1110", "1000.00", tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions.NONE);
        TenantContext.set(tenant);
        ledgerApi.startClosing(PERIOD, "finance-one");
        return tenant;
    }

    private static ExpenseAllocationInput totals(String m, String c, String a) {
        return new ExpenseAllocationInput(new BigDecimal(m), new BigDecimal(c), new BigDecimal(a), "Study 2026-Q3", null, null);
    }

    @Test
    void preparedThenApprovedByASecondPersonItPostsP19ByDriver() throws Exception {
        UUID tenant = closingMonth();
        var preview = allocations.preview(PERIOD, new BigDecimal("300.00"), new BigDecimal("100.00"), new BigDecimal("60.00"));
        assertThat(preview.pool()).isEqualByComparingTo("1000.00");
        assertThat(preview.overPool()).isFalse();
        assertThat(preview.lines()).extracting(l -> l.group() + " " + l.category() + " " + l.account() + " " + l.amount())
            .containsExactly("FUN-PAA-2026-REM MAINTENANCE 5210 100.00", "TERM-GMM-2026-REM MAINTENANCE 5210 200.00",
                "TERM-GMM-2026-REM CLAIMS_HANDLING 5215 100.00", "FUN-PAA-2026-REM ACQUISITION 5310 60.00");

        var prepared = allocations.prepare(PERIOD, totals("300.00", "100.00", "60.00"), "finance-one");
        assertThat(prepared.status()).isEqualTo("PREPARED");
        assertThatThrownBy(() -> allocations.approve(prepared.allocationId(), false, "finance-one"))
            .isInstanceOf(ExpenseAllocationStateException.class).hasMessageContaining("You prepared");
        var posted = allocations.approve(prepared.allocationId(), false, "finance-approver");
        assertThat(posted.status()).isEqualTo("POSTED");
        assertThat(posted.pool()).isEqualByComparingTo("1000.00");

        TenantContext.set(tenant);
        assertThat(jdbc.queryForList("SELECT p.account_code || ' ' || p.direction || ' ' || p.amount || ' '"
                + " || coalesce(p.ifrs17_group,'-') || ' ' || coalesce(p.movement_type,'-') || ' ' || j.source_type"
                + " FROM finaccounting.gl_posting p JOIN finaccounting.journal_entry j USING (journal_entry_id)"
                + " WHERE j.expense_allocation_id = ? ORDER BY p.account_code, p.ifrs17_group", String.class,
            prepared.allocationId()))
            .containsExactly("5210 DR 100.00 FUN-PAA-2026-REM - SYSTEM", "5210 DR 200.00 TERM-GMM-2026-REM - SYSTEM",
                "5215 DR 100.00 TERM-GMM-2026-REM - SYSTEM", "5310 DR 60.00 FUN-PAA-2026-REM - SYSTEM",
                "8490 CR 460.00 - - SYSTEM");
    }

    @Test
    void aGmmGroupsAcquisitionGoesTo2123TaggedExpAcq() throws Exception {
        UUID tenant = closingMonth();
        classify(tenant, "END-GMM-2026-REM", "GMM", "POL-E", "2026-08-20");
        var preview = allocations.preview(PERIOD, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10.00"));
        assertThat(preview.lines()).extracting(l -> l.group() + " " + l.account() + " " + l.amount())
            .containsExactly("END-GMM-2026-REM 2123 5.00", "FUN-PAA-2026-REM 5310 5.00");
        var prepared = allocations.prepare(PERIOD, totals("0", "0", "10.00"), "finance-one");
        allocations.approve(prepared.allocationId(), false, "finance-approver");
        TenantContext.set(tenant);
        assertThat(jdbc.queryForObject("SELECT p.movement_type FROM finaccounting.gl_posting p JOIN"
            + " finaccounting.journal_entry j USING (journal_entry_id) WHERE j.expense_allocation_id = ? AND p.account_code"
            + " = '2123'", String.class, prepared.allocationId())).isEqualTo("EXP_ACQ");
    }

    @Test
    void aboveThePoolTheApproverMustSaySo() throws Exception {
        closingMonth();
        var prepared = allocations.prepare(PERIOD, totals("1500.00", "0", "0"), "finance-one");
        assertThat(allocations.get(prepared.allocationId()).overPool()).isTrue();
        assertThatThrownBy(() -> allocations.approve(prepared.allocationId(), false, "finance-approver"))
            .isInstanceOf(ExpenseAllocationStateException.class).hasMessageContaining("above the month's pool");
        var posted = allocations.approve(prepared.allocationId(), true, "finance-approver");
        assertThat(posted.overPool()).isTrue();
    }

    @Test
    void aNilAllocationNeedsAReasonAndIsApprovedLikeAnyOther() throws Exception {
        UUID tenant = closingMonth();
        assertThatThrownBy(() -> allocations.prepare(PERIOD, new ExpenseAllocationInput(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, " "), "finance-one"))
            .isInstanceOf(tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException.class);
        var nil = allocations.prepare(PERIOD, new ExpenseAllocationInput(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null, null, "No attributable spend this month"), "finance-one");
        var posted = allocations.approve(nil.allocationId(), false, "finance-approver");
        assertThat(posted.status()).isEqualTo("POSTED");
        assertThat(posted.journalEntryId()).isNull();
    }

    @Test
    void aReplacementReversesTheOldJournalAndOnlyOneStaysPosted() throws Exception {
        UUID tenant = closingMonth();
        var first = allocations.prepare(PERIOD, totals("300.00", "0", "0"), "finance-one");
        allocations.approve(first.allocationId(), false, "finance-approver");
        allocations.prepare(PERIOD, totals("1.00", "0", "0"), "finance-one");
        assertThatThrownBy(() -> allocations.prepare(PERIOD, totals("2.00", "0", "0"), "finance-one"))
            .isInstanceOf(ExpenseAllocationStateException.class).hasMessageContaining("awaiting a decision");
        var second = allocations.list(PERIOD).get(0);
        var posted = allocations.approve(second.allocationId(), false, "finance-approver");
        assertThat(posted.replacesId()).isEqualTo(first.allocationId());
        assertThat(allocations.get(first.allocationId()).status()).isEqualTo("REPLACED");
        TenantContext.set(tenant);
        assertThat(jdbc.queryForObject("SELECT COALESCE(sum(CASE WHEN p.direction = 'DR' THEN p.amount ELSE -p.amount END), 0)"
            + " FROM finaccounting.gl_posting p WHERE p.tenant_id = ? AND p.account_code = '5210'", BigDecimal.class, tenant))
            .as("300 reversed, 1 posted").isEqualByComparingTo("1.00");
    }

    @Test
    void refusedOutsideAClosingMonthAndAcrossTenants() throws Exception {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        assertThatThrownBy(() -> allocations.prepare("2026-07", totals("1.00", "0", "0"), "finance-one"))
            .isInstanceOf(ExpenseAllocationStateException.class).hasMessageContaining("is OPEN");
        UUID other = closingMonth();
        var prepared = allocations.prepare(PERIOD, totals("1.00", "0", "0"), "finance-one");
        TenantContext.set(tenant);
        assertThatThrownBy(() -> allocations.get(prepared.allocationId()))
            .isInstanceOf(tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationNotFoundException.class);
        assertThatThrownBy(() -> allocations.reject(prepared.allocationId(), "no", "finance-approver"))
            .isInstanceOf(tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationNotFoundException.class);
        TenantContext.set(other);
        assertThat(allocations.reject(prepared.allocationId(), "Wrong study", "finance-approver").status()).isEqualTo("REJECTED");
    }
```
(Imports: `java.math.BigDecimal`, `tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationInput`, `tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationStateException`, `static org.assertj.core.api.Assertions.assertThatThrownBy`. The payroll fixture's credit account `1110` must be a
posting account in the chart -- check `ifrs17-chart.csv` and use the first cash/bank posting account if it is not.)

The claim posting is `5110 Dr / 2210 Cr` with reference `CLAIM`/`CLM-1` and the TERM group: the claims driver counts distinct claim references on 22xx lines with a group in the month.

- [ ] **Step 2: Run to see it fail** -- `./mvnw -B -o test "-Dtest=tz.co.nlolo.lifeplatform.finaccounting.application.ExpenseAllocationIntegrationTest"`: compilation fails (`ExpenseAllocations`).

- [ ] **Step 3: Implement `ExpenseAllocations`**

```java
package tz.co.nlolo.lifeplatform.finaccounting.application;

// imports: JdbcTemplate, Service, Transactional, TenantContext, api.* used below, domain.ExpenseAllocationSplit,
// domain.JournalEntry, java.math.*, java.sql.*, java.time.*, java.util.*

/**
 * P-19, the month's expense allocation (IFRS 17 I5b, month-end step 5). Finance types three totals for a closing month;
 * they are spread over the groups of insurance contracts by driver ({@link ExpenseAllocationSplit}); a FINANCE_APPROVER
 * who did not prepare it approves -- acknowledging a total above the month's pool -- and one SYSTEM journal posts
 * Dr 5210 / 5215 / 2123 or 5310, Cr 8490. A replacement reverses the posted one. The extract waits for a POSTED one.
 */
@Service
public class ExpenseAllocations {

    static final String EVENT = "ifrs17.ExpenseAllocation";
    static final String REVERSAL_EVENT = "ifrs17.ExpenseAllocationReversal";
    static final String CURRENCY = "TZS";
    private static final String IN_FORCE = "('ACTIVE','REINSTATED','PAID_UP')";

    private final JdbcTemplate jdbc;
    private final AccountingPeriods periods;
    private final FinaccountingApiImpl postings;
    private final jakarta.persistence.EntityManager entityManager;

    ExpenseAllocations(JdbcTemplate jdbc, AccountingPeriods periods, FinaccountingApiImpl postings,
                       jakarta.persistence.EntityManager entityManager) { /* assign */ }

    @Transactional(readOnly = true)
    public ExpenseAllocationPreview preview(String period, BigDecimal maintenance, BigDecimal claimsHandling,
                                            BigDecimal acquisition) {
        UUID tenantId = TenantContext.get();
        requirePeriod(period);
        BigDecimal m = amount("Maintenance", maintenance), c = amount("Claims handling", claimsHandling),
            a = amount("Acquisition", acquisition);
        BigDecimal pool = pool(tenantId, period);
        BigDecimal total = m.add(c).add(a);
        List<ExpenseAllocationView.Line> lines = lines(tenantId, period, m, c, a);
        return new ExpenseAllocationPreview(period, pool, total, total.compareTo(pool) > 0, lines);
    }

    @Transactional
    public ExpenseAllocationView prepare(String period, ExpenseAllocationInput in, String by) {
        UUID tenantId = TenantContext.get();
        requirePeriod(period);
        if (in == null) throw new FinaccountingValidationException("Give the three totals, or why there is no allocation");
        BigDecimal m = amount("Maintenance", in.maintenance()), c = amount("Claims handling", in.claimsHandling()),
            a = amount("Acquisition", in.acquisition());
        boolean nil = m.add(c).add(a).signum() == 0;
        String study = trim(in.studyReference(), 200, "A study reference");
        String nilReason = trim(in.nilReason(), 500, "A reason");
        if (nil && nilReason == null) {
            throw new FinaccountingValidationException("All three totals are zero: say why there is no allocation this month");
        }
        if (!nil && study == null) {
            throw new FinaccountingValidationException("Give the expense allocation study the totals come from");
        }
        requireClosing(period);
        if (!nil && groups(tenantId, period).isEmpty()) {
            throw new ExpenseAllocationStateException("No group of insurance contracts to allocate to");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO finaccounting.expense_allocation (allocation_id, tenant_id, period, status, maintenance,"
                    + " claims_handling, acquisition, study_reference, note, nil_reason, prepared_by)"
                    + " VALUES (?, ?, ?, 'PREPARED', ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, period, m, c, a, nil ? null : study, trim(in.note(), 500, "A note"), nil ? nilReason : null, by);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ExpenseAllocationStateException("An expense allocation for " + period
                + " is already awaiting a decision; approve or reject it first");
        }
        return get(id);
    }

    @Transactional
    public ExpenseAllocationView approve(UUID id, boolean aboveThePool, String by) {
        UUID tenantId = TenantContext.get();
        Row row = decidable(load(id), by);
        requireClosing(row.period());
        BigDecimal pool = pool(tenantId, row.period());
        BigDecimal total = row.total();
        boolean over = total.compareTo(pool) > 0;
        if (over && !aboveThePool) {
            throw new ExpenseAllocationStateException("The total " + total.toPlainString() + " is above the month's pool of "
                + pool.toPlainString() + "; approve above the pool to post it");
        }
        List<ExpenseAllocationView.Line> lines = lines(tenantId, row.period(), row.maintenance(), row.claimsHandling(),
            row.acquisition());
        if (total.signum() > 0 && lines.isEmpty()) {
            throw new ExpenseAllocationStateException("No group of insurance contracts to allocate to");
        }
        Optional<Row> previous = posted(tenantId, row.period());
        UUID reversal = previous.map(old -> reverse(tenantId, old, row, by)).orElse(null);
        UUID journal = null;
        if (total.signum() > 0) {
            JournalEntry entry = new JournalEntry(tenantId, EVENT, id.toString(), row.period(), null, by)
                .fromExpenseAllocation(id);
            for (ExpenseAllocationView.Line l : lines) {
                entry.addLeg(l.account(), PostingDirection.DR, l.amount(), CURRENCY, new LineDimensions(l.group(),
                    l.measurementModel(), "2123".equals(l.account()) ? "EXP_ACQ" : null, null, null, null, null, null,
                    "EXPENSE_ALLOCATION", id.toString()));
            }
            entry.addLeg("8490", PostingDirection.CR, total, CURRENCY, new LineDimensions(null, null, null, null, null,
                null, null, null, "EXPENSE_ALLOCATION", id.toString()));
            postings.postEntry(entry);
            entityManager.flush();
            journal = entry.getJournalEntryId();
            for (ExpenseAllocationView.Line l : lines) {
                jdbc.update("INSERT INTO finaccounting.expense_allocation_line (allocation_id, tenant_id, group_key,"
                        + " measurement_model, category, account_code, driver, driver_count, amount)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, tenantId, l.group(), l.measurementModel(), l.category(), l.account(), l.driver(), l.driverCount(),
                    l.amount());
            }
        }
        jdbc.update("UPDATE finaccounting.expense_allocation SET status = 'POSTED', decided_by = ?, decided_at = now(),"
                + " pool_at_approval = ?, over_pool = ?, replaces_id = ?, journal_entry_id = ?, reversal_journal_id = ?,"
                + " version = version + 1 WHERE tenant_id = ? AND allocation_id = ? AND status = 'PREPARED'",
            by, pool, over, previous.map(Row::id).orElse(null), journal, reversal, tenantId, id);
        return get(id);
    }

    @Transactional
    public ExpenseAllocationView reject(UUID id, String reason, String by) {
        decidable(load(id), by);
        String r = trim(reason, 500, "A reason");
        if (r == null) throw new FinaccountingValidationException("Say why the allocation is rejected");
        jdbc.update("UPDATE finaccounting.expense_allocation SET status = 'REJECTED', decided_by = ?, decided_at = now(),"
                + " decision_reason = ?, version = version + 1 WHERE tenant_id = ? AND allocation_id = ? AND status = 'PREPARED'",
            by, r, TenantContext.get(), id);
        return get(id);
    }

    @Transactional(readOnly = true)
    public ExpenseAllocationView get(UUID id) {
        return view(load(id));
    }

    @Transactional(readOnly = true)
    public List<ExpenseAllocationView> list(String period) {
        return jdbc.query("SELECT * FROM finaccounting.expense_allocation WHERE tenant_id = ? AND period = ?"
                + " ORDER BY prepared_at DESC", this::row, TenantContext.get(), period).stream().map(this::view).toList();
    }

    // ---- the drivers and the pool ----

    /** The month's net Dr - Cr on the expense pool 8100-8499, but 8490 and earlier allocations. */
    BigDecimal pool(UUID tenantId, String period) {
        BigDecimal pool = jdbc.queryForObject("SELECT COALESCE(sum(CASE WHEN p.direction = 'DR' THEN p.amount ELSE"
                + " -p.amount END), 0) FROM finaccounting.gl_posting p JOIN finaccounting.journal_entry j"
                + " ON j.journal_entry_id = p.journal_entry_id WHERE p.tenant_id = ? AND p.period = ?"
                + " AND p.account_code BETWEEN '8100' AND '8499' AND p.account_code <> '8490'"
                + " AND j.expense_allocation_id IS NULL", BigDecimal.class, tenantId, period);
        return (pool == null ? BigDecimal.ZERO : pool).setScale(2, RoundingMode.HALF_UP);
    }

    /** The insurance groups with their driver counts: in force now, claims notified and policies issued in the month. */
    List<ExpenseAllocationSplit.Group> groups(UUID tenantId, String period) {
        YearMonth month = YearMonth.parse(period);
        Map<String, long[]> counts = new TreeMap<>();
        Map<String, String> models = new HashMap<>();
        jdbc.query("SELECT c.group_key, c.measurement_model, count(*) AS n FROM finaccounting.policy_snapshot s"
                + " JOIN (SELECT DISTINCT ON (policy_number) policy_number, group_key, measurement_model"
                + "       FROM finaccounting.policy_classification WHERE tenant_id = ?"
                + "       ORDER BY policy_number, effective_from DESC) c ON c.policy_number = s.policy_number"
                + " WHERE s.tenant_id = ? AND s.status IN " + IN_FORCE + " GROUP BY c.group_key, c.measurement_model",
            rs -> { add(counts, models, rs, 0); }, tenantId, tenantId);
        jdbc.query("SELECT p.ifrs17_group AS group_key, g.measurement_model, count(DISTINCT p.reference) AS n"
                + " FROM finaccounting.gl_posting p JOIN finaccounting.group_of_contracts g"
                + " ON g.tenant_id = p.tenant_id AND g.group_key = p.ifrs17_group"
                + " WHERE p.tenant_id = ? AND p.period = ? AND p.account_code LIKE '22%' AND p.reference_type = 'CLAIM'"
                + " GROUP BY p.ifrs17_group, g.measurement_model",
            rs -> { add(counts, models, rs, 1); }, tenantId, period);
        jdbc.query("SELECT group_key, measurement_model, count(DISTINCT policy_number) AS n"
                + " FROM finaccounting.policy_classification WHERE tenant_id = ? AND reason = 'ISSUE'"
                + " AND effective_from BETWEEN ? AND ? GROUP BY group_key, measurement_model",
            rs -> { add(counts, models, rs, 2); }, tenantId, java.sql.Date.valueOf(month.atDay(1)),
            java.sql.Date.valueOf(month.atEndOfMonth()));
        return counts.entrySet().stream()
            .filter(e -> ExpenseAllocationSplit.MODELS.contains(models.get(e.getKey())))
            .map(e -> new ExpenseAllocationSplit.Group(e.getKey(), models.get(e.getKey()), e.getValue()[0],
                e.getValue()[1], e.getValue()[2]))
            .toList();
    }

    private static void add(Map<String, long[]> counts, Map<String, String> models, ResultSet rs, int slot) throws SQLException {
        String key = rs.getString("group_key");
        models.putIfAbsent(key, rs.getString("measurement_model"));
        counts.computeIfAbsent(key, k -> new long[3])[slot] += rs.getLong("n");
    }

    private List<ExpenseAllocationView.Line> lines(UUID tenantId, String period, BigDecimal m, BigDecimal c, BigDecimal a) {
        LocalDate end = YearMonth.parse(period).atEndOfMonth();
        boolean paaExpensed = postings.policyElectionInForce("ACQUISITION_CASH_FLOWS", "PAA", end)
            .map(e -> "EXPENSE_WHEN_INCURRED".equals(e.value())).orElse(false);
        return ExpenseAllocationSplit.split(m, c, a, groups(tenantId, period), paaExpensed ? Set.of("PAA") : Set.of())
            .stream().map(l -> new ExpenseAllocationView.Line(l.group(), l.model(), l.category(), l.account(), l.driver(),
                l.driverCount(), l.amount())).toList();
    }

    // ---- rows, gates, reversal ----

    private record Row(UUID id, String period, String status, BigDecimal maintenance, BigDecimal claimsHandling,
                       BigDecimal acquisition, String studyReference, String note, String nilReason, String preparedBy,
                       Instant preparedAt, String decidedBy, Instant decidedAt, String decisionReason,
                       BigDecimal poolAtApproval, Boolean overPool, UUID replacesId, UUID journalEntryId,
                       UUID reversalJournalId) {
        BigDecimal total() { return maintenance.add(claimsHandling).add(acquisition); }
    }

    private Row load(UUID id) {
        return jdbc.query("SELECT * FROM finaccounting.expense_allocation WHERE tenant_id = ? AND allocation_id = ?",
                this::row, TenantContext.get(), id).stream().findFirst()
            .orElseThrow(() -> new ExpenseAllocationNotFoundException("Expense allocation " + id + " not found"));
    }

    private Optional<Row> posted(UUID tenantId, String period) {
        return jdbc.query("SELECT * FROM finaccounting.expense_allocation WHERE tenant_id = ? AND period = ?"
            + " AND status = 'POSTED'", this::row, tenantId, period).stream().findFirst();
    }

    private static Row decidable(Row row, String by) {
        if (!"PREPARED".equals(row.status())) {
            throw new ExpenseAllocationStateException("Only a prepared allocation can be decided; this one is " + row.status());
        }
        if (row.preparedBy().equals(by)) {
            throw new ExpenseAllocationStateException("You prepared this allocation; a FINANCE_APPROVER other than you decides it");
        }
        return row;
    }

    private void requireClosing(String period) {
        PeriodStatus status = periods.view(period).status();
        if (status != PeriodStatus.CLOSING) {
            throw new ExpenseAllocationStateException("Period " + period + " is " + status + "; expense allocation is"
                + " month-end step 5, made once the period is closing");
        }
    }

    private static void requirePeriod(String period) {
        if (period == null || !period.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            throw new FinaccountingValidationException("A period is YYYY-MM, not '" + period + "'");
        }
    }

    private static BigDecimal amount(String what, BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        if (v.signum() < 0) throw new FinaccountingValidationException(what + " cannot be negative");
        if (v.stripTrailingZeros().scale() > 2) throw new FinaccountingValidationException(what + " has more than two decimals");
        return v.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String trim(String s, int max, String what) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        if (t.length() > max) throw new FinaccountingValidationException(what + " is at most " + max + " characters");
        return t;
    }

    /** The posted allocation's journal reversed -- legs swapped, dimensions kept -- and marked REPLACED. */
    private UUID reverse(UUID tenantId, Row old, Row replacement, String by) {
        UUID reversalId = null;
        if (old.journalEntryId() != null) {
            JournalEntry reversal = new JournalEntry(tenantId, REVERSAL_EVENT, old.journalEntryId().toString(),
                replacement.period(), null, by).fromExpenseAllocation(replacement.id()).reversing(old.journalEntryId());
            jdbc.query("SELECT account_code, direction, amount, currency, ifrs17_group, measurement_model, movement_type,"
                    + " reference_type, reference FROM finaccounting.gl_posting WHERE tenant_id = ? AND journal_entry_id = ?",
                rs -> {
                    reversal.addLeg(rs.getString("account_code"),
                        "DR".equals(rs.getString("direction")) ? PostingDirection.CR : PostingDirection.DR,
                        rs.getBigDecimal("amount"), rs.getString("currency").trim(),
                        new LineDimensions(rs.getString("ifrs17_group"), rs.getString("measurement_model"),
                            rs.getString("movement_type"), null, null, null, null, null, rs.getString("reference_type"),
                            rs.getString("reference")));
                },
                tenantId, old.journalEntryId());
            postings.postEntry(reversal);
            entityManager.flush();
            reversalId = reversal.getJournalEntryId();
        }
        jdbc.update("UPDATE finaccounting.expense_allocation SET status = 'REPLACED', version = version + 1"
            + " WHERE tenant_id = ? AND allocation_id = ? AND status = 'POSTED'", tenantId, old.id());
        return reversalId;
    }

    private ExpenseAllocationView view(Row r) {
        UUID tenantId = TenantContext.get();
        boolean prepared = "PREPARED".equals(r.status());
        BigDecimal pool = prepared ? pool(tenantId, r.period()) : r.poolAtApproval();
        List<ExpenseAllocationView.Line> lines = prepared
            ? (r.total().signum() == 0 ? List.of() : lines(tenantId, r.period(), r.maintenance(), r.claimsHandling(), r.acquisition()))
            : jdbc.query("SELECT * FROM finaccounting.expense_allocation_line WHERE tenant_id = ? AND allocation_id = ?"
                    + " ORDER BY CASE category WHEN 'MAINTENANCE' THEN 1 WHEN 'CLAIMS_HANDLING' THEN 2 ELSE 3 END, group_key",
                (rs, i) -> new ExpenseAllocationView.Line(rs.getString("group_key"), rs.getString("measurement_model"),
                    rs.getString("category"), rs.getString("account_code"), rs.getString("driver"),
                    rs.getLong("driver_count"), rs.getBigDecimal("amount")),
                tenantId, r.id());
        boolean over = prepared ? pool != null && r.total().compareTo(pool) > 0 : Boolean.TRUE.equals(r.overPool());
        Integer stale = null;
        if ("POSTED".equals(r.status())) {
            stale = jdbc.query("SELECT number FROM finaccounting.engine_extract WHERE tenant_id = ? AND period = ?"
                    + " AND created_at < ? ORDER BY number DESC LIMIT 1", (rs, i) -> rs.getInt("number"), tenantId,
                r.period(), Timestamp.from(r.decidedAt())).stream().findFirst()
                .filter(n -> jdbc.queryForObject("SELECT count(*) FROM finaccounting.engine_extract WHERE tenant_id = ?"
                    + " AND period = ? AND created_at >= ?", Integer.class, tenantId, r.period(),
                    Timestamp.from(r.decidedAt())) == 0)
                .orElse(null);
        }
        return new ExpenseAllocationView(r.id(), r.period(), r.status(), r.maintenance(), r.claimsHandling(),
            r.acquisition(), r.total(), CURRENCY, r.studyReference(), r.note(), r.nilReason(), pool, over, r.preparedBy(),
            r.preparedAt(), r.decidedBy(), r.decidedAt(), r.decisionReason(), r.replacesId(), r.journalEntryId(),
            r.reversalJournalId(), stale, lines);
    }

    private Row row(ResultSet rs, int i) throws SQLException {
        Timestamp prepared = rs.getTimestamp("prepared_at"), decided = rs.getTimestamp("decided_at");
        return new Row(rs.getObject("allocation_id", UUID.class), rs.getString("period"), rs.getString("status"),
            rs.getBigDecimal("maintenance"), rs.getBigDecimal("claims_handling"), rs.getBigDecimal("acquisition"),
            rs.getString("study_reference"), rs.getString("note"), rs.getString("nil_reason"), rs.getString("prepared_by"),
            prepared.toInstant(), rs.getString("decided_by"), decided == null ? null : decided.toInstant(),
            rs.getString("decision_reason"), rs.getBigDecimal("pool_at_approval"), (Boolean) rs.getObject("over_pool"),
            rs.getObject("replaces_id", UUID.class), rs.getObject("journal_entry_id", UUID.class),
            rs.getObject("reversal_journal_id", UUID.class));
    }
}
```

Notes for the implementer:
- `ux_expense_allocation_one_posted`: the old row must be REPLACED (in `reverse`) before the new row's UPDATE to POSTED -- the order above does that.
- `gl_posting.reference_type` is VARCHAR(20): `EXPENSE_ALLOCATION` is 18. Check the column's CHECK, if any, allows it (`\d finaccounting.gl_posting` on a test container or the V2/V10 migrations); if a CHECK lists reference types, extend it in V16.
- `policyElectionInForce` seeds the register for the tenant when absent; it needs `TenantContext` set (it is).

- [ ] **Step 4: Run to see it pass** -- same command; expected `Tests run: 6, Failures: 0, Errors: 0`. Look for the `Tests run:` line in `target/surefire-reports/...ExpenseAllocationIntegrationTest.txt`, not a truncated console.

- [ ] **Step 5: Commit**

```bash
git add -A backend
git commit -m "feat(ifrs17): I5b -- the expense allocation: preview, prepare, two-person approval posting P-19, replacement"
```

---

### Task 4: The gates -- extract, extract contents, lock, latest extract

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/application/EngineExtracts.java` (`create`)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/application/EngineLedger.java` (`cashFlows`)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/application/EngineLockGate.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/application/EngineRuns.java` (`upload`, `approve`)
- Modify: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/application/EngineCycleIntegrationTest.java`
- Test: `ExpenseAllocationIntegrationTest` (new tests)

**Interfaces:**
- Consumes: Task 3's `ExpenseAllocations` (tests only); the tables of Task 2.

- [ ] **Step 1: Failing tests** in `ExpenseAllocationIntegrationTest`:

```java
    @Test
    void theExtractWaitsForAPostedAllocationAndCarriesItsLines() throws Exception {
        UUID tenant = closingMonth();
        assertThatThrownBy(() -> extracts.create(PERIOD, "finance-one"))
            .isInstanceOf(tz.co.nlolo.lifeplatform.finaccounting.api.EngineStateException.class)
            .hasMessageContaining("Step 5 first");
        var prepared = allocations.prepare(PERIOD, totals("300.00", "100.00", "60.00"), "finance-one");
        assertThatThrownBy(() -> extracts.create(PERIOD, "finance-one")).hasMessageContaining("Step 5 first");
        allocations.approve(prepared.allocationId(), false, "finance-approver");
        var extract = extracts.create(PERIOD, "finance-one");
        String cash = new String(extracts.render(extract.extractId(), "csv", "cash-flows"), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(cash).contains("TERM-GMM-2026-REM,,5210,DR,200.00,TZS")
            .contains("TERM-GMM-2026-REM,,5215,DR,100.00,TZS")
            .contains("FUN-PAA-2026-REM,,5310,DR,60.00,TZS");
        assertThat(allocations.get(prepared.allocationId()).staleExtract()).isNull();

        allocations.prepare(PERIOD, totals("10.00", "0", "0"), "finance-one");
        var replacement = allocations.approve(allocations.list(PERIOD).get(0).allocationId(), false, "finance-approver");
        assertThat(replacement.staleExtract()).as("extract #1 predates the replacement").isEqualTo(1);
    }

    @Test
    void aPendingAllocationKeepsTheMonthFromLocking() throws Exception {
        UUID tenant = closingMonth();
        allocations.prepare(PERIOD, totals("1.00", "0", "0"), "finance-one");
        TenantContext.set(tenant);
        assertThatThrownBy(() -> ledgerApi.lockPeriod(PERIOD, "finance-one"))
            .hasMessageContaining("Expense allocation awaiting a decision");
    }
```
(Check the lock method's real name on `FinaccountingApiImpl` -- the one `AccountingPeriodController` calls for `/finance/periods/{period}/lock` -- and use it.)

And in `EngineCycleIntegrationTest`: a helper recording the nil allocation, called before every `extracts.create` (in `theExtractCarriesEveryGroupsCashFlowsBalancesAndPolicies` after `startClosing`, and in `closingMonthWithItsExtract`):

```java
    @Autowired private ExpenseAllocations allocations;

    /** Step 5 before step 6: none this month, approved by a second person. */
    private void noAllocation(UUID tenant) {
        TenantContext.set(tenant);
        var nil = allocations.prepare(PERIOD, new tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationInput(
            java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, null, null,
            "No attributable spend in the test month"), "finance-one");
        allocations.approve(nil.allocationId(), false, "finance-approver");
    }
```
Plus a test that results naming an older extract are rejected:

```java
    @Test
    void resultsMustAnswerTheLatestExtract() throws Exception {
        UUID tenant = closingMonthWithItsExtract();
        TenantContext.set(tenant);
        extracts.create(PERIOD, "finance-one");   // #2
        var run = runs.upload(results("RUN-OLD-EXTRACT", RUN_LINES, closing("14000000.00")), "r.xlsx", "finance-one");
        assertThat(run.status()).isEqualTo("REJECTED");
        assertThat(run.errors()).contains("Header: extract #1 is not the latest (#2); results answer the latest extract");
    }
```

- [ ] **Step 2: Run both classes to see the new tests fail** (and the I5a ones still pass with the helper in place -- they fail first on "Step 5 first" only after Step 3, so add the helper now).

- [ ] **Step 3: Implement**

`EngineExtracts.create`, after the unposted check:

```java
        Integer allocated = jdbc.queryForObject("SELECT count(*) FROM finaccounting.expense_allocation WHERE tenant_id = ?"
            + " AND period = ? AND status = 'POSTED'", Integer.class, tenantId, period);
        if (allocated == null || allocated == 0) {
            throw new EngineStateException("Step 5 first: " + period + " has no posted expense allocation; prepare one,"
                + " or record that there is none this month, and have it approved");
        }
```

`EngineLedger.cashFlows`, the policy-group clause becomes:

```java
                + " AND ((a.grp NOT LIKE 'RI-%' AND (a.account_code LIKE '21%' OR a.account_code LIKE '22%'"
                + "      OR a.account_code IN ('5210','5215','5310')) AND a.account_code <> '2190')"
```
and its javadoc gains: "Policy groups also carry their attributable expenses, allocated by P-19 (5210, 5215) or expensed when incurred (5310)."

`EngineLockGate.blocking`: prepend the pending allocation:

```java
    List<String> blocking(UUID tenantId, String period) {
        List<String> blocking = new java.util.ArrayList<>(jdbc.query("SELECT allocation_id FROM finaccounting.expense_allocation"
                + " WHERE tenant_id = ? AND period = ? AND status = 'PREPARED'",
            (rs, i) -> "Expense allocation awaiting a decision; approve or reject it", tenantId, period));
        blocking.addAll(/* the existing engine query, unchanged */);
        return blocking;
    }
```
(update the class comment: "...and an expense allocation still awaiting its decision (I5b)").

`EngineRuns`: a helper and two call sites:

```java
    /** Results answer the period's latest extract; an older one no longer shows the month (IFRS 17 I5b). */
    private Optional<String> staleExtract(UUID tenantId, EngineResults.Header h) {
        if (h.period() == null || h.extractNumber() == null) return Optional.empty();
        Integer latest = jdbc.queryForObject("SELECT max(number) FROM finaccounting.engine_extract WHERE tenant_id = ?"
            + " AND period = ?", Integer.class, tenantId, h.period());
        return latest != null && h.extractNumber() < latest
            ? Optional.of("Header: extract #" + h.extractNumber() + " is not the latest (#" + latest
                + "); results answer the latest extract")
            : Optional.empty();
    }
```
In `upload`, after `errors.addAll(results.problems(...))`: `staleExtract(tenantId, results.header()).ifPresent(errors::add);`. In `approve`, after `problems.addAll(results.problems(...))`: `staleExtract(tenantId, results.header()).ifPresent(problems::add);`.

- [ ] **Step 4: Run** `ExpenseAllocationIntegrationTest`, `EngineCycleIntegrationTest`, `AccountingPeriodAndPolicyRegisterIntegrationTest` (FQNs). Expected: all pass.

- [ ] **Step 5: Commit** -- `git commit -am "feat(ifrs17): I5b -- the extract waits for step 5 and carries 5210/5215/5310; a pending allocation blocks the lock; results answer the latest extract"`

---

### Task 5: HTTP: controller, OpenAPI, contract test

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/infrastructure/ExpenseAllocationController.java`
- Modify: `api/openapi/openapi-finaccounting.yaml`
- Modify: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/FinaccountingContractTest.java`

- [ ] **Step 1: Failing contract test** (after `theEngineCycleOverHttp`):

```java
    @Test
    void theExpenseAllocationOverHttp() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String period = "2026-08";
        String body = "{\"maintenance\":0,\"claimsHandling\":0,\"acquisition\":0,\"nilReason\":\"None this month\"}";
        mockMvc.perform(post("/ifrs17/periods/{p}/expense-allocations", period).with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("ALLOCATION_STATE"));
        mockMvc.perform(post("/finance/periods/{p}/closing", period).with(financeStaffOf(tenantId)))
            .andExpect(status().isOk());
        mockMvc.perform(post("/ifrs17/periods/{p}/expense-allocations", period).with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"maintenance\":-1}"))
            .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/ifrs17/periods/{p}/expense-allocation-preview", period).param("maintenance", "10.00")
                .with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.overPool").value(true));
        String created = mockMvc.perform(post("/ifrs17/periods/{p}/expense-allocations", period).with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("PREPARED"))
            .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(created, "$.allocationId");
        mockMvc.perform(post("/ifrs17/expense-allocations/{id}/approval", id).with(financeStaffOf(tenantId, "finance-two"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"aboveThePool\":false}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/ifrs17/expense-allocations/{id}/approval", id).with(financeApproverOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"aboveThePool\":false}"))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("POSTED"));
        mockMvc.perform(get("/ifrs17/periods/{p}/expense-allocations", period).with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/ifrs17/expense-allocations/{id}", id).with(financeStaffOf(UUID.randomUUID())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("ALLOCATION_NOT_FOUND"));
    }
```
(Use the status the closing endpoint really returns -- check `AccountingPeriodController` and the existing period contract test.)

- [ ] **Step 2: Run** `tz.co.nlolo.lifeplatform.finaccounting.FinaccountingContractTest`: the new test fails (404 on the paths).

- [ ] **Step 3: Controller**

```java
package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

/** P-19 over HTTP (IFRS 17 I5b): finance prepares a closing month's expense allocation; a FINANCE_APPROVER decides it. */
@RestController
public class ExpenseAllocationController {

    private static final String FINANCE = "hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))";
    private static final String APPROVER = "hasRole('REALM_STAFF') and hasRole('FINANCE_APPROVER')";

    public record ApprovalRequest(Boolean aboveThePool) {}
    public record RejectionRequest(String reason) {}

    private final ExpenseAllocations allocations;

    public ExpenseAllocationController(ExpenseAllocations allocations) { this.allocations = allocations; }

    @GetMapping("/ifrs17/periods/{period}/expense-allocation-preview")
    @PreAuthorize(FINANCE + " or " + APPROVER)
    public ExpenseAllocationPreview preview(@PathVariable String period,
                                            @RequestParam(required = false) BigDecimal maintenance,
                                            @RequestParam(required = false) BigDecimal claimsHandling,
                                            @RequestParam(required = false) BigDecimal acquisition) {
        return allocations.preview(period, maintenance, claimsHandling, acquisition);
    }

    @PostMapping("/ifrs17/periods/{period}/expense-allocations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(FINANCE)
    public ExpenseAllocationView prepare(@PathVariable String period, @RequestBody ExpenseAllocationInput body,
                                         @AuthenticationPrincipal Jwt jwt) {
        return allocations.prepare(period, body, jwt.getSubject());
    }

    @GetMapping("/ifrs17/periods/{period}/expense-allocations")
    @PreAuthorize(FINANCE + " or " + APPROVER)
    public List<ExpenseAllocationView> list(@PathVariable String period) { return allocations.list(period); }

    @GetMapping("/ifrs17/expense-allocations/{id}")
    @PreAuthorize(FINANCE + " or " + APPROVER)
    public ExpenseAllocationView get(@PathVariable UUID id) { return allocations.get(id); }

    @PostMapping("/ifrs17/expense-allocations/{id}/approval")
    @PreAuthorize(APPROVER)
    public ExpenseAllocationView approve(@PathVariable UUID id, @RequestBody(required = false) ApprovalRequest body,
                                         @AuthenticationPrincipal Jwt jwt) {
        return allocations.approve(id, body != null && Boolean.TRUE.equals(body.aboveThePool()), jwt.getSubject());
    }

    @PostMapping("/ifrs17/expense-allocations/{id}/rejection")
    @PreAuthorize(APPROVER)
    public ExpenseAllocationView reject(@PathVariable UUID id, @RequestBody(required = false) RejectionRequest body,
                                        @AuthenticationPrincipal Jwt jwt) {
        return allocations.reject(id, body == null ? null : body.reason(), jwt.getSubject());
    }
}
```

- [ ] **Step 4: OpenAPI** -- add the six paths with the tag the engine paths use, and schemas `ExpenseAllocationInput` (numbers ≥ 0, strings), `ExpenseAllocationPreview` (`period`, `pool`, `total`, `overPool`, `lines`), `ExpenseAllocationLine` (`group`, `measurementModel`, `category` enum, `account`, `driver` enum `IN_FORCE|CLAIMS|ISSUED|EQUAL`, `driverCount` integer, `amount` number), `ExpenseAllocationView` (every field of the record; nullable ones as `type: [string, "null"]` / `[number, "null"]` / `[integer, "null"]` as `EngineRunView` does; `status` enum `PREPARED|POSTED|REJECTED|REPLACED`). Responses: 200/201 with the schema; 403, 404, 409, 422 with `ProblemDetail` as the engine paths. Copy the engine paths' structure exactly.

- [ ] **Step 5: Run** `FinaccountingContractTest` and `FinaccountingSpecParsesTest`: pass. Then commit: `git commit -am "feat(ifrs17): I5b -- expense allocation over HTTP, in the OpenAPI, contract-tested"` (add the new controller file).

---

### Task 6: Console

**Files:**
- Modify: `frontend/src/api/types.ts`, `frontend/src/api/ifrs17Engine.ts`
- Create: `frontend/src/store/expenseAllocationStore.ts`, `frontend/src/features/finance/expenseAllocation.ts`, `frontend/src/features/finance/expenseAllocation.test.ts`, `frontend/src/features/finance/ExpenseAllocationPage.tsx`, `frontend/src/features/finance/ExpenseAllocationSection.tsx`
- Modify: `frontend/src/features/finance/EnginePage.tsx`, `frontend/src/lazyPages.ts(x)` and `frontend/src/screens.tsx` (route `ifrs17-engine/allocations/:allocationId`, `reach: 'drill-in'`, next to `ifrs17-engine/runs/:runId`)

**Interfaces:**
- Produces accessible names the e2e uses: section `Expense allocation`; inputs `Maintenance`, `Claims handling`, `Acquisition`, `Study reference`, `Note`; checkbox `No allocation this month`; input `Reason there is none`; table `Allocation preview`; button `Prepare allocation`; table `Expense allocations` with a link per allocation named `<period> · <total>`; page heading `Expense allocation <period>`; checkbox `Approve above the pool`; buttons `Approve and post`, `Reject`; input `Reason to reject`; table `Allocation lines`; text `A finance approver other than you will approve or reject it.`; "Create extract" disabled with text starting `Step 5 first`.

- [ ] **Step 1: Types and API**

```ts
// types.ts
export interface ExpenseAllocationLine {
  group: string; measurementModel: string; category: 'MAINTENANCE' | 'CLAIMS_HANDLING' | 'ACQUISITION';
  account: string; driver: 'IN_FORCE' | 'CLAIMS' | 'ISSUED' | 'EQUAL'; driverCount: number; amount: number;
}
export interface ExpenseAllocationPreview { period: string; pool: number; total: number; overPool: boolean; lines: ExpenseAllocationLine[] }
export interface ExpenseAllocationView {
  allocationId: string; period: string; status: 'PREPARED' | 'POSTED' | 'REJECTED' | 'REPLACED';
  maintenance: number; claimsHandling: number; acquisition: number; total: number; currency: string;
  studyReference: string | null; note: string | null; nilReason: string | null; pool: number | null; overPool: boolean;
  preparedBy: string; preparedAt: string; decidedBy: string | null; decidedAt: string | null; decisionReason: string | null;
  replacesId: string | null; journalEntryId: string | null; reversalJournalId: string | null; staleExtract: number | null;
  lines: ExpenseAllocationLine[];
}
export interface ExpenseAllocationInput {
  maintenance: number; claimsHandling: number; acquisition: number; studyReference?: string; note?: string; nilReason?: string;
}
```
```ts
// ifrs17Engine.ts -- P-19 (IFRS 17 I5b)
export function previewAllocation(period: string, m: number, c: number, a: number): Promise<ExpenseAllocationPreview> {
  return get<ExpenseAllocationPreview>(`/ifrs17/periods/${encodeURIComponent(period)}/expense-allocation-preview`, {
    params: { maintenance: String(m), claimsHandling: String(c), acquisition: String(a) },
  });
}
export function prepareAllocation(period: string, input: ExpenseAllocationInput): Promise<ExpenseAllocationView> {
  return post<ExpenseAllocationView>(`/ifrs17/periods/${encodeURIComponent(period)}/expense-allocations`, input);
}
export function listAllocations(period: string): Promise<ExpenseAllocationView[]> {
  return get<ExpenseAllocationView[]>(`/ifrs17/periods/${encodeURIComponent(period)}/expense-allocations`);
}
export function getAllocation(id: string): Promise<ExpenseAllocationView> {
  return get<ExpenseAllocationView>(`/ifrs17/expense-allocations/${encodeURIComponent(id)}`);
}
export function approveAllocation(id: string, aboveThePool: boolean): Promise<ExpenseAllocationView> {
  return post<ExpenseAllocationView>(`/ifrs17/expense-allocations/${encodeURIComponent(id)}/approval`, { aboveThePool });
}
export function rejectAllocation(id: string, reason: string): Promise<ExpenseAllocationView> {
  return post<ExpenseAllocationView>(`/ifrs17/expense-allocations/${encodeURIComponent(id)}/rejection`, { reason });
}
```

- [ ] **Step 2: Helpers with a failing vitest**

```ts
// expenseAllocation.test.ts
import { describe, expect, it } from 'vitest';
import { canDecideAllocation, extractGate, parseAmount } from './expenseAllocation';

describe('expense allocation helpers', () => {
  it('reads an amount of at most two decimals, zero when blank', () => {
    expect(parseAmount('')).toBe(0);
    expect(parseAmount('1,500.25')).toBe(1500.25);
    expect(parseAmount('1.234')).toBeNull();
    expect(parseAmount('-1')).toBeNull();
    expect(parseAmount('abc')).toBeNull();
  });
  it('lets the extract through only with a posted allocation', () => {
    expect(extractGate([])).toMatch(/^Step 5 first/);
    expect(extractGate([{ status: 'PREPARED' }])).toMatch(/^Step 5 first/);
    expect(extractGate([{ status: 'REPLACED' }, { status: 'POSTED' }])).toBeNull();
  });
  it('is decided by an approver who did not prepare it, while prepared', () => {
    const a = { status: 'PREPARED', preparedBy: 'u1' } as const;
    expect(canDecideAllocation(a, 'u2', true)).toBe(true);
    expect(canDecideAllocation(a, 'u1', true)).toBe(false);
    expect(canDecideAllocation(a, 'u2', false)).toBe(false);
    expect(canDecideAllocation({ status: 'POSTED', preparedBy: 'u1' }, 'u2', true)).toBe(false);
  });
});
```
```ts
// expenseAllocation.ts
/** P-19 (IFRS 17 I5b): the console's rules for the month's expense allocation. */
export const ALLOCATION_STATUS_LABEL: Record<string, string> = {
  PREPARED: 'Awaiting approval', POSTED: 'Posted', REJECTED: 'Rejected', REPLACED: 'Replaced',
};
export const CATEGORY_LABEL: Record<string, string> = {
  MAINTENANCE: 'Maintenance', CLAIMS_HANDLING: 'Claims handling', ACQUISITION: 'Acquisition',
};
export const DRIVER_LABEL: Record<string, string> = {
  IN_FORCE: 'policies in force', CLAIMS: 'claims notified', ISSUED: 'policies issued', EQUAL: 'equal shares',
};

/** An amount typed by finance: blank is zero; refused (null) when negative, not a number or over two decimals. */
export function parseAmount(text: string): number | null {
  const t = text.replace(/,/g, '').trim();
  if (t === '') return 0;
  if (!/^\d+(\.\d{1,2})?$/.test(t)) return null;
  return Number(t);
}

/** Why "Create extract" waits, or null when the month has a posted allocation. */
export function extractGate(allocations: { status: string }[]): string | null {
  return allocations.some((a) => a.status === 'POSTED')
    ? null
    : 'Step 5 first: the month needs a posted expense allocation (or a recorded "none this month") before its extract.';
}

export function canDecideAllocation(
  a: { status: string; preparedBy: string }, viewer: string | null | undefined, approver: boolean,
): boolean {
  return approver && a.status === 'PREPARED' && viewer != null && viewer !== a.preparedBy;
}
```
Run `npx vitest run expenseAllocation` (from `frontend/`): fail before the file exists, pass after.

- [ ] **Step 3: Store** (`expenseAllocationStore.ts`), same `run`/`track` pattern as `engineStore.ts`: state `allocations: Resource<ExpenseAllocationView[]>`, `preview: Resource<ExpenseAllocationPreview>`, `current: Resource<ExpenseAllocationView>`, `acting`; actions `loadPeriod(period)`, `previewTotals(period, m, c, a)` (keyed `preview`), `prepare(period, input): Promise<boolean>` (reloads the period), `load(id)`, `approve(id, aboveThePool)`, `reject(id, reason)` (both refresh `current`).

- [ ] **Step 4: `ExpenseAllocationSection`** (rendered by `EnginePage` above the Extracts section, given `period`):
  - `<section aria-label="Expense allocation">` titled "Expense allocation (step 5)".
  - A table `aria-label="Expense allocations"`: per allocation a `Link` to `allocations/${id}` with text `${period} · ${money(total)}`, its status label, prepared by/at.
  - The prepare form (`aria-label="Prepare expense allocation"`): `FormField` inputs Maintenance, Claims handling, Acquisition, Study reference, Note; a checkbox "No allocation this month" which swaps the amounts for an input "Reason there is none". Typing an amount calls `previewTotals` from the `onChange` handler through a 400 ms timeout held in a `useRef` (not in an effect -- the lint bans setState in effect bodies). When the preview is loaded and the total is positive: a line "Pool <money> · total <money>", a warning `role="status"` "The total is above the month's pool of <money>; the approver must approve above the pool" when `overPool`, and a table `aria-label="Allocation preview"` (group, category label, account, driver label with count, amount). Submit button "Prepare allocation", disabled while any amount is invalid; errors through `InlineError`.
  - `EnginePage`: load the allocations with the period (call the allocation store's `loadPeriod` beside the engine store's in the same effect), compute `const gate = extractGate(allocations.data ?? [])`, disable "Create extract" when `gate` and show `<p className="text-xs text-muted-foreground">{gate}</p>` next to it.

- [ ] **Step 5: `ExpenseAllocationPage`** (`ifrs17-engine/allocations/:allocationId`): loads by id; `PageHeader` title `Expense allocation ${period}`, description `${ALLOCATION_STATUS_LABEL[status]} · total ${money(total)} · pool ${money(pool)}`; an "About" section (study reference or nil reason, note, prepared/decided by and when, decision reason, "Replaces an earlier allocation" link); `staleExtract` notice "Extract #N predates this allocation; create a new extract"; the over-pool warning; table `aria-label="Allocation lines"` (group, category, account, driver, amount) plus a total row "8490 credit"; when `canDecideAllocation(...)`: a checkbox "Approve above the pool" (shown only when `overPool`), button "Approve and post" (disabled when over the pool and unticked), a "Reason to reject" input with "Reject"; when the viewer prepared it and it is PREPARED: "A finance approver other than you will approve or reject it." Register it in `lazyPages` and `screens.tsx` beside `EngineRunPage`.

- [ ] **Step 6: Checks** -- from `frontend/`: `npx tsc -b --noEmit`, `npx eslint src`, `npx vitest run expenseAllocation enginePeriod`. All clean. Commit: `git add -A frontend && git commit -m "feat(console): I5b -- the expense allocation step on the IFRS 17 engine page, and its approval page"`

---

### Task 7: e2e, dev stack, gate, merge

- [ ] **Spec update** `frontend/e2e/staff-ifrs17-engine.spec.ts`: after "Start closing", on the engine page tick "No allocation this month", fill "Reason there is none" (`E2E: nothing attributable`), click "Prepare allocation", open the allocation link in the "Expense allocations" table, keep its URL; the approver (`approverPage`) opens it and clicks "Approve and post", sees "Posted"; finance goes back to `/staff/ifrs17-engine?period=…` and continues as before (Create extract is now enabled).
- [ ] **New spec** `frontend/e2e/staff-ifrs17-expense-allocation.spec.ts`: the same `untouchedPeriod` helper (month before the earliest known period; copy it -- or move it to `frontend/e2e/periods.ts` and import it from both specs); start closing; engine page: Maintenance `1000.00`, Claims handling `200.00`, Acquisition `300.00`, Study reference `E2E study`; expect the "above the month's pool" warning and an "Allocation preview" table; "Prepare allocation"; open the allocation; expect "A finance approver other than you…"; approver opens the URL, ticks "Approve above the pool", "Approve and post", expects description starting `Posted`, the "Allocation lines" table containing `5210`, and `1,500.00`; finance locks the month on the periods page (two clicks on "Lock period") and sees "Locked by".
- [ ] **Dev:** apply `finaccounting/V16` with `docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 --single-transaction < db-migrations/finaccounting/V16__expense_allocation.sql`; restart the dev backend and Vite from `.worktrees/ifrs17-i5b` (stop the old ones and any orphan JVM on 8080 / node on 5173 first).
- [ ] **Run the two specs**, then **verify in the DB** that the allocation posted (journal with `expense_allocation_id`, 8490 credit 1,500.00) and both months are LOCKED.
- [ ] **Gate:** dev backend stopped; `./mvnw -B -o clean test` with every class under `finaccounting/` and `reinsurance/` plus `ModularityTests` as FQNs (expect 42 reports -- 40 before plus `ExpenseAllocationSplitTest`, `ExpenseAllocationIntegrationTest`); console `tsc`, `eslint`, full `vitest`; full Playwright suite with the dev stack up (no Maven running).
- [ ] **Merge:** `git merge --no-ff ifrs17-i5b -m "Merge IFRS 17 I5b: expense allocation (P-19)"` in the main checkout; `git push origin main && git push origin ifrs17-i5b`; update memory (`project_ifrs17_i5b_decisions.md`, MEMORY.md); next is I6 (month-end checklist) per the IFRS 17 plan.
