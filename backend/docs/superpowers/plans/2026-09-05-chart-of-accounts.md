# Chart of Accounts as a First-Class Structure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn `finaccounting.chart_of_account` from nine flat rows into a 36-account hierarchy with parent/level/status/posting-allowed/currency/control-account/description, migrate the existing ledger onto the new codes without orphaning a posting, and give staff an expand/collapse tree view and a searchable, sortable table view.

**Architecture:** The 36-account tree is defined **once** in Java (`ChartOfAccountBlueprint`) and duplicated in the V5 migration SQL, with a test asserting the two agree so they cannot drift. The domain entity gains the new fields plus factory methods that derive `level` and enforce the code-prefix rule; `FinaccountingApiImpl.postEntry` becomes the single enforcement point that refuses a leg targeting a header, an `INACTIVE` account, or a currency mismatch. The frontend keeps the existing flat unpaged `GET` and assembles the tree client-side in pure functions, so the Tree/Table toggle is instant and testable without rendering.

**Tech Stack:** Spring Boot / Spring Data JPA / Flyway-style SQL migrations / Testcontainers Postgres 16 (backend); React 19 / TypeScript / Zustand / react-hook-form / Zod / lucide-react / Playwright / Vitest (frontend).

**Design doc:** `backend/docs/superpowers/specs/2026-09-05-chart-of-accounts-design.md`

## Global Constraints

- **Every account remains a Finance sign-off PLACEHOLDER.** No document on this platform specifies real account codes. Every new file that names accounts carries that flag in its javadoc/comment, exactly as `PostingRule` and `ChartOfAccountSeeder` already do.
- **`accountType` and `normalBalance` stay DERIVED** from the account code's leading digit via `PostingRule.accountTypeFor` / `PostingRule.normalBalanceFor`. They are never stored as independently settable values and never accepted on a request body.
- **Account codes match `^[1-5]\d{3}$`** — 4 digits, leading block 1=ASSET, 2=LIABILITY, 3=EQUITY, 4=INCOME, 5=EXPENSE.
- **Journal entries and GL postings stay READ-ONLY, permanently.** No task adds a write endpoint for `journal_entry` or `gl_posting`. A correction is a future reversal entry, never an edit.
- **`4xxx` income accounts are seeded but nothing posts to them.** Premium earning is LRC release, which is C1-blocked (Actuarial). No task adds a `4xxx` posting rule.
- **All `/chart-of-accounts*` endpoints stay gated** `hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))`.
- **Every module `V*.sql` file is immutable once applied.** V1–V4 are never edited; this work is **V5**.
- **Never run Maven in Docker here** — use `./mvnw` on the host. After any record/DTO signature change run `./mvnw clean test-compile`, because incremental compile hides breaks in tests Maven does not recompile.
- **Never run Prettier.** It is not a dependency and has no config; it reformats the whole tree.
- **The dev DB and migrations are decoupled.** A green Testcontainers run does not prove the dev database is in sync. V5 needs a manual `psql` apply plus a backend restart before real-stack e2e reflects it.
- **e2e couples tightly to accessible names.** Any change to a rendered label, or to the class combination `staff-finaccounting.spec.ts`'s `accountRow()` helper selects on, breaks specs. e2e updates are part of the task that causes them, never a follow-up.

---

### Task 1: The blueprint and the domain entity

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/ChartOfAccountBlueprint.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/api/AccountStatus.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/ChartOfAccount.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/ChartOfAccountBlueprintTest.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/ChartOfAccountTest.java`

**Interfaces:**
- Consumes: `AccountType`, `PostingDirection`, `PostingRule.accountTypeFor(String)`, `PostingRule.normalBalanceFor(String)`, `FinaccountingValidationException` — all existing.
- Produces:
  - `ChartOfAccountBlueprint.Seed(String code, String name, String parentCode, boolean postingAllowed, String controlOf)`
  - `ChartOfAccountBlueprint.accounts()` → `List<Seed>`, 36 entries, parents strictly before children
  - `ChartOfAccountBlueprint.legacyRemap()` → `Map<String, String>`, 9 entries, old code → new code
  - `AccountStatus` enum: `ACTIVE`, `INACTIVE`
  - `ChartOfAccount.root(UUID, String code, String name, boolean postingAllowed, String currency, String createdBy)`
  - `ChartOfAccount.childOf(ChartOfAccount parent, String code, String name, boolean postingAllowed, String currency, String controlOf, String description, String createdBy)`
  - `ChartOfAccount.significantPrefix(String code)` → `String` (static)
  - Instance methods: `getParentCode()`, `getLevel()`, `isPostingAllowed()`, `getStatus()`, `getCurrency()`, `getControlOf()`, `getDescription()`, `becomeHeader(String updatedBy)`, `deactivate(String updatedBy)`, `activate(String updatedBy)`, `describe(String description, String updatedBy)`

- [ ] **Step 1: Write the failing blueprint test**

Create `ChartOfAccountBlueprintTest.java`:

```java
package tz.co.nlolo.lifeplatform.finaccounting;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint.Seed;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRule;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChartOfAccountBlueprintTest {

    @Test
    void seedsThirtySixAccounts() {
        assertThat(ChartOfAccountBlueprint.accounts()).hasSize(36);
    }

    @Test
    void everyParentAppearsBeforeItsChildren() {
        Set<String> seen = new HashSet<>();
        for (Seed seed : ChartOfAccountBlueprint.accounts()) {
            if (seed.parentCode() != null) {
                assertThat(seen)
                    .as("parent %s of %s must be inserted first", seed.parentCode(), seed.code())
                    .contains(seed.parentCode());
            }
            seen.add(seed.code());
        }
    }

    @Test
    void everyChildCodeSitsInsideItsParentsBlock() {
        for (Seed seed : ChartOfAccountBlueprint.accounts()) {
            if (seed.parentCode() == null) continue;
            String prefix = tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccount
                .significantPrefix(seed.parentCode());
            assertThat(seed.code())
                .as("%s must sit inside %s's block", seed.code(), seed.parentCode())
                .startsWith(prefix);
        }
    }

    @Test
    void noAccountWithChildrenAllowsPosting() {
        Set<String> parents = ChartOfAccountBlueprint.accounts().stream()
            .map(Seed::parentCode).filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        for (Seed seed : ChartOfAccountBlueprint.accounts()) {
            if (parents.contains(seed.code())) {
                assertThat(seed.postingAllowed())
                    .as("%s has children and must be a header", seed.code()).isFalse();
            }
        }
    }

    /** Every code PostingRule posts to must exist in the blueprint and accept postings --
     *  this is what fk_gl_posting_account_code enforces at runtime, asserted here at build time. */
    @Test
    void everyPostingRuleTargetIsAPostableBlueprintAccount() {
        var postable = ChartOfAccountBlueprint.accounts().stream()
            .filter(Seed::postingAllowed).map(Seed::code).collect(java.util.stream.Collectors.toSet());
        assertThat(postable).contains(
            PostingRule.CASH, PostingRule.PREMIUM_RECEIVABLE, PostingRule.REINSURANCE_RECOVERABLE,
            PostingRule.POLICY_LOAN_RECEIVABLE, PostingRule.UNEARNED_PREMIUM,
            PostingRule.REINSURANCE_PAYABLE, PostingRule.CLAIMS_EXPENSE,
            PostingRule.COMMISSION_EXPENSE, PostingRule.REINSURANCE_CEDED_PREMIUM);
    }

    @Test
    void everyRemapTargetExistsInTheBlueprint() {
        var codes = ChartOfAccountBlueprint.accounts().stream()
            .map(Seed::code).collect(java.util.stream.Collectors.toSet());
        assertThat(codes).containsAll(ChartOfAccountBlueprint.legacyRemap().values());
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd backend && ./mvnw test -Dtest=ChartOfAccountBlueprintTest`
Expected: FAIL — compilation error, `ChartOfAccountBlueprint` does not exist.

- [ ] **Step 3: Write `AccountStatus`**

Create `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/api/AccountStatus.java`:

```java
package tz.co.nlolo.lifeplatform.finaccounting.api;

/**
 * Whether an account may still receive new postings.
 *
 * <p>INACTIVE is the RETIREMENT path, and is distinct from deletion: historical postings must
 * stay mappable to the account they were booked to (finaccounting/V3's own comment says exactly
 * this), so an account that has ever been posted against is deactivated, never deleted.
 */
public enum AccountStatus { ACTIVE, INACTIVE }
```

- [ ] **Step 4: Write `ChartOfAccountBlueprint`**

Create `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/ChartOfAccountBlueprint.java`:

```java
package tz.co.nlolo.lifeplatform.finaccounting.domain;

import java.util.List;
import java.util.Map;

/**
 * THE canonical chart of accounts, defined once.
 *
 * <p><b>EVERY ACCOUNT BELOW IS A PLACEHOLDER pending FINANCE sign-off</b> -- the same treatment
 * M9 gave the original nine, and M2/M7/M8 gave underwriting, commission and cession. No document
 * on this platform specifies account codes or their debit/credit treatment.
 *
 * <p>finaccounting/V5 duplicates this list in SQL, because a migration cannot call Java.
 * {@code ChartOfAccountMigrationV5Test} asserts the two agree exactly, so they cannot drift.
 *
 * <p><b>The 4xxx income accounts are seeded and will stay at zero.</b> Premium is EARNED through
 * LRC release, which is C1-blocked (Actuarial), so no {@link PostingRule} entry targets them.
 * They exist for chart completeness; seeding them is not an un-deferral of IFRS 17 measurement.
 */
public final class ChartOfAccountBlueprint {

    private ChartOfAccountBlueprint() {}

    /** One seeded account. {@code parentCode} is null only for the five block roots. */
    public record Seed(String code, String name, String parentCode,
                        boolean postingAllowed, String controlOf) {}

    private static Seed header(String code, String name, String parentCode) {
        return new Seed(code, name, parentCode, false, null);
    }

    private static Seed post(String code, String name, String parentCode) {
        return new Seed(code, name, parentCode, true, null);
    }

    private static Seed post(String code, String name, String parentCode, String controlOf) {
        return new Seed(code, name, parentCode, true, controlOf);
    }

    private static final List<Seed> ACCOUNTS = List.of(
        header("1000", "Assets", null),
        header("1100", "Cash and Cash Equivalents", "1000"),
        post("1110", "Main Bank Account", "1100"),
        post("1120", "Mobile Money", "1100"),
        post("1130", "Petty Cash", "1100"),
        header("1200", "Receivables", "1000"),
        post("1210", "Premium Receivables", "1200", "BILLING"),
        post("1220", "Agent Receivables", "1200", "DISTRIBUTION"),
        post("1230", "Other Receivables", "1200"),
        post("1240", "Reinsurance Recoverable", "1200", "REINSURANCE"),
        post("1250", "Policy Loan Receivables", "1200", "POLICYLOAN"),
        post("1300", "Investments", "1000"),
        header("2000", "Liabilities", null),
        header("2100", "Insurance Liabilities", "2000"),
        post("2110", "Claims Payable", "2100", "CLAIMS"),
        post("2120", "Premiums Received in Advance", "2100"),
        post("2130", "Policyholder Benefits Payable", "2100"),
        post("2140", "Unearned Premium", "2100"),
        header("2200", "Payables", "2000"),
        post("2210", "Agent Commissions Payable", "2200", "DISTRIBUTION"),
        post("2220", "Reinsurance Payable", "2200", "REINSURANCE"),
        post("2300", "Other Liabilities", "2000"),
        header("3000", "Equity", null),
        post("3100", "Share Capital", "3000"),
        post("3200", "Retained Earnings", "3000"),
        post("3300", "Current Year Profit/Loss", "3000"),
        header("4000", "Income", null),
        post("4100", "Premium Income", "4000"),
        post("4200", "Investment Income", "4000"),
        post("4300", "Other Income", "4000"),
        header("5000", "Expenses", null),
        post("5100", "Claims Expense", "5000"),
        post("5200", "Commission Expense", "5000"),
        post("5300", "Operating Expenses", "5000"),
        post("5400", "Other Expenses", "5000"),
        post("5500", "Reinsurance Ceded Premium", "5000"));

    /** In insertion order: every parent appears before its children, so a caller may insert
     *  straight down the list without violating the self-referencing foreign key. */
    public static List<Seed> accounts() {
        return ACCOUNTS;
    }

    /**
     * The nine M9 codes, mapped to where their postings belong in this chart.
     *
     * <p><b>Three of these ROTATE</b> -- 5000 to 5100, 5100 to 5200, 5200 to 5500 -- which is why
     * finaccounting/V5 must drop {@code fk_gl_posting_account_code} for the duration of the remap
     * rather than updating rows in place.
     */
    public static Map<String, String> legacyRemap() {
        return Map.of(
            "1000", "1120",
            "1200", "1210",
            "1300", "1240",
            "1400", "1250",
            "2200", "2140",
            "2300", "2220",
            "5000", "5100",
            "5100", "5200",
            "5200", "5500");
    }

    /** The tenant functional currency every account is seeded with. */
    public static final String SEED_CURRENCY = "TZS";
}
```

- [ ] **Step 5: Write the failing domain test**

Create `ChartOfAccountTest.java`:

```java
package tz.co.nlolo.lifeplatform.finaccounting;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccount;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChartOfAccountTest {

    private static final UUID TENANT = UUID.randomUUID();

    private static ChartOfAccount receivables() {
        return ChartOfAccount.root(TENANT, "1200", "Receivables", false, "TZS", "test");
    }

    @Test
    void significantPrefixStripsTrailingZeros() {
        assertThat(ChartOfAccount.significantPrefix("1000")).isEqualTo("1");
        assertThat(ChartOfAccount.significantPrefix("1200")).isEqualTo("12");
        assertThat(ChartOfAccount.significantPrefix("1210")).isEqualTo("121");
    }

    @Test
    void aRootIsLevelOneAndHasNoParent() {
        ChartOfAccount root = ChartOfAccount.root(TENANT, "1000", "Assets", false, "TZS", "test");
        assertThat(root.getLevel()).isEqualTo((short) 1);
        assertThat(root.getParentCode()).isNull();
        assertThat(root.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void aChildIsOneLevelDeeperThanItsParent() {
        ChartOfAccount child = ChartOfAccount.childOf(
            receivables(), "1210", "Premium Receivables", true, "TZS", "BILLING", null, "test");
        assertThat(child.getLevel()).isEqualTo((short) 2);
        assertThat(child.getParentCode()).isEqualTo("1200");
        assertThat(child.getControlOf()).isEqualTo("BILLING");
    }

    @Test
    void typeAndNormalBalanceStayDerivedFromTheLeadingDigit() {
        ChartOfAccount child = ChartOfAccount.childOf(
            receivables(), "1210", "Premium Receivables", true, "TZS", null, null, "test");
        assertThat(child.getAccountType()).isEqualTo(AccountType.ASSET);
        assertThat(child.getNormalBalance()).isEqualTo(PostingDirection.DR);
    }

    @Test
    void aChildOutsideItsParentsBlockIsRejected() {
        assertThatThrownBy(() -> ChartOfAccount.childOf(
                receivables(), "2110", "Claims Payable", true, "TZS", null, null, "test"))
            .isInstanceOf(FinaccountingValidationException.class)
            .hasMessageContaining("2110")
            .hasMessageContaining("1200");
    }

    @Test
    void anAccountMayNotBeItsOwnChild() {
        assertThatThrownBy(() -> ChartOfAccount.childOf(
                receivables(), "1200", "Receivables again", true, "TZS", null, null, "test"))
            .isInstanceOf(FinaccountingValidationException.class);
    }

    @Test
    void becomingAHeaderRevokesPosting() {
        ChartOfAccount leaf = ChartOfAccount.root(TENANT, "1300", "Investments", true, "TZS", "test");
        assertThat(leaf.isPostingAllowed()).isTrue();
        leaf.becomeHeader("test");
        assertThat(leaf.isPostingAllowed()).isFalse();
        assertThat(leaf.getUpdatedBy()).isEqualTo("test");
    }

    @Test
    void deactivateAndActivateFlipStatus() {
        ChartOfAccount account = ChartOfAccount.root(TENANT, "1300", "Investments", true, "TZS", "test");
        account.deactivate("retiring");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.INACTIVE);
        account.activate("reinstating");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void anInactiveHeaderRefusesPostingForBothReasonsIndependently() {
        ChartOfAccount header = ChartOfAccount.root(TENANT, "1000", "Assets", false, "TZS", "test");
        assertThat(header.acceptsPostings()).isFalse();

        ChartOfAccount inactiveLeaf = ChartOfAccount.root(TENANT, "1300", "Investments", true, "TZS", "test");
        inactiveLeaf.deactivate("test");
        assertThat(inactiveLeaf.acceptsPostings()).isFalse();
    }
}
```

- [ ] **Step 6: Run it to make sure it fails**

Run: `cd backend && ./mvnw test -Dtest=ChartOfAccountTest`
Expected: FAIL — compilation error, `ChartOfAccount.root` / `childOf` / `significantPrefix` do not exist.

- [ ] **Step 7: Extend `ChartOfAccount`**

Replace the body of `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/ChartOfAccount.java`. Keep the existing `tenantId`, `accountCode`, `name`, `accountType`, `normalBalance`, `createdAt`, `createdBy`, `updatedAt`, `updatedBy` fields and their getters, keep `rename(...)`, and add:

```java
    @Column(name = "parent_code")
    private String parentCode;

    @Column(name = "level", nullable = false)
    private short level;

    @Column(name = "posting_allowed", nullable = false)
    private boolean postingAllowed;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "control_of")
    private String controlOf;

    @Column(name = "description")
    private String description;

    /**
     * A block root -- {@code parentCode} null, {@code level} 1. Used by the seeder for the five
     * 1000/2000/3000/4000/5000 rows and by {@code createAccount} when no parent is supplied.
     */
    public static ChartOfAccount root(UUID tenantId, String accountCode, String name,
                                       boolean postingAllowed, String currency, String createdBy) {
        ChartOfAccount account = new ChartOfAccount(tenantId, accountCode, name,
            PostingRule.accountTypeFor(accountCode), PostingRule.normalBalanceFor(accountCode), createdBy);
        account.level = 1;
        account.postingAllowed = postingAllowed;
        account.currency = currency;
        return account;
    }

    /**
     * A child of {@code parent}, one level deeper.
     *
     * <p><b>The code-prefix rule is what keeps the numbering and the hierarchy from
     * contradicting each other.</b> A child's code must begin with its parent's SIGNIFICANT
     * prefix -- the parent's code with trailing zeros stripped -- so 1210 may hang off 1200 but
     * 2110 may not. Because a child's code always strictly extends its parent's prefix, a cycle
     * is structurally impossible and needs no separate check.
     *
     * <p>The rule deliberately does NOT forbid SKIPPING a level: 1110 may attach directly to
     * 1000. A skipped level is a flat branch, not an inconsistency.
     *
     * @throws FinaccountingValidationException if {@code accountCode} falls outside the parent's
     *         block, or equals the parent's own code
     */
    public static ChartOfAccount childOf(ChartOfAccount parent, String accountCode, String name,
                                          boolean postingAllowed, String currency, String controlOf,
                                          String description, String createdBy) {
        if (accountCode.equals(parent.accountCode)) {
            throw new FinaccountingValidationException(
                "An account cannot be its own parent: " + accountCode);
        }
        String prefix = significantPrefix(parent.accountCode);
        if (!accountCode.startsWith(prefix)) {
            throw new FinaccountingValidationException("Account " + accountCode
                + " cannot hang off " + parent.accountCode + ": a child's code must begin with \""
                + prefix + "\"");
        }
        ChartOfAccount account = new ChartOfAccount(tenantIdOf(parent), accountCode, name,
            PostingRule.accountTypeFor(accountCode), PostingRule.normalBalanceFor(accountCode), createdBy);
        account.parentCode = parent.accountCode;
        account.level = (short) (parent.level + 1);
        account.postingAllowed = postingAllowed;
        account.currency = currency;
        account.controlOf = controlOf;
        account.description = description;
        return account;
    }

    private static UUID tenantIdOf(ChartOfAccount parent) {
        return parent.tenantId;
    }

    /** The parent's code with trailing zeros stripped: 1000 -> "1", 1200 -> "12", 1210 -> "121". */
    public static String significantPrefix(String accountCode) {
        int end = accountCode.length();
        while (end > 1 && accountCode.charAt(end - 1) == '0') {
            end--;
        }
        return accountCode.substring(0, end);
    }

    /** True only when this account may receive a new posting leg -- both conditions, independently. */
    public boolean acceptsPostings() {
        return postingAllowed && status == AccountStatus.ACTIVE;
    }

    /** Called when this account gains its first child: a parent never receives postings. */
    public void becomeHeader(String updatedBy) {
        this.postingAllowed = false;
        touch(updatedBy);
    }

    public void deactivate(String updatedBy) {
        this.status = AccountStatus.INACTIVE;
        touch(updatedBy);
    }

    public void activate(String updatedBy) {
        this.status = AccountStatus.ACTIVE;
        touch(updatedBy);
    }

    public void describe(String description, String updatedBy) {
        this.description = description;
        touch(updatedBy);
    }

    private void touch(String updatedBy) {
        this.updatedAt = Instant.now();
        this.updatedBy = updatedBy;
    }

    public String getParentCode() { return parentCode; }
    public short getLevel() { return level; }
    public boolean isPostingAllowed() { return postingAllowed; }
    public AccountStatus getStatus() { return status; }
    public String getCurrency() { return currency; }
    public String getControlOf() { return controlOf; }
    public String getDescription() { return description; }
```

Add the imports `tz.co.nlolo.lifeplatform.finaccounting.api.AccountStatus` and `tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException`. Refactor the existing `rename(...)` to call `touch(updatedBy)` rather than setting `updatedAt`/`updatedBy` inline.

- [ ] **Step 8: Run both tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest='ChartOfAccountBlueprintTest,ChartOfAccountTest'`
Expected: PASS, all tests green.

- [ ] **Step 9: Verify nothing else broke**

Run: `cd backend && ./mvnw clean test-compile`
Expected: BUILD SUCCESS. (`clean` is required — the existing `ChartOfAccount` constructor is unchanged, but incremental compile would hide a break in any test Maven does not recompile.)

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/ backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/
git commit -m "feat(finaccounting): define the chart once, and give an account a parent"
```

---

### Task 2: The V5 migration

**Files:**
- Create: `backend/db-migrations/finaccounting/V5__chart_of_account_hierarchy.sql`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/ChartOfAccountMigrationV5Test.java`

**Interfaces:**
- Consumes: `ChartOfAccountBlueprint.accounts()`, `ChartOfAccountBlueprint.legacyRemap()`, `ChartOfAccountBlueprint.SEED_CURRENCY` (Task 1); `MigrationTestSupport.applyMigration(String, String, String, String...)` (existing).
- Produces: the migrated schema every later task reads. No new Java API.

- [ ] **Step 1: Write the failing migration test**

Create `ChartOfAccountMigrationV5Test.java`. It seeds the OLD nine accounts, writes a posting to each, applies V5, and asserts every posting resolves to the correct new code:

```java
package tz.co.nlolo.lifeplatform.finaccounting;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint.Seed;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The remap is the highest-risk step in this whole change: three of the nine legacy codes ROTATE
 * (5000 to 5100, 5100 to 5200, 5200 to 5500), so a posting that lands on the wrong row is a
 * financial misstatement that still balances. This test is the reason that cannot ship silently.
 */
@Testcontainers
class ChartOfAccountMigrationV5Test {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @BeforeAll
    static void migrateAndSeedLegacyData() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            "db-migrations/finaccounting/V1__create_finaccounting_schema.sql",
            "db-migrations/finaccounting/V2__grants_rls_chart_of_accounts_journal_entry_and_posting_columns.sql",
            "db-migrations/finaccounting/V3__account_code_foreign_key.sql",
            "db-migrations/finaccounting/V4__chart_of_account_writable_via_api.sql");

        // TWO tenants, deliberately: the migration must be generic over tenant_id, and a
        // single-tenant fixture would let a hardcoded id pass.
        for (UUID tenant : new UUID[] { TENANT_A, TENANT_B }) {
            seedLegacyChart(tenant);
            for (String oldCode : ChartOfAccountBlueprint.legacyRemap().keySet()) {
                writePosting(tenant, oldCode);
            }
        }

        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            "db-migrations/finaccounting/V5__chart_of_account_hierarchy.sql");
    }

    private static void seedLegacyChart(UUID tenant) throws Exception {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO finaccounting.chart_of_account "
                 + "(tenant_id, account_code, name, account_type, normal_balance, created_by) "
                 + "VALUES (?, ?, ?, ?, ?, 'legacy-seed')")) {
            for (String code : ChartOfAccountBlueprint.legacyRemap().keySet()) {
                ps.setObject(1, tenant);
                ps.setString(2, code);
                ps.setString(3, "Legacy " + code);
                ps.setString(4, switch (code.charAt(0)) {
                    case '1' -> "ASSET"; case '2' -> "LIABILITY"; case '5' -> "EXPENSE";
                    default -> throw new IllegalStateException(code); });
                ps.setString(5, code.startsWith("2") ? "CR" : "DR");
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** One journal entry plus one posting leg carrying {@code accountCode}, so the remap has
     *  something real to move and the recreated foreign key has something real to validate. */
    private static void writePosting(UUID tenant, String accountCode) throws Exception {
        try (Connection c = connect()) {
            UUID entryId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO finaccounting.journal_entry "
                    + "(tenant_id, source_event, source_ref, period, created_by) "
                    + "VALUES (?, ?, ?, '2026-09', 'legacy-seed') RETURNING journal_entry_id")) {
                ps.setObject(1, tenant);
                ps.setString(2, "test.Legacy" + accountCode);
                ps.setString(3, tenant + ":" + accountCode);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    entryId = rs.getObject(1, UUID.class);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO finaccounting.gl_posting "
                    + "(tenant_id, journal_entry_id, account_code, direction, amount, currency, "
                    + " period, source_event, source_ref) "
                    + "VALUES (?, ?, ?, 'DR', 100.00, 'TZS', '2026-09', ?, ?)")) {
                ps.setObject(1, tenant);
                ps.setObject(2, entryId);
                ps.setString(3, accountCode);
                ps.setString(4, "test.Legacy" + accountCode);
                ps.setString(5, tenant + ":" + accountCode);
                ps.executeUpdate();
            }
        }
    }

    @Test
    void everyLegacyPostingNowCarriesItsNewAccountCode() throws Exception {
        for (Map.Entry<String, String> remap : ChartOfAccountBlueprint.legacyRemap().entrySet()) {
            for (UUID tenant : new UUID[] { TENANT_A, TENANT_B }) {
                try (Connection c = connect();
                     PreparedStatement ps = c.prepareStatement(
                         "SELECT account_code FROM finaccounting.gl_posting "
                         + "WHERE tenant_id = ? AND source_ref = ?")) {
                    ps.setObject(1, tenant);
                    ps.setString(2, tenant + ":" + remap.getKey());
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).as("posting for legacy %s survived", remap.getKey()).isTrue();
                        assertThat(rs.getString(1))
                            .as("legacy %s must remap to %s", remap.getKey(), remap.getValue())
                            .isEqualTo(remap.getValue());
                    }
                }
            }
        }
    }

    @Test
    void noPostingWasOrphanedOrLost() throws Exception {
        int expected = ChartOfAccountBlueprint.legacyRemap().size() * 2;
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM finaccounting.gl_posting")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(expected);
        }
        // The recreated FK is the real proof: it cannot exist if any posting names a missing code.
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT count(*) FROM pg_constraint WHERE conname = 'fk_gl_posting_account_code'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void theMigratedChartMatchesTheJavaBlueprintExactly() throws Exception {
        Map<String, Seed> expected = new HashMap<>();
        ChartOfAccountBlueprint.accounts().forEach(s -> expected.put(s.code(), s));

        Map<String, Seed> actual = new HashMap<>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT account_code, name, parent_code, posting_allowed, control_of "
                 + "FROM finaccounting.chart_of_account WHERE tenant_id = ?")) {
            ps.setObject(1, TENANT_A);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    actual.put(rs.getString(1), new Seed(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getBoolean(4), rs.getString(5)));
                }
            }
        }
        assertThat(actual).as("V5's SQL and ChartOfAccountBlueprint must not drift")
            .containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void levelsAreDerivedFromTheParentChain() throws Exception {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT account_code, level FROM finaccounting.chart_of_account "
                 + "WHERE tenant_id = ? AND account_code IN ('1000','1100','1110','5500')")) {
            ps.setObject(1, TENANT_A);
            Map<String, Integer> levels = new HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) levels.put(rs.getString(1), rs.getInt(2));
            }
            assertThat(levels).containsEntry("1000", 1).containsEntry("1100", 2)
                .containsEntry("1110", 3).containsEntry("5500", 2);
        }
    }

    @Test
    void noLegacyAccountSurvives() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT count(*) FROM finaccounting.chart_of_account WHERE account_code = '1400'")) {
            rs.next();
            // 1400 is the one legacy code absent from the new chart -- if it survived, the
            // delete step did not run.
            assertThat(rs.getInt(1)).isZero();
        }
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd backend && ./mvnw test -Dtest=ChartOfAccountMigrationV5Test`
Expected: FAIL — `NoSuchFileException` on `V5__chart_of_account_hierarchy.sql`.

- [ ] **Step 3: Write the migration**

Create `backend/db-migrations/finaccounting/V5__chart_of_account_hierarchy.sql`:

```sql
-- Module: finaccounting V5 -- the chart of accounts becomes a real hierarchy.
--
-- Design: docs/superpowers/specs/2026-09-05-chart-of-accounts-design.md
--
-- EVERY ACCOUNT BELOW IS A PLACEHOLDER pending FINANCE sign-off, exactly as the
-- nine it replaces were. No document on this platform specifies account codes.
--
-- WHY THE FOREIGN KEY COMES DOWN. Eight of the nine legacy codes are reused in
-- this chart with a DIFFERENT meaning, and three of them rotate:
--   5000 Claims Expense            -> 5100
--   5100 Commission Expense        -> 5200
--   5200 Reinsurance Ceded Premium -> 5500
-- There is therefore no ordering of plain INSERTs and DELETEs that avoids a
-- primary-key collision on (tenant_id, account_code), and no ordering of
-- UPDATEs that avoids violating fk_gl_posting_account_code. The constraint is
-- dropped for the duration of the remap and recreated at the end -- and its
-- successful recreation is itself the assertion that every posting landed on a
-- code that exists. An orphan makes this migration FAIL rather than pass
-- quietly.
--
-- Every statement is generic over tenant_id. Nothing is hardcoded to a tenant,
-- for the reason V2 section 5 gave when it kept the seed INSERT out of SQL.

-- 1. The new columns. Defaults keep the nine legacy rows valid for the moment
--    they remain; steps 4-5 replace those rows outright.
ALTER TABLE finaccounting.chart_of_account ADD COLUMN parent_code VARCHAR(20);
ALTER TABLE finaccounting.chart_of_account ADD COLUMN level SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE finaccounting.chart_of_account ADD COLUMN posting_allowed BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE finaccounting.chart_of_account ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE','INACTIVE'));
ALTER TABLE finaccounting.chart_of_account ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'TZS';
ALTER TABLE finaccounting.chart_of_account ADD COLUMN control_of VARCHAR(20);
ALTER TABLE finaccounting.chart_of_account ADD COLUMN description TEXT;

ALTER TABLE finaccounting.chart_of_account
    ADD CONSTRAINT fk_chart_of_account_parent
    FOREIGN KEY (tenant_id, parent_code)
    REFERENCES finaccounting.chart_of_account (tenant_id, account_code);

CREATE INDEX idx_chart_of_account_parent
    ON finaccounting.chart_of_account (tenant_id, parent_code);

-- 2. Release the ledger. gl_posting is PARTITION BY RANGE (created_at); an
--    outgoing FK on a partitioned table is dropped once, for every partition
--    (PostgreSQL 12+), exactly as V3 noted when it added one.
ALTER TABLE finaccounting.gl_posting DROP CONSTRAINT fk_gl_posting_account_code;

-- 3. Remap every existing posting. Correlated per tenant via a VALUES list --
--    the rotation is safe here precisely because nothing constrains the column
--    right now, and because a single UPDATE evaluates its FROM against the
--    PRE-update snapshot, so 5100 -> 5200 cannot cascade onto 5200 -> 5500.
UPDATE finaccounting.gl_posting AS p
SET account_code = m.new_code
FROM (VALUES
    ('1000','1120'), ('1200','1210'), ('1300','1240'), ('1400','1250'),
    ('2200','2140'), ('2300','2220'), ('5000','5100'), ('5100','5200'),
    ('5200','5500')
) AS m(old_code, new_code)
WHERE p.account_code = m.old_code;

-- 4. The legacy chart rows are now unreferenced.
DELETE FROM finaccounting.chart_of_account
WHERE account_code IN ('1000','1200','1300','1400','2200','2300','5000','5100','5200');

-- 5. Insert the 36-account chart for every tenant that had one, parents before
--    children so fk_chart_of_account_parent holds at every step. Mirrors
--    ChartOfAccountBlueprint.accounts() exactly; ChartOfAccountMigrationV5Test
--    asserts the two agree so they cannot drift.
--
--    account_type/normal_balance stay DERIVED from the leading digit, computed
--    here the same way PostingRule.accountTypeFor/normalBalanceFor do it.
INSERT INTO finaccounting.chart_of_account
    (tenant_id, account_code, name, account_type, normal_balance,
     parent_code, level, posting_allowed, status, currency, control_of, created_by)
SELECT t.tenant_id, a.code, a.name,
       CASE left(a.code, 1)
           WHEN '1' THEN 'ASSET' WHEN '2' THEN 'LIABILITY' WHEN '3' THEN 'EQUITY'
           WHEN '4' THEN 'INCOME' ELSE 'EXPENSE' END,
       CASE WHEN left(a.code, 1) IN ('2','3','4') THEN 'CR' ELSE 'DR' END,
       a.parent_code, a.level, a.posting_allowed, 'ACTIVE', 'TZS', a.control_of,
       'migration:finaccounting/V5'
FROM (SELECT DISTINCT tenant_id FROM finaccounting.chart_of_account) AS t
CROSS JOIN (VALUES
    ('1000','Assets',                        NULL,   1, FALSE, NULL),
    ('1100','Cash and Cash Equivalents',     '1000', 2, FALSE, NULL),
    ('1110','Main Bank Account',             '1100', 3, TRUE,  NULL),
    ('1120','Mobile Money',                  '1100', 3, TRUE,  NULL),
    ('1130','Petty Cash',                    '1100', 3, TRUE,  NULL),
    ('1200','Receivables',                   '1000', 2, FALSE, NULL),
    ('1210','Premium Receivables',           '1200', 3, TRUE,  'BILLING'),
    ('1220','Agent Receivables',             '1200', 3, TRUE,  'DISTRIBUTION'),
    ('1230','Other Receivables',             '1200', 3, TRUE,  NULL),
    ('1240','Reinsurance Recoverable',       '1200', 3, TRUE,  'REINSURANCE'),
    ('1250','Policy Loan Receivables',       '1200', 3, TRUE,  'POLICYLOAN'),
    ('1300','Investments',                   '1000', 2, TRUE,  NULL),
    ('2000','Liabilities',                   NULL,   1, FALSE, NULL),
    ('2100','Insurance Liabilities',         '2000', 2, FALSE, NULL),
    ('2110','Claims Payable',                '2100', 3, TRUE,  'CLAIMS'),
    ('2120','Premiums Received in Advance',  '2100', 3, TRUE,  NULL),
    ('2130','Policyholder Benefits Payable', '2100', 3, TRUE,  NULL),
    ('2140','Unearned Premium',              '2100', 3, TRUE,  NULL),
    ('2200','Payables',                      '2000', 2, FALSE, NULL),
    ('2210','Agent Commissions Payable',     '2200', 3, TRUE,  'DISTRIBUTION'),
    ('2220','Reinsurance Payable',           '2200', 3, TRUE,  'REINSURANCE'),
    ('2300','Other Liabilities',             '2000', 2, TRUE,  NULL),
    ('3000','Equity',                        NULL,   1, FALSE, NULL),
    ('3100','Share Capital',                 '3000', 2, TRUE,  NULL),
    ('3200','Retained Earnings',             '3000', 2, TRUE,  NULL),
    ('3300','Current Year Profit/Loss',      '3000', 2, TRUE,  NULL),
    ('4000','Income',                        NULL,   1, FALSE, NULL),
    ('4100','Premium Income',                '4000', 2, TRUE,  NULL),
    ('4200','Investment Income',             '4000', 2, TRUE,  NULL),
    ('4300','Other Income',                  '4000', 2, TRUE,  NULL),
    ('5000','Expenses',                      NULL,   1, FALSE, NULL),
    ('5100','Claims Expense',                '5000', 2, TRUE,  NULL),
    ('5200','Commission Expense',            '5000', 2, TRUE,  NULL),
    ('5300','Operating Expenses',            '5000', 2, TRUE,  NULL),
    ('5400','Other Expenses',                '5000', 2, TRUE,  NULL),
    ('5500','Reinsurance Ceded Premium',     '5000', 2, TRUE,  NULL)
) AS a(code, name, parent_code, level, posting_allowed, control_of)
ORDER BY a.level, a.code;

-- 6. Re-arm the ledger. This statement FAILS if step 3 left any posting naming
--    a code that does not exist -- which is the assertion, not just the repair.
ALTER TABLE finaccounting.gl_posting
    ADD CONSTRAINT fk_gl_posting_account_code
    FOREIGN KEY (tenant_id, account_code)
    REFERENCES finaccounting.chart_of_account (tenant_id, account_code);
```

- [ ] **Step 4: Run the migration test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ChartOfAccountMigrationV5Test`
Expected: PASS, 5 tests green.

If `theMigratedChartMatchesTheJavaBlueprintExactly` fails, the SQL `VALUES` list and `ChartOfAccountBlueprint.accounts()` have diverged — fix whichever is wrong; do not relax the assertion.

- [ ] **Step 5: Commit**

```bash
git add backend/db-migrations/finaccounting/V5__chart_of_account_hierarchy.sql backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/ChartOfAccountMigrationV5Test.java
git commit -m "feat(finaccounting): migrate the ledger onto a chart with a shape"
```

---

### Task 3: Reseed and renumber

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/PostingRule.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/infrastructure/ChartOfAccountSeeder.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/GlPostingCalculatorTest.java` (existing — update expected codes)
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/PremiumPostingEndToEndTest.java` (existing — update expected codes)
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/ClaimAndCommissionPostingEndToEndTest.java` (existing — update expected codes)
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/ReinsuranceAndLoanPostingEndToEndTest.java` (existing — update expected codes)

**Interfaces:**
- Consumes: `ChartOfAccountBlueprint.accounts()`, `ChartOfAccount.root(...)`, `ChartOfAccount.childOf(...)` (Task 1).
- Produces: `PostingRule`'s nine constants at their new values. `ChartOfAccountSeeder.seedIfAbsent(UUID, String)` keeps its exact signature — every caller is untouched.

- [ ] **Step 1: Renumber `PostingRule`'s constants**

In `PostingRule.java`, change only the nine constant values, and delete `seedAccounts()` entirely (the blueprint owns seeding now):

```java
    public static final String CASH = "1120";
    public static final String PREMIUM_RECEIVABLE = "1210";
    public static final String REINSURANCE_RECOVERABLE = "1240";
    public static final String POLICY_LOAN_RECEIVABLE = "1250";
    public static final String UNEARNED_PREMIUM = "2140";
    public static final String REINSURANCE_PAYABLE = "2220";
    public static final String CLAIMS_EXPENSE = "5100";
    public static final String COMMISSION_EXPENSE = "5200";
    public static final String REINSURANCE_CEDED_PREMIUM = "5500";
```

Update the class javadoc's two account references: `1200 Premium Receivable` becomes `1210 Premium Receivables`, `2200 Unearned Premium` becomes `2140 Unearned Premium`, and `1400 Policy Loan Receivable` becomes `1250 Policy Loan Receivables`. Leave every other word of that javadoc — including the accrual-ledger explanation and the accrued-loan-interest gap — exactly as it is; those statements are still true.

Replace the `seedAccounts()` javadoc reference in the "no 4xxx account is even seeded" sentence with: *"M9 accumulated unearned premium and recognised zero earned premium. The 4xxx accounts now EXIST in the chart (finaccounting/V5) but no rule below targets them, so they stay at zero — see `ChartOfAccountBlueprint`."*

- [ ] **Step 2: Rewrite the seeder's loop**

In `ChartOfAccountSeeder.seedIfAbsent`, replace the `for (Map.Entry<String, String> account : PostingRule.seedAccounts().entrySet())` loop with one that walks the blueprint and resolves parents as it goes. Keep the surrounding `TransactionTemplate`, the double `existsByTenantId` check, and the `DataIntegrityViolationException` catch **exactly as they are** — that arrangement is the fix for a real M9 review finding and its javadoc explains why:

```java
                Map<String, ChartOfAccount> byCode = new HashMap<>();
                for (ChartOfAccountBlueprint.Seed seed : ChartOfAccountBlueprint.accounts()) {
                    // Parents come first in blueprint order, so the lookup below always hits.
                    ChartOfAccount account = seed.parentCode() == null
                        ? ChartOfAccount.root(tenantId, seed.code(), seed.name(),
                            seed.postingAllowed(), ChartOfAccountBlueprint.SEED_CURRENCY, seededBy)
                        : ChartOfAccount.childOf(byCode.get(seed.parentCode()), seed.code(), seed.name(),
                            seed.postingAllowed(), ChartOfAccountBlueprint.SEED_CURRENCY,
                            seed.controlOf(), null, seededBy);
                    // saveAndFlush, not save: the id is application-assigned via an @IdClass, so a
                    // plain save() defers the write past this block and a violation would surface
                    // only at commit -- outside where the catch below could see it.
                    byCode.put(seed.code(), chartOfAccountRepository.saveAndFlush(account));
                }
```

Update the class javadoc's opening line from "Seeds the nine accounts M9 posts to (see `PostingRule#seedAccounts()`)" to "Seeds the 36-account chart (see `ChartOfAccountBlueprint#accounts()`)".

- [ ] **Step 3: Update the four existing posting tests**

These tests assert on literal account codes. Change only the codes, using the Task 3 Step 1 mapping — do not change what any test asserts:

Run `grep -rn "\"1000\"\|\"1200\"\|\"1300\"\|\"1400\"\|\"2200\"\|\"2300\"\|\"5000\"\|\"5100\"\|\"5200\"" backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/` and update each literal to its new value. Prefer replacing the literal with the `PostingRule` constant where the test already imports it.

- [ ] **Step 4: Run the finaccounting suite**

Run: `cd backend && ./mvnw clean test -Dtest='tz.co.nlolo.lifeplatform.finaccounting.*Test'`
Expected: PASS. Every posting end-to-end test now writes to the new codes and the seeder gives them a chart to write into.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/ backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/
git commit -m "feat(finaccounting): post to the codes the new chart actually uses"
```

---

### Task 4: Refuse a posting the chart does not allow

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/application/FinaccountingApiImpl.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/application/FinaccountingApiIntegrationTest.java` (existing — add cases)

**Interfaces:**
- Consumes: `ChartOfAccount.acceptsPostings()`, `ChartOfAccount.getCurrency()` (Task 1); `ChartOfAccountRepository` (already injected into `FinaccountingApiImpl`).
- Produces: `postEntry` now throws `IllegalStateException` for a leg the chart refuses. No signature change.

- [ ] **Step 1: Write the failing tests**

Add to `FinaccountingApiIntegrationTest`:

```java
    @Test
    void refusesALegTargetingAHeaderAccount() {
        // 1000 Assets is a header in the seeded chart -- it has children, so it never posts.
        JournalEntry entry = balancedEntryAgainst("1000", "1120");
        assertThatThrownBy(() -> finaccountingApiImpl.postEntry(entry))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("1000")
            .hasMessageContaining("does not accept postings");
    }

    @Test
    void refusesALegTargetingAnInactiveAccount() {
        ChartOfAccount investments = chartOfAccountRepository
            .findByTenantIdAndAccountCode(TENANT_ID, "1300").orElseThrow();
        investments.deactivate("test");
        chartOfAccountRepository.saveAndFlush(investments);

        JournalEntry entry = balancedEntryAgainst("1300", "1120");
        assertThatThrownBy(() -> finaccountingApiImpl.postEntry(entry))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("1300")
            .hasMessageContaining("does not accept postings");
    }

    @Test
    void refusesALegWhoseCurrencyDisagreesWithItsAccount() {
        JournalEntry entry = balancedEntryAgainst("1210", "1120", "USD");
        assertThatThrownBy(() -> finaccountingApiImpl.postEntry(entry))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("USD")
            .hasMessageContaining("TZS");
    }

    @Test
    void stillPostsABalancedEntryBetweenTwoPostableAccounts() {
        JournalEntry entry = balancedEntryAgainst("1210", "2140");
        assertThat(finaccountingApiImpl.postEntry(entry)).isPresent();
    }
```

Add the two helpers alongside the existing fixtures in that class (`TENANT_ID` and `chartOfAccountRepository` are already available there):

```java
    private JournalEntry balancedEntryAgainst(String debitCode, String creditCode) {
        return balancedEntryAgainst(debitCode, creditCode, "TZS");
    }

    /** One balanced DR/CR pair for 100.00, with a source_ref unique per call so the
     *  idempotency early-return in postEntry never masks the assertion under test. */
    private JournalEntry balancedEntryAgainst(String debitCode, String creditCode, String currency) {
        JournalEntry entry = new JournalEntry(TENANT_ID, "test.PostingGuard",
            UUID.randomUUID().toString(), "2026-09", null, "test");
        entry.addLeg(debitCode, PostingDirection.DR, new BigDecimal("100.00"), currency);
        entry.addLeg(creditCode, PostingDirection.CR, new BigDecimal("100.00"), currency);
        return entry;
    }
```

If `JournalEntry`'s constructor or `addLeg` signature differs from the above, match the shape the existing tests in this file already use rather than changing `JournalEntry`.

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=FinaccountingApiIntegrationTest`
Expected: FAIL — the three refusal tests pass an unbalanced-check but persist successfully; no guard exists yet.

- [ ] **Step 3: Add the guard to `postEntry`**

In `FinaccountingApiImpl.postEntry`, immediately after the `isBalanced()` check and **before** `journalEntryRepository.save(entry)`:

```java
        // The chart is the authority on where a posting may land, and this is the single place
        // that asks it. fk_gl_posting_account_code already guarantees the account EXISTS; it
        // cannot know whether the account is a non-posting header or a retired one. Without this
        // check, posting_allowed and status would be decoration -- a header would silently
        // accumulate a balance that its own children also carry, double-counting the block.
        for (JournalEntry.Leg leg : entry.getLegs()) {
            ChartOfAccount account = chartOfAccountRepository
                .findByTenantIdAndAccountCode(entry.getTenantId(), leg.accountCode())
                .orElseThrow(() -> new IllegalStateException(
                    "Refusing to post to unknown account " + leg.accountCode()
                        + ": sourceEvent=" + entry.getSourceEvent()));
            if (!account.acceptsPostings()) {
                throw new IllegalStateException("Refusing to post to " + leg.accountCode()
                    + ": it does not accept postings (postingAllowed=" + account.isPostingAllowed()
                    + ", status=" + account.getStatus() + ")");
            }
            if (!account.getCurrency().equals(leg.currency())) {
                throw new IllegalStateException("Refusing to post " + leg.currency() + " to "
                    + leg.accountCode() + ", which is denominated in " + account.getCurrency());
            }
        }
```

If `ChartOfAccountRepository` has no `findByTenantIdAndAccountCode`, add it:

```java
    Optional<ChartOfAccount> findByTenantIdAndAccountCode(UUID tenantId, String accountCode);
```

- [ ] **Step 4: Run to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=FinaccountingApiIntegrationTest`
Expected: PASS.

- [ ] **Step 5: Verify the eight real posting paths still work**

Run: `cd backend && ./mvnw test -Dtest='PremiumPostingEndToEndTest,ClaimAndCommissionPostingEndToEndTest,ReinsuranceAndLoanPostingEndToEndTest'`
Expected: PASS. Every `PostingRule` target is a postable account in the blueprint (asserted in Task 1), so the guard must not fire on any real path. If one fires, the blueprint and `PostingRule` disagree — fix that, not the guard.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/ backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/
git commit -m "feat(finaccounting): a header account can no longer take a posting"
```

---

### Task 5: The API surface

**Files:**
- Modify: `backend/api/openapi/openapi-finaccounting.yaml`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/api/ChartOfAccountView.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/api/FinaccountingApi.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/application/FinaccountingApiImpl.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/infrastructure/ChartOfAccountController.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/infrastructure/ChartOfAccountResponseDto.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/infrastructure/CreateAccountRequestDto.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/infrastructure/RenameAccountRequestDto.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/api/AccountHasChildrenException.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/infrastructure/FinaccountingExceptionHandler.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/FinaccountingContractTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–4.
- Produces:
  - `ChartOfAccountView(String accountCode, String name, AccountType accountType, PostingDirection normalBalance, String parentCode, short level, boolean postingAllowed, AccountStatus status, String currency, String controlOf, String description, Instant createdAt, String createdBy)`
  - `FinaccountingApi.createAccount(String accountCode, String parentCode, String name, String description, String currency, boolean postingAllowed, String createdBy)`
  - `FinaccountingApi.updateAccount(String accountCode, String name, String description, String updatedBy)` (replaces `renameAccount`)
  - `FinaccountingApi.setAccountStatus(String accountCode, AccountStatus status, String updatedBy)`
  - `AccountHasChildrenException`

- [ ] **Step 1: Widen the OpenAPI spec**

In `backend/api/openapi/openapi-finaccounting.yaml`, replace the three schemas at lines 286–307:

```yaml
    AccountStatus:
      type: string
      enum: [ACTIVE, INACTIVE]
    ChartOfAccountView:
      type: object
      required: [accountCode, name, accountType, normalBalance, level, postingAllowed, status, currency]
      properties:
        accountCode: { type: string }
        name: { type: string }
        accountType: { $ref: '#/components/schemas/AccountType' }
        normalBalance: { $ref: '#/components/schemas/PostingDirection' }
        parentCode:
          type: string
          nullable: true
          description: "Null only for the five block roots (1000/2000/3000/4000/5000)."
        level: { type: integer, description: "Depth in the parent chain; a root is 1." }
        postingAllowed:
          type: boolean
          description: "False for any account with children -- a header never receives a posting."
        status: { $ref: '#/components/schemas/AccountStatus' }
        currency: { type: string, minLength: 3, maxLength: 3 }
        controlOf:
          type: string
          nullable: true
          description: "The subledger module owning this account's detail. A documented label; nothing enforces it, because the ledger is event-only and no manual journal entry surface exists."
        description: { type: string, nullable: true }
        createdAt: { type: string, format: date-time }
        createdBy: { type: string, nullable: true }
    CreateAccountRequest:
      type: object
      required: [accountCode, name]
      properties:
        accountCode:
          type: string
          pattern: '^[1-5]\d{3}$'
          description: "4 digits, leading block 1=ASSET, 2=LIABILITY, 3=EQUITY, 4=INCOME, 5=EXPENSE -- accountType/normalBalance are derived from it server-side, never accepted here."
        parentCode:
          type: string
          pattern: '^[1-5]\d{3}$'
          nullable: true
          description: "Must already exist, and accountCode must begin with its significant prefix (the parent's code with trailing zeros stripped). Omit to create a block root."
        name: { type: string, maxLength: 200 }
        description: { type: string, maxLength: 2000, nullable: true }
        currency: { type: string, minLength: 3, maxLength: 3, default: 'TZS' }
        postingAllowed: { type: boolean, default: true }
    UpdateAccountRequest:
      type: object
      required: [name]
      properties:
        name: { type: string, maxLength: 200 }
        description: { type: string, maxLength: 2000, nullable: true }
```

Delete the `RenameAccountRequest` schema. Under `paths`, change `PUT /chart-of-accounts/{accountCode}` to reference `UpdateAccountRequest`, and add two paths mirroring the existing POST's response and security blocks:

```yaml
  /chart-of-accounts/{accountCode}/activate:
    post:
      summary: Return a retired account to service
      operationId: activateAccount
      parameters:
        - name: accountCode
          in: path
          required: true
          schema: { type: string }
      responses:
        '200':
          description: The account, now ACTIVE
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ChartOfAccountView' }
        '404': { description: No such account in this tenant }
  /chart-of-accounts/{accountCode}/deactivate:
    post:
      summary: Retire an account from new postings, keeping its history readable
      operationId: deactivateAccount
      parameters:
        - name: accountCode
          in: path
          required: true
          schema: { type: string }
      responses:
        '200':
          description: The account, now INACTIVE
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ChartOfAccountView' }
        '404': { description: No such account in this tenant }
```

Add a `'409'` response (`description: The account has children`) to the existing `DELETE /chart-of-accounts/{accountCode}`.

- [ ] **Step 2: Widen the Java view and request DTOs**

`ChartOfAccountView.java`:

```java
package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.time.Instant;

/** Read view of {@code finaccounting.chart_of_account}. Every account on this platform is
 * currently a PLACEHOLDER pending Finance sign-off. */
public record ChartOfAccountView(String accountCode, String name, AccountType accountType,
                                  PostingDirection normalBalance, String parentCode, short level,
                                  boolean postingAllowed, AccountStatus status, String currency,
                                  String controlOf, String description, Instant createdAt,
                                  String createdBy) {}
```

`CreateAccountRequestDto.java`:

```java
package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code parentCode} is optional: omitting it creates a block root. {@code currency} and
 * {@code postingAllowed} default in the compact constructor rather than being required, so a
 * body written against the pre-hierarchy shape still creates a sane account.
 *
 * <p>{@code accountType}/{@code normalBalance} are absent deliberately -- they stay derived
 * server-side from {@code accountCode}'s leading digit and are never accepted from a client.
 */
public record CreateAccountRequestDto(
        @NotBlank @Pattern(regexp = "^[1-5]\\d{3}$") String accountCode,
        @Pattern(regexp = "^[1-5]\\d{3}$") String parentCode,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @Size(min = 3, max = 3) String currency,
        Boolean postingAllowed) {

    public CreateAccountRequestDto {
        currency = currency == null ? "TZS" : currency;
        postingAllowed = postingAllowed == null ? Boolean.TRUE : postingAllowed;
    }
}
```

Rename `RenameAccountRequestDto` to `UpdateAccountRequestDto` and add `description`:

```java
public record UpdateAccountRequestDto(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description) {}
```

`ChartOfAccountResponseDto.from(ChartOfAccountView)` gains the nine new fields, mapping straight across.

- [ ] **Step 3: Widen `FinaccountingApi` and its impl**

Replace `createAccount`/`renameAccount` in the interface and add `setAccountStatus`, carrying the javadoc rules verbatim:

```java
    /**
     * @param parentCode the parent account, or null for a block root. Must already exist in this
     *        tenant, and {@code accountCode} must begin with its significant prefix.
     * @throws DuplicateAccountCodeException if {@code (tenant, accountCode)} already exists
     * @throws AccountNotFoundException if {@code parentCode} names no account in this tenant
     * @throws FinaccountingValidationException if {@code accountCode} falls outside the parent's block
     */
    ChartOfAccountView createAccount(String accountCode, String parentCode, String name,
                                      String description, String currency, boolean postingAllowed,
                                      String createdBy);

    /** Name and description. {@code accountCode}, {@code accountType}, {@code normalBalance},
     *  {@code parentCode} and {@code level} are never editable -- see this interface's javadoc. */
    ChartOfAccountView updateAccount(String accountCode, String name, String description, String updatedBy);

    /** Retire an account from new postings, or return it to service. Deactivating is always
     *  allowed; it is the correct answer for an account with history, which must never be deleted. */
    ChartOfAccountView setAccountStatus(String accountCode, AccountStatus status, String updatedBy);
```

In `FinaccountingApiImpl.createAccount`, load the parent when `parentCode` is non-null, build via `ChartOfAccount.childOf(...)`, and **flip the parent to a header in the same transaction** — rejecting if the parent already has postings. The whole method body:

```java
    @Override
    @Transactional
    public ChartOfAccountView createAccount(String accountCode, String parentCode, String name,
                                             String description, String currency,
                                             boolean postingAllowed, String createdBy) {
        UUID tenantId = TenantContext.get();
        if (chartOfAccountRepository.existsByTenantIdAndAccountCode(tenantId, accountCode)) {
            throw new DuplicateAccountCodeException(accountCode);
        }

        ChartOfAccount account;
        if (parentCode == null) {
            account = ChartOfAccount.root(tenantId, accountCode, name, postingAllowed, currency, createdBy);
            account.describe(description, createdBy);
        } else {
            ChartOfAccount parent = chartOfAccountRepository
                .findByTenantIdAndAccountCode(tenantId, parentCode)
                .orElseThrow(() -> new AccountNotFoundException(parentCode));
            if (parent.isPostingAllowed()
                    && glPostingRepository.existsByTenantIdAndAccountCode(tenantId, parentCode)) {
                // Flipping it to a header would strand the postings it already carries under an
                // account that, by this chart's own rule, cannot hold any.
                throw new AccountInUseException(parentCode);
            }
            // A parent never receives postings. Done here, in the same transaction as the child's
            // insert, so the invariant can never be observed broken.
            parent.becomeHeader(createdBy);
            chartOfAccountRepository.save(parent);
            account = ChartOfAccount.childOf(parent, accountCode, name, postingAllowed,
                currency, null, description, createdBy);
        }

        return toView(chartOfAccountRepository.saveAndFlush(account));
    }
```

`controlOf` is passed as `null`: it names the subledger owning an account's detail, which is a property of the seeded chart, not something a console user assigns. Use whatever the class's existing `toView(ChartOfAccount)` helper is called; add one if `createAccount` currently builds its `ChartOfAccountView` inline.

In `deleteAccount`, add the children check before the existing in-use check:

```java
        if (chartOfAccountRepository.existsByTenantIdAndParentCode(tenantId, accountCode)) {
            throw new AccountHasChildrenException(accountCode);
        }
```

Add the two repository methods `existsByTenantIdAndAccountCode` / `existsByTenantIdAndParentCode` (and `GlPostingRepository.existsByTenantIdAndAccountCode`) if absent.

- [ ] **Step 4: Wire the controller and exception handler**

Add to `ChartOfAccountController`, with the same `@PreAuthorize` as every sibling:

```java
    @PostMapping("/chart-of-accounts/{accountCode}/activate")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<ChartOfAccountResponseDto> activateAccount(@PathVariable String accountCode,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ChartOfAccountResponseDto.from(
            finaccountingApi.setAccountStatus(accountCode, AccountStatus.ACTIVE, jwt.getSubject())));
    }

    @PostMapping("/chart-of-accounts/{accountCode}/deactivate")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<ChartOfAccountResponseDto> deactivateAccount(@PathVariable String accountCode,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ChartOfAccountResponseDto.from(
            finaccountingApi.setAccountStatus(accountCode, AccountStatus.INACTIVE, jwt.getSubject())));
    }
```

Map `AccountHasChildrenException` to `409 CONFLICT` in `FinaccountingExceptionHandler`, following the existing `AccountInUseException` handler exactly.

- [ ] **Step 5: Add contract tests**

Add to `FinaccountingContractTest`, matching the file's existing MockMvc + `openApi().isValid(SPEC_PATH)` + `SpecTypeConformance` idiom:

```java
    @Test
    void createsAChildAccountUnderAnExistingParent() throws Exception {
        mockMvc.perform(post("/chart-of-accounts").with(financeOfficer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountCode":"1260","parentCode":"1200","name":"Sundry Receivables"}"""))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.parentCode").value("1200"))
            .andExpect(jsonPath("$.level").value(3))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.currency").value("TZS"))
            .andExpect(jsonPath("$.accountType").value("ASSET"))
            .andExpect(openApi().isValid(SPEC_PATH));
    }

    @Test
    void rejectsAChildOutsideItsParentsBlock() throws Exception {
        mockMvc.perform(post("/chart-of-accounts").with(financeOfficer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountCode":"2160","parentCode":"1200","name":"Wrong block"}"""))
            .andExpect(status().isBadRequest());
    }

    @Test
    void deactivatesAndReactivatesAnAccount() throws Exception {
        mockMvc.perform(post("/chart-of-accounts/1300/deactivate").with(financeOfficer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("INACTIVE"))
            .andExpect(openApi().isValid(SPEC_PATH));

        mockMvc.perform(post("/chart-of-accounts/1300/activate").with(financeOfficer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void refusesToDeleteAnAccountWithChildren() throws Exception {
        mockMvc.perform(delete("/chart-of-accounts/1200").with(financeOfficer()))
            .andExpect(status().isConflict());
    }

    @Test
    void deactivateIsGatedOnFinanceOrAdmin() throws Exception {
        mockMvc.perform(post("/chart-of-accounts/1300/deactivate").with(underwriter()))
            .andExpect(status().isForbidden());
    }
```

Use whatever `financeOfficer()` / `underwriter()` request post-processors this file already defines; do not introduce new ones.

- [ ] **Step 6: Run the tests**

Run: `cd backend && ./mvnw clean test -Dtest='tz.co.nlolo.lifeplatform.finaccounting.*Test'`
Expected: PASS. `clean` is mandatory here — `ChartOfAccountView` is a widened record, and incremental compile hides breaks in every unchanged test that constructs one.

- [ ] **Step 7: Commit**

```bash
git add backend/api/openapi/openapi-finaccounting.yaml backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/ backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/
git commit -m "feat(finaccounting): the API can say where an account sits and whether it is retired"
```

---

### Task 6: Frontend data layer

**Files:**
- Modify: `frontend/src/types/api/finaccounting.ts` (regenerated, not hand-edited)
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/finaccounting.ts`
- Modify: `frontend/src/store/finaccountingStore.ts`
- Modify: `frontend/src/features/finaccounting/createAccountForm.ts`
- Modify: `frontend/src/features/finaccounting/createAccountForm.test.ts`
- Rename: `frontend/src/features/finaccounting/renameAccountForm.ts` → `updateAccountForm.ts` (and its test)

**Interfaces:**
- Consumes: the OpenAPI spec from Task 5.
- Produces:
  - `updateAccount(accountCode, request): Promise<ChartOfAccountView>`
  - `setAccountStatus(accountCode, status: 'ACTIVE' | 'INACTIVE'): Promise<ChartOfAccountView>`
  - Store: `updateAccount`, `resetUpdateAccount`, `settingStatus: Keyed<ChartOfAccountView>`, `setAccountStatus`, `resetSetAccountStatus`
  - `AccountStatus` type re-exported from `@/api/types`

- [ ] **Step 1: Regenerate the types**

Run: `cd frontend && npm run generate:api`
Expected: `src/types/api/finaccounting.ts` gains `AccountStatus`, `UpdateAccountRequest`, and the widened `ChartOfAccountView`.

- [ ] **Step 2: Re-export the new types**

In `frontend/src/api/types.ts`, beside the existing finaccounting exports:

```ts
export type AccountStatus = FinaccountingComponents['schemas']['AccountStatus'];
export type UpdateAccountRequest = FinaccountingComponents['schemas']['UpdateAccountRequest'];
```

Delete the `RenameAccountRequest` export.

- [ ] **Step 3: Update the API client**

In `frontend/src/api/finaccounting.ts`, rename `renameAccount` to `updateAccount` (same `put`, now taking `UpdateAccountRequest`) and add:

```ts
/**
 * `POST /chart-of-accounts/{accountCode}/{activate|deactivate}` -- staff
 * FINANCE_OFFICER/ADMIN only. Deactivating is the retirement path for an account
 * that has history: it refuses new postings while leaving every existing one
 * readable, which deleting cannot do.
 */
export function setAccountStatus(
  accountCode: string,
  status: AccountStatus,
): Promise<ChartOfAccountView> {
  const action = status === 'ACTIVE' ? 'activate' : 'deactivate';
  return post<ChartOfAccountView>(
    `/chart-of-accounts/${encodeURIComponent(accountCode)}/${action}`,
    {},
  );
}
```

- [ ] **Step 4: Update the store**

In `frontend/src/store/finaccountingStore.ts`, rename `renaming`/`renameAccount`/`resetRenameAccount`/`selectRenamingAccount` to `updating`/`updateAccount`/`resetUpdateAccount`/`selectUpdatingAccount`, and add a keyed `settingStatus` slot following the identical `track(...)` shape the existing `deleting` slot uses:

```ts
  settingStatus: Keyed<ChartOfAccountView>;
  setAccountStatus: (accountCode: string, status: AccountStatus) => Promise<void>;
  resetSetAccountStatus: (accountCode: string) => void;
```

```ts
  setAccountStatus: (accountCode, status) =>
    track(
      `finaccounting.setAccountStatus.${accountCode}`,
      getState().settingStatus[accountCode] ?? idle<ChartOfAccountView>(),
      (next) => set((s) => ({ settingStatus: { ...s.settingStatus, [accountCode]: next } })),
      async () => {
        const updated = await setAccountStatus(accountCode, status);
        await getState().loadChartOfAccounts();
        return updated;
      },
    ),
```

```ts
export const selectSettingStatus = (accountCode: string) => (s: FinaccountingState) =>
  s.settingStatus[accountCode] ?? idle<ChartOfAccountView>();
```

- [ ] **Step 5: Widen the create form schema**

In `createAccountForm.ts`, add the optional fields, keeping the existing `accountCode` message verbatim (`staff-finaccounting.spec.ts` asserts on it):

```ts
export const createAccountFormSchema = z.object({
  accountCode: z
    .string()
    .regex(/^[1-5]\d{3}$/, 'Must be 4 digits starting with 1-5'),
  parentCode: z.string(),
  name: z.string().min(1, 'Required').max(200),
  description: z.string().max(2000),
});
```

`toApiRequest` maps `parentCode: ''` and `description: ''` to `undefined` so a root account and an empty description are both sent as absent rather than as an empty string.

Add a test case to `createAccountForm.test.ts` asserting exactly that:

```ts
it('sends an omitted parent and description as absent, not as empty strings', () => {
  const request = toApiRequest({
    accountCode: '1260',
    parentCode: '',
    name: 'Sundry Receivables',
    description: '',
  });
  expect(request.parentCode).toBeUndefined();
  expect(request.description).toBeUndefined();
});
```

Rename `renameAccountForm.ts`/`.test.ts` to `updateAccountForm.ts`/`.test.ts` and add the optional `description` field to its schema.

- [ ] **Step 6: Typecheck and test**

Run: `cd frontend && npx tsc --noEmit ; npx vitest run src/features/finaccounting`
Expected: typecheck clean (except in `ChartOfAccountsPage.tsx`, which Task 8 rewrites — leave those errors), form tests pass.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/types/api/finaccounting.ts frontend/src/api/ frontend/src/store/finaccountingStore.ts frontend/src/features/finaccounting/
git commit -m "feat(console): teach the finaccounting client about parents and retirement"
```

---

### Task 7: Tree and table, as pure functions

**Files:**
- Create: `frontend/src/features/finaccounting/accountTree.ts`
- Test: `frontend/src/features/finaccounting/accountTree.test.ts`

**Interfaces:**
- Consumes: `ChartOfAccountView`, `AccountStatus`, `AccountType` from `@/api/types`.
- Produces:
  - `AccountNode = ChartOfAccountView & { children: AccountNode[] }`
  - `buildAccountTree(accounts: ChartOfAccountView[]): AccountNode[]`
  - `AccountFilters = { search: string; types: AccountType[]; statuses: AccountStatus[]; postingOnly: boolean }`
  - `filterAccounts(accounts: ChartOfAccountView[], filters: AccountFilters): ChartOfAccountView[]`
  - `sortAccounts(accounts: ChartOfAccountView[], key: SortKey, direction: 'asc' | 'desc'): ChartOfAccountView[]`
  - `SortKey = 'accountCode' | 'name' | 'accountType' | 'parentCode' | 'level' | 'status'`
  - `visibleDescendantCodes(nodes: AccountNode[], expanded: Set<string>): string[]`

- [ ] **Step 1: Write the failing test**

Create `accountTree.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import type { ChartOfAccountView } from '@/api/types';
import { buildAccountTree, filterAccounts, sortAccounts } from './accountTree';

function account(overrides: Partial<ChartOfAccountView> & { accountCode: string }): ChartOfAccountView {
  return {
    name: `Account ${overrides.accountCode}`,
    accountType: 'ASSET',
    normalBalance: 'DR',
    level: 1,
    postingAllowed: true,
    status: 'ACTIVE',
    currency: 'TZS',
    ...overrides,
  } as ChartOfAccountView;
}

const CHART = [
  account({ accountCode: '1000', name: 'Assets', postingAllowed: false }),
  account({ accountCode: '1200', name: 'Receivables', parentCode: '1000', level: 2, postingAllowed: false }),
  account({ accountCode: '1210', name: 'Premium Receivables', parentCode: '1200', level: 3 }),
  account({ accountCode: '1220', name: 'Agent Receivables', parentCode: '1200', level: 3, status: 'INACTIVE' }),
  account({ accountCode: '5000', name: 'Expenses', accountType: 'EXPENSE', postingAllowed: false }),
  account({ accountCode: '5100', name: 'Claims Expense', accountType: 'EXPENSE', parentCode: '5000', level: 2 }),
];

describe('buildAccountTree', () => {
  it('nests children under their parent and returns only roots at the top', () => {
    const tree = buildAccountTree(CHART);
    expect(tree.map((n) => n.accountCode)).toEqual(['1000', '5000']);
    expect(tree[0].children.map((n) => n.accountCode)).toEqual(['1200']);
    expect(tree[0].children[0].children.map((n) => n.accountCode)).toEqual(['1210', '1220']);
  });

  it('orders siblings by account code regardless of input order', () => {
    const shuffled = [...CHART].reverse();
    const tree = buildAccountTree(shuffled);
    expect(tree.map((n) => n.accountCode)).toEqual(['1000', '5000']);
    expect(tree[0].children[0].children.map((n) => n.accountCode)).toEqual(['1210', '1220']);
  });

  /* A parentCode naming an account the response does not contain must not make the row
     vanish -- a dropped account in a chart of accounts is worse than a misplaced one. */
  it('surfaces an orphan at the root rather than dropping it', () => {
    const tree = buildAccountTree([...CHART, account({ accountCode: '9990', parentCode: '8888' })]);
    expect(tree.map((n) => n.accountCode)).toContain('9990');
  });

  it('does not hang on a cyclic parent reference', () => {
    const cyclic = [
      account({ accountCode: '1000', parentCode: '1200' }),
      account({ accountCode: '1200', parentCode: '1000' }),
    ];
    const tree = buildAccountTree(cyclic);
    expect(tree.length).toBeGreaterThan(0);
  });
});

describe('filterAccounts', () => {
  const none = { search: '', types: [], statuses: [], postingOnly: false };

  it('matches search against both code and name, case-insensitively', () => {
    expect(filterAccounts(CHART, { ...none, search: 'premium' }).map((a) => a.accountCode))
      .toEqual(['1210']);
    expect(filterAccounts(CHART, { ...none, search: '52' })).toEqual([]);
    expect(filterAccounts(CHART, { ...none, search: '510' }).map((a) => a.accountCode))
      .toEqual(['5100']);
  });

  it('filters by type, status and posting-allowed independently', () => {
    expect(filterAccounts(CHART, { ...none, types: ['EXPENSE'] }).map((a) => a.accountCode))
      .toEqual(['5000', '5100']);
    expect(filterAccounts(CHART, { ...none, statuses: ['INACTIVE'] }).map((a) => a.accountCode))
      .toEqual(['1220']);
    expect(filterAccounts(CHART, { ...none, postingOnly: true }).map((a) => a.accountCode))
      .toEqual(['1210', '1220', '5100']);
  });

  it('combines filters conjunctively', () => {
    expect(
      filterAccounts(CHART, { ...none, types: ['EXPENSE'], postingOnly: true })
        .map((a) => a.accountCode),
    ).toEqual(['5100']);
  });
});

describe('sortAccounts', () => {
  it('sorts by name descending without mutating the input', () => {
    const input = [...CHART];
    const sorted = sortAccounts(input, 'name', 'desc');
    expect(sorted[0].name).toBe('Receivables');
    expect(input).toEqual(CHART);
  });

  it('puts a missing parentCode last regardless of direction', () => {
    const asc = sortAccounts(CHART, 'parentCode', 'asc');
    expect(asc[asc.length - 1].parentCode).toBeUndefined();
  });
});
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd frontend && npx vitest run src/features/finaccounting/accountTree.test.ts`
Expected: FAIL — cannot resolve `./accountTree`.

- [ ] **Step 3: Implement `accountTree.ts`**

```ts
import type { AccountStatus, AccountType, ChartOfAccountView } from '@/api/types';

/**
 * Tree assembly, filtering and sorting for the chart of accounts, as pure functions.
 *
 * `GET /chart-of-accounts` answers with a flat, unpaged array on purpose -- a chart is
 * bounded reference data (36 rows seeded, a few hundred for a real Finance-authored
 * chart), so the client owning the hierarchy is what makes the Tree/Table toggle
 * instant and keeps search and sort off the network entirely.
 */

export type AccountNode = ChartOfAccountView & { children: AccountNode[] };

export interface AccountFilters {
  search: string;
  types: AccountType[];
  statuses: AccountStatus[];
  postingOnly: boolean;
}

export type SortKey = 'accountCode' | 'name' | 'accountType' | 'parentCode' | 'level' | 'status';

/**
 * A flat array in, roots out.
 *
 * Two defensive cases, both deliberate. An account whose `parentCode` names a row the
 * response does not contain is surfaced at the ROOT rather than dropped -- silently
 * losing an account from a chart of accounts is worse than showing it in the wrong
 * place. And a cyclic parent chain (which the backend's prefix rule makes structurally
 * impossible, but which this function must not hang on regardless) is broken by only
 * ever attaching a node once.
 */
export function buildAccountTree(accounts: ChartOfAccountView[]): AccountNode[] {
  const nodes = new Map<string, AccountNode>();
  for (const account of accounts) {
    nodes.set(account.accountCode, { ...account, children: [] });
  }

  const roots: AccountNode[] = [];
  const attached = new Set<string>();

  for (const node of nodes.values()) {
    const parent = node.parentCode ? nodes.get(node.parentCode) : undefined;
    if (!parent || parent.accountCode === node.accountCode || attached.has(node.accountCode)) {
      roots.push(node);
      continue;
    }
    // Walk up to be sure `node` is not already an ancestor of `parent`: attaching then
    // would make the two unreachable from any root.
    let cursor: AccountNode | undefined = parent;
    let cyclic = false;
    while (cursor) {
      if (cursor.accountCode === node.accountCode) {
        cyclic = true;
        break;
      }
      cursor = cursor.parentCode ? nodes.get(cursor.parentCode) : undefined;
    }
    if (cyclic) {
      roots.push(node);
      continue;
    }
    parent.children.push(node);
    attached.add(node.accountCode);
  }

  const byCode = (a: AccountNode, b: AccountNode) => a.accountCode.localeCompare(b.accountCode);
  const sortDeep = (list: AccountNode[]) => {
    list.sort(byCode);
    list.forEach((n) => sortDeep(n.children));
  };
  sortDeep(roots);
  return roots;
}

export function filterAccounts(
  accounts: ChartOfAccountView[],
  filters: AccountFilters,
): ChartOfAccountView[] {
  const needle = filters.search.trim().toLowerCase();
  return accounts.filter((account) => {
    if (
      needle &&
      !account.accountCode.toLowerCase().includes(needle) &&
      !(account.name ?? '').toLowerCase().includes(needle)
    ) {
      return false;
    }
    if (filters.types.length > 0 && !filters.types.includes(account.accountType)) return false;
    if (filters.statuses.length > 0 && !filters.statuses.includes(account.status)) return false;
    if (filters.postingOnly && !account.postingAllowed) return false;
    return true;
  });
}

/** Returns a NEW array -- the caller's list is never mutated, so a memoised source stays stable. */
export function sortAccounts(
  accounts: ChartOfAccountView[],
  key: SortKey,
  direction: 'asc' | 'desc',
): ChartOfAccountView[] {
  const sign = direction === 'asc' ? 1 : -1;
  return [...accounts].sort((a, b) => {
    const left = a[key];
    const right = b[key];
    // An absent value always sorts last, in BOTH directions: a blank cell floating to
    // the top of a descending sort reads as data, not as absence.
    if (left === undefined || left === null) return right === undefined || right === null ? 0 : 1;
    if (right === undefined || right === null) return -1;
    if (typeof left === 'number' && typeof right === 'number') return (left - right) * sign;
    return String(left).localeCompare(String(right)) * sign;
  });
}

/** The codes currently rendered by the tree, in render order -- for keyboard navigation. */
export function visibleDescendantCodes(nodes: AccountNode[], expanded: Set<string>): string[] {
  const out: string[] = [];
  const walk = (list: AccountNode[]) => {
    for (const node of list) {
      out.push(node.accountCode);
      if (expanded.has(node.accountCode)) walk(node.children);
    }
  };
  walk(nodes);
  return out;
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd frontend && npx vitest run src/features/finaccounting/accountTree.test.ts`
Expected: PASS, all tests green.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/finaccounting/accountTree.ts frontend/src/features/finaccounting/accountTree.test.ts
git commit -m "feat(console): assemble the account hierarchy without rendering it"
```

---

### Task 8: The two views

**Files:**
- Modify: `frontend/src/features/finaccounting/ChartOfAccountsPage.tsx` (substantial rewrite)
- Create: `frontend/src/features/finaccounting/AccountTreeView.tsx`
- Create: `frontend/src/features/finaccounting/AccountTableView.tsx`

**Interfaces:**
- Consumes: `buildAccountTree`, `filterAccounts`, `sortAccounts`, `visibleDescendantCodes`, `AccountFilters`, `SortKey` (Task 7); the store slots from Task 6; existing `PageHeader`, `Panel`, `FilterChip`, `StatusBadge`, `FormField`, `Button`, `Input`, `EmptyState`, `ErrorPanel`, `LoadingBlock`.
- Produces: no exports other than the three components.

**Also modify:** `frontend/src/lib/status.ts`, `frontend/src/lib/status.test.ts`

**Splitting rationale:** the current page is 307 lines and gains two full views plus a filter bar. Tree and table are independently testable, change for different reasons, and neither needs the other's internals — so they get their own files while the page keeps ownership of data loading, the view toggle, and the shared filter state.

- [ ] **Step 1: Teach `STATUS_MAPS` about account status**

`StatusBadge` takes `{ kind, value }` — a `StatusKind` naming the domain, and the raw backend literal — and resolves the colour bucket through `STATUS_MAPS` in `frontend/src/lib/status.ts`. An unmapped literal renders neutral with a ringed "unrecognised" treatment, so skipping this step would ship a visibly-flagged badge rather than a working one.

Add to `STATUS_MAPS`, beside the existing domains:

```ts
  // finaccounting/api/AccountStatus.java
  account: {
    ACTIVE: 'active',
    // Retired from NEW postings; every historical posting stays readable. Neutral, not
    // danger: retiring an account is routine housekeeping, not a failure.
    INACTIVE: 'neutral',
  },
```

Add the matching case to `status.test.ts`, following the file's existing per-kind shape:

```ts
it('maps account status', () => {
  expect(bucket('account', 'ACTIVE')).toBe('active');
  expect(bucket('account', 'INACTIVE')).toBe('neutral');
});
```

Run: `cd frontend && npx vitest run src/lib/status.test.ts`
Expected: PASS.

- [ ] **Step 2: Build `AccountTreeView.tsx`**

A controlled component: it receives nodes, the expanded set, and callbacks. Icons are lucide (`Folder`, `FolderOpen`, `Coins`) — **not emoji**; the console is lucide throughout.

```tsx
import { ChevronRight, Coins, Folder, FolderOpen } from 'lucide-react';
import type { AccountNode } from './accountTree';
import { StatusBadge } from '@/components/StatusBadge';
import { cn } from '@/lib/cn';

/**
 * The hierarchy view. A header account renders as a folder and cannot be posted to; a
 * leaf renders as coins. Expansion is controlled by the page so the Tree/Table toggle
 * and the URL both stay the single source of truth.
 *
 * `role="tree"`/`treeitem` with `aria-expanded` and `aria-level`: a screen reader must
 * announce depth, and an indent alone conveys none.
 */
export function AccountTreeView({
  nodes,
  expanded,
  onToggle,
  renderActions,
}: {
  nodes: AccountNode[];
  expanded: Set<string>;
  onToggle: (accountCode: string) => void;
  renderActions: (node: AccountNode) => React.ReactNode;
}) {
  return (
    <ul role="tree" aria-label="Chart of accounts" className="py-1">
      {nodes.map((node) => (
        <AccountTreeRow
          key={node.accountCode}
          node={node}
          expanded={expanded}
          onToggle={onToggle}
          renderActions={renderActions}
        />
      ))}
    </ul>
  );
}

function AccountTreeRow({
  node,
  expanded,
  onToggle,
  renderActions,
}: {
  node: AccountNode;
  expanded: Set<string>;
  onToggle: (accountCode: string) => void;
  renderActions: (node: AccountNode) => React.ReactNode;
}) {
  const hasChildren = node.children.length > 0;
  const isOpen = expanded.has(node.accountCode);
  const Icon = hasChildren ? (isOpen ? FolderOpen : Folder) : Coins;

  return (
    <li role="treeitem" aria-expanded={hasChildren ? isOpen : undefined} aria-level={node.level}>
      <div
        className="flex items-center gap-2 px-3 py-1.5 hover:bg-hover"
        style={{ paddingLeft: `${(node.level - 1) * 20 + 12}px` }}
      >
        {hasChildren ? (
          <button
            type="button"
            onClick={() => onToggle(node.accountCode)}
            aria-label={`${isOpen ? 'Collapse' : 'Expand'} ${node.name}`}
            className="rounded p-0.5 hover:bg-selected"
          >
            <ChevronRight
              className={cn('h-3.5 w-3.5 transition-transform', isOpen && 'rotate-90')}
            />
          </button>
        ) : (
          <span className="w-[18px]" aria-hidden="true" />
        )}

        <Icon className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
        <span className="font-mono text-xs text-muted-foreground">{node.accountCode}</span>
        <span className="text-sm font-medium">{node.name}</span>

        <span className="ml-auto flex shrink-0 items-center gap-3 text-xs text-muted-foreground">
          <span>{node.accountType}</span>
          <span>{node.normalBalance}</span>
          {node.status === 'INACTIVE' && <StatusBadge kind="account" value={node.status} />}
          {renderActions(node)}
        </span>
      </div>

      {hasChildren && isOpen && (
        <ul role="group">
          {node.children.map((child) => (
            <AccountTreeRow
              key={child.accountCode}
              node={child}
              expanded={expanded}
              onToggle={onToggle}
              renderActions={renderActions}
            />
          ))}
        </ul>
      )}
    </li>
  );
}
```

Check `StatusBadge`'s real prop names before wiring it and match them; the six-bucket vocabulary is fixed platform-wide and must not be extended here.

- [ ] **Step 3: Build `AccountTableView.tsx`**

A sortable table over the already-filtered, already-sorted list. Sort state lives in the page; this component renders header buttons and calls back.

```tsx
import { ArrowDown, ArrowUp } from 'lucide-react';
import type { ChartOfAccountView } from '@/api/types';
import { StatusBadge } from '@/components/StatusBadge';
import type { SortKey } from './accountTree';

const COLUMNS: { key: SortKey; label: string }[] = [
  { key: 'accountCode', label: 'Code' },
  { key: 'name', label: 'Account' },
  { key: 'accountType', label: 'Type' },
  { key: 'parentCode', label: 'Parent' },
  { key: 'level', label: 'Level' },
  { key: 'status', label: 'Status' },
];

/** The accountant's view: every account on one flat, sortable, searchable surface. */
export function AccountTableView({
  accounts,
  sortKey,
  sortDirection,
  onSort,
  renderActions,
}: {
  accounts: ChartOfAccountView[];
  sortKey: SortKey;
  sortDirection: 'asc' | 'desc';
  onSort: (key: SortKey) => void;
  renderActions: (account: ChartOfAccountView) => React.ReactNode;
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border text-left text-xs text-muted-foreground">
            {COLUMNS.map((column) => (
              <th
                key={column.key}
                scope="col"
                className="px-3 py-2 font-medium"
                aria-sort={
                  sortKey === column.key
                    ? sortDirection === 'asc'
                      ? 'ascending'
                      : 'descending'
                    : 'none'
                }
              >
                <button
                  type="button"
                  onClick={() => onSort(column.key)}
                  className="flex items-center gap-1 hover:text-foreground"
                >
                  {column.label}
                  {sortKey === column.key &&
                    (sortDirection === 'asc' ? (
                      <ArrowUp className="h-3 w-3" aria-hidden="true" />
                    ) : (
                      <ArrowDown className="h-3 w-3" aria-hidden="true" />
                    ))}
                </button>
              </th>
            ))}
            <th scope="col" className="px-3 py-2" />
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {accounts.map((account) => (
            <tr key={account.accountCode} className="hover:bg-hover">
              <td className="px-3 py-2 font-mono text-xs text-muted-foreground">
                {account.accountCode}
              </td>
              <td className="px-3 py-2 font-medium">{account.name}</td>
              <td className="px-3 py-2 text-xs text-muted-foreground">{account.accountType}</td>
              <td className="px-3 py-2 font-mono text-xs text-muted-foreground">
                {account.parentCode ?? '—'}
              </td>
              {/* Right-aligned: it is a number, and this console right-aligns numeric columns. */}
              <td className="px-3 py-2 text-right tabular-nums">{account.level}</td>
              <td className="px-3 py-2">
                <StatusBadge kind="account" value={account.status} />
              </td>
              <td className="px-3 py-2 text-right">{renderActions(account)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
```

- [ ] **Step 4: Rewrite `ChartOfAccountsPage.tsx`**

Keep the existing `CreateAccountForm`, `DeleteAccountForm` and the renamed `UpdateAccountForm` **as they are** apart from the new fields, and replace the flat `renderBody()` list with: a view toggle, a filter bar, and whichever view is selected.

Key behaviours to implement:

```tsx
  const [searchParams, setSearchParams] = useSearchParams();
  const view = searchParams.get('view') === 'table' ? 'table' : 'tree';
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [filters, setFilters] = useState<AccountFilters>({
    search: '',
    types: [],
    statuses: [],
    postingOnly: false,
  });
  const [sortKey, setSortKey] = useState<SortKey>('accountCode');
  const [sortDirection, setSortDirection] = useState<'asc' | 'desc'>('asc');

  const rows = accounts.data ?? [];
  const filtered = useMemo(() => filterAccounts(rows, filters), [rows, filters]);
  // The TREE ignores filters other than search on purpose: hiding a parent would hide
  // matching children with it, and a hierarchy with holes in it misleads. A search in
  // tree view instead auto-expands the branches that contain a match.
  const tree = useMemo(() => buildAccountTree(rows), [rows]);
  const table = useMemo(
    () => sortAccounts(filtered, sortKey, sortDirection),
    [filtered, sortKey, sortDirection],
  );
```

Roots start expanded on first load:

```tsx
  useEffect(() => {
    if (rows.length > 0 && expanded.size === 0) {
      setExpanded(new Set(rows.filter((a) => !a.parentCode).map((a) => a.accountCode)));
    }
  }, [rows, expanded.size]);
```

Search debounce, matching the console's existing 300 ms list-search idiom:

```tsx
  const [searchInput, setSearchInput] = useState('');
  useEffect(() => {
    const timer = setTimeout(() => setFilters((f) => ({ ...f, search: searchInput })), 300);
    return () => clearTimeout(timer);
  }, [searchInput]);
```

The toggle writes to the URL so a view is shareable, exactly as the GL postings filter already does:

```tsx
  <div className="flex items-center gap-1" role="group" aria-label="View">
    <Button
      size="sm"
      variant={view === 'tree' ? 'primary' : 'ghost'}
      aria-pressed={view === 'tree'}
      onClick={() => setSearchParams((p) => { p.set('view', 'tree'); return p; })}
    >
      Tree
    </Button>
    <Button
      size="sm"
      variant={view === 'table' ? 'primary' : 'ghost'}
      aria-pressed={view === 'table'}
      onClick={() => setSearchParams((p) => { p.set('view', 'table'); return p; })}
    >
      Table
    </Button>
  </div>
```

Row actions become a shared `renderActions` passed to both views: **Rename**, **Retire**/**Restore** (calling `setAccountStatus`), and **Delete**. Keep every existing button label byte-for-byte where it already exists (`Rename`, `Delete`, `Delete account`, `New account`, `Create account`, `Cancel`) — `staff-finaccounting.spec.ts` selects on all of them.

The type/status/posting filter chips use the existing `FilterChip` with `aria-pressed`, and the empty state after filtering says so rather than claiming the tenant has no accounts:

```tsx
  if (filtered.length === 0 && rows.length > 0) {
    return <EmptyState title="No matching accounts" description="No account matches these filters." />;
  }
```

- [ ] **Step 5: Typecheck, lint and run the whole unit suite**

Run: `cd frontend && npx tsc --noEmit ; npx eslint src/features/finaccounting src/lib/status.ts ; npx vitest run`
Expected: typecheck clean, no lint errors, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/finaccounting/ frontend/src/lib/status.ts frontend/src/lib/status.test.ts
git commit -m "feat(console): read the chart as a tree, or work it as a table"
```

---

### Task 9: End-to-end

**Files:**
- Modify: `frontend/e2e/staff-finaccounting.spec.ts`

**Interfaces:**
- Consumes: the live backend from Tasks 1–5 and the UI from Task 8.
- Produces: nothing consumed by later tasks — this is the last task.

**Before starting:** V5 must be applied to the dev database and the backend restarted. A green Testcontainers run does not put the migration into the dev DB.

- [ ] **Step 1: Apply the migration to the dev database and restart**

Run the repo's normal dev migration path (`backend/scripts/migrate.sh`, or `psql` against the dev database with `backend/db-migrations/finaccounting/V5__chart_of_account_hierarchy.sql`), then restart the backend.

Verify: `psql -c "SELECT count(*) FROM finaccounting.chart_of_account;"` → 36 per tenant.

- [ ] **Step 2: Fix the row selector, which this work breaks**

`accountRow()` currently locates a row by the class combination `span.font-mono.text-xs.text-muted-foreground` and climbs three `..` levels. The tree and table rows have different depths, so it cannot survive. Replace it with a role-based lookup that works in both views:

```ts
/**
 * A row in either view. The tree renders `treeitem`s and the table renders `row`s, so
 * this asks for whichever the current view provides rather than climbing a fixed number
 * of DOM levels from a class combination -- the shape that broke when the flat list
 * became two views.
 */
function accountRow(page: Page, code: string) {
  return page.getByRole('treeitem').filter({ hasText: code })
    .or(page.getByRole('row').filter({ hasText: code }));
}
```

- [ ] **Step 3: Update the two tests that name migrated codes**

`chart of accounts lists the real seeded placeholder accounts` asserts on `1200` and describes it as Premium Receivable. Both halves are now wrong — `1200` is the Receivables header and `1210` is Premium Receivables:

```ts
  test('chart of accounts lists the real seeded placeholder accounts', async ({ page }) => {
    await page.goto('/staff/chart-of-accounts');
    await expect(page.getByRole('heading', { name: 'Chart of accounts' })).toBeVisible();
    // 1210 Premium Receivables / 2140 Unearned Premium is the real accrual pair every
    // premium invoice posts against (PostingRule.PREMIUM_RECEIVABLE / UNEARNED_PREMIUM).
    await expect(page.getByText('1210')).toBeVisible();
  });
```

`blocks deleting an account a real GL posting already references` must target `1210`, which is the account postings now land on. Keep the assertion; change only the code.

- [ ] **Step 4: Update the create/rename/delete lifecycle test**

An account now needs a parent whose block it sits inside. `5${Date.now() % 1000}` produces codes like `5123`, which is inside `5100`'s block only when it starts `51`. Pin the parent explicitly and derive a code that satisfies the prefix rule:

```ts
  test('creates an account under a parent, renames it, retires it, then deletes it', async ({
    page,
  }) => {
    // 53xx sits inside 5300 Operating Expenses' block, which the prefix rule requires.
    const code = `53${String(Date.now() % 100).padStart(2, '0')}`;
    const name = `E2E Expense ${Date.now()}`;

    await page.goto('/staff/chart-of-accounts?view=table');
    await page.getByRole('button', { name: 'New account' }).click();
    await page.getByLabel('Account code').fill(code);
    await page.getByLabel('Parent account').fill('5300');
    await page.getByLabel('Name').fill(name);
    await page.getByRole('button', { name: 'Create account' }).click();

    const row = accountRow(page, code);
    await expect(row.getByText(name)).toBeVisible({ timeout: 15_000 });
    await expect(row.getByText('EXPENSE', { exact: true })).toBeVisible();

    // The BUTTON says "Retire"; the BADGE says "Inactive", because StatusBadge renders
    // humanizeStatus() of the raw backend literal and never a hand-written label.
    await row.getByRole('button', { name: 'Retire' }).click();
    await expect(accountRow(page, code).getByText('Inactive')).toBeVisible({ timeout: 15_000 });

    await accountRow(page, code).getByRole('button', { name: 'Delete' }).click();
    await accountRow(page, code).getByRole('button', { name: 'Delete account' }).click();
    await expect(accountRow(page, code)).toHaveCount(0, { timeout: 15_000 });
  });
```

Update the duplicate-code test the same way (a `43xx` code under `4300 Other Income`).

- [ ] **Step 5: Add the two new tests this work earns**

```ts
  test('the tree expands and collapses a branch, and the view is shareable through the URL', async ({
    page,
  }) => {
    await page.goto('/staff/chart-of-accounts');
    // Roots open by default; 1200 Receivables is one level down and starts collapsed.
    await expect(page.getByText('Receivables', { exact: true })).toBeVisible();
    await expect(page.getByText('Premium Receivables')).not.toBeVisible();

    await page.getByRole('button', { name: 'Expand Receivables' }).click();
    await expect(page.getByText('Premium Receivables')).toBeVisible();

    await page.getByRole('button', { name: 'Collapse Receivables' }).click();
    await expect(page.getByText('Premium Receivables')).not.toBeVisible();

    await page.getByRole('button', { name: 'Table' }).click();
    await expect(page).toHaveURL(/view=table/);
    // Every account is flat in the table, whatever the tree had collapsed.
    await expect(page.getByRole('row').filter({ hasText: 'Premium Receivables' })).toBeVisible();
  });

  test('the table searches and sorts the whole chart', async ({ page }) => {
    await page.goto('/staff/chart-of-accounts?view=table');
    await page.getByPlaceholder('Search code or name').fill('reinsurance');
    await expect(page.getByRole('row').filter({ hasText: 'Reinsurance Recoverable' })).toBeVisible();
    await expect(page.getByRole('row').filter({ hasText: 'Petty Cash' })).toHaveCount(0);

    await page.getByPlaceholder('Search code or name').fill('');
    await page.getByRole('button', { name: 'Code' }).click();
    const first = page.getByRole('row').nth(1);
    await expect(first).toContainText('5500');
  });
```

- [ ] **Step 6: Run the finaccounting e2e**

Run: `cd frontend && npx playwright test e2e/staff-finaccounting.spec.ts`
Expected: PASS, every test in the file.

- [ ] **Step 7: Run the whole e2e suite**

Run: `cd frontend && npm run test:e2e`
Expected: PASS. A component rewrite has broken unrelated specs on this console three times before; the full suite is the only thing that proves it did not happen again. Any failure here is part of this task, not a follow-up.

- [ ] **Step 8: Commit**

```bash
git add frontend/e2e/staff-finaccounting.spec.ts
git commit -m "test(console): drive the tree, the table and the migrated codes for real"
```

---

## Verification

After Task 9, the whole change is verified by:

```bash
cd backend && ./mvnw clean test
cd frontend && npx tsc --noEmit && npx eslint . && npx vitest run && npm run test:e2e
```

All four must pass. The backend suite was 897 tests before this work; it gains the blueprint, domain, migration and contract tests added above.
