# Credit Life Plan 3: Premium, Exits and Refunds — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a credit-life scheme charge the premium it actually earns — one invoice per accepted file, computed from a per-lender rate — and make a loan that ends early give that premium back, with the bank's commission clawed back alongside it.

**Architecture:** Six tasks in the order the user approved, refunds last. Tasks 1–2 replace the master policy's wrongly-generated recurring billing schedule with a single premium raised per accepted enrolment file. Tasks 3–4 give a loan a way to leave, first one at a time and then in the monthly bulk file the client asked for. Tasks 5–6 return money: a pro-rata refund on the unexpired term, and a pro-rata commission reversal that must not be separable from it.

**Tech Stack:** Java 21, Spring Boot, Spring Modulith, JPA/Hibernate, Flyway (per-module dirs under `backend/db-migrations/`, applied by `backend/scripts/migrate.sh`, **not** on boot), Postgres 16 with RLS, Testcontainers, JUnit 5, AssertJ, Apache Commons CSV.

---

## Global Constraints

Copied from the spec and from constraints this codebase has already paid for. Every task's requirements implicitly include this section.

- **Premium is an agreed percent PER ANNUM of the ORIGINAL principal, charged once** (spec §2.8, client answer 3.1). The rate is **adjustable per lender** — 0.4% and 0.5% were both quoted — so it lives on the scheme, never on the product.
- **Currency is TZS only for v1** (client answer to q49). Scheme-level, already on `group_scheme.currency`.
- **Cover declines straight-line**: `principal × (n−k)/n`. `AmortisationCalculator.outstandingPrincipalAt` with `InterestMethod.FLAT_RATE` is the one implementation; never write a second one.
- **The insurer deals with the lender only** (client answer 3.6). No borrower is ever notified of anything, in any task here.
- **Two-person control on anything that changes cover in bulk** (spec §2.10). The exits file in Task 4 inherits it: proposer and accepter must be different users, enforced by a DB CHECK, not only in Java.
- **Money is `BigDecimal` with an explicit currency string**, never a bare number. Follow `MoneyDto` and the `Map.of("amount", …, "currencyCode", …)` event payload shape already used by billing and distribution.
- **RLS predicates must use `NULLIF(current_setting('app.current_tenant_id', true), '')::uuid`.** A bare cast raises on a RESET GUC. All 91 existing policies use NULLIF; any new policy must too.
- **A cross-module call needs the `module::api` named-interface form** in `allowedDependencies`, or Spring Modulith fails the dependency test confusingly.
- **Never a `default` interface method for anything transactional** — self-invocation skips the proxy, the row saves, and the AFTER_COMMIT event silently never reaches `audit_log`.
- **After any record/DTO signature change, run `./mvnw clean test-compile`.** Maven's incremental compile leaves unchanged tests uncompiled, so a scoped run stays green while the suite is broken.
- **Stop the dev backend before `clean test`.** `clean` deletes `target/` under the live JVM; the tell is `NoClassDefFoundError` plus byte-buddy "class redefinition failed", never assertion failures.
- **A Maven failure naming `target/` is a Windows file lock, not code.** Re-run the same command.
- **Baseline: 1294 tests green on `main` at `9a080ed`.** Every task ends green against that floor.

---

## Three defects this plan closes, found while planning it

These are not speculative. Each was read out of the code on 2026-09-22 and each is live on `main` right now.

**1. A credit-life master policy bills itself, monthly, for a premium nobody agreed.**
`billing.application.PolicyEventListener.handlePolicyIssued` reacts to every `policy.PolicyIssued` by calling `generateScheduleForNewPolicy`, which builds a `BillingSchedule` and then twelve months of `PremiumInvoice` rows ahead of it. `issueGroupScheme` passes `request.premiumAmount()` and `request.premiumFrequency()` straight through, and there is **no `SINGLE` value** in `product.api.PremiumFrequency` — only MONTHLY, QUARTERLY, ANNUALLY — so a credit-life scheme issued today gets a recurring schedule for a hand-typed figure. Those invoices then fall due, go into arrears, and dun the bank for money the contract never asked for. Task 1 closes this.

**2. Accepting an enrolment file publishes no event at all.**
`EnrolmentApiImpl.accept` enrols the members, sets the counts and returns. It publishes nothing. So no other module can know a file was accepted — which is exactly what billing needs in order to raise the one invoice that file earns. Task 2 closes this.

**3. FIRST_YEAR commission on a credit-life scheme accrues once, on the wrong number, and never again.**
`distribution` accrues FIRST_YEAR at policy activation from the projection's issuance premium, and RENEWAL only on later collections (`PremiumEventListener.handlePremiumCollected` explicitly returns without accruing on the first collected invoice). A credit-life scheme's issuance premium is the hand-typed master-policy figure from defect 1; the real premium arrives file by file, for the life of the scheme. Left alone, the bank earns commission on a placeholder. Task 2 fixes the basis; Task 6 makes it reversible.

A fourth, unrelated and cosmetic: `db-migrations/document/` contains **two files claiming V3** — `V3__enrolment_schedule_document_type.sql` and `V3__rls_fail_closed.sql`. `migrate.sh` sorts with `sort -V` and applies both, and they do not conflict, so nothing is broken today. Task 1 renames the enrolment one to V4 while it is cheap.

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `backend/db-migrations/policy/V18__scheme_premium_rate.sql` | `premium_rate_percent` on `group_scheme`; `single_premium` marker on `policy` |
| `backend/db-migrations/policy/V19__member_exit_reason.sql` | `exit_reason` and `outstanding_balance_at_exit` on `policy_member` |
| `backend/db-migrations/policy/V20__exit_submission.sql` | `exit_submission` + `exit_submission_row`, mirroring V15's two-table shape and its two-person CHECK |
| `backend/db-migrations/billing/V5__single_premium_invoice.sql` | Lets a `premium_invoice` exist with no `billing_schedule_id`, plus `enrolment_submission_id` |
| `backend/src/main/java/.../policy/domain/CreditLifePremium.java` | Pure: premium for one loan, and the pro-rata unearned share at an exit date |
| `backend/src/main/java/.../policy/api/ExitReason.java` | `SETTLED_EARLY`, `REFINANCED`, `WRITTEN_OFF`, `CANCELLED`, `CLAIM_SETTLED` |
| `backend/src/main/java/.../policy/domain/ExitCsvParser.java` | Pure: the four-column exits file |
| `backend/src/main/java/.../policy/domain/ExitSubmission.java`, `ExitSubmissionRow.java` | The exits-file state machine |
| `backend/src/main/java/.../policy/api/ExitApi.java` + `application/ExitApiImpl.java` | Propose/accept/withdraw an exits file |
| `backend/src/test/java/.../policy/CreditLifePremiumTest.java` | Pure arithmetic, no Spring |
| `backend/src/test/java/.../policy/MemberExitIntegrationTest.java` | Task 3 |
| `backend/src/test/java/.../policy/ExitCsvParserTest.java`, `ExitFileIntegrationTest.java` | Task 4 |
| `backend/src/test/java/.../billing/SinglePremiumIntegrationTest.java` | Tasks 1, 2, 5 |
| `backend/src/test/java/.../distribution/CreditLifeCommissionTest.java` | Tasks 2, 6 |

**Modified:**

| File | Change |
|---|---|
| `product/api/PremiumFrequency.java` | Add `SINGLE(0)` |
| `policy/domain/GroupScheme.java` | `premiumRatePercent` |
| `policy/domain/PolicyMember.java` | `exitReason`, `outstandingBalanceAtExit` |
| `policy/application/PolicyApiImpl.java` | `exitMember`; `dischargeMember` generalised |
| `policy/application/EnrolmentApiImpl.java` | Compute and publish the file's premium |
| `billing/application/PolicyEventListener.java` | Skip schedule generation for SINGLE |
| `billing/application/BillingApiImpl.java` | `raiseSinglePremiumInvoice`, `creditInvoice` |
| `distribution/application/PolicyEventListener.java` | Pro-rata reversal on a credit-life exit |

---

## Task 1: A single-premium policy stops billing itself, and the scheme carries its rate

Defect 1, closed. Nothing else in the plan can be tested honestly until a credit-life scheme stops generating twelve invoices it should never have had.

**Files:**
- Create: `backend/db-migrations/policy/V18__scheme_premium_rate.sql`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/product/api/PremiumFrequency.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/domain/GroupScheme.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/PolicyApiImpl.java` (`issueGroupScheme`)
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/billing/application/PolicyEventListener.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/billing/application/BillingApiImpl.java` (`nextPeriodStart`)
- Rename: `backend/db-migrations/document/V3__enrolment_schedule_document_type.sql` → `V4__…`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/billing/SinglePremiumIntegrationTest.java`

**Interfaces:**
- Produces: `PremiumFrequency.SINGLE`; `GroupScheme.getPremiumRatePercent()` returning `BigDecimal`; `IssueGroupSchemeRequest.premiumRatePercent()`.
- Consumes: nothing new.

- [ ] **Step 1: Write the failing test**

`SinglePremiumIntegrationTest`, a `@SpringBootTest` with its own `PostgreSQLContainer`, applying the policy/product/billing migration lists the other integration tests apply plus `policy/V18` and `billing/V5`. Issue a CREDIT_LIFE scheme with `premiumFrequency = SINGLE` and assert billing raised nothing:

```java
@Test
void aCreditLifeSchemeGeneratesNoBillingScheduleOfItsOwn() {
    // The master policy is a container for members, not a thing that is itself billed.
    // Before this test, issuing one produced a BillingSchedule and TWELVE invoices for a
    // hand-typed premium nobody agreed -- which then fell due and dunned the bank.
    GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.50"));

    assertThat(billingScheduleRepository
        .findByTenantIdAndPolicyNumber(tenantId, scheme.policyNumber())).isEmpty();
    assertThat(premiumInvoiceRepository
        .findByTenantIdAndPolicyNumber(tenantId, scheme.policyNumber())).isEmpty();
}

@Test
void anOrdinaryGroupSchemeStillBillsMonthlyAsItAlwaysDid() {
    // The guard is on SINGLE, not on the category. An employer scheme must be untouched,
    // and this is the test that fails if the guard is written as "if CREDIT_LIFE".
    GroupSchemeView scheme = issueEmployerScheme(PremiumFrequency.MONTHLY);

    assertThat(billingScheduleRepository
        .findByTenantIdAndPolicyNumber(tenantId, scheme.policyNumber())).isPresent();
    assertThat(premiumInvoiceRepository
        .findByTenantIdAndPolicyNumber(tenantId, scheme.policyNumber())).isNotEmpty();
}

@Test
void aCreditLifeSchemeMustStateItsRate() {
    // 0.4% for one lender and 0.5% for another was the client's answer. A scheme with no
    // rate cannot price a single member, and finding that out at the first accepted file
    // means a lender's whole month bounces.
    assertThatThrownBy(() -> issueCreditLifeScheme(null))
        .isInstanceOf(InvalidPolicyStateException.class)
        .hasMessageContaining("rate");
}

@Test
void anEmployerSchemeMayNotCarryARate() {
    // Symmetric with the interest-method check already in issueGroupScheme: a field that
    // means nothing on this shape of contract is refused rather than stored and ignored.
    assertThatThrownBy(() -> issueEmployerSchemeWithRate(new BigDecimal("0.50")))
        .isInstanceOf(InvalidPolicyStateException.class);
}
```

- [ ] **Step 2: Run the test to verify it fails**

```
cd backend && ./mvnw test -Dtest=SinglePremiumIntegrationTest
```

Expected: FAIL — `PremiumFrequency.SINGLE` does not compile.

- [ ] **Step 3: Write `policy/V18__scheme_premium_rate.sql`**

```sql
-- The rate a lender agreed, and the fact that this contract is paid once.
--
-- Client answer 3.1, 2026-09-22: "Rate eka adjustible maanake kuna wengine tutawapa 0.4
-- wengine 0.5" -- the rate is negotiated per lender. So it belongs on the SCHEME and not
-- on the product: two lenders on the same filed credit-life product pay different rates,
-- and a product-level rate would force a duplicate product per lender.

ALTER TABLE policy.group_scheme
    ADD COLUMN premium_rate_percent NUMERIC(6,4);

-- Percent per annum of the original principal. 0.5000 means 0.5%, not 50%.
-- Bounded because a fat finger here misprices an entire lender's book: every borrower on
-- every future file, silently, until somebody reconciles.
ALTER TABLE policy.group_scheme
    ADD CONSTRAINT chk_group_scheme_premium_rate_sane
        CHECK (premium_rate_percent IS NULL
               OR (premium_rate_percent > 0 AND premium_rate_percent <= 10));

-- A loan-basis scheme MUST have one; nothing else may.
--
-- Stated in the database rather than only in issueGroupScheme because a scheme reaching
-- its first accepted file without a rate cannot price a single member, and the failure
-- would surface as a whole lender's month bouncing rather than as a setup mistake.
ALTER TABLE policy.group_scheme
    ADD CONSTRAINT chk_group_scheme_rate_iff_loan_basis
        CHECK ((benefit_basis = 'AMORTISING_LOAN') = (premium_rate_percent IS NOT NULL));
```

- [ ] **Step 4: Add `SINGLE` to `PremiumFrequency`**

```java
    /**
     * Charged once, at the moment cover is written, and never again.
     *
     * <p>instalmentsPerYear is 0 rather than 1: there is no recurring period, and a 1 here
     * would read as "annually" to any arithmetic that divides by it. Every caller that
     * schedules by frequency must branch on SINGLE explicitly -- see
     * BillingApiImpl.nextPeriodStart, which throws for it by design.
     */
    SINGLE(0);
```

- [ ] **Step 5: Make `nextPeriodStart` refuse SINGLE loudly**

In `BillingApiImpl.nextPeriodStart`, add before `default`:

```java
            // Reaching here means something tried to schedule a contract that has no next
            // period. Better a named exception than silently stepping a year.
            case "SINGLE" -> throw new IllegalArgumentException(
                "A SINGLE premium has no next period; this policy should never have had a "
                    + "billing schedule (see billing.PolicyEventListener.handlePolicyIssued)");
```

- [ ] **Step 6: Guard schedule generation in `billing.PolicyEventListener.handlePolicyIssued`**

Immediately after `premiumFrequency` is read:

```java
        // A single-premium contract is not billed on a cycle, so it gets no schedule and no
        // invoices ahead. Before this guard, a credit-life master policy produced a
        // BillingSchedule and twelve PremiumInvoice rows for a hand-typed figure nobody
        // agreed, which then fell due and put the lender into arrears.
        //
        // Keyed on the FREQUENCY, not on the product category. Billing has no business
        // knowing what credit life is, and a category test would miss the next
        // single-premium product while this one catches it.
        if ("SINGLE".equals(premiumFrequency)) {
            log.info("Policy {} is single-premium -- no billing schedule; its premium is raised "
                + "per accepted enrolment file", policyNumber);
            return;
        }
```

- [ ] **Step 7: Carry the rate through `issueGroupScheme`**

Add `premiumRatePercent` to `IssueGroupSchemeRequest`, and beside the existing `interestMethod` checks:

```java
        if (loanBasis && request.premiumRatePercent() == null) {
            throw new InvalidPolicyStateException(
                "A credit-life scheme must state the premium rate its lender agreed; the rate is "
                    + "negotiated per lender, so there is no default to fall back on");
        }
        if (!loanBasis && request.premiumRatePercent() != null) {
            throw new InvalidPolicyStateException(
                "A premium rate percent belongs only on a credit-life scheme");
        }
        if (loanBasis && request.premiumFrequency() != PremiumFrequency.SINGLE) {
            throw new InvalidPolicyStateException(
                "A credit-life scheme is paid by a single premium per enrolment file, not "
                    + request.premiumFrequency() + "; a cycle here would bill the master policy");
        }
```

Pass `request.premiumRatePercent()` into the `GroupScheme` constructor and add the field with its getter.

- [ ] **Step 8: Rename the duplicate document migration**

```bash
cd backend && git mv db-migrations/document/V3__enrolment_schedule_document_type.sql \
  db-migrations/document/V4__enrolment_schedule_document_type.sql
```

Then update the file list in `EnrolmentIntegrationTest`'s `applyMigrations`.

- [ ] **Step 9: Run the test to verify it passes**

```
cd backend && ./mvnw clean test-compile && ./mvnw test -Dtest='SinglePremiumIntegrationTest+GroupSchemeIntegrationTest+EnrolmentIntegrationTest'
```

Expected: PASS. `clean test-compile` first is not optional — `IssueGroupSchemeRequest` gained a component, and every test that constructs one needs recompiling.

- [ ] **Step 10: Commit**

```bash
git add -A && git commit -m "feat(policy,billing): a credit-life scheme stops billing itself, and carries its lender's rate"
```

---

## Task 2: One invoice per accepted file

**Files:**
- Create: `backend/db-migrations/billing/V5__single_premium_invoice.sql`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/domain/CreditLifePremium.java`
- Create: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/CreditLifePremiumTest.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/EnrolmentApiImpl.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/domain/EnrolmentSubmission.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/billing/application/BillingApiImpl.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/billing/application/PolicyEventListener.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/billing/SinglePremiumIntegrationTest.java` (extend)

**Interfaces:**
- Consumes: `GroupScheme.getPremiumRatePercent()` (Task 1).
- Produces:
  - `CreditLifePremium.forLoan(BigDecimal principal, int termMonths, BigDecimal ratePercent) -> BigDecimal`
  - `CreditLifePremium.unearnedAt(BigDecimal premiumCharged, int termMonths, LocalDate disbursedOn, LocalDate exitOn) -> BigDecimal` (used by Task 5)
  - Event `policy.EnrolmentAccepted`, payload `{submissionId, policyNumber, enrolledCount, premium:{amount,currencyCode}}`
  - `BillingApiImpl.raiseSinglePremiumInvoice(UUID tenantId, String policyNumber, UUID enrolmentSubmissionId, BigDecimal amount, String currency, LocalDate dueDate) -> UUID`

- [ ] **Step 1: Write the failing pure test**

`CreditLifePremiumTest` — no Spring, no database, same reasoning as `AmortisationCalculatorTest`:

```java
@Test
void premiumIsTheRateTimesPrincipalTimesTheYearsOfCover() {
    // 8,500,000 at 0.5% per annum over 48 months = 4 years.
    // 8,500,000 x 0.005 x 4 = 170,000.
    assertThat(CreditLifePremium.forLoan(
            new BigDecimal("8500000.00"), 48, new BigDecimal("0.50")))
        .isEqualByComparingTo("170000.00");
}

@Test
void aPartYearIsChargedProRataAndNotRoundedUpToAWholeYear() {
    // 18 months is 1.5 years. Rounding up to 2 would overcharge every short loan by a
    // third, and short loans are most of a microlender's book.
    assertThat(CreditLifePremium.forLoan(
            new BigDecimal("2400000.00"), 18, new BigDecimal("0.50")))
        .isEqualByComparingTo("18000.00");
}

@Test
void thePremiumIsOnTheORIGINALPrincipalEvenThoughCoverDeclines() {
    // Cover falls straight-line but premium does not follow it down: the client charges on
    // the disbursed amount. This test exists because "cover declines, so premium should
    // decline" is the most plausible wrong turn in this whole feature.
    assertThat(CreditLifePremium.forLoan(
            new BigDecimal("1000000.00"), 12, new BigDecimal("1.00")))
        .isEqualByComparingTo("10000.00");
}

@Test
void moneyIsRoundedToTheCentAndNeverLeftAtFullScale() {
    // 3,200,000 x 0.004 x 2 = 25,600.00 exactly; use a rate that does not divide evenly to
    // prove the scale, e.g. 0.45% over 30 months.
    BigDecimal premium = CreditLifePremium.forLoan(
        new BigDecimal("6000000.00"), 30, new BigDecimal("0.45"));
    assertThat(premium.scale()).isEqualTo(2);
    assertThat(premium).isEqualByComparingTo("67500.00");
}

@Test
void aZeroOrNegativeRateIsRefusedRatherThanProducingFreeCover() {
    assertThatThrownBy(() -> CreditLifePremium.forLoan(
            new BigDecimal("1000000.00"), 12, BigDecimal.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: Run it to verify it fails**

```
cd backend && ./mvnw test -Dtest=CreditLifePremiumTest
```

Expected: FAIL — `CreditLifePremium` does not exist.

- [ ] **Step 3: Write `CreditLifePremium`**

```java
package tz.co.nlolo.lifeplatform.policy.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * What one loan costs to insure, and what is owed back when it ends early.
 *
 * <p>Pure, like {@link AmortisationCalculator}, and for the same reason: this is the
 * arithmetic an argument with a lender will be about, so it must be assertable without a
 * database, a tenant or a Spring context.
 *
 * <p><b>Premium is charged on the ORIGINAL principal, even though cover declines.</b> That
 * is the client's practice, confirmed 2026-09-22, and it is not an oversight to be tidied
 * up later: the rate they negotiated was negotiated against the disbursed amount.
 */
public final class CreditLifePremium {

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal MONTHS_PER_YEAR = new BigDecimal("12");
    private static final BigDecimal PERCENT = new BigDecimal("100");

    private CreditLifePremium() {}

    /**
     * @param ratePercent percent PER ANNUM, so 0.50 means 0.5% -- the scheme's own rate,
     *     negotiated with that lender
     */
    public static BigDecimal forLoan(BigDecimal principal, int termMonths, BigDecimal ratePercent) {
        if (principal == null || principal.signum() <= 0) {
            throw new IllegalArgumentException("A loan with no principal cannot be priced");
        }
        if (termMonths <= 0) {
            throw new IllegalArgumentException("A loan with no term cannot be priced");
        }
        if (ratePercent == null || ratePercent.signum() <= 0) {
            throw new IllegalArgumentException(
                "A premium rate of " + ratePercent + " would write free cover");
        }
        // Full scale through the multiplication, rounded once at the end. Rounding the
        // year fraction first would lose up to a month of premium on every short loan.
        BigDecimal years = new BigDecimal(termMonths).divide(MONTHS_PER_YEAR, 10, RoundingMode.HALF_UP);
        return principal
            .multiply(ratePercent).divide(PERCENT, 10, RoundingMode.HALF_UP)
            .multiply(years)
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The unexpired share of a premium already charged, at the date a loan left.
     *
     * <p>Straight-line on ELAPSED MONTHS, matching how cover itself declines -- refunding on
     * a different basis than the one cover ran down on is how a refund and a claim can
     * disagree about the same loan on the same day.
     *
     * <p>Never negative: a loan that ran its full term refunds nothing, and an exit dated
     * after maturity returns zero rather than a charge.
     */
    public static BigDecimal unearnedAt(BigDecimal premiumCharged, int termMonths,
                                         LocalDate disbursedOn, LocalDate exitOn) {
        if (exitOn.isBefore(disbursedOn)) {
            throw new IllegalArgumentException(
                "A loan cannot leave (" + exitOn + ") before it was disbursed (" + disbursedOn + ")");
        }
        long elapsed = ChronoUnit.MONTHS.between(disbursedOn, exitOn);
        long remaining = Math.max(0, termMonths - elapsed);
        if (remaining == 0) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE);
        }
        return premiumCharged
            .multiply(new BigDecimal(remaining))
            .divide(new BigDecimal(termMonths), MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

```
cd backend && ./mvnw test -Dtest=CreditLifePremiumTest
```

Expected: PASS.

- [ ] **Step 5: Write `billing/V5__single_premium_invoice.sql`**

```sql
-- An invoice that belongs to a file, not to a cycle.
--
-- Every premium_invoice until now hung off a billing_schedule, because every premium until
-- now recurred. A credit-life file's premium recurs never: it is one charge for one batch
-- of borrowers, and there is no schedule for it to belong to.

ALTER TABLE billing.premium_invoice
    ALTER COLUMN billing_schedule_id DROP NOT NULL;

-- What earned it. This is the file-to-invoice correspondence spec 2.8 asks for by name --
-- "one file, one invoice" is what a reconciliation argument with a lender is actually about,
-- and without this column the answer to "which file is this charge for" is a date guess.
ALTER TABLE billing.premium_invoice
    ADD COLUMN enrolment_submission_id UUID;

-- Exactly one of the two, always. An invoice with neither belongs to nothing; an invoice
-- with both claims two different origins for one charge.
ALTER TABLE billing.premium_invoice
    ADD CONSTRAINT chk_premium_invoice_has_exactly_one_origin
        CHECK ((billing_schedule_id IS NULL) <> (enrolment_submission_id IS NULL));

-- One invoice per accepted file, enforced here and not only in Java: a redelivered
-- EnrolmentAccepted event must not double-charge a lender for the same borrowers.
CREATE UNIQUE INDEX ux_premium_invoice_per_submission
    ON billing.premium_invoice (tenant_id, enrolment_submission_id)
    WHERE enrolment_submission_id IS NOT NULL;
```

- [ ] **Step 6: Compute and publish the premium on acceptance**

In `EnrolmentApiImpl.accept`, accumulate as each row enrols:

```java
        BigDecimal premiumTotal = BigDecimal.ZERO;
        ...
            // Priced from the member's OWN loan, against the scheme's own rate. Summed here
            // rather than recomputed later so the invoice and the refunds that may follow it
            // are arithmetically the same premium -- a total re-derived from the members
            // would drift the moment one of them exits.
            BigDecimal memberPremium = CreditLifePremium.forLoan(
                judged.loanPrincipalAmount(), judged.loanTermMonths(),
                scheme.getPremiumRatePercent());
            row.wasPriced(memberPremium);
            premiumTotal = premiumTotal.add(memberPremium);
        ...
        submission.accept(acceptedBy, enrolled, premiumTotal);

        // Acceptance published NOTHING before this. So nothing downstream could know a file
        // had been accepted, and the premium it earned was never charged to anybody.
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.EnrolmentAccepted", tenantId,
            Map.of("submissionId", submission.getSubmissionId(),
                   "policyNumber", submission.getPolicyNumber(),
                   "enrolledCount", enrolled,
                   "premium", Map.of("amount", premiumTotal.toPlainString(),
                                     "currencyCode", scheme.getCurrency()))));
```

Add `premium_amount` to `enrolment_submission_row` and `premium_total` to `enrolment_submission` in `policy/V18` (fold into Task 1's migration rather than adding a fourth file), with `EnrolmentSubmissionRow.wasPriced(BigDecimal)` and the widened `EnrolmentSubmission.accept(String, int, BigDecimal)`.

- [ ] **Step 7: Raise the invoice in billing**

Add to `billing.PolicyEventListener.onDomainEvent`:

```java
            case "policy.EnrolmentAccepted" -> withTenant(envelope, this::handleEnrolmentAccepted);
```

and:

```java
    private void handleEnrolmentAccepted(Map<String, Object> payload) {
        UUID submissionId = (UUID) payload.get("submissionId");
        String policyNumber = (String) payload.get("policyNumber");
        @SuppressWarnings("unchecked")
        Map<String, Object> premium = (Map<String, Object>) payload.get("premium");
        BigDecimal amount = new BigDecimal((String) premium.get("amount"));

        // A file that enrolled nobody -- every row rejected -- earns no premium and must not
        // raise a zero invoice: chk_premium_invoice_amount_positive would refuse it, and a
        // zero charge is not a thing to send a lender anyway.
        if (amount.signum() == 0) {
            log.info("Submission {} enrolled nobody -- no invoice", submissionId);
            return;
        }
        billingApiImpl.raiseSinglePremiumInvoice(TenantContext.get(), policyNumber, submissionId,
            amount, (String) premium.get("currencyCode"), LocalDate.now().plusDays(30));
    }
```

`raiseSinglePremiumInvoice` builds one `PremiumInvoice` with a null `billingScheduleId`, catches the unique-index violation on `ux_premium_invoice_per_submission` and returns the existing invoice's id rather than throwing — **an AFTER_COMMIT listener that throws on redelivery is a listener that retries forever**.

- [ ] **Step 8: Extend `SinglePremiumIntegrationTest`**

```java
@Test
void anAcceptedFileRaisesExactlyOneInvoiceForTheSumOfItsMembers() {
    // "One file, one invoice" (spec 2.8). Three borrowers, one charge.
    GroupSchemeView scheme = issueCreditLifeScheme(new BigDecimal("0.50"));
    UUID submissionId = submitAndAccept(scheme.policyNumber(), THREE_BORROWER_CSV);

    List<PremiumInvoice> invoices = premiumInvoiceRepository
        .findByTenantIdAndPolicyNumber(tenantId, scheme.policyNumber());
    assertThat(invoices).hasSize(1);
    assertThat(invoices.get(0).getAmount()).isEqualByComparingTo(EXPECTED_TOTAL);
    assertThat(invoices.get(0).getEnrolmentSubmissionId()).isEqualTo(submissionId);
    assertThat(invoices.get(0).getBillingScheduleId()).isNull();
}

@Test
void aSecondFileRaisesASecondInvoiceAndDoesNotAmendTheFirst() {
    // Monthly files, monthly charges. An implementation that "tops up" the running invoice
    // destroys the file-to-invoice correspondence this whole design is built on.
    ...
    assertThat(invoices).hasSize(2);
}

@Test
void aRedeliveredAcceptanceEventDoesNotChargeTheLenderTwice() {
    // AFTER_COMMIT listeners get redelivered. ux_premium_invoice_per_submission is the
    // guarantee; this asserts the listener SWALLOWS the violation rather than retrying.
    ...
}

@Test
void aFileThatEnrolledNobodyRaisesNoInvoice() {
    ...
    assertThat(premiumInvoiceRepository
        .findByTenantIdAndPolicyNumber(tenantId, scheme.policyNumber())).isEmpty();
}
```

- [ ] **Step 9: Run and commit**

```bash
cd backend && ./mvnw clean test-compile && ./mvnw test -Dtest='CreditLifePremiumTest+SinglePremiumIntegrationTest+EnrolmentIntegrationTest'
git add -A && git commit -m "feat(policy,billing): one invoice per accepted enrolment file"
```

---

## Task 3: A loan can leave the scheme

**Files:**
- Create: `backend/db-migrations/policy/V19__member_exit_reason.sql`
- Create: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/api/ExitReason.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/domain/PolicyMember.java`
- Modify: `backend/src/main/java/tz/co/nlolo/lifeplatform/policy/application/PolicyApiImpl.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/policy/MemberExitIntegrationTest.java`

**Interfaces:**
- Produces: `PolicyApi.exitMember(String policyNumber, UUID policyMemberId, LocalDate exitDate, ExitReason reason, BigDecimal outstandingBalanceAtExit, String actor) -> PolicyMemberView`; the `policy.GroupMemberExited` event gains a `premiumUnearned` field.
- Consumes: `CreditLifePremium.unearnedAt` (Task 2).

**Note for the implementer:** `PolicyApiImpl.dischargeMember` (around line 940) **already does almost all of this** — exit, flush, restate the scheme total, close the scheme when the last life goes, publish `policy.GroupMemberExited` with a `reason` of `"CLAIM_SETTLED"`. Do not write a second copy. Generalise it: take `ExitReason` as a parameter instead of hardcoding the string, and make `exitMember` the second caller. Read its comments first — particularly why the exit is dated to the **event** and not to the processing run.

- [ ] **Step 1: Write the failing test**

```java
@Test
void aSettledLoanLeavesTheSchemeAndStopsBeingCovered() {
    PolicyMemberView member = enrolOneBorrower();
    policyApi.exitMember(policyNumber, member.policyMemberId(), LocalDate.of(2026, 9, 15),
        ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.one");

    assertThat(policyApi.getMember(member.policyMemberId()).status()).isEqualTo(MemberStatus.EXITED);
    assertThat(policyApi.claimableCover(policyNumber, member.policyMemberId(),
        LocalDate.of(2026, 9, 16)).amount()).isEqualByComparingTo("0.00");
}

@Test
void aClaimOnADateBEFORETheExitIsStillCovered() {
    // The reason an exited member is never deleted. A death in August reported in October,
    // on a loan settled in September, is a covered claim -- and an implementation that
    // checks "is this member active" rather than "were they covered on the date of event"
    // refuses it.
    PolicyMemberView member = enrolOneBorrower();   // disbursed 2026-08-03, 48 months
    policyApi.exitMember(policyNumber, member.policyMemberId(), LocalDate.of(2026, 9, 15),
        ExitReason.SETTLED_EARLY, BigDecimal.ZERO, "staff.one");

    assertThat(policyApi.claimableCover(policyNumber, member.policyMemberId(),
        LocalDate.of(2026, 8, 20)).amount()).isGreaterThan(BigDecimal.ZERO);
}

@Test
void theSchemeTotalFallsByExactlyTheDepartingMembersCover() { ... }

@Test
void exitingAnAlreadyExitedMemberIsIdempotentAndDoesNotRestateTheTotalTwice() {
    // The monthly exits file in Task 4 can legitimately repeat a row, and a second exit that
    // subtracted the cover again would understate the scheme.
    ...
}

@Test
void theLastLoanLeavingClosesTheScheme() {
    // Already true for the claim path; this asserts the generalised method kept it.
    ...
}

@Test
void anExitDatedBeforeDisbursementIsRefused() {
    ...
}

@Test
void theExitedMemberKeepsItsReferenceAndItsReason() {
    // The reference is how the lender named this loan and how they will ask about it later.
    ...
}
```

- [ ] **Step 2: Run to verify it fails.** `./mvnw test -Dtest=MemberExitIntegrationTest` → FAIL, `exitMember` undefined.

- [ ] **Step 3: Write `policy/V19__member_exit_reason.sql`**

```sql
-- WHY a loan left, and what was still owed when it did.
--
-- policy_member has carried left_on since V9, but nothing about the reason: every exit was
-- a settled claim, so there was only one. A refund (plan 3 task 5) and a clawback (task 6)
-- both turn on the reason, and "the loan was written off" and "the borrower died" must not
-- refund the same way.

ALTER TABLE policy.policy_member
    ADD COLUMN exit_reason VARCHAR(30),
    ADD COLUMN outstanding_balance_at_exit NUMERIC(19,2);

-- An exited member states why; an active one must not.
ALTER TABLE policy.policy_member
    ADD CONSTRAINT chk_policy_member_exit_reason_iff_exited
        CHECK ((status = 'EXITED') = (exit_reason IS NOT NULL));

ALTER TABLE policy.policy_member
    ADD CONSTRAINT chk_policy_member_exit_reason_known
        CHECK (exit_reason IS NULL OR exit_reason IN
            ('SETTLED_EARLY', 'REFINANCED', 'WRITTEN_OFF', 'CANCELLED', 'CLAIM_SETTLED'));
```

**Backfill note, and do not skip it:** existing EXITED rows have a null `exit_reason` and the new CHECK will refuse the migration on any database with one. Add before the constraint:

```sql
UPDATE policy.policy_member SET exit_reason = 'CLAIM_SETTLED'
    WHERE status = 'EXITED' AND exit_reason IS NULL;
```

That is truthful: every exit that exists today came from `dischargeForSettledClaim`.

- [ ] **Step 4: Generalise `dischargeMember` and add `exitMember`.** Replace the hardcoded `"reason", "CLAIM_SETTLED"` with the passed `ExitReason`, add `premiumUnearned` to the published payload (computed with `CreditLifePremium.unearnedAt` when the scheme is loan-basis, omitted otherwise), and have `exitMember` do the authorization and date validation before delegating.

- [ ] **Step 5: Run to verify it passes, then commit**

```bash
cd backend && ./mvnw clean test-compile && ./mvnw test -Dtest='MemberExitIntegrationTest+GroupSchemeIntegrationTest+GroupClaimIntegrationTest'
git add -A && git commit -m "feat(policy): a loan can leave a scheme for a reason other than a claim"
```

---

## Task 4: The exits file

Monthly, per client answer 3.5. Same shape as the enrolment file: propose, judge, a second person accepts, a report goes back.

**Files:**
- Create: `backend/db-migrations/policy/V20__exit_submission.sql`
- Create: `policy/domain/ExitCsvParser.java`, `ExitSubmission.java`, `ExitSubmissionRow.java`
- Create: `policy/api/ExitApi.java`, `ExitRejection.java`; `policy/application/ExitApiImpl.java`
- Test: `ExitCsvParserTest.java`, `ExitFileIntegrationTest.java`

**Interfaces:**
- Consumes: `PolicyApi.exitMember` (Task 3); `PolicyMemberRepository.findByTenantIdAndMemberReference`.
- Produces: `ExitApi.submit/accept/withdraw/listRows/renderReport`, mirroring `EnrolmentApi` exactly.

**Four columns**, per `docs/superpowers/specs/credit-life-exits-sample.csv`:

| Column | Req | Why |
|---|---|---|
| `member_reference` | ✓ | The reference we issued. An exits file is about loans we already cover, so unlike enrolment there is no blank case |
| `exit_date` | ✓ | Dates the exit and the refund |
| `exit_reason` | ✓ | One of the five in V19 |
| `outstanding_balance_at_exit` | ✓ | Recorded, not trusted: it is the lender's figure and reconciles against ours |

- [ ] **Step 1: Write `ExitCsvParserTest`** — mirror `EnrolmentCsvParserTest` case for case: BOM, case-insensitive headers, ignored extra columns, blank-row skip, Excel serial dates refused, one finding per row, a missing required column failing the whole file. Plus the two that are specific here:

```java
@Test
void anUnknownReferenceIsARowErrorNamingTheReference() {
    // The single most likely real failure: a lender quotes a reference from a report they
    // edited, or from the wrong scheme. The message must say which reference.
    ...
    assertThat(parsed.errors().get(0).reason()).isEqualTo(ExitRejection.UNKNOWN_MEMBER_REFERENCE);
}

@Test
void anUnknownExitReasonIsRefusedRatherThanStoredAsFreeText() {
    // chk_policy_member_exit_reason_known would refuse it at acceptance, half-way through a
    // file, after earlier loans had already been exited.
    ...
}
```

- [ ] **Step 2: Run to verify it fails.**

- [ ] **Step 3: Write `policy/V20__exit_submission.sql`** — copy `V15__enrolment_submission.sql` structurally, including `ux_exit_submission_in_flight` (partial on PENDING) and `chk_exit_submission_two_person`. The two-person rule is not optional here: an exits file cancels cover in bulk, which is the same risk as writing it.

- [ ] **Step 4: Write the parser, entities and `ExitApiImpl`.** Judge each row at submission (reference resolves, in THIS scheme, currently ACTIVE, exit date not before disbursement, reason known); enrol nothing until acceptance; on acceptance call `policyApi.exitMember` per row.

- [ ] **Step 5: Write `ExitFileIntegrationTest`** — end to end against Testcontainers, including its own `MinIOContainer` (see the trap note below), and these:

```java
@Test
void oneBadRowDoesNotStopTheOtherLoansLeaving() { ... }

@Test
void nobodyLeavesUntilASecondPersonAccepts() {
    // The whole point of the control. After submit, every member is still ACTIVE.
    ...
}

@Test
void theSamePersonCannotAcceptTheExitsFileTheySubmitted() { ... }

@Test
void areferenceBelongingToAnotherSchemeIsRejectedAndNotExited() {
    // Two lenders, two schemes, one tenant. This is the cross-scheme leak test.
    ...
}
```

**Trap, do not rediscover:** an integration test that calls `documentApi.upload` passes locally because the dev compose MinIO happens to be running. It needs its own `MinIOContainer` and explicit bucket creation, like `ClaimEvidenceIntegrationTest` and `EnrolmentIntegrationTest`. Without it the test is green on this machine and red everywhere else.

- [ ] **Step 6: Run and commit**

```bash
cd backend && ./mvnw clean test-compile && ./mvnw test -Dtest='ExitCsvParserTest+ExitFileIntegrationTest+MemberExitIntegrationTest'
git add -A && git commit -m "feat(policy): a lender's monthly exits file"
```

---

## Task 5: Early settlement refunds pro rata

**Files:**
- Modify: `billing/application/BillingApiImpl.java` (`creditInvoice`), `billing/api/BillingApi.java`
- Modify: `billing/application/PolicyEventListener.java`
- Modify: `billing/V5__single_premium_invoice.sql` is already applied — add `backend/db-migrations/billing/V6__premium_credit.sql`
- Test: extend `SinglePremiumIntegrationTest`

**Interfaces:**
- Consumes: `policy.GroupMemberExited` with its `premiumUnearned` field (Task 3).
- Produces: event `billing.PremiumRefundDue`, payload `{policyNumber, policyMemberId, originalInvoiceId, amount:{…}}` — Task 6's only input.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void aLoanSettledHalfwayThroughItsTermRefundsHalfItsPremium() {
    // 2,400,000 over 18 months at 0.5% = 18,000 premium. Exit at month 9 leaves 9 of 18
    // months unexpired: 9,000.
    ...
    assertThat(refund.amount()).isEqualByComparingTo("9000.00");
}

@Test
void aLoanThatRanItsFullTermRefundsNothing() {
    // And specifically does not refund a negative amount, which is a charge.
    ...
    assertThat(refundsFor(member)).isEmpty();
}

@Test
void aSETTLEDCLAIMRefundsNothingBecauseTheCoverWasUsed() {
    // The distinction V19's exit_reason exists for. The insurer paid out; the premium was
    // fully earned the moment it did. Refunding here would pay the claim AND give back the
    // premium that funded it.
    ...
}

@Test
void aWRITTENOFFLoanStillRefundsTheUnexpiredTerm() {
    // The lender's credit loss is not the insurer's premium to keep: cover ended, so the
    // unexpired premium goes back. Stated as a test because the instinct is to treat a
    // write-off as a claim, and it is not one.
    ...
}

@Test
void theRefundIsCreditedAgainstTheInvoiceThatChargedIt() {
    // enrolment_submission_id is what makes this answerable: without it, "which of the
    // eleven monthly invoices charged this borrower" is a guess.
    ...
    assertThat(credit.getOriginalInvoiceId()).isEqualTo(theInvoiceForTheFileThatEnrolledThem);
}

@Test
void refundsAcrossManyExitsNeverExceedWhatWasCharged() {
    // The property that matters. Exit every member of a file and the total credited equals
    // the invoice, to the cent -- no rounding drift across 400 borrowers.
    ...
}
```

- [ ] **Step 2–4:** Write `V6__premium_credit.sql` (a `premium_credit` table: tenant, policy, member, original invoice, amount, reason, created; unique on `(tenant_id, policy_member_id)` so one member refunds once), `creditInvoice`, and the `policy.GroupMemberExited` branch in `billing.PolicyEventListener` that skips `CLAIM_SETTLED`.

- [ ] **Step 5: Run and commit**

```bash
cd backend && ./mvnw clean test-compile && ./mvnw test -Dtest='SinglePremiumIntegrationTest+ExitFileIntegrationTest'
git add -A && git commit -m "feat(billing): early settlement refunds the unexpired premium"
```

---

## Task 6: Commission claws back with the refund

Last, deliberately: it is the task that drags in the three broken links of the commission chain.

**Files:**
- Modify: `distribution/application/PolicyEventListener.java`
- Test: `backend/src/test/java/tz/co/nlolo/lifeplatform/distribution/CreditLifeCommissionTest.java`

**Interfaces:**
- Consumes: `billing.PremiumRefundDue` (Task 5).
- Produces: nothing new. A clawback is an ordinary reversal accrual.

**Read first:** `distribution.domain.CommissionAccrual`'s javadoc. A clawback is a **new row with `reversesAccrualId` set**, never a mutation of the original, and it always targets the **currently open** period rather than the period of the accrual it reverses. Both rules already exist; this task adds a trigger, not a mechanism.

**Known broken before you start** — all three are dev-data problems, not code, and the tests below must construct their own fixtures rather than lean on the seeder:
1. The commission close sweep was never applied in dev, so payout always 409s.
2. Zero OVERRIDE rules exist.
3. Only 19 of 134 products have a commission plan — a credit-life product will not be one of them.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void aRefundedPremiumClawsBackTheCommissionItEarnedProRata() {
    // 18,000 premium, 10% first-year commission = 1,800 accrued. A 9,000 refund claws back
    // 900 -- the same proportion, not a flat amount and not the whole accrual.
    ...
    assertThat(reversal.getAmount()).isEqualByComparingTo("-900.00");
    assertThat(reversal.getReversesAccrualId()).isEqualTo(original.getAccrualId());
}

@Test
void theClawbackIsNotSubjectToTheLAPSEWindow() {
    // TZ_COMMISSION_CLAWBACK_MONTHS gates the lapse path, correctly: a policy that lapses
    // in year four keeps its first-year commission. A refund is different in kind -- the
    // insurer physically returned the money, in month 40 as much as in month 2 -- and
    // applying the window here would let the bank keep commission on premium it no longer
    // has. This test is why this task is not "reuse handlePolicyLapsed".
    ...
}

@Test
void aRefundOnASchemeSoldDirectClawsBackNothingAndDoesNotThrow() {
    // No agent of record. Must log and return, matching handlePremiumCollected's
    // fail-silent-and-log contract -- an AFTER_COMMIT listener that throws retries forever.
    ...
}

@Test
void aRedeliveredRefundEventDoesNotClawBackTwice() {
    // Keyed on reversesAccrualId: a second reversal of the same accrual is refused.
    ...
}

@Test
void clawingBackMoreThanWasAccruedIsRefused() {
    // The safety net. Rounding across 400 members must never produce a reversal larger than
    // the accrual it reverses, which would turn a statement negative.
    ...
}
```

- [ ] **Step 2: Run to verify it fails.**

- [ ] **Step 3: Add the `billing.PremiumRefundDue` branch** to `distribution.PolicyEventListener`, resolving the original accrual by policy and reversing pro rata.

- [ ] **Step 4: Run, then run the whole suite**

```bash
cd backend && ./mvnw clean test
```

Expected: **≥ 1294 + the new tests, 0 failures, 0 errors.**

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(distribution): commission claws back with the premium it was earned on"
```

---

## Self-review against the spec

**Spec coverage.** §2.8 premium and commission → Tasks 1, 2, 6. §2.12 exits file → Tasks 3, 4. Pro-rata refund → Task 5. Client answers 3.1 (adjustable rate) → Task 1, 3.5 (monthly exits) → Task 4, 3.6 (lender-only, no borrower contact) → nothing to build, which is the point.

**Deliberately NOT in this plan**, and each belongs to a later one:
- **Claim and payout on a credit-life scheme** (spec §2.9, EFT disbursement type) — Plan 4. Task 5 touches `CLAIM_SETTLED` only to exclude it from refunds.
- **`regreporting` consuming member movement** (spec §2.14) — Plan 5. It listens to neither `GroupMemberAdded` nor `GroupMemberExited` today, so `policy_dimension` goes stale on both. This plan adds a third route to the same known staleness and does not fix it.
- **Any console or bank-portal screen** — Plans 6 and 7. Everything here is API and events.
- **The `broker`/`bancassurance` agent discriminator** — waits for a second partner, per §2.8.

**One thing a reviewer should push back on if they disagree:** Task 5 refunds a `WRITTEN_OFF` loan. The argument is in the test comment — cover ended, so unexpired premium goes back — but a lender may expect a write-off to be treated as a claim. It is a client question, and the code is written so the answer is one line in the reason switch.
