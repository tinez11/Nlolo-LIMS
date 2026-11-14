# Shareholders Register Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A register of shareholders and share issues in the finance module; approving an issue posts Dr bank / Cr share capital / Cr share premium; three capital checks on the page and in the capital adequacy report.

**Architecture:** `finaccounting` gains a JDBC service `ShareRegister` (same shape as `BaselineSignOffs`) over four new tables, a pure `ShareIssueRules` (split and validation) and a pure `CapitalChecks`. Approval reuses the manual-journal mechanics: `OwnWork` for acting alone, a MANUAL `JournalEntry` posted through `FinaccountingApiImpl.postEntry`, and an `ApprovalSource` "share-issue". `reporting` reads the checks through a new `finaccounting.api.ShareRegisterApi`. The console gets one page, **Ledger > Shareholders**, below Manual journals.

**Tech Stack:** Spring Boot 3 / Java 21, JdbcTemplate, PostgreSQL 16 (RLS), Testcontainers; React 19 + TypeScript, openapi-typescript, Vitest, Playwright.

**Spec:** `backend/docs/superpowers/specs/2026-11-14-shareholders-register-design.md`

## Global Constraints

- Sidebar: **Ledger** group, item **Shareholders** directly below **Manual journals**.
- Permissions: view `ledger.view`; record `ledger.manage`; approve or reject `ledger.approve`; approve own `ledger.approve-own`. No new permissions.
- Posting: Dr chosen bank (an account under 1100, posting allowed, mode MAN or BOTH) = amount paid; Cr 3110 (ordinary) or 3130 (preference) = shares × nominal; Cr 3120 = amount paid − shares × nominal. Price below nominal refused. Fully paid only: amount paid = shares × price.
- The journal's period is the month of the date the money was received; a LOCKED month is refused with "That month is locked". A future date is refused.
- Minimum paid-up capital starts empty and shows **"Not set"**; the platform never guesses TIRA's figure.
- Tanzanian ownership check: Tanzanian shareholders hold ≥ one-third of issued shares.
- Dev seed: "Demo Holdings Ltd" (company, Tanzanian), 1,000,000 ordinary shares at nominal and price TZS 1,000 into 1110.
- Simple English in every message; money shown in full (`1,000,000,000.00`).
- Host Maven only: `export JAVA_HOME=~/.jdks/jdk-21.0.12.1+1` then `./mvnw` in `backend/`. Never in Docker.
- Stop the dev backend before backend test runs; restart it after (with `.env` sourced, `SMS_GATEWAY_LIVE=true`, `SMS_GATEWAY_ALLOWED_RECIPIENTS=255755418369`).
- One heavy run at a time (Maven, vitest, Playwright never together). Logs to the scratchpad only, never the Desktop.
- Edit files with Write/Edit, never shell heredocs/sed for code. Never run Prettier. Never `git add backend/docs` as a directory (the two PDFs there are the user's).
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## File Map

Backend (`backend/`):
- Create `db-migrations/finaccounting/V22__share_register.sql` — four tables, RLS, grants, two-person CHECK.
- Create `db-migrations/refdata/V12__share_capital_reason_code.sql` — JOURNAL_REASON `SHARE_CAPITAL`.
- Create `src/main/java/.../finaccounting/domain/ShareIssueRules.java` — pure split + validation.
- Create `src/main/java/.../finaccounting/domain/CapitalChecks.java` — pure three checks.
- Create `src/main/java/.../finaccounting/api/ShareRegisterApi.java` — interface + views + inputs.
- Create `src/main/java/.../finaccounting/api/ShareRegisterStateException.java`, `ShareRegisterNotFoundException.java`.
- Create `src/main/java/.../finaccounting/application/ShareRegister.java` — implementation.
- Create `src/main/java/.../finaccounting/infrastructure/ShareRegisterController.java`.
- Modify `src/main/java/.../finaccounting/infrastructure/FinaccountingExceptionHandler.java` — 409/404 mapping.
- Modify `src/main/java/.../finaccounting/infrastructure/FinanceApprovalSources.java` — "share-issue" source.
- Modify `src/main/java/.../approvals/application/Approvals.java` — ORDER and OWN_WORK.
- Modify `src/main/java/.../reporting/application/CapitalAdequacyReport.java` — a "Paid-up capital" section.
- Modify `api/openapi/openapi-finaccounting.yaml` — paths and schemas.
- Modify `scripts/seed-dev-data.sh` — the demo issue.
- Tests: `src/test/java/.../finaccounting/domain/ShareIssueRulesTest.java`, `CapitalChecksTest.java`, `src/test/java/.../finaccounting/application/ShareRegisterIntegrationTest.java`; every test migration list naming `finaccounting/V21__baseline_sign_off.sql`.

Frontend (`frontend/`):
- Modify `src/api/types.ts`, `src/api/finaccounting.ts`; regenerate `src/types/api/finaccounting.ts`.
- Create `src/features/finance/shareRegister.ts` (+ `.test.ts`) — pure helpers.
- Create `src/features/finance/ShareholdersPage.tsx` (+ `.test.tsx`).
- Modify `src/screens.tsx` — route, permission map, sidebar item.
- Modify `src/features/approvals/approvalLinks.ts` — `'share-issue'`.
- Create `e2e/staff-shareholders.spec.ts`.

`...` = `tz/co/nlolo/lifeplatform`.

---

### Task 1: Migrations

**Files:**
- Create: `backend/db-migrations/finaccounting/V22__share_register.sql`
- Create: `backend/db-migrations/refdata/V12__share_capital_reason_code.sql`
- Modify: every test file listing `db-migrations/finaccounting/V21__baseline_sign_off.sql` (22 files)

**Interfaces:**
- Produces: tables `finaccounting.shareholder`, `finaccounting.share_class`, `finaccounting.share_issue`, `finaccounting.capital_minimum`; reason code `SHARE_CAPITAL`.

- [ ] **Step 1: Write V22**

```sql
-- 2026-11-14, shareholders register (spec 2026-11-14-shareholders-register-design.md): who holds the company's shares,
-- each issue of shares proposed by one person and approved by a second (or by the proposer holding ledger.approve-own,
-- acted_alone), and the minimum paid-up capital finance enters from TIRA. An approved issue posts its journal; nothing
-- here is edited after approval. Re-runnable: migrate.sh is not idempotent.

CREATE TABLE IF NOT EXISTS finaccounting.shareholder (
    shareholder_id   UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    name             VARCHAR(200) NOT NULL CHECK (length(trim(name)) > 0),
    kind             VARCHAR(8) NOT NULL CHECK (kind IN ('PERSON','COMPANY')),
    tanzanian        BOOLEAN NOT NULL,
    id_number        VARCHAR(60) NOT NULL CHECK (length(trim(id_number)) > 0),
    phone            VARCHAR(40),
    address          VARCHAR(300),
    created_by       VARCHAR(100) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id_number)
);

CREATE TABLE IF NOT EXISTS finaccounting.share_class (
    tenant_id        UUID NOT NULL,
    class_code       VARCHAR(10) NOT NULL CHECK (class_code IN ('ORDINARY','PREFERENCE')),
    nominal_value    NUMERIC(19,2) NOT NULL CHECK (nominal_value > 0),
    set_by           VARCHAR(100) NOT NULL,
    set_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, class_code)
);

CREATE TABLE IF NOT EXISTS finaccounting.share_issue (
    issue_id         UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    shareholder_id   UUID NOT NULL REFERENCES finaccounting.shareholder (shareholder_id),
    class_code       VARCHAR(10) NOT NULL,
    shares           BIGINT NOT NULL CHECK (shares > 0),
    price_per_share  NUMERIC(19,2) NOT NULL CHECK (price_per_share > 0),
    nominal_value    NUMERIC(19,2) NOT NULL CHECK (nominal_value > 0),
    amount_paid      NUMERIC(19,2) NOT NULL CHECK (amount_paid > 0),
    received_on      DATE NOT NULL,
    bank_account     VARCHAR(20) NOT NULL,
    reference        VARCHAR(200) NOT NULL CHECK (length(trim(reference)) > 0),
    status           VARCHAR(10) NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED','APPROVED','REJECTED','WITHDRAWN')),
    proposed_by      VARCHAR(100) NOT NULL,
    proposed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by       VARCHAR(100),
    decided_at       TIMESTAMPTZ,
    decision_reason  VARCHAR(500),
    journal_entry_id UUID,
    acted_alone      BOOLEAN NOT NULL DEFAULT false,
    FOREIGN KEY (tenant_id, class_code) REFERENCES finaccounting.share_class (tenant_id, class_code),
    CONSTRAINT share_issue_two_person CHECK (decided_by IS NULL OR decided_by <> proposed_by OR acted_alone
        OR status = 'WITHDRAWN'),
    CONSTRAINT share_issue_approved_posted CHECK (status <> 'APPROVED' OR journal_entry_id IS NOT NULL),
    CONSTRAINT share_issue_not_below_nominal CHECK (price_per_share >= nominal_value)
);
CREATE INDEX IF NOT EXISTS share_issue_tenant_status ON finaccounting.share_issue (tenant_id, status);

CREATE TABLE IF NOT EXISTS finaccounting.capital_minimum (
    tenant_id        UUID NOT NULL,
    set_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    minimum_paid_up  NUMERIC(19,2) CHECK (minimum_paid_up IS NULL OR minimum_paid_up > 0),
    set_by           VARCHAR(100) NOT NULL,
    PRIMARY KEY (tenant_id, set_at)
);

ALTER TABLE finaccounting.shareholder ENABLE ROW LEVEL SECURITY;
ALTER TABLE finaccounting.share_class ENABLE ROW LEVEL SECURITY;
ALTER TABLE finaccounting.share_issue ENABLE ROW LEVEL SECURITY;
ALTER TABLE finaccounting.capital_minimum ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS shareholder_tenant_isolation ON finaccounting.shareholder;
CREATE POLICY shareholder_tenant_isolation ON finaccounting.shareholder
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
DROP POLICY IF EXISTS share_class_tenant_isolation ON finaccounting.share_class;
CREATE POLICY share_class_tenant_isolation ON finaccounting.share_class
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
DROP POLICY IF EXISTS share_issue_tenant_isolation ON finaccounting.share_issue;
CREATE POLICY share_issue_tenant_isolation ON finaccounting.share_issue
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
DROP POLICY IF EXISTS capital_minimum_tenant_isolation ON finaccounting.capital_minimum;
CREATE POLICY capital_minimum_tenant_isolation ON finaccounting.capital_minimum
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON finaccounting.shareholder TO app_role;
GRANT SELECT, INSERT, UPDATE ON finaccounting.share_class TO app_role;
GRANT SELECT, INSERT, UPDATE ON finaccounting.share_issue TO app_role;
GRANT SELECT, INSERT ON finaccounting.capital_minimum TO app_role;
```

(`capital_minimum` is append-only: each change is a row, the latest is in force, so the history is its audit. A NULL row means "cleared, Not set".)

- [ ] **Step 2: Write refdata V12**

```sql
-- 2026-11-14, shareholders register: the reason code an approved share issue carries on bank 1110 (a BOTH account
-- needs one, V10's LEDGER_MODE guard).
INSERT INTO refdata.reference_code_set (code_set_key, code, label, value, jurisdiction) VALUES
    ('JOURNAL_REASON', 'SHARE_CAPITAL', 'Shares issued for cash', 'SHARE_CAPITAL', 'TZ')
ON CONFLICT DO NOTHING;
```

Check first that `refdata.reference_code_set` has a unique key `ON CONFLICT DO NOTHING` can use:
`grep -n "UNIQUE\|PRIMARY KEY" backend/db-migrations/refdata/V1__create_refdata_schema.sql`. If none covers (code_set_key, code), replace `ON CONFLICT DO NOTHING` with `WHERE NOT EXISTS (...)` as an `INSERT ... SELECT`.

- [ ] **Step 3: Add V22 to every test migration list**

Run from `backend/`: `grep -rl "finaccounting/V21__baseline_sign_off.sql" src/test` (expect 22 files). In each, use Edit to put `"db-migrations/finaccounting/V22__share_register.sql"` right after the V21 entry. Before editing a file, check it is not a list composed from another (e.g. `UnitLinkedTestMigrations` = `FuneralTestMigrations.ALL` + its own): never add V22 twice to one composed list. Refdata V12 is NOT added to test lists: the ledger guard checks only that a reason code is present, not that refdata knows it (verify: `grep -n "reason_code" backend/db-migrations/finaccounting/V10__ifrs17_ledger_foundation.sql` shows no foreign key to refdata).

- [ ] **Step 4: Prove the migration applies and RLS covers the tables**

Run: `./mvnw -B -o test -Dtest=TenantTableRlsCoverageIntegrationTest,AppRolePrivilegesIntegrationTest > "$SCRATCH/t1.log" 2>&1; tail -30 "$SCRATCH/t1.log"`
Expected: BUILD SUCCESS. (`TenantTableRlsCoverageIntegrationTest` reads every migration and fails a tenant table with no policy.)

- [ ] **Step 5: Commit**

```bash
git add backend/db-migrations/finaccounting/V22__share_register.sql backend/db-migrations/refdata/V12__share_capital_reason_code.sql backend/src/test
git commit -m "feat(ledger): share register tables

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Pure rules — the split and the checks

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/ShareIssueRules.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/CapitalChecks.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/domain/ShareIssueRulesTest.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/domain/CapitalChecksTest.java`

**Interfaces:**
- Produces:
  - `ShareIssueRules.Split ShareIssueRules.split(long shares, BigDecimal nominal, BigDecimal price, BigDecimal amountPaid)` → `record Split(BigDecimal capital, BigDecimal premium)`
  - `List<String> ShareIssueRules.problems(long shares, BigDecimal nominal, BigDecimal price, BigDecimal amountPaid, LocalDate receivedOn, LocalDate today)`
  - `static String ShareIssueRules.capitalAccount(String classCode)` → `"3110"` / `"3130"`; constant `PREMIUM_ACCOUNT = "3120"`
  - `CapitalChecks.Result CapitalChecks.of(BigDecimal registerCapital, BigDecimal ledgerCapital, long tanzanianShares, long allShares, BigDecimal minimum)` → `record Result(Check books, Check tanzanian, Check minimum)`, `record Check(String state, String figure)`; `state` ∈ `MET`, `NOT_MET`, `NOT_SET`.

- [ ] **Step 1: Write the failing tests**

```java
package tz.co.nlolo.lifeplatform.finaccounting.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

class ShareIssueRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);

    @Test
    void sharesAtNominalAreAllCapital() {
        var s = ShareIssueRules.split(1_000_000, new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("1000000000"));
        assertThat(s.capital()).isEqualByComparingTo("1000000000.00");
        assertThat(s.premium()).isEqualByComparingTo("0.00");
    }

    @Test
    void anythingAboveNominalIsPremium() {
        var s = ShareIssueRules.split(1_000_000, new BigDecimal("1000"), new BigDecimal("1500"), new BigDecimal("1500000000"));
        assertThat(s.capital()).isEqualByComparingTo("1000000000.00");
        assertThat(s.premium()).isEqualByComparingTo("500000000.00");
    }

    @Test
    void aDiscountAPartPaymentAndAFutureDateAreRefused() {
        assertThat(ShareIssueRules.problems(10, new BigDecimal("1000"), new BigDecimal("900"), new BigDecimal("9000"),
            TODAY, TODAY)).containsExactly("Shares cannot be issued below their nominal value of 1,000.00");
        assertThat(ShareIssueRules.problems(10, new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("9000"),
            TODAY, TODAY)).containsExactly("The amount paid must be shares × price: 10,000.00");
        assertThat(ShareIssueRules.problems(10, new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("10000"),
            TODAY.plusDays(1), TODAY)).containsExactly("The money cannot be received in the future");
        assertThat(ShareIssueRules.problems(10, new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("10000"),
            TODAY, TODAY)).isEmpty();
    }

    @Test
    void preferenceSharesGoToTheirOwnAccount() {
        assertThat(ShareIssueRules.capitalAccount("ORDINARY")).isEqualTo("3110");
        assertThat(ShareIssueRules.capitalAccount("PREFERENCE")).isEqualTo("3130");
    }
}
```

```java
package tz.co.nlolo.lifeplatform.finaccounting.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class CapitalChecksTest {

    @Test
    void allThreeMet() {
        var r = CapitalChecks.of(new BigDecimal("1000000000"), new BigDecimal("1000000000"), 400, 1000,
            new BigDecimal("1000000000"));
        assertThat(r.books().state()).isEqualTo("MET");
        assertThat(r.tanzanian().state()).isEqualTo("MET");
        assertThat(r.tanzanian().figure()).isEqualTo("40.0%");
        assertThat(r.minimum().state()).isEqualTo("MET");
    }

    @Test
    void aDifferenceUnderOneThirdAndShortCapitalAreNotMet() {
        var r = CapitalChecks.of(new BigDecimal("1000"), new BigDecimal("900"), 333, 1000, new BigDecimal("2000"));
        assertThat(r.books().state()).isEqualTo("NOT_MET");
        assertThat(r.books().figure()).isEqualTo("100.00");
        assertThat(r.tanzanian().state()).isEqualTo("NOT_MET");
        assertThat(r.tanzanian().figure()).isEqualTo("33.3%");
        assertThat(r.minimum().state()).isEqualTo("NOT_MET");
        assertThat(r.minimum().figure()).isEqualTo("1,100.00");   // short by
    }

    @Test
    void noMinimumIsNotSetAndNoSharesIsNotMet() {
        var r = CapitalChecks.of(BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, null);
        assertThat(r.minimum().state()).isEqualTo("NOT_SET");
        assertThat(r.minimum().figure()).isNull();
        assertThat(r.tanzanian().state()).isEqualTo("NOT_MET");
        assertThat(r.books().state()).isEqualTo("MET");
    }

    @Test
    void exactlyOneThirdIsMet() {
        assertThat(CapitalChecks.of(BigDecimal.ONE, BigDecimal.ONE, 1, 3, null).tanzanian().state()).isEqualTo("MET");
    }
}
```

- [ ] **Step 2: Run to see them fail**

Run: `./mvnw -B -o test -Dtest=ShareIssueRulesTest,CapitalChecksTest > "$SCRATCH/t2.log" 2>&1; grep -E "ERROR|Tests run" "$SCRATCH/t2.log" | head`
Expected: compilation failure, `ShareIssueRules` / `CapitalChecks` not found.

- [ ] **Step 3: Implement**

```java
package tz.co.nlolo.lifeplatform.finaccounting.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * An issue of shares for cash (2026-11-14, shareholders register): what of the money is share capital (shares ×
 * nominal value) and what is share premium (the rest), and what makes an issue impossible. Pure.
 */
public final class ShareIssueRules {

    public static final String PREMIUM_ACCOUNT = "3120";

    private ShareIssueRules() {}

    public record Split(BigDecimal capital, BigDecimal premium) {}

    public static Split split(long shares, BigDecimal nominal, BigDecimal price, BigDecimal amountPaid) {
        BigDecimal capital = nominal.multiply(BigDecimal.valueOf(shares)).setScale(2, RoundingMode.HALF_UP);
        return new Split(capital, amountPaid.setScale(2, RoundingMode.HALF_UP).subtract(capital));
    }

    public static List<String> problems(long shares, BigDecimal nominal, BigDecimal price, BigDecimal amountPaid,
                                        LocalDate receivedOn, LocalDate today) {
        List<String> problems = new ArrayList<>();
        if (price.compareTo(nominal) < 0) {
            problems.add("Shares cannot be issued below their nominal value of " + money(nominal));
        }
        BigDecimal due = price.multiply(BigDecimal.valueOf(shares)).setScale(2, RoundingMode.HALF_UP);
        if (price.compareTo(nominal) >= 0 && amountPaid.setScale(2, RoundingMode.HALF_UP).compareTo(due) != 0) {
            problems.add("The amount paid must be shares × price: " + money(due));
        }
        if (receivedOn.isAfter(today)) {
            problems.add("The money cannot be received in the future");
        }
        return problems;
    }

    public static String capitalAccount(String classCode) {
        return "PREFERENCE".equals(classCode) ? "3130" : "3110";
    }

    static String money(BigDecimal v) {
        return new DecimalFormat("#,##0.00").format(v.setScale(2, RoundingMode.HALF_UP));
    }
}
```

```java
package tz.co.nlolo.lifeplatform.finaccounting.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The three questions about the company's capital (2026-11-14): does the register agree with the books, do
 * Tanzanians hold at least one-third of the shares, and is paid-up capital at least the minimum finance entered.
 * Pure. {@code figure}: the difference, the Tanzanian share, or how much is short (null when met or not set).
 */
public final class CapitalChecks {

    public static final String MET = "MET";
    public static final String NOT_MET = "NOT_MET";
    public static final String NOT_SET = "NOT_SET";

    private CapitalChecks() {}

    public record Check(String state, String figure) {}

    public record Result(Check books, Check tanzanian, Check minimum) {}

    public static Result of(BigDecimal registerCapital, BigDecimal ledgerCapital, long tanzanianShares, long allShares,
                            BigDecimal minimum) {
        BigDecimal difference = registerCapital.subtract(ledgerCapital).abs();
        Check books = difference.signum() == 0 ? new Check(MET, null)
            : new Check(NOT_MET, ShareIssueRules.money(difference));

        Check tanzanian;
        if (allShares == 0) {
            tanzanian = new Check(NOT_MET, null);
        } else {
            BigDecimal percent = BigDecimal.valueOf(tanzanianShares).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(allShares), 1, RoundingMode.HALF_UP);
            boolean met = tanzanianShares * 3 >= allShares;   // exact: at least one-third
            tanzanian = new Check(met ? MET : NOT_MET, percent.toPlainString() + "%");
        }

        Check min;
        if (minimum == null) {
            min = new Check(NOT_SET, null);
        } else {
            BigDecimal short_ = minimum.subtract(ledgerCapital);
            min = short_.signum() <= 0 ? new Check(MET, null) : new Check(NOT_MET, ShareIssueRules.money(short_));
        }
        return new Result(books, tanzanian, min);
    }
}
```

Note `tanzanian().figure()` is shown also when MET (the percentage held) — the first test expects `"40.0%"`.

- [ ] **Step 4: Run to see them pass**

Run: `./mvnw -B -o test -Dtest=ShareIssueRulesTest,CapitalChecksTest > "$SCRATCH/t2.log" 2>&1; grep -E "Tests run|BUILD" "$SCRATCH/t2.log" | tail -3`
Expected: `Tests run: 8, Failures: 0`, BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/ShareIssueRules.java backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/CapitalChecks.java backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/domain/ShareIssueRulesTest.java backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/domain/CapitalChecksTest.java
git commit -m "feat(ledger): share issue split and the three capital checks

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: The register service — propose, decide, post

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/api/ShareRegisterApi.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/api/ShareRegisterStateException.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/api/ShareRegisterNotFoundException.java`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/application/ShareRegister.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/application/ShareRegisterIntegrationTest.java`

**Interfaces:**
- Consumes: Task 2's `ShareIssueRules`, `CapitalChecks`; existing `FinaccountingApiImpl.postEntry(JournalEntry)` (package-private, same package), `AccountingPeriods.view(String)`, `ChartOfAccountSeeder.seedIfAbsent(UUID, String)`, `ChartOfAccountRepository.findByTenantIdOrderByAccountCodeAsc(UUID)`, `FinaccountingApi.trialBalance(String)`, `approvals.api.OwnWork`.
- Produces (`ShareRegisterApi`):

```java
package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The shareholders register (2026-11-14): who holds the shares, each issue, and the three capital checks. */
public interface ShareRegisterApi {

    record ShareholderInput(String name, String kind, boolean tanzanian, String idNumber, String phone, String address) {}

    record ShareholderView(UUID shareholderId, String name, String kind, boolean tanzanian, String idNumber, String phone,
                           String address, long shares, BigDecimal paid) {}

    record ShareClassView(String classCode, BigDecimal nominalValue, String setBy, Instant setAt, boolean issued) {}

    record IssueInput(UUID shareholderId, String classCode, long shares, BigDecimal pricePerShare, BigDecimal amountPaid,
                      LocalDate receivedOn, String bankAccount, String reference) {}

    record IssueView(UUID issueId, UUID shareholderId, String shareholderName, String classCode, long shares,
                     BigDecimal pricePerShare, BigDecimal nominalValue, BigDecimal amountPaid, BigDecimal shareCapital,
                     BigDecimal sharePremium, LocalDate receivedOn, String bankAccount, String reference, String status,
                     String proposedBy, Instant proposedAt, String decidedBy, Instant decidedAt, String decisionReason,
                     UUID journalEntryId, boolean actedAlone) {}

    record CheckView(String state, String figure) {}

    record CapitalView(BigDecimal paidUpCapital, BigDecimal ledgerCapital, long issuedShares, BigDecimal minimumPaidUp,
                       String minimumSetBy, Instant minimumSetAt, CheckView books, CheckView tanzanian,
                       CheckView minimum) {}

    record BankAccountView(String code, String name) {}

    List<ShareholderView> shareholders();
    ShareholderView addShareholder(ShareholderInput input, String by);
    ShareholderView updateShareholder(UUID id, ShareholderInput input, String by);

    List<ShareClassView> classes();
    ShareClassView setNominal(String classCode, BigDecimal nominalValue, String by);

    List<IssueView> issues(String status);
    IssueView propose(IssueInput input, String by);
    IssueView withdraw(UUID issueId, String by);
    IssueView approve(UUID issueId, String by);
    IssueView reject(UUID issueId, String reason, String by);

    CapitalView capital();
    CapitalView setMinimum(BigDecimal minimumPaidUp, String by);

    /** The accounts a share issue may debit: under 1100, posting allowed, MAN or BOTH. */
    List<BankAccountView> bankAccounts();
}
```

Exceptions (each one line body, as `ManualJournalStateException`):

```java
package tz.co.nlolo.lifeplatform.finaccounting.api;

/** A register step its state or its people refuse (2026-11-14): deciding one's own issue, a locked month. 409. */
public class ShareRegisterStateException extends RuntimeException {
    public ShareRegisterStateException(String message) { super(message); }
}
```

```java
package tz.co.nlolo.lifeplatform.finaccounting.api;

/** No such shareholder, class or issue for this tenant (2026-11-14). 404. */
public class ShareRegisterNotFoundException extends RuntimeException {
    public ShareRegisterNotFoundException(String message) { super(message); }
}
```

- [ ] **Step 1: Write the failing integration test**

Copy `ManualJournalIntegrationTest`'s harness exactly: `@Testcontainers`, `@SpringBootTest(classes = Application.class)`, the same `POSTGRES` container, `datasourceProperties`, and the same `applyMigrationsAndBootstrapAppRole` migration list (it now ends with V22 after Task 1), the `signedInAs` helper and `@AfterEach clearTenant`. Then:

```java
    @Autowired private ShareRegisterApi register;
    @Autowired private FinaccountingApi ledger;
    @Autowired private JdbcTemplate jdbc;

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    private UUID demoHoldings() {
        register.setNominal("ORDINARY", new BigDecimal("1000"), "finance-one");
        return register.addShareholder(new ShareRegisterApi.ShareholderInput("Demo Holdings Ltd", "COMPANY", true,
            "REG-001", null, null), "finance-one").shareholderId();
    }

    private ShareRegisterApi.IssueInput issue(UUID holder, long shares, String price, LocalDate on) {
        BigDecimal p = new BigDecimal(price);
        return new ShareRegisterApi.IssueInput(holder, "ORDINARY", shares, p, p.multiply(BigDecimal.valueOf(shares)),
            on, "1110", "Board resolution 2026/01");
    }

    @Test
    void anIssueApprovedByASecondPersonPostsCapitalAndPremium() {
        TenantContext.set(UUID.randomUUID());
        UUID holder = demoHoldings();
        var proposed = register.propose(issue(holder, 1_000_000, "1500", TODAY), "finance-one");
        assertThat(proposed.status()).isEqualTo("PROPOSED");
        assertThat(proposed.shareCapital()).isEqualByComparingTo("1000000000.00");
        assertThat(proposed.sharePremium()).isEqualByComparingTo("500000000.00");
        assertThat(register.capital().ledgerCapital()).isEqualByComparingTo("0");

        assertThatThrownBy(() -> register.approve(proposed.issueId(), "finance-one"))
            .isInstanceOf(ShareRegisterStateException.class)
            .hasMessage("You recorded this issue; a second person must approve it");

        var approved = register.approve(proposed.issueId(), "finance-approver");
        assertThat(approved.status()).isEqualTo("APPROVED");
        String period = YearMonth.from(TODAY).toString();
        assertThat(jdbc.queryForObject("SELECT period FROM finaccounting.journal_entry WHERE journal_entry_id = ?",
            String.class, approved.journalEntryId())).isEqualTo(period);
        assertThat(jdbc.queryForList("SELECT account_code || ' ' || direction || ' ' || amount FROM finaccounting.gl_posting"
            + " WHERE journal_entry_id = ? ORDER BY account_code", String.class, approved.journalEntryId()))
            .containsExactly("1110 DR 1500000000.00", "3110 CR 1000000000.00", "3120 CR 500000000.00");

        var capital = register.capital();
        assertThat(capital.paidUpCapital()).isEqualByComparingTo("1500000000.00");
        assertThat(capital.ledgerCapital()).isEqualByComparingTo("1500000000.00");
        assertThat(capital.books().state()).isEqualTo("MET");
        assertThat(capital.tanzanian().state()).isEqualTo("MET");
        assertThat(capital.minimum().state()).isEqualTo("NOT_SET");
        assertThat(register.shareholders()).singleElement().satisfies(s -> assertThat(s.shares()).isEqualTo(1_000_000));
    }

    @Test
    void theRecorderMayApproveAloneOnlyWithApproveOwn() {
        TenantContext.set(UUID.randomUUID());
        UUID holder = demoHoldings();
        var proposed = register.propose(issue(holder, 10, "1000", TODAY), "finance-alone");
        signedInAs("finance-alone", "ROLE_FINANCE_OFFICER", "ROLE_APPROVE_OWN_WORK");
        try {
            var approved = register.approve(proposed.issueId(), "finance-alone");
            assertThat(approved.actedAlone()).isTrue();
            assertThat(jdbc.queryForObject("SELECT acted_alone FROM finaccounting.journal_entry WHERE journal_entry_id = ?",
                Boolean.class, approved.journalEntryId())).isTrue();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void aRejectionSaysWhyAndPostsNothing() {
        TenantContext.set(UUID.randomUUID());
        UUID holder = demoHoldings();
        var proposed = register.propose(issue(holder, 10, "1000", TODAY), "finance-one");
        assertThatThrownBy(() -> register.reject(proposed.issueId(), " ", "finance-approver"))
            .isInstanceOf(FinaccountingValidationException.class);
        var rejected = register.reject(proposed.issueId(), "Wrong shareholder", "finance-approver");
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.journalEntryId()).isNull();
        assertThat(register.capital().ledgerCapital()).isEqualByComparingTo("0");
    }

    @Test
    void aDiscountAFutureDateAndAnAutoBankAreRefused() {
        TenantContext.set(UUID.randomUUID());
        UUID holder = demoHoldings();
        assertThatThrownBy(() -> register.propose(issue(holder, 10, "900", TODAY), "finance-one"))
            .isInstanceOf(FinaccountingValidationException.class).hasMessageContaining("below their nominal value");
        assertThatThrownBy(() -> register.propose(issue(holder, 10, "1000", TODAY.plusDays(1)), "finance-one"))
            .isInstanceOf(FinaccountingValidationException.class).hasMessageContaining("future");
        var autoBank = new ShareRegisterApi.IssueInput(holder, "ORDINARY", 10, new BigDecimal("1000"),
            new BigDecimal("10000"), TODAY, "1120", "Board resolution 2026/01");
        assertThatThrownBy(() -> register.propose(autoBank, "finance-one"))
            .isInstanceOf(FinaccountingValidationException.class).hasMessageContaining("1120");
    }

    @Test
    void theNominalValueIsFixedOnceSharesAreIssuedAndTheMinimumKeepsItsHistory() {
        TenantContext.set(UUID.randomUUID());
        UUID holder = demoHoldings();
        register.approve(register.propose(issue(holder, 10, "1000", TODAY), "finance-one").issueId(), "finance-approver");
        assertThatThrownBy(() -> register.setNominal("ORDINARY", new BigDecimal("500"), "finance-one"))
            .isInstanceOf(ShareRegisterStateException.class).hasMessageContaining("already issued");

        register.setMinimum(new BigDecimal("1000000000"), "finance-one");
        var c = register.capital();
        assertThat(c.minimum().state()).isEqualTo("NOT_MET");
        register.setMinimum(null, "finance-one");
        assertThat(register.capital().minimum().state()).isEqualTo("NOT_SET");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finaccounting.capital_minimum", Integer.class)).isEqualTo(2);
    }

    @Test
    void anIssueIntoALockedMonthIsRefusedAtApproval() {
        TenantContext.set(UUID.randomUUID());
        UUID holder = demoHoldings();
        LocalDate lastMonth = TODAY.minusMonths(1).withDayOfMonth(1);
        var proposed = register.propose(issue(holder, 10, "1000", lastMonth), "finance-one");
        // Lock last month the way the month-end close does: read AccountingPeriods' lock method in
        // finaccounting/application/AccountingPeriods.java and call that here (or the SQL it runs).
        lockPeriod(YearMonth.from(lastMonth).toString());
        assertThatThrownBy(() -> register.approve(proposed.issueId(), "finance-approver"))
            .isInstanceOf(ShareRegisterStateException.class).hasMessage("That month is locked");
    }
```

For `lockPeriod`, open `AccountingPeriodAndPolicyRegisterIntegrationTest` (same folder) and copy the helper it uses to reach LOCKED; if it locks through `FinaccountingApi` calls (close then lock), copy those calls into a private `lockPeriod(String period)` here.

- [ ] **Step 2: Run to see it fail**

Run: `./mvnw -B -o test -Dtest=ShareRegisterIntegrationTest > "$SCRATCH/t3.log" 2>&1; grep -E "ERROR|Tests run" "$SCRATCH/t3.log" | head`
Expected: compilation failure (`ShareRegisterApi` missing) until the API files exist; then failures for the missing bean.

- [ ] **Step 3: Implement `ShareRegister`**

```java
package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.approvals.api.OwnWork;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.ShareRegisterApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.ShareRegisterNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.ShareRegisterStateException;
import tz.co.nlolo.lifeplatform.finaccounting.domain.CapitalChecks;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccount;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ShareIssueRules;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountRepository;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountSeeder;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The shareholders register (2026-11-14, spec 2026-11-14-shareholders-register-design.md). An issue is recorded by
 * one person and approved by a second -- or by the recorder holding ledger.approve-own -- and approving it posts one
 * MANUAL journal on the day the money came: Dr the bank, Cr share capital (shares × nominal), Cr share premium (the
 * rest). The register and the ledger change in one transaction, so they cannot drift apart.
 */
@Service
public class ShareRegister implements ShareRegisterApi {

    static final String EVENT = "finaccounting.ShareIssue";
    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");
    private static final Set<String> CAPITAL_ACCOUNTS = Set.of("3110", "3120", "3130");

    private final JdbcTemplate jdbc;
    private final FinaccountingApiImpl ledger;
    private final FinaccountingApi finance;
    private final AccountingPeriods periods;
    private final ChartOfAccountSeeder seeder;
    private final ChartOfAccountRepository chart;
    private final OwnWork ownWork;

    ShareRegister(JdbcTemplate jdbc, FinaccountingApiImpl ledger, FinaccountingApi finance, AccountingPeriods periods,
                  ChartOfAccountSeeder seeder, ChartOfAccountRepository chart, OwnWork ownWork) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.finance = finance;
        this.periods = periods;
        this.seeder = seeder;
        this.chart = chart;
        this.ownWork = ownWork;
    }

    // ---- shareholders ---------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<ShareholderView> shareholders() {
        return jdbc.query("SELECT s.*, COALESCE(sum(i.shares) FILTER (WHERE i.status = 'APPROVED'), 0) AS held,"
                + " COALESCE(sum(i.amount_paid) FILTER (WHERE i.status = 'APPROVED'), 0) AS paid"
                + " FROM finaccounting.shareholder s LEFT JOIN finaccounting.share_issue i ON i.shareholder_id = s.shareholder_id"
                + " WHERE s.tenant_id = ? GROUP BY s.shareholder_id ORDER BY s.name",
            (rs, n) -> new ShareholderView(rs.getObject("shareholder_id", UUID.class), rs.getString("name"),
                rs.getString("kind"), rs.getBoolean("tanzanian"), rs.getString("id_number"), rs.getString("phone"),
                rs.getString("address"), rs.getLong("held"), rs.getBigDecimal("paid")),
            TenantContext.get());
    }

    @Override
    @Transactional
    public ShareholderView addShareholder(ShareholderInput in, String by) {
        validate(in);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO finaccounting.shareholder (shareholder_id, tenant_id, name, kind, tanzanian, id_number,"
                    + " phone, address, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, TenantContext.get(), in.name().trim(), in.kind(), in.tanzanian(), in.idNumber().trim(),
                blankToNull(in.phone()), blankToNull(in.address()), by);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ShareRegisterStateException("A shareholder with ID " + in.idNumber().trim() + " is already registered");
        }
        return shareholder(id);
    }

    /** Contact details and name only; whether they are Tanzanian changes the ownership check, so it is kept too. */
    @Override
    @Transactional
    public ShareholderView updateShareholder(UUID id, ShareholderInput in, String by) {
        validate(in);
        shareholder(id);
        jdbc.update("UPDATE finaccounting.shareholder SET name = ?, kind = ?, tanzanian = ?, id_number = ?, phone = ?,"
                + " address = ? WHERE tenant_id = ? AND shareholder_id = ?",
            in.name().trim(), in.kind(), in.tanzanian(), in.idNumber().trim(), blankToNull(in.phone()),
            blankToNull(in.address()), TenantContext.get(), id);
        return shareholder(id);
    }

    private ShareholderView shareholder(UUID id) {
        return shareholders().stream().filter(s -> s.shareholderId().equals(id)).findFirst()
            .orElseThrow(() -> new ShareRegisterNotFoundException("Shareholder " + id + " not found"));
    }

    private static void validate(ShareholderInput in) {
        if (in.name() == null || in.name().isBlank()) throw new FinaccountingValidationException("Enter the shareholder's name");
        if (!"PERSON".equals(in.kind()) && !"COMPANY".equals(in.kind())) {
            throw new FinaccountingValidationException("Say whether the shareholder is a person or a company");
        }
        if (in.idNumber() == null || in.idNumber().isBlank()) {
            throw new FinaccountingValidationException("Enter the ID or company registration number");
        }
    }

    // ---- classes --------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<ShareClassView> classes() {
        return jdbc.query("SELECT c.*, EXISTS (SELECT 1 FROM finaccounting.share_issue i WHERE i.tenant_id = c.tenant_id"
                + " AND i.class_code = c.class_code AND i.status = 'APPROVED') AS issued"
                + " FROM finaccounting.share_class c WHERE c.tenant_id = ? ORDER BY c.class_code",
            (rs, n) -> new ShareClassView(rs.getString("class_code"), rs.getBigDecimal("nominal_value"),
                rs.getString("set_by"), rs.getTimestamp("set_at").toInstant(), rs.getBoolean("issued")),
            TenantContext.get());
    }

    @Override
    @Transactional
    public ShareClassView setNominal(String classCode, BigDecimal nominalValue, String by) {
        if (!"ORDINARY".equals(classCode) && !"PREFERENCE".equals(classCode)) {
            throw new FinaccountingValidationException("A share class is ORDINARY or PREFERENCE");
        }
        if (nominalValue == null || nominalValue.signum() <= 0) {
            throw new FinaccountingValidationException("The nominal value is more than zero");
        }
        if (classes().stream().anyMatch(c -> c.classCode().equals(classCode) && c.issued())) {
            throw new ShareRegisterStateException("Shares of this class are already issued; the nominal value cannot change");
        }
        jdbc.update("INSERT INTO finaccounting.share_class (tenant_id, class_code, nominal_value, set_by, set_at)"
                + " VALUES (?, ?, ?, ?, now()) ON CONFLICT (tenant_id, class_code)"
                + " DO UPDATE SET nominal_value = EXCLUDED.nominal_value, set_by = EXCLUDED.set_by, set_at = now()",
            TenantContext.get(), classCode, nominalValue, by);
        return classes().stream().filter(c -> c.classCode().equals(classCode)).findFirst().orElseThrow();
    }

    // ---- issues ---------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<IssueView> issues(String status) {
        String filter = status == null || status.isBlank() ? "" : " AND i.status = ?";
        Object[] args = filter.isEmpty() ? new Object[] {TenantContext.get()} : new Object[] {TenantContext.get(), status};
        return jdbc.query("SELECT i.*, s.name AS shareholder_name FROM finaccounting.share_issue i"
                + " JOIN finaccounting.shareholder s ON s.shareholder_id = i.shareholder_id"
                + " WHERE i.tenant_id = ?" + filter + " ORDER BY i.proposed_at DESC", ShareRegister::issue, args);
    }

    @Override
    @Transactional
    public IssueView propose(IssueInput in, String by) {
        shareholder(in.shareholderId());
        BigDecimal nominal = classes().stream().filter(c -> c.classCode().equals(in.classCode())).findFirst()
            .map(ShareClassView::nominalValue)
            .orElseThrow(() -> new FinaccountingValidationException("Set the nominal value of " + in.classCode()
                + " shares first"));
        if (in.pricePerShare() == null || in.amountPaid() == null || in.receivedOn() == null || in.shares() <= 0) {
            throw new FinaccountingValidationException("Enter the shares, the price, the amount paid and the date received");
        }
        if (in.reference() == null || in.reference().isBlank()) {
            throw new FinaccountingValidationException("Name the board resolution or allotment the issue rests on");
        }
        if (bankAccounts().stream().noneMatch(b -> b.code().equals(in.bankAccount()))) {
            throw new FinaccountingValidationException("Account " + in.bankAccount()
                + " cannot take a share issue; choose a bank account finance posts to by hand");
        }
        List<String> problems = ShareIssueRules.problems(in.shares(), nominal, in.pricePerShare(), in.amountPaid(),
            in.receivedOn(), LocalDate.now(CIVIL));
        if (!problems.isEmpty()) throw new FinaccountingValidationException(String.join("; ", problems));
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO finaccounting.share_issue (issue_id, tenant_id, shareholder_id, class_code, shares,"
                + " price_per_share, nominal_value, amount_paid, received_on, bank_account, reference, proposed_by)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, TenantContext.get(), in.shareholderId(), in.classCode(), in.shares(), in.pricePerShare(), nominal,
            in.amountPaid(), Date.valueOf(in.receivedOn()), in.bankAccount(), in.reference().trim(), by);
        return issue(id);
    }

    @Override
    @Transactional
    public IssueView withdraw(UUID issueId, String by) {
        IssueView i = issue(issueId);
        if (!i.proposedBy().equals(by)) {
            throw new ShareRegisterStateException("Only the person who recorded it can withdraw it");
        }
        move(issueId, "WITHDRAWN", by, null, false, null);
        return issue(issueId);
    }

    @Override
    @Transactional
    public IssueView approve(UUID issueId, String by) {
        UUID tenantId = TenantContext.get();
        IssueView i = issue(issueId);
        boolean alone = decidable(i, by, "approve");
        String period = YearMonth.from(i.receivedOn()).toString();
        if (periods.view(period).status() == PeriodStatus.LOCKED) {
            throw new ShareRegisterStateException("That month is locked");
        }
        seeder.seedIfAbsent(tenantId, by);
        JournalEntry entry = new JournalEntry(tenantId, EVENT, issueId.toString(), period, null, by)
            .asManual(i.proposedBy(), by, "Shares issued to " + i.shareholderName() + " (" + i.reference() + ")",
                "SHARE_CAPITAL", "");
        entry.addLeg(i.bankAccount(), PostingDirection.DR, i.amountPaid(), "TZS", LineDimensions.NONE);
        entry.addLeg(ShareIssueRules.capitalAccount(i.classCode()), PostingDirection.CR, i.shareCapital(), "TZS",
            LineDimensions.NONE);
        if (i.sharePremium().signum() > 0) {
            entry.addLeg(ShareIssueRules.PREMIUM_ACCOUNT, PostingDirection.CR, i.sharePremium(), "TZS", LineDimensions.NONE);
        }
        JournalEntry posted;
        try {
            posted = ledger.postEntry(entry)
                .orElseThrow(() -> new ShareRegisterStateException("This issue was already posted"));
        } catch (ShareRegisterStateException e) {
            throw e;
        } catch (RuntimeException e) {
            String guard = ManualJournalApiImpl.ledgerGuard(e);
            if (guard != null) throw new ShareRegisterStateException("The ledger refused this issue: " + guard);
            throw e;
        }
        move(issueId, "APPROVED", by, null, alone, posted.getJournalEntryId());
        if (alone) ownWork.record("Share issue approved by the person who recorded it", issueId.toString(), null, by);
        return issue(issueId);
    }

    @Override
    @Transactional
    public IssueView reject(UUID issueId, String reason, String by) {
        IssueView i = issue(issueId);
        boolean alone = decidable(i, by, "reject");
        if (reason == null || reason.isBlank()) throw new FinaccountingValidationException("Say why the issue is rejected");
        move(issueId, "REJECTED", by, reason.trim(), alone, null);
        if (alone) ownWork.record("Share issue rejected by the person who recorded it", issueId.toString(), null, by);
        return issue(issueId);
    }

    private boolean decidable(IssueView i, String by, String action) {
        if (!"PROPOSED".equals(i.status())) {
            throw new ShareRegisterStateException("Only a recorded issue can be " + action + "d; this one is " + i.status());
        }
        if (!i.proposedBy().equals(by)) return false;
        if (!ownWork.mayActAlone("ledger.approve-own")) {
            throw new ShareRegisterStateException("You recorded this issue; a second person must " + action + " it");
        }
        return true;
    }

    private void move(UUID issueId, String to, String by, String reason, boolean alone, UUID journalId) {
        int n = jdbc.update("UPDATE finaccounting.share_issue SET status = ?, decided_by = ?, decided_at = now(),"
                + " decision_reason = ?, acted_alone = ?, journal_entry_id = ? WHERE tenant_id = ? AND issue_id = ?"
                + " AND status = 'PROPOSED'",
            to, by, reason, alone, journalId, TenantContext.get(), issueId);
        if (n == 0) throw new ShareRegisterStateException("This issue is no longer waiting for a decision");
    }

    private IssueView issue(UUID id) {
        return jdbc.query("SELECT i.*, s.name AS shareholder_name FROM finaccounting.share_issue i"
                + " JOIN finaccounting.shareholder s ON s.shareholder_id = i.shareholder_id"
                + " WHERE i.tenant_id = ? AND i.issue_id = ?", ShareRegister::issue, TenantContext.get(), id)
            .stream().findFirst().orElseThrow(() -> new ShareRegisterNotFoundException("Share issue " + id + " not found"));
    }

    private static IssueView issue(ResultSet rs, int n) throws SQLException {
        long shares = rs.getLong("shares");
        var split = ShareIssueRules.split(shares, rs.getBigDecimal("nominal_value"), rs.getBigDecimal("price_per_share"),
            rs.getBigDecimal("amount_paid"));
        Timestamp decided = rs.getTimestamp("decided_at");
        return new IssueView(rs.getObject("issue_id", UUID.class), rs.getObject("shareholder_id", UUID.class),
            rs.getString("shareholder_name"), rs.getString("class_code"), shares, rs.getBigDecimal("price_per_share"),
            rs.getBigDecimal("nominal_value"), rs.getBigDecimal("amount_paid"), split.capital(), split.premium(),
            rs.getDate("received_on").toLocalDate(), rs.getString("bank_account"), rs.getString("reference"),
            rs.getString("status"), rs.getString("proposed_by"), rs.getTimestamp("proposed_at").toInstant(),
            rs.getString("decided_by"), decided == null ? null : decided.toInstant(), rs.getString("decision_reason"),
            rs.getObject("journal_entry_id", UUID.class), rs.getBoolean("acted_alone"));
    }

    // ---- capital --------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public CapitalView capital() {
        UUID tenantId = TenantContext.get();
        Map<String, Object> reg = jdbc.queryForMap("SELECT COALESCE(sum(amount_paid), 0) AS paid,"
                + " COALESCE(sum(i.shares), 0) AS shares,"
                + " COALESCE(sum(i.shares) FILTER (WHERE s.tanzanian), 0) AS tz"
                + " FROM finaccounting.share_issue i JOIN finaccounting.shareholder s ON s.shareholder_id = i.shareholder_id"
                + " WHERE i.tenant_id = ? AND i.status = 'APPROVED'", tenantId);
        BigDecimal paid = (BigDecimal) reg.get("paid");
        long shares = ((Number) reg.get("shares")).longValue();
        long tz = ((Number) reg.get("tz")).longValue();
        BigDecimal ledgerCapital = BigDecimal.ZERO;
        for (AccountBalanceView a : finance.trialBalance(null).accounts()) {
            if (CAPITAL_ACCOUNTS.contains(a.accountCode())) {
                ledgerCapital = ledgerCapital.add(a.ownCredit().subtract(a.ownDebit()));
            }
        }
        var latest = jdbc.query("SELECT minimum_paid_up, set_by, set_at FROM finaccounting.capital_minimum"
                + " WHERE tenant_id = ? ORDER BY set_at DESC LIMIT 1",
            (rs, n) -> new Object[] {rs.getBigDecimal(1), rs.getString(2), rs.getTimestamp(3).toInstant()}, tenantId);
        BigDecimal minimum = latest.isEmpty() ? null : (BigDecimal) latest.get(0)[0];
        var r = CapitalChecks.of(paid, ledgerCapital, tz, shares, minimum);
        return new CapitalView(paid, ledgerCapital, shares, minimum,
            latest.isEmpty() ? null : (String) latest.get(0)[1], latest.isEmpty() ? null : (Instant) latest.get(0)[2],
            new CheckView(r.books().state(), r.books().figure()), new CheckView(r.tanzanian().state(), r.tanzanian().figure()),
            new CheckView(r.minimum().state(), r.minimum().figure()));
    }

    @Override
    @Transactional
    public CapitalView setMinimum(BigDecimal minimumPaidUp, String by) {
        if (minimumPaidUp != null && minimumPaidUp.signum() <= 0) {
            throw new FinaccountingValidationException("The minimum is more than zero, or empty for Not set");
        }
        jdbc.update("INSERT INTO finaccounting.capital_minimum (tenant_id, set_at, minimum_paid_up, set_by)"
            + " VALUES (?, clock_timestamp(), ?, ?)", TenantContext.get(), minimumPaidUp, by);
        return capital();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BankAccountView> bankAccounts() {
        return chart.findByTenantIdOrderByAccountCodeAsc(TenantContext.get()).stream()
            .filter(a -> a.getAccountCode().startsWith("11") && a.isPostingAllowed()
                && (a.getMode().name().equals("MAN") || a.getMode().name().equals("BOTH")))
            .map(a -> new BankAccountView(a.getAccountCode(), a.getName())).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
```

Points to check while implementing (read the named file, do not guess):
- `ChartOfAccount.getMode()` returns the posting-mode enum; confirm its constant names in `finaccounting/api/PostingMode.java` and compare with the enum, not `name()` strings, if they differ.
- A fresh tenant has no chart until `seeder.seedIfAbsent` runs, so `bankAccounts()` is empty for it. Call `seeder.seedIfAbsent(TenantContext.get(), by)` at the start of `propose` (as `ManualJournalApiImpl.create` does) so a first issue sees 1110.
- `capital_minimum` uses `clock_timestamp()` so two changes in one transaction (the test) get different keys.
- `trialBalance(null)` is inception-to-date; `ownCredit`/`ownDebit` are the un-rolled figures (see `AccountBalanceView`).

- [ ] **Step 4: Run to see it pass**

Run: `./mvnw -B -o test -Dtest=ShareRegisterIntegrationTest,ShareIssueRulesTest,CapitalChecksTest > "$SCRATCH/t3.log" 2>&1; grep -E "Tests run:|BUILD|FAIL" "$SCRATCH/t3.log" | tail -5`
Expected: all pass, BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/api/ShareRegisterApi.java backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/api/ShareRegisterStateException.java backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/api/ShareRegisterNotFoundException.java backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/application/ShareRegister.java backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/application/ShareRegisterIntegrationTest.java
git commit -m "feat(ledger): shareholders register; an approved issue posts capital and premium

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Endpoints, OpenAPI, Approvals queue

**Files:**
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/infrastructure/ShareRegisterController.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/infrastructure/FinaccountingExceptionHandler.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/infrastructure/FinanceApprovalSources.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/approvals/application/Approvals.java:54-60,127-147`
- Modify: `backend/api/openapi/openapi-finaccounting.yaml`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/FinaccountingContractTest.java`

**Interfaces:**
- Consumes: Task 3's `ShareRegisterApi`.
- Produces (paths, all `staffAuth`):

| Method | Path | Permission | Body | Returns |
|---|---|---|---|---|
| GET | `/finance/share-register/shareholders` | ledger.view | — | `ShareholderView[]` |
| POST | `/finance/share-register/shareholders` | ledger.manage | `ShareholderInput` | 201 `ShareholderView` |
| PUT | `/finance/share-register/shareholders/{id}` | ledger.manage | `ShareholderInput` | `ShareholderView` |
| GET | `/finance/share-register/classes` | ledger.view | — | `ShareClassView[]` |
| PUT | `/finance/share-register/classes/{classCode}` | ledger.manage | `{nominalValue}` | `ShareClassView` |
| GET | `/finance/share-register/issues?status=` | ledger.view | — | `ShareIssueView[]` |
| POST | `/finance/share-register/issues` | ledger.manage | `ShareIssueInput` | 201 `ShareIssueView` |
| POST | `/finance/share-register/issues/{id}/withdrawal` | ledger.manage | — | `ShareIssueView` |
| POST | `/finance/share-register/issues/{id}/approval` | ledger.approve | — | `ShareIssueView` |
| POST | `/finance/share-register/issues/{id}/rejection` | ledger.approve | `{reason}` | `ShareIssueView` |
| GET | `/finance/share-register/capital` | ledger.view | — | `CapitalView` |
| PUT | `/finance/share-register/capital/minimum` | ledger.manage | `{minimumPaidUp: number\|null}` | `CapitalView` |
| GET | `/finance/share-register/bank-accounts` | ledger.view | — | `BankAccountView[]` |

- [ ] **Step 1: Write the controller**

```java
package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.finaccounting.api.ShareRegisterApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.ShareRegisterApi.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** The shareholders register (2026-11-14): finance records, a finance approver -- never the recorder alone unless
 * they hold ledger.approve-own -- approves, and the approval posts the journal. */
@RestController
public class ShareRegisterController {

    public record NominalRequest(BigDecimal nominalValue) {}
    public record RejectionRequest(String reason) {}
    public record MinimumRequest(BigDecimal minimumPaidUp) {}

    private static final String BASE = "/finance/share-register";
    private final ShareRegisterApi api;

    public ShareRegisterController(ShareRegisterApi api) {
        this.api = api;
    }

    @GetMapping(BASE + "/shareholders")
    @PreAuthorize("@access.can('ledger.view')")
    public List<ShareholderView> shareholders() { return api.shareholders(); }

    @PostMapping(BASE + "/shareholders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.can('ledger.manage')")
    public ShareholderView add(@RequestBody ShareholderInput in, @AuthenticationPrincipal Jwt jwt) {
        return api.addShareholder(in, jwt.getSubject());
    }

    @PutMapping(BASE + "/shareholders/{id}")
    @PreAuthorize("@access.can('ledger.manage')")
    public ShareholderView update(@PathVariable UUID id, @RequestBody ShareholderInput in, @AuthenticationPrincipal Jwt jwt) {
        return api.updateShareholder(id, in, jwt.getSubject());
    }

    @GetMapping(BASE + "/classes")
    @PreAuthorize("@access.can('ledger.view')")
    public List<ShareClassView> classes() { return api.classes(); }

    @PutMapping(BASE + "/classes/{classCode}")
    @PreAuthorize("@access.can('ledger.manage')")
    public ShareClassView setNominal(@PathVariable String classCode, @RequestBody NominalRequest body,
                                     @AuthenticationPrincipal Jwt jwt) {
        return api.setNominal(classCode, body.nominalValue(), jwt.getSubject());
    }

    @GetMapping(BASE + "/issues")
    @PreAuthorize("@access.can('ledger.view')")
    public List<IssueView> issues(@RequestParam(required = false) String status) { return api.issues(status); }

    @PostMapping(BASE + "/issues")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@access.can('ledger.manage')")
    public IssueView propose(@RequestBody IssueInput in, @AuthenticationPrincipal Jwt jwt) {
        return api.propose(in, jwt.getSubject());
    }

    @PostMapping(BASE + "/issues/{id}/withdrawal")
    @PreAuthorize("@access.can('ledger.manage')")
    public IssueView withdraw(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return api.withdraw(id, jwt.getSubject());
    }

    @PostMapping(BASE + "/issues/{id}/approval")
    @PreAuthorize("@access.can('ledger.approve')")
    public IssueView approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return api.approve(id, jwt.getSubject());
    }

    @PostMapping(BASE + "/issues/{id}/rejection")
    @PreAuthorize("@access.can('ledger.approve')")
    public IssueView reject(@PathVariable UUID id, @RequestBody RejectionRequest body, @AuthenticationPrincipal Jwt jwt) {
        return api.reject(id, body.reason(), jwt.getSubject());
    }

    @GetMapping(BASE + "/capital")
    @PreAuthorize("@access.can('ledger.view')")
    public CapitalView capital() { return api.capital(); }

    @PutMapping(BASE + "/capital/minimum")
    @PreAuthorize("@access.can('ledger.manage')")
    public CapitalView minimum(@RequestBody MinimumRequest body, @AuthenticationPrincipal Jwt jwt) {
        return api.setMinimum(body.minimumPaidUp(), jwt.getSubject());
    }

    @GetMapping(BASE + "/bank-accounts")
    @PreAuthorize("@access.can('ledger.view')")
    public List<BankAccountView> bankAccounts() { return api.bankAccounts(); }
}
```

- [ ] **Step 2: Map the two exceptions** in `FinaccountingExceptionHandler`, beside `ManualJournalStateException`:

```java
    @ExceptionHandler(ShareRegisterStateException.class)
    public ProblemDetail handleShareRegisterState(ShareRegisterStateException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), "SHARE_REGISTER_STATE");
    }

    @ExceptionHandler(ShareRegisterNotFoundException.class)
    public ProblemDetail handleShareRegisterNotFound(ShareRegisterNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), "SHARE_REGISTER_NOT_FOUND");
    }
```

(plus the two imports from `finaccounting.api`).

- [ ] **Step 3: Add the approval source** to `FinanceApprovalSources` (after `manualJournalApprovals`):

```java
    @Bean
    ApprovalSource shareIssueApprovals(ShareRegisterApi register) {
        return ApprovalSource.of("share-issue", "Share issues", "Approve and post", "ledger.approve",
            limit -> ApprovalSource.Pending.of(register.issues("PROPOSED").stream()
                .sorted(Comparator.comparing(ShareRegisterApi.IssueView::proposedAt))
                .map(i -> new ApprovalSource.Item(i.issueId().toString(), null,
                    i.shares() + " " + i.classCode().toLowerCase() + " shares to " + i.shareholderName(),
                    i.amountPaid(), "TZS", i.proposedBy(), i.proposedAt()))
                .toList(), limit));
    }
```

- [ ] **Step 4: Register the kind** in `Approvals.java`: in `ORDER` put `"share-issue"` right after `"manual-journal"`; in `OWN_WORK` add `Map.entry("share-issue", "ledger.approve-own")` after the manual-journal entry.

- [ ] **Step 5: OpenAPI.** In `backend/api/openapi/openapi-finaccounting.yaml` add the 13 operations of the table above, each with `security: [staffAuth: []]`, responses `401`/`403` as refs, `409: StateConflict` on the issue actions and class PUT, `422: UnprocessableEntity` on every write, `404` (`$ref: '#/components/responses/NotFound'` — check the file has it with `grep -n "NotFound:" backend/api/openapi/openapi-finaccounting.yaml`; if it does not, write the 404 inline as the file does elsewhere). Schemas, with nullable fields as `anyOf [..., {type: 'null'}]` for `$ref`s and `nullable: true` for plain types (the file's two conventions):

```yaml
    ShareholderInput:
      type: object
      required: [name, kind, tanzanian, idNumber]
      properties:
        name: { type: string, maxLength: 200 }
        kind: { type: string, enum: [PERSON, COMPANY] }
        tanzanian: { type: boolean }
        idNumber: { type: string, maxLength: 60 }
        phone: { type: string, nullable: true, maxLength: 40 }
        address: { type: string, nullable: true, maxLength: 300 }
    ShareholderView:
      type: object
      required: [shareholderId, name, kind, tanzanian, idNumber, phone, address, shares, paid]
      properties:
        shareholderId: { type: string, format: uuid }
        name: { type: string }
        kind: { type: string, enum: [PERSON, COMPANY] }
        tanzanian: { type: boolean }
        idNumber: { type: string }
        phone: { type: string, nullable: true }
        address: { type: string, nullable: true }
        shares: { type: integer, format: int64, description: "Shares held through approved issues" }
        paid: { type: number }
    ShareClassView:
      type: object
      required: [classCode, nominalValue, setBy, setAt, issued]
      properties:
        classCode: { type: string, enum: [ORDINARY, PREFERENCE] }
        nominalValue: { type: number }
        setBy: { type: string }
        setAt: { type: string, format: date-time }
        issued: { type: boolean, description: "True once an issue of this class is approved: the nominal value is then fixed" }
    ShareIssueInput:
      type: object
      required: [shareholderId, classCode, shares, pricePerShare, amountPaid, receivedOn, bankAccount, reference]
      properties:
        shareholderId: { type: string, format: uuid }
        classCode: { type: string, enum: [ORDINARY, PREFERENCE] }
        shares: { type: integer, format: int64, minimum: 1 }
        pricePerShare: { type: number }
        amountPaid: { type: number }
        receivedOn: { type: string, format: date }
        bankAccount: { type: string }
        reference: { type: string, maxLength: 200, description: "The board resolution or allotment the issue rests on" }
    ShareIssueView:
      type: object
      required: [issueId, shareholderId, shareholderName, classCode, shares, pricePerShare, nominalValue, amountPaid,
                 shareCapital, sharePremium, receivedOn, bankAccount, reference, status, proposedBy, proposedAt,
                 decidedBy, decidedAt, decisionReason, journalEntryId, actedAlone]
      properties:
        issueId: { type: string, format: uuid }
        shareholderId: { type: string, format: uuid }
        shareholderName: { type: string }
        classCode: { type: string, enum: [ORDINARY, PREFERENCE] }
        shares: { type: integer, format: int64 }
        pricePerShare: { type: number }
        nominalValue: { type: number }
        amountPaid: { type: number }
        shareCapital: { type: number }
        sharePremium: { type: number }
        receivedOn: { type: string, format: date }
        bankAccount: { type: string }
        reference: { type: string }
        status: { type: string, enum: [PROPOSED, APPROVED, REJECTED, WITHDRAWN] }
        proposedBy: { type: string }
        proposedAt: { type: string, format: date-time }
        decidedBy: { type: string, nullable: true }
        decidedAt: { type: string, format: date-time, nullable: true }
        decisionReason: { type: string, nullable: true }
        journalEntryId: { type: string, format: uuid, nullable: true }
        actedAlone: { type: boolean }
    CapitalCheck:
      type: object
      required: [state, figure]
      properties:
        state: { type: string, enum: [MET, NOT_MET, NOT_SET] }
        figure: { type: string, nullable: true }
    CapitalView:
      type: object
      required: [paidUpCapital, ledgerCapital, issuedShares, minimumPaidUp, minimumSetBy, minimumSetAt, books, tanzanian, minimum]
      properties:
        paidUpCapital: { type: number }
        ledgerCapital: { type: number }
        issuedShares: { type: integer, format: int64 }
        minimumPaidUp: { type: number, nullable: true }
        minimumSetBy: { type: string, nullable: true }
        minimumSetAt: { type: string, format: date-time, nullable: true }
        books: { $ref: '#/components/schemas/CapitalCheck' }
        tanzanian: { $ref: '#/components/schemas/CapitalCheck' }
        minimum: { $ref: '#/components/schemas/CapitalCheck' }
    BankAccountView:
      type: object
      required: [code, name]
      properties:
        code: { type: string }
        name: { type: string }
```

The Java view `IssueView` is named `ShareIssueView` in the spec and `IssueInput` → `ShareIssueInput` (Jackson ignores the record name).

- [ ] **Step 6: Contract test.** Open `FinaccountingContractTest` and copy how it exercises one baseline sign-off endpoint against the spec; add a test that walks: set nominal → add shareholder → propose → approve (as a second subject) → GET capital, validating each response against the spec the same way. Add `db-migrations/finaccounting/V22__share_register.sql` to its migration list if Task 1's sweep did not (it lists V21).

Run: `./mvnw -B -o test -Dtest=FinaccountingContractTest,FinaccountingSpecParsesTest,ApprovalsIntegrationTest > "$SCRATCH/t4.log" 2>&1; grep -E "Tests run:|BUILD|FAIL" "$SCRATCH/t4.log" | tail -5`
(If `ApprovalsIntegrationTest` is not the name, find the approvals test with `grep -rl "OWN_WORK\|forViewer" backend/src/test` and run that.)
Expected: BUILD SUCCESS.

- [ ] **Step 7: Modulith check**

Run: `./mvnw -B -o test -Dtest=ModularityTests > "$SCRATCH/t4m.log" 2>&1; grep -E "Tests run:|BUILD" "$SCRATCH/t4m.log" | tail -2` (find the name with `grep -rl "ApplicationModules.of" backend/src/test`).
Expected: pass (`finaccounting` already depends on `approvals::api`).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/infrastructure backend/src/main/java/tz/co/nlolo/lifeplatform/approvals/application/Approvals.java backend/api/openapi/openapi-finaccounting.yaml backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/FinaccountingContractTest.java
git commit -m "feat(ledger): share register endpoints and the share-issue approval

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: The checks in the capital adequacy report

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/application/CapitalAdequacyReport.java`
- Test: the existing capital adequacy report test (find: `grep -rl "CapitalAdequacyReport" backend/src/test`)

**Interfaces:**
- Consumes: `ShareRegisterApi.capital()` (reporting may use `finaccounting::api`).

- [ ] **Step 1: Write the failing test** in the existing capital adequacy test class: run the report for the current month on a tenant with no issues and assert a section titled `"Paid-up capital"` with three rows whose `check` values are `"Register agrees with the books"`, `"Tanzanian ownership (at least one-third)"`, `"Minimum paid-up capital"` and whose `result` values are `"Met"`, `"NOT met"`, `"Not set"`.

- [ ] **Step 2: Run it to see it fail** (`-Dtest=<that class>`). Expected: no such section.

- [ ] **Step 3: Implement.** Inject `ShareRegisterApi register` into the constructor; in `body(...)` add after `sections.add(thresholds(...))`:

```java
        sections.add(paidUpCapital(register.capital()));
```

and:

```java
    /** The register's three checks (2026-11-14, shareholders register): today's position, whatever month is chosen. */
    static ReportSection paidUpCapital(ShareRegisterApi.CapitalView c) {
        List<ReportColumn> columns = List.of(new ReportColumn("check", "Check", TEXT),
            new ReportColumn("result", "Result", TEXT), new ReportColumn("figure", "Figure", TEXT));
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("Register agrees with the books", c.books(), "difference "));
        rows.add(row("Tanzanian ownership (at least one-third)", c.tanzanian(), "held "));
        rows.add(row("Minimum paid-up capital", c.minimum(), "short by "));
        return new ReportSection("Paid-up capital", columns, rows, Map.of());
    }

    private static Map<String, String> row(String check, ShareRegisterApi.CheckView v, String prefix) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("check", check);
        row.put("result", switch (v.state()) { case "MET" -> "Met"; case "NOT_SET" -> "Not set"; default -> "NOT met"; });
        row.put("figure", v.figure() == null ? "" : prefix + v.figure());
        return row;
    }
```

The section says "today's position": the register has no month history. Say so in the section title only if the report already shows a period note elsewhere; otherwise leave the title as above.

- [ ] **Step 4: Run** the report test class + `ShareRegisterIntegrationTest`. Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/tz/co/nlolo/lifeplatform/reporting/application/CapitalAdequacyReport.java backend/src/test/java/tz/co/nlolo/lifeplatform/reporting
git commit -m "feat(reports): capital adequacy shows the paid-up capital checks

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Console — Ledger > Shareholders

**REQUIRED SUB-SKILL:** invoke `impeccable` (target `frontend/src/features/finance/ShareholdersPage.tsx`) before writing the page; follow DESIGN.md ("The Lit Figure"), the platform's finance pages (`ManualJournalsPage.tsx`, `BaselineSignOff.tsx`) and the user's rule that panels say each figure once with little explanation.

**Files:**
- Modify: `frontend/src/types/api/finaccounting.ts` (regenerated), `frontend/src/api/types.ts`, `frontend/src/api/finaccounting.ts`
- Create: `frontend/src/features/finance/shareRegister.ts`, `frontend/src/features/finance/shareRegister.test.ts`
- Create: `frontend/src/features/finance/ShareholdersPage.tsx`, `frontend/src/features/finance/ShareholdersPage.test.tsx`
- Modify: `frontend/src/screens.tsx` (permission map near line 408; route + sidebar near line 781)
- Modify: `frontend/src/features/approvals/approvalLinks.ts`

**Interfaces:**
- Consumes: Task 4's endpoints.
- Produces: route `/staff/shareholders`; approval link `'share-issue'` → `/staff/shareholders`.

- [ ] **Step 1: Types and client.** Run `cd frontend; npm run generate:api`. In `src/api/types.ts`, beside `BaselineSignOffView`:

```ts
export type ShareholderView = FinaccountingComponents['schemas']['ShareholderView'];
export type ShareholderInput = FinaccountingComponents['schemas']['ShareholderInput'];
export type ShareClassView = FinaccountingComponents['schemas']['ShareClassView'];
export type ShareIssueView = FinaccountingComponents['schemas']['ShareIssueView'];
export type ShareIssueInput = FinaccountingComponents['schemas']['ShareIssueInput'];
export type CapitalView = FinaccountingComponents['schemas']['CapitalView'];
export type CapitalCheck = FinaccountingComponents['schemas']['CapitalCheck'];
export type BankAccountView = FinaccountingComponents['schemas']['BankAccountView'];
```

In `src/api/finaccounting.ts` (import the types above from `./types` the way the file's other imports do):

```ts
const SR = '/finance/share-register';
export const listShareholders = () => get<ShareholderView[]>(`${SR}/shareholders`);
export const addShareholder = (body: ShareholderInput) => post<ShareholderView>(`${SR}/shareholders`, body);
export const updateShareholder = (id: string, body: ShareholderInput) => put<ShareholderView>(`${SR}/shareholders/${id}`, body);
export const listShareClasses = () => get<ShareClassView[]>(`${SR}/classes`);
export const setShareNominal = (classCode: 'ORDINARY' | 'PREFERENCE', nominalValue: number) =>
  put<ShareClassView>(`${SR}/classes/${classCode}`, { nominalValue });
export const listShareIssues = (status?: string) =>
  get<ShareIssueView[]>(`${SR}/issues${status ? `?status=${status}` : ''}`);
export const proposeShareIssue = (body: ShareIssueInput) => post<ShareIssueView>(`${SR}/issues`, body);
export const withdrawShareIssue = (id: string) => post<ShareIssueView>(`${SR}/issues/${id}/withdrawal`, {});
export const approveShareIssue = (id: string) => post<ShareIssueView>(`${SR}/issues/${id}/approval`, {});
export const rejectShareIssue = (id: string, reason: string) => post<ShareIssueView>(`${SR}/issues/${id}/rejection`, { reason });
export const getCapital = () => get<CapitalView>(`${SR}/capital`);
export const setMinimumPaidUp = (minimumPaidUp: number | null) => put<CapitalView>(`${SR}/capital/minimum`, { minimumPaidUp });
export const listShareBankAccounts = () => get<BankAccountView[]>(`${SR}/bank-accounts`);
```

Check `post`/`put` signatures in `src/lib/http.ts` before using them (body argument position).

- [ ] **Step 2: Pure helpers, test first.** `shareRegister.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { checkLabel, issueSplit, ownershipShare } from './shareRegister';

describe('share register helpers', () => {
  it('splits an issue into capital and premium', () => {
    expect(issueSplit(1_000_000, 1000, 1500)).toEqual({ capital: 1_000_000_000, premium: 500_000_000, total: 1_500_000_000 });
  });
  it('labels each check state', () => {
    expect(checkLabel('MET')).toBe('Met');
    expect(checkLabel('NOT_MET')).toBe('Not met');
    expect(checkLabel('NOT_SET')).toBe('Not set');
  });
  it('gives each holder a share of the issued shares', () => {
    expect(ownershipShare(400, 1000)).toBe('40.0%');
    expect(ownershipShare(0, 0)).toBe('—');
  });
});
```

`shareRegister.ts`:

```ts
/** Shareholders register helpers (2026-11-14). Pure. */
export function issueSplit(shares: number, nominal: number, price: number) {
  const capital = Math.round(shares * nominal * 100) / 100;
  const total = Math.round(shares * price * 100) / 100;
  return { capital, premium: Math.round((total - capital) * 100) / 100, total };
}

export function checkLabel(state: 'MET' | 'NOT_MET' | 'NOT_SET'): string {
  return state === 'MET' ? 'Met' : state === 'NOT_SET' ? 'Not set' : 'Not met';
}

export function ownershipShare(held: number, all: number): string {
  return all === 0 ? '—' : `${((held * 100) / all).toFixed(1)}%`;
}
```

Run: `npx vitest run src/features/finance/shareRegister.test.ts` → fail first (missing module), pass after.

- [ ] **Step 3: Page test first.** `ShareholdersPage.test.tsx` — copy the mocking pattern of the nearest finance page test (`MonthEndPage.test.tsx`: how it mocks `@/api/finaccounting`, `react-oidc-context` and `can`). Cases:
  1. Renders three check rows: "Register agrees with the books — Met", "Tanzanian ownership — 40.0%", "Minimum paid-up capital — Not set".
  2. Lists shareholders with shares held and percentage.
  3. A PROPOSED issue recorded by someone else shows **Approve** and **Reject** to a viewer with `ledger.approve`; the recorder sees **Withdraw** and no Approve (unless `ledger.approve-own`).
  4. The record-an-issue form shows the live split ("Share capital 1,000,000,000.00 · Share premium 500,000,000.00") as the user types shares and price.
  5. Without `ledger.manage` there is no "Add shareholder" / "Record an issue".

- [ ] **Step 4: The page.** `ShareholdersPage.tsx`, top to bottom (each figure said once):
  - `PageHeader` title "Shareholders".
  - **Checks strip** (three items, from `getCapital()`): Register agrees with the books; Tanzanian ownership (figure = % held); Minimum paid-up capital (figure = "short by …" when not met). Paid-up capital shown once, in full, as the lit figure. "Set minimum" (ledger.manage) opens an inline money field; empty saves `null` → "Not set". When the ORDINARY class has no nominal value yet, the strip is replaced by one prompt: "Set the nominal value of ordinary shares" with a money field (`setShareNominal`).
  - **Shareholders table** (`DataTable`): Name, Person/Company, Tanzanian/Foreign, ID number, Shares held, % of issued, Paid. "Add shareholder" (ledger.manage) opens a form (name, kind, Tanzanian yes/no, ID, phone, address); Edit per row.
  - **Issues**: waiting issues first, each with shareholder, shares, price, amount, received date, bank, reference, recorded by (`StaffName`). Actions per the rules in step 3 (use `lib/ownWork.ts`'s helper the way `TreatyDecision.tsx` does for the approve-own case). Reject asks for a reason. Below, decided issues (Approved/Rejected/Withdrawn) with the journal link `/staff/journal-entries/{journalEntryId}` if that route exists (`grep -n "journal-entries" src/screens.tsx`), otherwise no link.
  - "Record an issue" (ledger.manage): shareholder (select), class (classes with a nominal value), shares, price per share, amount paid (prefilled shares × price, editable), date received (`DatePicker`, no future), bank account (`listShareBankAccounts`), board resolution or allotment reference. The live split line under the form.
  - Errors with `InlineError`; loading/empty with `LoadingBlock`/`EmptyState`/`ErrorPanel`.

- [ ] **Step 5: Wire it.** In `screens.tsx`: permission map `'shareholders': ['ledger.view']`; import the page; add right after the `manual-journals` route object:

```tsx
  {
    path: 'shareholders',
    element: <ShareholdersPage />,
    reach: { group: 'ledger', label: 'Shareholders', icon: Landmark },
  },
```

(`Landmark` from `lucide-react`; check it is exported by the installed version with `grep -c "Landmark" node_modules/lucide-react/dist/lucide-react.d.ts`, else use `Users`.) The sidebar renders items in route order, so placing it after manual journals puts it directly below **Manual journals**. In `approvalLinks.ts`: `'share-issue': () => '/staff/shareholders',`.

- [ ] **Step 6: Run the affected frontend checks**

Run: `npx vitest run src/features/finance src/features/approvals src/screens > "$SCRATCH/t6.log" 2>&1; tail -15 "$SCRATCH/t6.log"; npm run typecheck; npm run lint`
Expected: all pass; no type or lint errors. (Do not run the full vitest suite here; it runs at the milestone, Task 8.)

- [ ] **Step 7: Commit**

```bash
git add frontend/src/types/api/finaccounting.ts frontend/src/api frontend/src/features/finance/shareRegister.ts frontend/src/features/finance/shareRegister.test.ts frontend/src/features/finance/ShareholdersPage.tsx frontend/src/features/finance/ShareholdersPage.test.tsx frontend/src/screens.tsx frontend/src/features/approvals/approvalLinks.ts
git commit -m "feat(console): Ledger > Shareholders

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Dev data and the end-to-end spec

**Files:**
- Modify: `backend/scripts/seed-dev-data.sh` (after the funds block, ~line 372)
- Create: `frontend/e2e/staff-shareholders.spec.ts`

- [ ] **Step 1: Seeder** — finance records, the finance approver approves (a second person):

```bash
# The company's paid-up capital (2026-11-14, shareholders register): demo figures, NOT the insurer's real capital.
# Finance sets the nominal value and records the issue; the finance approver -- a second person -- approves it,
# which posts Dr 1110 / Cr 3110 1,000,000,000.
api "$STAFF_FINANCE_TOKEN" PUT "/finance/share-register/classes/ORDINARY" '{"nominalValue":1000}' >/dev/null
DEMO_HOLDER_JSON=$(api "$STAFF_FINANCE_TOKEN" POST "/finance/share-register/shareholders" \
  '{"name":"Demo Holdings Ltd","kind":"COMPANY","tanzanian":true,"idNumber":"DEMO-REG-0001"}')
DEMO_ISSUE_JSON=$(api "$STAFF_FINANCE_TOKEN" POST "/finance/share-register/issues" \
  "{\"shareholderId\":\"$(jsonval "$DEMO_HOLDER_JSON" shareholderId)\",\"classCode\":\"ORDINARY\",\"shares\":1000000,\"pricePerShare\":1000,\"amountPaid\":1000000000,\"receivedOn\":\"$(date +%F)\",\"bankAccount\":\"1110\",\"reference\":\"Demo allotment\"}")
```

`STAFF_APPROVER_TOKEN` is only defined at line ~673; put the approval call there, right after that token is made:

```bash
api "$STAFF_APPROVER_TOKEN" POST "/finance/share-register/issues/$(jsonval "$DEMO_ISSUE_JSON" issueId)/approval" >/dev/null
```

- [ ] **Step 2: Apply to the current dev database** (dev migrations are not run by the backend). Stop nothing for this; run:

```bash
docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform < backend/db-migrations/finaccounting/V22__share_register.sql
docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform < backend/db-migrations/refdata/V12__share_capital_reason_code.sql
```

then restart the dev backend (from this branch's build) and run the three seeder calls plus the approval by hand with the same `api` helper (`source backend/scripts/seed-lib.sh`, tokens from `token_for`). Check: `select account_code, direction, amount from finaccounting.gl_posting where source_event='finaccounting.ShareIssue'` → 1110 DR / 3110 CR 1,000,000,000.00; the balance sheet report says the sheet balances.

- [ ] **Step 3: E2E spec** `staff-shareholders.spec.ts`, using the finance session (`storageState` as `staff-ifrs17-manual-journals.spec.ts` does) and `approverPage` from `e2e/approverSession.ts`:
  1. Finance opens **Ledger > Shareholders** from the sidebar.
  2. Adds a shareholder with a unique ID (`E2E-${Date.now()}`), records an issue of 10 ordinary shares at the class's nominal value (read it from the page) into 1110 with reference "E2E allotment".
  3. Finance sees **Withdraw**, not **Approve**.
  4. The approver opens **Approvals**, finds "10 ordinary shares to <name>", follows it to Shareholders, approves.
  5. Finance reloads: the issue is Approved, the shareholder holds 10 shares, "Register agrees with the books" is Met.

- [ ] **Step 4: Run it** (dev backend + console running, nothing else heavy): `cd frontend; npx playwright test e2e/staff-shareholders.spec.ts --workers=1 > "$SCRATCH/t7.log" 2>&1; tail -20 "$SCRATCH/t7.log"`. Expected: 1 passed. If it fails on sign-in, re-run with its setup projects first (`--no-deps` reuses stale auth).

- [ ] **Step 5: Commit**

```bash
git add backend/scripts/seed-dev-data.sh frontend/e2e/staff-shareholders.spec.ts
git commit -m "feat(dev): demo share capital; e2e for the shareholders register

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Merge gate

- [ ] **Step 1: Affected backend classes** (dev backend stopped first):
`./mvnw -B -o clean test -Dtest='ShareIssueRulesTest,CapitalChecksTest,ShareRegisterIntegrationTest,ManualJournalIntegrationTest,FinaccountingContractTest,FinaccountingSpecParsesTest,TenantTableRlsCoverageIntegrationTest,AppRolePrivilegesIntegrationTest,<capital adequacy test>,<approvals test>,<modularity test>' > "$SCRATCH/t8.log" 2>&1; grep -E "Tests run:|BUILD|FAIL" "$SCRATCH/t8.log" | tail -8`
Expected: BUILD SUCCESS. Restart the dev backend.
- [ ] **Step 2: Full vitest** (a milestone: the user wants it before a feature merge with `designGuards`): `cd frontend; npx vitest run > "$SCRATCH/vitest.log" 2>&1; tail -8 "$SCRATCH/vitest.log"`. Expected: all files pass.
- [ ] **Step 3: Whole-branch review** — read `git diff main...shareholders-register` end to end against the spec; look at the seams (register ↔ ledger amounts, approval queue ↔ page, OpenAPI ↔ records).
- [ ] **Step 4: Merge and push**

```bash
git switch main
git merge --no-ff shareholders-register -m "Merge branch 'shareholders-register': Ledger > Shareholders

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push origin main
```

---

## Self-review notes

- Spec coverage: register fields (T1, T3), classes with nominal (T1, T3), issue propose/approve/reject/withdraw (T3, T4), posting split and date (T2, T3), locked month (T3), approvals queue (T4), permissions (T4, T6), three checks (T2, T3) on page (T6) and in the report (T5), minimum "Not set" (T2, T3, T6), sidebar placement (T6), dev data (T7), tests at each level (T2–T7).
- Added beyond the spec, flagged to the user: a required "board resolution or allotment" reference on each issue (it goes into the journal's reason, and the ledger requires a reason on a manual entry); a WITHDRAWN state (the recorder takes back their own proposal, as manual journals allow); refdata reason code SHARE_CAPITAL (the ledger needs a reason code on 1110).
