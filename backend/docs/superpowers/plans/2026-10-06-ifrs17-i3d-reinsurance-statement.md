# IFRS 17 I3d — Reinsurance Statement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> This user prefers inline execution with a solo final review (no subagents).

**Goal:** Settle each reinsurance treaty quarterly. A maker-checker statement clears the bordereau and recovery
balances on 1430, 1431 and 1420 into the current account 1434, and records funds withheld (R-04) and profit commission
(R-03). It posts as one SYSTEM journal through the rules file.

**Architecture:** The statement lives in `reinsurance` (V7 tables, a pure `StatementCalculator`, a JDBC store, a
service). Approval publishes `reinsurance.StatementApproved`. `posting-rules.yaml` v4 adds rule `R-STMT`, marked with a
new rule-level `source: SYSTEM` flag; the engine and validator honour that flag, because the journal mixes AUTO and MAN
accounts. The console gets a statements panel on the treaty, a statement page, and a finance list.

**Tech Stack:** Spring Boot 3 / Java 21, JdbcTemplate, Spring Modulith, Postgres 16 (RLS, app_role), Testcontainers,
React 18 + Vite + Zustand, Vitest, Playwright.

**Spec:** `backend/docs/superpowers/specs/2026-10-06-ifrs17-i3d-reinsurance-statement-design.md` (1ed6e4b1).

## Global Constraints

- Calendar quarters `YYYY-Qn`. A quarter "has ended" when today in Africa/Dar_es_Salaam is on or after the first day
  of the next quarter.
- Funds withheld W: 0 ≤ W ≤ P. Profit commission PC ≥ 0. Every amount has 2 decimals.
- Maker-checker: prepare, edit, submit and withdraw need FINANCE_OFFICER or ADMIN, by the preparer. Approve and reject
  need FINANCE_APPROVER, and never the preparer.
- One live (not REJECTED) statement per treaty and quarter; each bordereau and recovery is on at most one live
  statement.
- A recovery with no `reinsurance.RecoveryCalculated` journal (recorded before I3c) is `legacy` and is never included.
- The journal posts in the civil month of approval: SYSTEM, reference type STATEMENT, reference the statement id.
- The document owner is `statement:{id}` (46 chars; `owner_context` is VARCHAR(50)).
- Every new endpoint is in `api/openapi/openapi-reinsurance.yaml`, with nullable enums as inline
  `type: [string, "null"]` (never `oneOf` with null).
- Paths (a deviation from the spec's `/reinsurance/...`, to match this module's unprefixed style and avoid the generic
  `/statements`): `GET /reinsurance-statements`, `POST /treaties/{treatyId}/statements`,
  `GET|PUT /reinsurance-statements/{id}`, and `POST /reinsurance-statements/{id}/{documents|submission|withdrawal|approval|rejection}`.
- Test migration lists are hand-written. Add each new migration to every class that loads the previous one in its
  module, using `scripts/dev/append-test-migration.mjs` (I2) or the same exact-line insert.
- Never run Maven while the dev backend runs from the same worktree. Never run Prettier.

---

### Task 1: Rules may post as SYSTEM (`source: SYSTEM`)

**Files:**
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/PostingRuleSet.java` (Rule gains
  `boolean system`)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/PostingRuleParser.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/PostingRuleValidator.java:113`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/application/PostingEngine.java:148,190-191`
- Modify: `backend/src/main/resources/finaccounting/posting-rules.yaml` (I-03 gets `source: SYSTEM`)
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/PostingRuleSourceTest.java` (new, pure)

**Interfaces:**
- Produces: `PostingRuleSet.Rule#system()`. YAML `source: SYSTEM` makes it true; any other value is a parse error.
  The validator allows a MAN account only on a system rule. The engine posts a system rule's journal as
  `JournalSource.SYSTEM` whatever the caller passed.

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform.finaccounting;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRuleParser;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRuleSet;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRuleValidator;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostingRuleSourceTest {

    private static PostingRuleSet parse(String yaml) {
        return PostingRuleParser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static final String MAN_RULE = """
        version: 1
        rules:
          - id: X-01
            event: finaccounting.PaaRevenueEarned
            models: [PAA]
            %s
            lines:
              - {dr: "1434", amount: amount}
              - {cr: "4160", amount: amount}
        """;

    @Test
    void aSystemRuleIsReadAndMayPostToAManualAccount() {
        PostingRuleSet set = parse(MAN_RULE.formatted("source: SYSTEM"));
        assertThat(set.rules().get(0).system()).isTrue();
        assertThat(PostingRuleValidator.problems(set, ChartOfAccountBlueprint.accounts()))
            .noneMatch(p -> p.contains("MAN"));
    }

    @Test
    void anEventRuleStillMayNotPostToAManualAccount() {
        PostingRuleSet set = parse(MAN_RULE.formatted("description: event rule"));
        assertThat(set.rules().get(0).system()).isFalse();
        assertThat(PostingRuleValidator.problems(set, ChartOfAccountBlueprint.accounts()))
            .anyMatch(p -> p.contains("1434 is MAN"));
    }

    @Test
    void anUnknownSourceIsRefused() {
        assertThatThrownBy(() -> parse(MAN_RULE.formatted("source: MANUAL")))
            .hasMessageContaining("'source' must be SYSTEM");
    }

    @Test
    void theShippedPaaEarningRuleIsSystem() {
        PostingRuleSet shipped = PostingRuleParser.parse(
            PostingRuleSourceTest.class.getResourceAsStream("/finaccounting/posting-rules.yaml"));
        assertThat(shipped.rules()).filteredOn(r -> r.id().equals("I-03")).singleElement()
            .satisfies(r -> assertThat(r.system()).isTrue());
    }
}
```

Check `PostingRuleValidator`'s public entry point first (`grep -n "public static" PostingRuleValidator.java`) and use
its real signature in place of `problems(set, accounts)` if it differs.

- [ ] **Step 2: Run it and see it fail**

Run: `./mvnw -B -o test -Dtest=PostingRuleSourceTest`
Expected: compile failure, because `system()` doesn't exist yet.

- [ ] **Step 3: Implement**

`PostingRuleSet.Rule`: add `boolean system` after `boolean post`, with this javadoc:
`@param system true for a rule whose journal is the platform's own (source: SYSTEM): it may post to MAN accounts, and a closing period still takes it`.

`PostingRuleParser.rule(...)`:

```java
        Object source = m.get("source");
        if (source != null && !"SYSTEM".equals(String.valueOf(source))) {
            throw new IllegalStateException(where + ": 'source' must be SYSTEM when given");
        }
        return new PostingRuleSet.Rule(id, text(m.get("event")), models, when, from != null ? from : BEGINNING,
            date(m.get("effectiveTo"), where), text(m.get("description")), lines, !Boolean.FALSE.equals(m.get("post")),
            source != null);
```

Also document `source: SYSTEM  # optional` in the class javadoc's example.

`PostingRuleValidator` line 113: `} else if (account.mode() == PostingMode.MAN && !rule.system()) {`. Update the javadoc
bullet at line 19: "never MAN, unless the rule is `source: SYSTEM` (the ledger guard lets SYSTEM post to MAN)".

`PostingEngine.postInTransaction`, just before building the entry:

```java
        JournalSource effective = rule.get().system() ? JournalSource.SYSTEM : source;
        JournalEntry entry = new JournalEntry(tenantId, facts.eventType(), facts.sourceRef(), period,
            facts.policyNumber(), by).underRuleVersion(set.versionLabel()).withSource(effective);
```

`PostingEngine.retry`: delete the `PaaEarningJob.EVENT.equals(...)` line and call `post(tenantId, today, by)`. The
rule's flag now decides.

`posting-rules.yaml` rule I-03: add `source: SYSTEM` under `models`. Keep `version: 3` (no posting changes).

- [ ] **Step 4: Run the tests and see them pass**

Run: `./mvnw -B -o test -Dtest='PostingRuleSourceTest,PostingEngineIntegrationTest,FinaccountingSpecParsesTest'`
Expected: PASS. `PostingEngineIntegrationTest` covers PAA earning still posting SYSTEM.

- [ ] **Step 5: Commit**

```bash
git add -A backend/src && git commit -m "feat(finaccounting): a posting rule may be the platform's own (source: SYSTEM) -- MAN accounts allowed, posted SYSTEM"
```

---

### Task 2: The statement's figures — V7 and `StatementCalculator`

**Files:**
- Create: `backend/db-migrations/reinsurance/V7__statement.sql`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/reinsurance/domain/StatementCalculator.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/reinsurance/StatementCalculatorTest.java`

**Interfaces:**
- Produces:
  - `StatementCalculator.Quarter.parse(String) -> Quarter(int year, int q)`, with `first()` / `last()` (YearMonth),
    `months()` (List<YearMonth>), `start()` / `endExclusive()` (LocalDate), `hasEnded(LocalDate today)`, and
    `toString()` giving `YYYY-Qn`.
  - `StatementCalculator.monthsRequired(Quarter, LocalDate effectiveFrom, LocalDate effectiveTo) -> List<YearMonth>`.
  - `StatementCalculator.Figures(BigDecimal premium, BigDecimal commission, BigDecimal recoveries, BigDecimal withheld,
    BigDecimal profitCommission)` with `problems()`, `owedToUs()`, `owedByUs()`, `journal()`.
  - `StatementCalculator.JournalLine(String entry, String account, String side, BigDecimal amount)`.

- [ ] **Step 1: Write the migration**

```sql
-- db-migrations/reinsurance/V7__statement.sql
-- IFRS 17 I3d (guide R-01, R-03, R-04): the quarterly statement settling a treaty. The platform's figures (the
-- quarter's bordereaux and recoveries) plus what finance enters from the reinsurer's statement (funds withheld,
-- profit commission). Approved by a FINANCE_APPROVER who is not the preparer; posted as one SYSTEM journal.

CREATE TABLE reinsurance.statement (
    statement_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    treaty_id         UUID NOT NULL REFERENCES reinsurance.reinsurance_treaty (treaty_id),
    quarter           VARCHAR(7) NOT NULL CHECK (quarter ~ '^\d{4}-Q[1-4]$'),
    currency          CHAR(3) NOT NULL,
    status            VARCHAR(10) NOT NULL CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED')),
    premium           NUMERIC(19,2) NOT NULL CHECK (premium >= 0),
    commission        NUMERIC(19,2) NOT NULL CHECK (commission >= 0),
    recoveries        NUMERIC(19,2) NOT NULL CHECK (recoveries >= 0),
    funds_withheld    NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (funds_withheld >= 0),
    profit_commission NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (profit_commission >= 0),
    reason            VARCHAR(500),
    document_refs     TEXT[] NOT NULL DEFAULT '{}',
    preparer          VARCHAR(100) NOT NULL,
    prepared_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at      TIMESTAMPTZ,
    decided_by        VARCHAR(100),
    decided_at        TIMESTAMPTZ,
    decision_reason   VARCHAR(500),
    version           BIGINT NOT NULL DEFAULT 0,
    CHECK (funds_withheld <= premium),
    CHECK (decided_by IS NULL OR decided_by <> preparer),
    CHECK (status <> 'REJECTED' OR decision_reason IS NOT NULL)
);
CREATE UNIQUE INDEX ux_statement_live ON reinsurance.statement (tenant_id, treaty_id, quarter)
    WHERE status <> 'REJECTED';
CREATE INDEX idx_statement_status ON reinsurance.statement (tenant_id, status, prepared_at);

-- What a statement settles. `live` mirrors the statement (false once REJECTED) so each bordereau and recovery is on
-- at most one live statement.
CREATE TABLE reinsurance.statement_item (
    statement_id  UUID NOT NULL REFERENCES reinsurance.statement (statement_id),
    tenant_id     UUID NOT NULL,
    item_type     VARCHAR(10) NOT NULL CHECK (item_type IN ('BORDEREAU','RECOVERY')),
    item_id       UUID NOT NULL,
    live          BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY (statement_id, item_type, item_id)
);
CREATE UNIQUE INDEX ux_statement_item_once ON reinsurance.statement_item (tenant_id, item_type, item_id) WHERE live;

-- Recoveries recorded before I3c never posted 1420 (I3c Q4): a statement must not clear them.
ALTER TABLE reinsurance.claim_recovery ADD COLUMN legacy BOOLEAN NOT NULL DEFAULT false;
DO $$
BEGIN
    IF to_regclass('finaccounting.journal_entry') IS NULL THEN
        RETURN;   -- a module test without the ledger: nothing was ever posted, nothing to mark
    END IF;
    UPDATE reinsurance.claim_recovery r SET legacy = true
     WHERE NOT EXISTS (SELECT 1 FROM finaccounting.journal_entry j
                        WHERE j.tenant_id = r.tenant_id AND j.source_event = 'reinsurance.RecoveryCalculated'
                          AND j.source_ref = r.recovery_id::text);
END;
$$;

ALTER TABLE reinsurance.statement ENABLE ROW LEVEL SECURITY;
CREATE POLICY statement_tenant_isolation ON reinsurance.statement
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE reinsurance.statement_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY statement_item_tenant_isolation ON reinsurance.statement_item
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON reinsurance.statement, reinsurance.statement_item TO app_role;
```

Before writing the legacy UPDATE, confirm what `RecoveryCalculated`'s `recoveryId` holds:
`grep -n "recoveryId" backend/src/main/java/tz/co/nlolo/lifeplatform/reinsurance/application/ClaimEventListener.java`.
It must be `claim_recovery.recovery_id`. If it is the claim id, join on `claim_id` instead.

- [ ] **Step 2: Write the failing calculator test**

```java
package tz.co.nlolo.lifeplatform.reinsurance;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.reinsurance.domain.StatementCalculator;
import tz.co.nlolo.lifeplatform.reinsurance.domain.StatementCalculator.Figures;
import tz.co.nlolo.lifeplatform.reinsurance.domain.StatementCalculator.Quarter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatementCalculatorTest {

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    /** The guide's R-01 example: premium 1,800,000, commission 360,000, recoveries 30,000,000 -- the reinsurer owes us. */
    @Test
    void theGuidesExampleNetsToTheReinsurerOwingUs() {
        Figures f = new Figures(d("1800000.00"), d("360000.00"), d("30000000.00"), d("0.00"), d("0.00"));
        assertThat(f.owedToUs()).isEqualByComparingTo("28560000.00");
        assertThat(f.owedByUs()).isZero();
        assertThat(f.journal()).extracting(l -> l.entry() + " " + l.side() + " " + l.account() + " " + l.amount().toPlainString())
            .containsExactly("R-01 DR 1430 1800000.00", "R-01 CR 1431 360000.00", "R-01 CR 1420 30000000.00",
                "R-01 DR 1434 28560000.00");
    }

    @Test
    void premiumAboveRecoveriesAndCommissionMeansWeOweTheReinsurer() {
        Figures f = new Figures(d("500000.00"), d("50000.00"), d("100000.00"), d("0.00"), d("0.00"));
        assertThat(f.owedByUs()).isEqualByComparingTo("350000.00");
        assertThat(f.journal()).extracting(l -> l.side() + " " + l.account())
            .containsExactly("DR 1430", "CR 1431", "CR 1420", "CR 1434");
    }

    @Test
    void fundsWithheldAndProfitCommissionPostTheirOwnEntriesAndTheJournalBalances() {
        Figures f = new Figures(d("1000000.00"), d("0.00"), d("0.00"), d("200000.00"), d("75000.00"));
        assertThat(f.journal()).extracting(l -> l.entry() + " " + l.side() + " " + l.account() + " " + l.amount().toPlainString())
            .containsExactly("R-04 DR 1430 200000.00", "R-04 CR 2550 200000.00", "R-01 DR 1430 800000.00",
                "R-01 CR 1434 800000.00", "R-03 DR 1433 75000.00", "R-03 CR 6120 75000.00");
        BigDecimal dr = f.journal().stream().filter(l -> l.side().equals("DR")).map(StatementCalculator.JournalLine::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cr = f.journal().stream().filter(l -> l.side().equals("CR")).map(StatementCalculator.JournalLine::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(dr).isEqualByComparingTo(cr);
    }

    @Test
    void zeroLinesAreLeftOutAndAQuietQuarterPostsNothing() {
        assertThat(new Figures(d("0.00"), d("0.00"), d("0.00"), d("0.00"), d("0.00")).journal()).isEmpty();
    }

    @Test
    void fundsWithheldCannotExceedThePremiumAndNothingIsNegative() {
        assertThat(new Figures(d("100.00"), d("0.00"), d("0.00"), d("100.01"), d("0.00")).problems())
            .containsExactly("Funds withheld (100.01) cannot exceed the quarter's premium payable (100.00)");
        assertThat(new Figures(d("100.00"), d("0.00"), d("0.00"), d("0.00"), d("-1.00")).problems())
            .containsExactly("Profit commission cannot be negative");
        assertThat(new Figures(d("100.00"), d("0.00"), d("0.00"), d("0.001"), d("0.00")).problems())
            .containsExactly("Funds withheld has at most two decimal places");
    }

    @Test
    void aQuarterHasItsMonthsAndEndsWhenTheNextBegins() {
        Quarter q3 = Quarter.parse("2026-Q3");
        assertThat(q3.months()).containsExactly(YearMonth.of(2026, 7), YearMonth.of(2026, 8), YearMonth.of(2026, 9));
        assertThat(q3.hasEnded(LocalDate.of(2026, 9, 30))).isFalse();
        assertThat(q3.hasEnded(LocalDate.of(2026, 10, 1))).isTrue();
        assertThat(q3.toString()).isEqualTo("2026-Q3");
        assertThat(Quarter.of(LocalDate.of(2026, 11, 5)).toString()).isEqualTo("2026-Q4");
        assertThatThrownBy(() -> Quarter.parse("2026-Q5")).hasMessageContaining("YYYY-Qn");
    }

    @Test
    void onlyMonthsInsideTheTreatysDatesNeedABordereau() {
        Quarter q3 = Quarter.parse("2026-Q3");
        assertThat(StatementCalculator.monthsRequired(q3, LocalDate.of(2026, 9, 1), null))
            .containsExactly(YearMonth.of(2026, 9));
        assertThat(StatementCalculator.monthsRequired(q3, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 15)))
            .containsExactly(YearMonth.of(2026, 7), YearMonth.of(2026, 8));
        assertThat(StatementCalculator.monthsRequired(q3, LocalDate.of(2027, 1, 1), null)).isEmpty();
    }
}
```

- [ ] **Step 3: Run it and see it fail**

Run: `./mvnw -B -o test -Dtest=StatementCalculatorTest`. Expected: compile failure, because there is no `StatementCalculator`.

- [ ] **Step 4: Implement `StatementCalculator`**

```java
package tz.co.nlolo.lifeplatform.reinsurance.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The quarterly reinsurance statement's arithmetic (IFRS 17 I3d, guide R-01, R-03, R-04). Pure.
 *
 * <p>With P premium payable, C commission receivable, R recoveries, W funds withheld and PC profit commission:
 * R-04 Dr 1430 / Cr 2550 W; R-01 Dr 1430 (P - W), Cr 1431 C, Cr 1420 R, and 1434 takes the balance -- Dr when the
 * reinsurer owes us (R + C > P - W), Cr when we owe it; R-03 Dr 1433 / Cr 6120 PC. Zero lines are left out.
 */
public final class StatementCalculator {

    private StatementCalculator() {}

    public record Quarter(int year, int q) {
        private static final Pattern FORMAT = Pattern.compile("^(\\d{4})-Q([1-4])$");

        public static Quarter parse(String s) {
            Matcher m = s == null ? null : FORMAT.matcher(s);
            if (m == null || !m.matches()) {
                throw new IllegalArgumentException("A quarter is YYYY-Qn, for example 2026-Q3; got '" + s + "'");
            }
            return new Quarter(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }

        public static Quarter of(LocalDate date) {
            return new Quarter(date.getYear(), (date.getMonthValue() - 1) / 3 + 1);
        }

        public YearMonth first() { return YearMonth.of(year, (q - 1) * 3 + 1); }
        public YearMonth last() { return first().plusMonths(2); }
        public List<YearMonth> months() { return List.of(first(), first().plusMonths(1), last()); }
        public LocalDate start() { return first().atDay(1); }
        public LocalDate endExclusive() { return last().plusMonths(1).atDay(1); }
        public boolean hasEnded(LocalDate today) { return !today.isBefore(endExclusive()); }

        @Override
        public String toString() { return year + "-Q" + q; }
    }

    /** The quarter's months that fall inside the treaty's dates: each needs its bordereau before the quarter settles. */
    public static List<YearMonth> monthsRequired(Quarter quarter, LocalDate effectiveFrom, LocalDate effectiveTo) {
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth m : quarter.months()) {
            boolean afterStart = !m.atEndOfMonth().isBefore(effectiveFrom);
            boolean beforeEnd = effectiveTo == null || !m.atDay(1).isAfter(effectiveTo);
            if (afterStart && beforeEnd) {
                months.add(m);
            }
        }
        return months;
    }

    public record JournalLine(String entry, String account, String side, BigDecimal amount) {}

    public record Figures(BigDecimal premium, BigDecimal commission, BigDecimal recoveries, BigDecimal withheld,
                          BigDecimal profitCommission) {

        public List<String> problems() {
            List<String> problems = new ArrayList<>();
            if (withheld.signum() < 0) {
                problems.add("Funds withheld cannot be negative");
            } else if (withheld.scale() > 2 && withheld.stripTrailingZeros().scale() > 2) {
                problems.add("Funds withheld has at most two decimal places");
            } else if (withheld.compareTo(premium) > 0) {
                problems.add("Funds withheld (" + withheld.toPlainString() + ") cannot exceed the quarter's premium payable ("
                    + premium.toPlainString() + ")");
            }
            if (profitCommission.signum() < 0) {
                problems.add("Profit commission cannot be negative");
            } else if (profitCommission.scale() > 2 && profitCommission.stripTrailingZeros().scale() > 2) {
                problems.add("Profit commission has at most two decimal places");
            }
            return problems;
        }

        private BigDecimal net() { return recoveries.add(commission).subtract(premium.subtract(withheld)); }

        public BigDecimal owedToUs() { return net().max(BigDecimal.ZERO); }

        public BigDecimal owedByUs() { return net().negate().max(BigDecimal.ZERO); }

        public List<JournalLine> journal() {
            List<JournalLine> lines = new ArrayList<>();
            add(lines, "R-04", "1430", "DR", withheld);
            add(lines, "R-04", "2550", "CR", withheld);
            add(lines, "R-01", "1430", "DR", premium.subtract(withheld));
            add(lines, "R-01", "1431", "CR", commission);
            add(lines, "R-01", "1420", "CR", recoveries);
            add(lines, "R-01", "1434", "DR", owedToUs());
            add(lines, "R-01", "1434", "CR", owedByUs());
            add(lines, "R-03", "1433", "DR", profitCommission);
            add(lines, "R-03", "6120", "CR", profitCommission);
            return lines;
        }

        private static void add(List<JournalLine> lines, String entry, String account, String side, BigDecimal amount) {
            if (amount.signum() != 0) {
                lines.add(new JournalLine(entry, account, side, amount));
            }
        }
    }
}
```

- [ ] **Step 5: Run it and see it pass**

Run: `./mvnw -B -o test -Dtest=StatementCalculatorTest`. Expected: 7 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/db-migrations/reinsurance/V7__statement.sql backend/src && git commit -m "feat(reinsurance): I3d -- the statement's tables (V7, legacy recoveries) and its arithmetic"
```

---

### Task 3: The statement workflow — store, service and API

**Files:**
- Create: `reinsurance/api/StatementView.java`, `reinsurance/api/StatementNotFoundException.java`,
  `reinsurance/api/StatementStateException.java`
- Modify: `reinsurance/api/ReinsuranceApi.java` (statement methods)
- Create: `reinsurance/application/Statements.java` (JDBC store), `reinsurance/application/ReinsuranceStatements.java`
  (public service with a `today` seam)
- Modify: `reinsurance/application/ReinsuranceApiImpl.java` (delegates to the service)
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/reinsurance/StatementIntegrationTest.java`
- Modify: every test class whose list holds `db-migrations/reinsurance/V6__scheme_may_open_empty.sql` (append V7)

**Interfaces:**
- Consumes: `StatementCalculator` (Task 2).
- Produces, on `ReinsuranceApi`:
  - `StatementView prepareStatement(UUID treatyId, String quarter, String preparer)`
  - `StatementView updateStatement(UUID id, BigDecimal fundsWithheld, BigDecimal profitCommission, String reason, String by)`
  - `StatementView submitStatement(UUID id, String by)`, `withdrawStatement(UUID id, String by)`
  - `StatementView approveStatement(UUID id, String approver)`, `rejectStatement(UUID id, String reason, String by)`
  - `StatementView getStatement(UUID id)`, `List<StatementView> listStatements(String status, UUID treatyId)`
  - `void requireStatementEditable(UUID id, String by)`, `StatementView attachStatementDocument(UUID id, String ref, String by)`

  `StatementView(UUID statementId, UUID treatyId, String reinsurerName, String quarter, String currency, String status,
  BigDecimal premium, BigDecimal commission, BigDecimal recoveries, BigDecimal fundsWithheld,
  BigDecimal profitCommission, BigDecimal owedToUs, BigDecimal owedByUs, String reason, List<String> documentRefs,
  String preparer, Instant preparedAt, Instant submittedAt, String decidedBy, Instant decidedAt, String decisionReason,
  List<Item> items, List<JournalLine> journal)`, with `Item(String type, UUID id, String label, BigDecimal amount)` and
  `JournalLine(String entry, String account, String side, BigDecimal amount)`.

  `ReinsuranceStatements` (public, `@Service`) has `public StatementView prepare(UUID treatyId, String quarter, String
  preparer, LocalDate today)`, so the cross-module tests can name the date.

  Event `reinsurance.StatementApproved`, payload: `statementId`, `treatyId`, `reinsurerName`, `quarter`, and
  `premium`, `commission`, `recoveries`, `fundsWithheld`, `profitCommission`, `owedToUs`, `owedByUs`, each as
  `{amount, currencyCode}`.

- [ ] **Step 1: Append V7 to the test migration lists**

Run (from `backend/`):
`node scripts/dev/append-test-migration.mjs db-migrations/reinsurance/V6__scheme_may_open_empty.sql db-migrations/reinsurance/V7__statement.sql`.
Confirm the script's arguments with its header comment first. If they differ, use the exact-line insert from the
empty-scheme fix (a node script reading `git grep -l -F` and splicing the next line, keeping CRLF).

- [ ] **Step 2: Write the failing integration test**

`StatementIntegrationTest`: `@Testcontainers @SpringBootTest(classes = Application.class)`, as app_role, with
BordereauIntegrationTest's migration list plus `db-migrations/reinsurance/V6__scheme_may_open_empty.sql` and
`db-migrations/reinsurance/V7__statement.sql`, and the same `publish`, `quotaShare` and `activate` helpers (copy them;
the classes are independent). Autowire `ReinsuranceApi api`, `ReinsuranceStatements statements`,
`BordereauJob bordereauJob`, and an `EventRecorder` filtering `reinsurance.StatementApproved`. The tests:

```java
    private static final LocalDate AFTER_Q1 = LocalDate.of(2026, 4, 2);

    /** Q1 2026 with every bordereau written: Jan-Mar of a 50% quota share, 100,000 monthly, 20% commission. */
    private TreatyView settledQ1(UUID tenantId) {
        TreatyView treaty = quotaShare(tenantId, "50.00", "20.00");
        activate(tenantId, "TERM_LIFE", "MONTHLY", "100000.00", LocalDate.of(2026, 1, 5));
        bordereauJob.drain(LocalDate.of(2026, 4, 1));
        return treaty;
    }

    @Test
    void aQuarterIsPreparedFromItsBordereauxAndApprovedByASecondPerson() {
        UUID tenant = UUID.randomUUID();
        TreatyView treaty = settledQ1(tenant);
        TenantContext.set(tenant);
        StatementView draft = statements.prepare(treaty.treatyId(), "2026-Q1", "finance-one", AFTER_Q1);
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.premium()).isEqualByComparingTo("150000.00");     // 3 x 50,000
        assertThat(draft.commission()).isEqualByComparingTo("30000.00");   // 20% of it
        assertThat(draft.items()).filteredOn(i -> i.type().equals("BORDEREAU")).hasSize(3);
        assertThat(draft.owedByUs()).isEqualByComparingTo("120000.00");

        assertThatThrownBy(() -> api.submitStatement(draft.statementId(), "finance-one"))
            .isInstanceOf(ReinsuranceValidationException.class).hasMessageContaining("reinsurer's statement");
        api.attachStatementDocument(draft.statementId(), "doc-q1", "finance-one");
        api.updateStatement(draft.statementId(), new BigDecimal("20000.00"), new BigDecimal("5000.00"),
            "Africa Re Q1 statement agreed", "finance-one");
        api.submitStatement(draft.statementId(), "finance-one");
        assertThatThrownBy(() -> api.approveStatement(draft.statementId(), "finance-one"))
            .isInstanceOf(StatementStateException.class).hasMessageContaining("second person");

        StatementView approved = api.approveStatement(draft.statementId(), "finance-approver");
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(recorder.statementsOf(tenant)).singleElement().satisfies(e -> {
            @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) e.payload();
            assertThat(p.get("owedByUs")).isEqualTo(Map.of("amount", "100000.00", "currencyCode", "TZS"));
            assertThat(p.get("profitCommission")).isEqualTo(Map.of("amount", "5000.00", "currencyCode", "TZS"));
        });
    }

    @Test
    void aQuarterIsRefusedBeforeItEndsOrWhileABordereauIsMissing() {
        UUID tenant = UUID.randomUUID();
        TreatyView treaty = quotaShare(tenant, "50.00", "0");
        TenantContext.set(tenant);
        assertThatThrownBy(() -> statements.prepare(treaty.treatyId(), "2026-Q1", "finance-one", LocalDate.of(2026, 3, 31)))
            .isInstanceOf(StatementStateException.class).hasMessageContaining("has not ended");
        assertThatThrownBy(() -> statements.prepare(treaty.treatyId(), "2026-Q1", "finance-one", AFTER_Q1))
            .isInstanceOf(StatementStateException.class).hasMessageContaining("2026-01").hasMessageContaining("bordereau");
    }

    @Test
    void aQuarterIsSettledOnceAndAgainOnlyAfterARejection() {
        UUID tenant = UUID.randomUUID();
        TreatyView treaty = settledQ1(tenant);
        TenantContext.set(tenant);
        StatementView first = statements.prepare(treaty.treatyId(), "2026-Q1", "finance-one", AFTER_Q1);
        assertThatThrownBy(() -> statements.prepare(treaty.treatyId(), "2026-Q1", "finance-two", AFTER_Q1))
            .isInstanceOf(StatementStateException.class).hasMessageContaining("already");
        api.attachStatementDocument(first.statementId(), "doc", "finance-one");
        api.updateStatement(first.statementId(), BigDecimal.ZERO, BigDecimal.ZERO, "Q1", "finance-one");
        api.submitStatement(first.statementId(), "finance-one");
        api.rejectStatement(first.statementId(), "Reinsurer disputes March", "finance-approver");
        assertThat(statements.prepare(treaty.treatyId(), "2026-Q1", "finance-two", AFTER_Q1).status()).isEqualTo("DRAFT");
    }

    @Test
    void legacyRecoveriesAreNeverSettled() {
        UUID tenant = UUID.randomUUID();
        TreatyView treaty = settledQ1(tenant);
        // a recovery recorded in Q1 but marked legacy (it never posted 1420)
        jdbc.update("INSERT INTO reinsurance.claim_recovery (tenant_id, claim_id, treaty_id, recoverable_amount,"
            + " created_at, legacy) VALUES (?, ?, ?, 900000.00, '2026-02-10T09:00:00Z', true)",
            tenant, UUID.randomUUID(), treaty.treatyId());
        TenantContext.set(tenant);
        assertThat(statements.prepare(treaty.treatyId(), "2026-Q1", "finance-one", AFTER_Q1).recoveries()).isZero();
    }

    @Test
    void anotherTenantSeesNoStatement() {
        UUID tenant = UUID.randomUUID();
        TreatyView treaty = settledQ1(tenant);
        TenantContext.set(tenant);
        StatementView mine = statements.prepare(treaty.treatyId(), "2026-Q1", "finance-one", AFTER_Q1);
        TenantContext.set(UUID.randomUUID());
        assertThat(api.listStatements(null, null)).isEmpty();
        assertThatThrownBy(() -> api.getStatement(mine.statementId())).isInstanceOf(StatementNotFoundException.class);
    }
```

The legacy insert runs as app_role under RLS. If the policy blocks it, set the tenant first with
`TenantContext.set(tenant)` and run it through a `TransactionTemplate`, the way `publish` does.

- [ ] **Step 3: Run it and see it fail** (compile: the API and service don't exist yet)

- [ ] **Step 4: Implement**

`StatementStateException` (409) and `StatementNotFoundException` (404) are plain `RuntimeException`s like
`BordereauNotFoundException`. `StatementView` is the record from the Interfaces block.

`Statements` (package-private JDBC store, under the caller's tenant):
- `insert(...)` returns the id.
- `insertItem(statementId, tenantId, type, itemId)`.
- `find(tenantId, id)` returns `Optional<Row>`; `list(tenantId, status, treatyId)` is newest first, at most 500.
- `items(tenantId, id)`.
- `bordereauxOf(tenantId, treatyId, Quarter)` returns `List<BordereauRow(id, period, premium, commission)>`.
- `recoveriesOf(tenantId, treatyId, Quarter)` returns `List<RecoveryRow(id, claimId, amount)>`: recoveries with
  `created_at` in [`quarter.start()`, `endExclusive()`) at Africa/Dar_es_Salaam midnight, `NOT legacy`, and not on a
  live statement.
- `updateFigures(...)` (DRAFT only), `move(tenantId, id, from, to)`, `decide(...)`.
- `addDocument` (DRAFT only, `array_append` when absent), and `release(tenantId, id)`, which sets
  `statement_item.live = false` on rejection.

Every state change is one guarded `UPDATE ... AND status = ?`, as in I4's `ManualJournals`.

`ReinsuranceStatements` (public `@Service`):
- **`prepare(treatyId, quarter, preparer, today)`:** parse the quarter (an IllegalArgumentException becomes a
  `ReinsuranceValidationException`). Load the treaty, or throw `TreatyNotFoundException`. If
  `!quarter.hasEnded(today)`, refuse with `"Quarter " + quarter + " has not ended; it settles from " + quarter.endExclusive()`.
  For each month in `monthsRequired(quarter, treaty.effectiveFrom, treaty.effectiveTo)` with no bordereau, refuse with
  `"The " + month + " bordereau of this treaty is not written yet; a quarter settles once all its months are"`. If a
  live statement exists, refuse with `"Quarter " + quarter + " already has a statement (" + status + ")"`. Then insert
  the statement with P/C/R summed from the rows, insert every item, and catch a `DuplicateKeyException` as the same
  "already" refusal.
- **`update`:** preparer only, DRAFT only. Checks `Figures.problems()` and refuses with them joined by `; ` (422).
- **`submit`:** preparer only, from DRAFT. Problems: `reason` blank → "Say why (the statement's reference or what was
  agreed)"; no document → "Attach the reinsurer's statement"; plus `Figures.problems()`. Then move to SUBMITTED.
- **`withdraw`:** preparer only, from SUBMITTED to DRAFT.
- **`approve`:** from SUBMITTED, never the preparer ("You prepared this statement; a second person must approve it"),
  with the figures checked again. Then `decide(APPROVED)`. If the journal is not empty, publish
  `reinsurance.StatementApproved` with the payload above (money as `{amount: toPlainString(), currencyCode}`), using
  `ApplicationEventPublisher` inside the transaction so the AFTER_COMMIT listeners post it.
- **`reject`:** needs a reason ("Say why the statement is rejected"), from SUBMITTED, never the preparer. Then
  `decide(REJECTED, reason)` and `release`.
- **`view`:** builds `StatementView` with `owedToUs`/`owedByUs` and `journal` from `Figures`. Items are labelled
  "Bordereau 2026-01" or "Recovery on claim <claimId>".

All methods are `@Transactional`, and `get`/`list` are `readOnly`. `ReinsuranceApiImpl` delegates;
`prepareStatement` calls `prepare(..., LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam")))`.

- [ ] **Step 5: Run the tests and see them pass**

Run: `./mvnw -B -o test -Dtest='StatementIntegrationTest,StatementCalculatorTest,BordereauIntegrationTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A backend && git commit -m "feat(reinsurance): I3d -- prepare, submit, approve and reject a quarter's statement; approval publishes StatementApproved"
```

---

### Task 4: Posting the statement — rules v4, R-STMT

**Files:**
- Modify: `backend/src/main/resources/finaccounting/posting-rules.yaml` (`version: 4`, rule R-STMT)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/finaccounting/application/PostingFactsExtractor.java`
  (shape and case)
- Modify: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/FinaccountingContractTest.java:800`
  (`posting-rules v4`)
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/finaccounting/ReinsuranceAndLoanPostingEndToEndTest.java`
  (a new test method)

**Interfaces:**
- Consumes: the `reinsurance.StatementApproved` payload (Task 3) and `Rule#system()` (Task 1).
- Produces: one SYSTEM journal per approved statement, source event `reinsurance.StatementApproved`, source ref the
  statement id, refType STATEMENT.

- [ ] **Step 1: Write the failing test**

Add to `ReinsuranceAndLoanPostingEndToEndTest` (it already wires the whole flow, with a real recovery posting 1420 and
a real bordereau). Autowire `tz.co.nlolo.lifeplatform.reinsurance.application.ReinsuranceStatements statements` and
`tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi reinsuranceApi`.

```java
    /**
     * IFRS 17 I3d: a quarter's statement clears exactly what the bordereaux and recoveries posted. After it, 1430, 1431
     * and 1420 are zero for the treaty and 1434 carries the balance -- one SYSTEM journal (R-01, R-04, R-03).
     */
    @Test
    void aQuartersStatementClearsTheBordereauxAndRecoveriesIntoTheCurrentAccount() throws Exception {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        Fixture fixture = buildFixture(tenantId, "STMT-" + UUID.randomUUID().toString().substring(0, 6));
        createQuotaShareTreaty(tenantId, new BigDecimal("50.00"));
        String policy = issuePolicy(tenantId, fixture);
        // ... approve a death claim on it exactly as the existing test does (registerAndAssessDeathClaim + approval),
        //     so a RecoveryCalculated journal posts 1420 in this quarter.
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Dar_es_Salaam"));
        var quarter = tz.co.nlolo.lifeplatform.reinsurance.domain.StatementCalculator.Quarter.of(today);
        bordereauJob.drain(quarter.endExclusive());                     // every month of this quarter written

        TenantContext.set(tenantId);
        UUID treatyId = reinsuranceApi.listTreaties(null).get(0).treatyId();
        var draft = statements.prepare(treatyId, quarter.toString(), "finance-one", quarter.endExclusive());
        reinsuranceApi.attachStatementDocument(draft.statementId(), "doc-stmt", "finance-one");
        reinsuranceApi.updateStatement(draft.statementId(), BigDecimal.ZERO, new BigDecimal("1000.00"), "agreed", "finance-one");
        reinsuranceApi.submitStatement(draft.statementId(), "finance-one");
        reinsuranceApi.approveStatement(draft.statementId(), "finance-approver");

        JournalEntry stmt = singleEntryFor(tenantId, "reinsurance.StatementApproved", draft.statementId().toString());
        assertThat(stmt.getSourceType()).isEqualTo(JournalSource.SYSTEM);
        List<GlPosting> all = glPostingRepository.findByTenantId(tenantId);   // use the repository's real finder
        assertThat(netFor(all, "1430")).as("premium payable cleared").isZero();
        assertThat(netFor(all, "1431")).as("commission receivable cleared").isZero();
        assertThat(netFor(all, "1420")).as("recoveries cleared").isZero();
        assertThat(netFor(all, "1433")).isEqualByComparingTo("1000.00");
    }
```

Copy the claim approval steps literally from the existing test method in the same file, and the repository finder from
`legsFor` / `netFor`. Read lines 437-470 before writing this. `getSourceType()` is the JournalEntry getter for
`source_type`; check its name with `grep -n "public JournalSource" JournalEntry.java`. If the posted day is the same
period, the existing `netFor(List, account)` returns Dr minus Cr.

- [ ] **Step 2: Run it and see it fail**

Run: `./mvnw -B -o test -Dtest=ReinsuranceAndLoanPostingEndToEndTest`. Expected: FAIL, because the event is queued as
UNMAPPED and `singleEntryFor` finds no journal.

- [ ] **Step 3: Implement**

`PostingFactsExtractor` SHAPES:

```java
        Map.entry("reinsurance.StatementApproved", new EventShape(Set.of("premiumNet", "withheld", "commission",
            "recoveries", "owedToUs", "owedByUs", "profitCommission"), Set.of())),
```

and the case:

```java
            case "reinsurance.StatementApproved" -> {
                BigDecimal withheld = amountOf(p.get("fundsWithheld"));
                yield List.of(facts(type, string(p.get("statementId")), null, currencyOf(p.get("premium")), today,
                    Map.of("premiumNet", amountOf(p.get("premium")).subtract(withheld), "withheld", withheld,
                        "commission", amountOf(p.get("commission")), "recoveries", amountOf(p.get("recoveries")),
                        "owedToUs", amountOf(p.get("owedToUs")), "owedByUs", amountOf(p.get("owedByUs")),
                        "profitCommission", amountOf(p.get("profitCommission"))),
                    Map.of("refType", "STATEMENT")));
            }
```

`posting-rules.yaml`: `version: 4`, and after B-05:

```yaml
  # IFRS 17 I3d: the quarterly reinsurance statement settles what K-01/K-02 and B-05 posted into the current account
  # (R-01), keeps funds withheld (R-04) and books profit commission (R-03). The platform's own journal: it mixes AUTO
  # (1430, 1431, 1420, 6120) and MAN (1434, 1433, 2550) accounts, which only a SYSTEM journal may do.
  - id: R-STMT
    event: reinsurance.StatementApproved
    models: [NONE]
    source: SYSTEM
    description: Reinsurance statement -- R-04 funds withheld, R-01 offset into the current account, R-03 profit commission
    lines:
      - {dr: "1430", amount: withheld}
      - {cr: "2550", amount: withheld}
      - {dr: "1430", amount: premiumNet}
      - {cr: "1431", amount: commission}
      - {cr: "1420", amount: recoveries}
      - {dr: "1434", amount: owedToUs}
      - {cr: "1434", amount: owedByUs}
      - {dr: "1433", amount: profitCommission}
      - {cr: "6120", amount: profitCommission}
```

`FinaccountingContractTest:800`: `posting-rules v4`.

- [ ] **Step 4: Run the tests and see them pass**

Run: `./mvnw -B -o test -Dtest='ReinsuranceAndLoanPostingEndToEndTest,FinaccountingContractTest,PostingEngineIntegrationTest,PostingRuleSourceTest'`.
Expected: PASS. The posting-rules page test may also pin the version; check with `grep -rn "v3" frontend/e2e`.

- [ ] **Step 5: Commit**

```bash
git add -A backend && git commit -m "feat(ifrs17): I3d -- posting rules v4: an approved reinsurance statement posts R-04, R-01 and R-03 as one SYSTEM journal"
```

---

### Task 5: HTTP — documents, controller, OpenAPI, contract

**Files:**
- Create: `backend/db-migrations/document/V8__reinsurance_statement_document_type.sql`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/document/api/DocumentType.java` (`REINSURANCE_STATEMENT`)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/reinsurance/package-info.java`
  (`allowedDependencies = { "refdata::api", "document::api" }`)
- Create: `reinsurance/infrastructure/StatementController.java`
- Modify: `reinsurance/infrastructure/ReinsuranceExceptionHandler.java` (404 `STATEMENT_NOT_FOUND`, 409 `STATEMENT_STATE`)
- Modify: `backend/api/openapi/openapi-reinsurance.yaml`
- Test: `ReinsuranceContractTest` (new method), `StatementControllerTest` (owner length, pure)
- Modify: test lists holding `document/V7__journal_support_document_type.sql` (append V8)

**Interfaces:**
- Consumes: the `ReinsuranceApi` statement methods (Task 3) and
  `DocumentApi.upload(owner, DocumentType, by, InputStream, size, contentType, fileName)`.
- Produces: the endpoints listed in Global Constraints. Request bodies: `{quarter}` (prepare);
  `{fundsWithheld, profitCommission, reason}` (update); `{reason}` (rejection). The response is the `StatementView`
  JSON.

- [ ] **Step 1: The migration** (copy document V7 and add the type)

```sql
-- db-migrations/document/V8__reinsurance_statement_document_type.sql
-- IFRS 17 I3d: the reinsurer's quarterly statement, attached to the statement that settles it.
ALTER TABLE document.document_record DROP CONSTRAINT IF EXISTS document_record_document_type_check;
ALTER TABLE document.document_record
    ADD CONSTRAINT document_record_document_type_check
    CHECK (document_type IN ('KYC_EVIDENCE','POLICY_DOCUMENT','CLAIM_EVIDENCE','SIGNED_FORM',
                             'UNDERWRITING_EVIDENCE','ENROLMENT_SCHEDULE','EXITS_FILE','ACCOUNT_STATEMENT',
                             'JOURNAL_SUPPORT','REINSURANCE_STATEMENT'));
```

Append it to the test lists:
`node scripts/dev/append-test-migration.mjs db-migrations/document/V7__journal_support_document_type.sql db-migrations/document/V8__reinsurance_statement_document_type.sql`.

- [ ] **Step 2: Write the failing tests**

`StatementControllerTest` (pure):

```java
    @Test
    void aStatementsDocumentOwnerFitsTheDocumentStore() {
        assertThat(StatementController.ownerContextOf(UUID.randomUUID())).startsWith("statement:").hasSizeLessThanOrEqualTo(50);
    }
```

`ReinsuranceContractTest`: a new method that seeds Q1 through `ReinsuranceStatements.prepare` (with
`LocalDate.of(2026, 4, 2)`), attaches a document reference through the API, updates and submits it. Then:
- `POST /reinsurance-statements/{id}/approval` as finance staff without FINANCE_APPROVER → 403;
- the same as an approver (`ROLE_FINANCE_APPROVER` added to the token) → 200, `openApi().isValid(SPEC)`,
  `$.status == APPROVED`;
- `GET /reinsurance-statements?status=APPROVED` → 200 and valid;
- `POST /treaties/{id}/statements` with `{"quarter":"2099-Q1"}` → 409 `STATEMENT_STATE`.

Copy the token helpers from `FinaccountingContractTest#financeApproverOf`.

- [ ] **Step 3: Run them and see them fail**

- [ ] **Step 4: Implement**

`StatementController`, following I4's `ManualJournalController`:
- `FINANCE` = `hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))`;
- `APPROVER` = `hasRole('REALM_STAFF') and hasRole('FINANCE_APPROVER')`;
- list and get allow `FINANCE or APPROVER`;
- documents: `api.requireStatementEditable(id, sub)` first, then `AllowedDocumentContentTypes.normalizeOrThrow`, then
  `documents.upload(ownerContextOf(id), DocumentType.REINSURANCE_STATEMENT, ...)`, then `api.attachStatementDocument`.

```java
    /** The document store's owner: VARCHAR(50), and "statement:" + a UUID is 46 (I4's 51-char owner failed every upload). */
    static String ownerContextOf(UUID id) {
        return "statement:" + id;
    }
```

OpenAPI:
- one path per endpoint, under `security: [staffAuth: []]`;
- schemas `StatementView` (`additionalProperties: false`, with nested `StatementItem` and `StatementJournalLine`),
  `PrepareStatementRequest {quarter}`, `UpdateStatementRequest {fundsWithheld, profitCommission, reason}`,
  `StatementRejectionRequest {reason}`;
- status enum `[DRAFT, SUBMITTED, APPROVED, REJECTED]`; money fields `type: number`; nullable fields as
  `type: [string, "null"]`.

- [ ] **Step 5: Run the tests and see them pass**

Run: `./mvnw -B -o test -Dtest='ReinsuranceContractTest,StatementControllerTest,ModularityTests,DocumentContractTest'`.
Expected: PASS, with ModularityTests accepting the new `document::api` dependency.

- [ ] **Step 6: Commit**

```bash
git add -A backend && git commit -m "feat(reinsurance): I3d -- statement endpoints, the reinsurer's statement as a document (document V8)"
```

---

### Task 6: Console — statements on the treaty, the statement page, the finance list

**Files:**
- Modify: `frontend/src/api/reinsurance.ts` (types re-exported from `@/api/types` after `npm run generate:api`)
- Modify: `frontend/src/api/types.ts` (`StatementView` and the request types, from `ReinsuranceComponents`)
- Modify: `frontend/src/store/reinsuranceStore.ts` (statements per treaty, the current statement, the list, actions)
- Create: `frontend/src/features/reinsurance/statementForm.ts` (pure: quarter helpers, the 1434 side label)
- Create: `frontend/src/features/reinsurance/statementForm.test.ts`
- Create: `frontend/src/features/reinsurance/StatementPage.tsx`, `ReinsuranceStatementsPage.tsx`
- Modify: `frontend/src/features/reinsurance/TreatyDetailPage.tsx` (a `TreatyStatements` panel after `TreatyBordereaux`)
- Modify: `frontend/src/lazyPages.ts`, `frontend/src/screens.tsx`

**Interfaces:**
- Consumes: the endpoints from Task 5.
- Produces:
  - routes `treaties/:treatyId/statements/:statementId` (drill-in) and `reinsurance-statements` (finance group, label
    "Reinsurance statements");
  - the accessible names e2e uses: a "Prepare statement for 2026-Q3" button; a "Statement" region; "Funds withheld",
    "Profit commission" and "Reason" labels; a "Statement file" input; the buttons "Save", "Submit for approval",
    "Approve and post", "Reject"; the "Posted" status text; and a "Journal" table.

- [ ] **Step 1: Write the failing unit test**

```ts
import { describe, expect, it } from 'vitest';
import { endedQuarters, settlementSide } from './statementForm';

describe('statementForm', () => {
  it('offers the quarters that have ended since the treaty began, newest first', () => {
    expect(endedQuarters('2026-02-10', null, new Date('2026-10-06T10:00:00+03:00'))).toEqual(['2026-Q3', '2026-Q2', '2026-Q1']);
    expect(endedQuarters('2026-09-01', null, new Date('2026-09-30T23:00:00+03:00'))).toEqual([]);
  });
  it('says which way the current account falls', () => {
    expect(settlementSide({ owedToUs: 28560000, owedByUs: 0 })).toBe('The reinsurer owes us 28,560,000.00');
    expect(settlementSide({ owedToUs: 0, owedByUs: 120000 })).toBe('We owe the reinsurer 120,000.00');
    expect(settlementSide({ owedToUs: 0, owedByUs: 0 })).toBe('Settled: nothing owed either way');
  });
});
```

- [ ] **Step 2: Run it and see it fail** — `npx vitest run src/features/reinsurance/statementForm.test.ts`

- [ ] **Step 3: Implement `statementForm.ts`**

```ts
/** Calendar quarters (IFRS 17 I3d) that have ended by `now` in Dar es Salaam, from the treaty's start, newest first. */
export function endedQuarters(effectiveFrom: string, effectiveTo: string | null, now: Date = new Date()): string[] {
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Dar_es_Salaam' }).format(now); // YYYY-MM-DD
  const [ty, tm] = today.split('-').map(Number);
  const currentIndex = ty * 4 + Math.floor((tm - 1) / 3);
  const [fy, fm] = effectiveFrom.split('-').map(Number);
  let index = fy * 4 + Math.floor((fm - 1) / 3);
  const last = effectiveTo
    ? (() => { const [ey, em] = effectiveTo.split('-').map(Number); return ey * 4 + Math.floor((em - 1) / 3); })()
    : Number.MAX_SAFE_INTEGER;
  const out: string[] = [];
  for (; index < currentIndex && index <= last; index++) {
    out.push(`${Math.floor(index / 4)}-Q${(index % 4) + 1}`);
  }
  return out.reverse();
}

const money = (n: number) => n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** Which way the reinsurer current account (1434) falls once the statement posts. */
export function settlementSide(s: { owedToUs: number; owedByUs: number }): string {
  if (s.owedToUs > 0) return `The reinsurer owes us ${money(s.owedToUs)}`;
  if (s.owedByUs > 0) return `We owe the reinsurer ${money(s.owedByUs)}`;
  return 'Settled: nothing owed either way';
}
```

- [ ] **Step 4: Pages.** Follow `ManualJournalDetailPage.tsx` and `TreatyDetailPage.tsx`'s Panel and DataTable patterns.

  **`TreatyStatements` (on the treaty page):**
  - a Panel titled "Statements" listing the treaty's statements (quarter, status, net side, prepared);
  - for each quarter in `endedQuarters(...)` with no live statement, a `Button` named
    `Prepare statement for {quarter}`. On click: `prepareStatement(treatyId, quarter)`, then navigate to
    `statements/{id}`.

  **`StatementPage`:**
  - header `{reinsurerName} · {quarter}` with the status label;
  - a "Statement" region with the platform figures;
  - an "Items" table;
  - when DRAFT and the viewer is the preparer: a form with `Funds withheld`, `Profit commission` and `Reason`, and a
    Save button;
  - documents with a `Statement file` input;
  - a "Journal" table from `journal`, plus `settlementSide`;
  - actions: Submit for approval and Withdraw (preparer); Approve and post and Reject with a reason (approver who is
    not the preparer, `canApproveJournals`); "Waiting for a finance approver other than you" for the preparer.

  **`ReinsuranceStatementsPage`:** a status filter and a table linking to each statement.

  Routes in `screens.tsx`:
  - `{ path: 'treaties/:treatyId/statements/:statementId', element: <StatementPage />, reach: 'drill-in' }`;
  - `{ path: 'reinsurance-statements', element: <ReinsuranceStatementsPage />, reach: { group: 'finance', label: 'Reinsurance statements', icon: Scale } }`,
    with an icon from `lucide-react` that the file already imports, or one added to its import.

  Keep `useEffect` bodies free of setState: the lint bans it, so load through store actions only.

- [ ] **Step 5: Verify**

Run: `npm run -s generate:api && npm run -s typecheck && npm run -s lint && npx vitest run src/features/reinsurance src/store`.
Expected: clean, and all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add -A frontend && git commit -m "feat(console): I3d -- treaty statements, the statement page with its journal preview, reinsurance statements list"
```

---

### Task 7: e2e, the dev stack, gate, merge

**Files:**
- Create: `frontend/e2e/staff-reinsurance-statement.spec.ts`

- [ ] **Step 1: Write the spec**

```ts
import { expect, test } from '@playwright/test';

/**
 * IFRS 17 I3d through the real stack: a treaty's ended quarter is prepared from its bordereaux, the reinsurer's
 * statement is uploaded (a real file -- I4's document bug hid behind fake references), submitted, approved by a finance
 * approver, and posted.
 */
test.describe('reinsurance statement', () => {
  test.use({ storageState: 'e2e/.auth/staff-finance.json' });

  test('a quarter is prepared, approved by a finance approver and posted', async ({ page, browser }) => {
    await page.goto('/staff/treaties');
    // a dev treaty whose Q3 2026 September bordereau is written (I3c's first run wrote 8 for 2026-09)
    await page.getByRole('row').filter({ hasText: 'Quota share' }).first().click();
    await page.getByRole('button', { name: /^Prepare statement for 2026-Q3$/ }).click();
    await expect(page.getByRole('region', { name: 'Statement' })).toBeVisible({ timeout: 15_000 });
    await page.getByLabel('Reason').fill('Q3 2026 statement agreed (e2e)');
    await page.getByRole('button', { name: 'Save' }).click();
    await page.getByLabel('Statement file').setInputFiles({
      name: 'q3-statement.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4\n% e2e statement\n'),
    });
    await expect(page.getByRole('region', { name: 'Documents' }).locator('li')).toHaveCount(1, { timeout: 15_000 });
    await page.getByRole('button', { name: 'Submit for approval' }).click();
    await expect(page.getByText('Waiting for a finance approver other than you')).toBeVisible();
    const url = page.url();

    const approverContext = await browser.newContext({ storageState: 'e2e/.auth/staff-finance-approver.json' });
    const approver = await approverContext.newPage();
    await approver.goto(url);
    await expect(approver.getByRole('table', { name: 'Journal' })).toContainText('1430');
    await approver.getByRole('button', { name: 'Approve and post' }).click();
    await expect(approver.getByText('Posted', { exact: true })).toBeVisible({ timeout: 15_000 });
    await approverContext.close();
  });
});
```

The treaty must have a 2026-Q3 that can still be settled. That is any treaty effective by September 2026 whose
September bordereau exists and that has no live Q3 statement. A rerun would find Q3 already settled, so pick a treaty
that still shows the Q3 button:

```ts
const treatyRows = page.getByRole('row').filter({ hasText: 'Quota share' });
// open treaties until one offers "Prepare statement for 2026-Q3"
```

If no dev treaty can settle Q3, create one through the UI (as `staff-reinsurance.spec.ts` does) effective 2026-09-01,
and write its September bordereau with the API (`POST` is not exposed). Instead, settle the quarter the bordereau job
already wrote. Decide this while writing the spec, after checking the dev data:

```sql
SELECT t.treaty_id FROM reinsurance.reinsurance_treaty t
 WHERE EXISTS (SELECT 1 FROM reinsurance.bordereau b WHERE b.treaty_id = t.treaty_id AND b.period = '2026-09')
   AND NOT EXISTS (SELECT 1 FROM reinsurance.bordereau b WHERE b.treaty_id = t.treaty_id AND b.period IN ('2026-07','2026-08'))
   AND t.effective_from >= '2026-09-01';
```

- [ ] **Step 2: The dev stack**

Apply `reinsurance/V7__statement.sql` and `document/V8__reinsurance_statement_document_type.sql` to dev with
`docker exec -i infra-postgres-1 psql -U postgres -d lifeplatform -v ON_ERROR_STOP=1 -q --single-transaction`. Check:
`SELECT count(*) FILTER (WHERE legacy), count(*) FROM reinsurance.claim_recovery` should show the pre-I3c ones as
legacy and the 4 posted ones as not. Then stop the I4 dev backend and Vite (find their PIDs on 8080/5173), and start
both from `.worktrees/ifrs17-i3d` (junction `node_modules` to `ifrs17-i3c`'s).

- [ ] **Step 3: Gate**

- **Affected backend classes:**
  `git diff --name-only main HEAD -- 'backend/src/test/**/*Test.java'` → class names →
  `./mvnw -B -o clean test -Dtest=<list>` (dev backend stopped). Expected: 0 failures.
- **Console:** `npm run -s typecheck && npm run -s lint && npx vitest run`.
- **e2e:** the full suite `npx playwright test --reporter=line` with the dev backend running from this worktree. A
  timeout failure gets rerun alone before it is diagnosed.

- [ ] **Step 4: Merge and push**

```bash
git merge --no-ff ifrs17-i3d -m "Merge IFRS 17 I3d: the quarterly reinsurance statement (R-01, R-03, R-04)"
git push origin main && git push origin ifrs17-i3d
```

Then update memory `project_ifrs17_i3d_statement.md` with the merge commit, and record I5 (engine interface) as next.
