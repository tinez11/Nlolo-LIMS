package tz.co.nlolo.lifeplatform.product.domain;

import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Everything a CHECK cannot say about a with-profits version (product step 4, Q6 and Q9). Pure and
 * static, like AccumulationPlanValidator.
 */
public final class BonusPlanValidator {

    private static final Set<ProductCategory> CATEGORIES = EnumSet.of(ProductCategory.ENDOWMENT, ProductCategory.WHOLE_LIFE);
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private BonusPlanValidator() {}

    public static void validate(ProductCategory category, BonusPlan plan, CashValuePlan cashValue,
                                AccumulationPlan accumulation, PayoutPlan payout) {
        validate(category, plan, cashValue, accumulation, payout, DepositPlan.none());
    }

    /**
     * {@code accumulation} is the EFFECTIVE account plan -- for a deposit, the zero-charge one the
     * server builds -- so a deposit is caught here first, in its own words, rather than by the
     * account rule it only breaks as a consequence.
     */
    public static void validate(ProductCategory category, BonusPlan plan, CashValuePlan cashValue,
                                AccumulationPlan accumulation, PayoutPlan payout, DepositPlan deposit) {
        if (plan == null || !plan.participating()) {
            return;
        }
        if (!CATEGORIES.contains(category)) {
            fail("A " + category + " product cannot be with-profits");
        }
        if (deposit != null && deposit.isDeposit()) {
            fail("A fixed-term deposit cannot be with-profits");
        }
        if (accumulation != null && accumulation.isAccount()) {
            fail("A version is valued either by an account or with profits, not both");
        }
        if (plan.method() == null) {
            fail("A with-profits version must state its bonus method (SIMPLE or COMPOUND)");
        }
        if (plan.surrenderBasis() == null) {
            fail("A with-profits version must state how attached bonuses count toward surrender (NONE, SUM_ASSURED_SCALE or OWN_SCALE)");
        }
        switch (plan.surrenderBasis()) {
            case NONE, SUM_ASSURED_SCALE -> {
                if (!plan.surrenderRows().isEmpty()) {
                    fail("Bonus surrender rows are only for OWN_SCALE");
                }
                if (plan.surrenderBasis() == BonusSurrenderBasis.SUM_ASSURED_SCALE
                        && (cashValue == null || !cashValue.isPresent())) {
                    fail("SUM_ASSURED_SCALE needs the version's own cash-value scale");
                }
            }
            case OWN_SCALE -> checkOwnScale(plan);
        }
        boolean paysMaturity = payout != null && payout.rows().stream().anyMatch(r -> r.kind() == PayoutKind.MATURITY);
        if (category == ProductCategory.ENDOWMENT && !paysMaturity) {
            fail("A with-profits ENDOWMENT needs a MATURITY payout, or its bonuses could never be paid at term end");
        }
    }

    private static void checkOwnScale(BonusPlan plan) {
        if (plan.surrenderRows().isEmpty()) {
            fail("OWN_SCALE needs at least one row of bonus surrender values");
        }
        Set<Integer> starts = new HashSet<>();
        for (BonusSurrenderRow row : plan.surrenderRows()) {
            if (row.fromCompletedYears() < 0) {
                fail("A bonus surrender row cannot start before year 0");
            }
            if (!starts.add(row.fromCompletedYears())) {
                fail("Bonus surrender rows must each start at a different completed year");
            }
            if (row.perMille() == null || row.perMille().signum() < 0 || row.perMille().compareTo(THOUSAND) > 0) {
                fail("A bonus surrender value must be between 0 and 1000 per mille");
            }
        }
    }

    private static void fail(String message) {
        throw new InvalidProductVersionException(message);
    }
}
