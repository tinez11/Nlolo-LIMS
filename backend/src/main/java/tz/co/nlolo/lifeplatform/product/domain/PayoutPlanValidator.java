package tz.co.nlolo.lifeplatform.product.domain;

import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

/**
 * The per-category rules for a payout schedule (step 2 spec §3.2), and everything a CHECK cannot
 * say: what category the product is, and which terms a row makes necessary.
 *
 * <p>Pure and static, so the whole rule set is unit-tested in milliseconds rather than behind a
 * Postgres container -- the same arrangement {@link CashValuePlanValidator} uses, and for the same
 * reason.
 */
public final class PayoutPlanValidator {

    /**
     * Products sold to one person, which carry a statutory free-look window (guide §21.3). ANNUITY
     * joined in product step 5 (spec Q9): an annuity is cancellable in free-look, never surrendered.
     */
    private static final Set<ProductCategory> INDIVIDUAL = EnumSet.of(
        ProductCategory.TERM_LIFE, ProductCategory.ENDOWMENT, ProductCategory.WHOLE_LIFE,
        ProductCategory.EDUCATION_SAVINGS, ProductCategory.ANNUITY);

    /** The two that pay while the life assured lives, and so carry a schedule at all. */
    private static final Set<ProductCategory> SCHEDULED = EnumSet.of(
        ProductCategory.ENDOWMENT, ProductCategory.EDUCATION_SAVINGS);

    private PayoutPlanValidator() {}

    /** A SCALE version's payouts -- every version before product step 3. */
    public static void validate(ProductCategory category, PayoutPlan plan) {
        validate(category, plan, AccumulationPlan.none());
    }

    public static void validate(ProductCategory category, PayoutPlan plan, AccumulationPlan accumulation) {
        validate(category, plan, accumulation, false);
    }

    /**
     * {@code deposit}: a fixed-term deposit matures through its account (accumulation's drain), so
     * an endowment deposit carries no MATURITY row. Free-look and term bounds still apply.
     */
    public static void validate(ProductCategory category, PayoutPlan plan, AccumulationPlan accumulation, boolean deposit) {
        if (plan == null || !plan.authored()) {
            return;
        }
        if (plan.rows().stream().anyMatch(r -> r.kind() == PayoutKind.ANNUITY)) {
            fail("ANNUITY payouts are set by an annuity's forms, not authored as rows");
        }
        checkAccountRules(plan, accumulation);
        PayoutTerms terms = plan.terms();
        if (INDIVIDUAL.contains(category) && terms.freeLookDays() == null) {
            fail("A free-look period in days is required on an individual product");
        }
        if (terms.freeLookDays() != null && (terms.freeLookDays() < 1 || terms.freeLookDays() > 365)) {
            fail("A free-look period must be between 1 and 365 days");
        }
        // A product with no rows is a perfectly ordinary term or whole-life version; only the two
        // SCHEDULED categories must carry one, and they are checked below -- unless it is a deposit.
        if (plan.rows().isEmpty() && (!SCHEDULED.contains(category) || deposit)) {
            checkTermBounds(terms, plan);
            return;
        }
        checkCategory(category, plan);
        for (PayoutRowInput row : plan.rows()) {
            checkShape(row);
        }
        checkTermsAgainstRows(terms, plan);
        checkTermBounds(terms, plan);
    }

    private static void checkCategory(ProductCategory category, PayoutPlan plan) {
        if (category == ProductCategory.TERM_LIFE) {
            // Return-of-premium term and nothing else: a term policy that matures would not be term.
            if (plan.rows().size() != 1 || plan.rows().get(0).kind() != PayoutKind.RETURN_OF_PREMIUM) {
                fail("A TERM_LIFE product may carry only a single RETURN_OF_PREMIUM row");
            }
            return;
        }
        if (!SCHEDULED.contains(category)) {
            fail("A " + category + " product cannot carry a payout schedule");
            return;
        }
        if (plan.rowsOf(PayoutKind.MATURITY).size() != 1) {
            fail("An " + category + " product must carry exactly one MATURITY row");
        }
        if (!plan.rowsOf(PayoutKind.RETURN_OF_PREMIUM).isEmpty()) {
            fail("RETURN_OF_PREMIUM rows are only valid on TERM_LIFE products");
        }
    }

    private static void checkShape(PayoutRowInput row) {
        if (row.amountValue() == null || row.amountValue().signum() <= 0) {
            fail("A payout row's amount must be greater than zero");
        }
        boolean endOfTerm = row.kind() == PayoutKind.MATURITY || row.kind() == PayoutKind.RETURN_OF_PREMIUM;
        if (endOfTerm) {
            if (row.fromPolicyYear() != null || row.toPolicyYear() != null || row.frequency() != null) {
                fail("A " + row.kind() + " row pays on the policy's maturity date and takes no years or frequency");
            }
        } else if (row.fromPolicyYear() == null || row.toPolicyYear() == null || row.frequency() == null
                || row.fromPolicyYear() < 1 || row.toPolicyYear() < row.fromPolicyYear()) {
            fail("A " + row.kind() + " row needs a from and to policy year (from >= 1, to >= from) and a frequency");
        }
        boolean offPremiums = row.amountBasis() == PayoutAmountBasis.PERCENT_OF_PREMIUMS;
        if (offPremiums != (row.kind() == PayoutKind.RETURN_OF_PREMIUM)) {
            fail(offPremiums
                ? "Only a RETURN_OF_PREMIUM row is valued as a percent of premiums"
                : "A RETURN_OF_PREMIUM row is valued as a percent of premiums");
        }
    }

    /**
     * An account version's money lives in the account, so it pays out the account and nothing that
     * would be drawn from the sum assured instead -- a survival benefit or a %-of-SA maturity on a
     * ledger product would pay from nowhere while the customer's balance sat untouched.
     *
     * <p>Checked FIRST, before the per-category rules, so an account version is told the account
     * rule rather than a general one it only breaks as a consequence.
     */
    private static void checkAccountRules(PayoutPlan plan, AccumulationPlan accumulation) {
        boolean account = accumulation != null && accumulation.isAccount();
        for (PayoutRowInput row : plan.rows()) {
            boolean paysAccount = row.amountBasis() == PayoutAmountBasis.ACCOUNT_VALUE;
            if (paysAccount && row.kind() != PayoutKind.MATURITY) {
                fail("Only a MATURITY row may pay the account value");
            }
            if (paysAccount && !account) {
                fail("Only an account-based version can pay the account value");
            }
            if (paysAccount && (row.amountValue() == null || row.amountValue().compareTo(new BigDecimal("100")) != 0)) {
                fail("An account-value maturity pays the whole account (100)");
            }
            if (account && row.kind() == PayoutKind.MATURITY && !paysAccount) {
                fail("An account-based version's maturity pays the account value");
            }
            if (account && (row.kind() == PayoutKind.SURVIVAL || row.kind() == PayoutKind.INCOME)) {
                fail("An account-based version pays only its account value; survival and income payouts are not offered");
            }
        }
    }

    /** The terms a row makes necessary: neither has a sensible default, so neither gets one. */
    private static void checkTermsAgainstRows(PayoutTerms terms, PayoutPlan plan) {
        if (!plan.rowsOf(PayoutKind.SURVIVAL).isEmpty() && terms.survivalBenefitsDeductedFromDeath() == null) {
            fail("A product with SURVIVAL rows must say whether survival benefits paid are deducted from the death benefit");
        }
        if (!plan.rowsOf(PayoutKind.INCOME).isEmpty() && terms.proofOfLifeIntervalMonths() == null) {
            fail("A product with INCOME rows needs a proof-of-life interval in months");
        }
    }

    private static void checkTermBounds(PayoutTerms terms, PayoutPlan plan) {
        Integer interval = terms.proofOfLifeIntervalMonths();
        if (interval != null && (interval < 1 || interval > 60)) {
            fail("A proof-of-life interval must be between 1 and 60 months");
        }
        BigDecimal pct = terms.deathBenefitPremiumPercent();
        if (pct != null && (pct.signum() <= 0 || pct.compareTo(new BigDecimal("1000")) > 0)) {
            fail("A death-benefit premium percent must be greater than 0 and at most 1000");
        }
    }

    private static void fail(String message) {
        throw new InvalidProductVersionException(message);
    }
}
