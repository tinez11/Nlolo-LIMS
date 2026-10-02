package tz.co.nlolo.lifeplatform.product.domain;

import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything a CHECK cannot say about a fixed-term deposit (spec §3.1). Pure and static, like
 * {@link AccumulationPlanValidator}, and run BEFORE it: a deposit's account plan is built by the
 * server, so the author is told the deposit rule rather than an account rule broken as a result.
 */
public final class DepositPlanValidator {

    private static final Set<ProductCategory> DEPOSIT_CATEGORIES =
        EnumSet.of(ProductCategory.ENDOWMENT, ProductCategory.WHOLE_LIFE, ProductCategory.EDUCATION_SAVINGS);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private DepositPlanValidator() {}

    public static void validate(ProductCategory category, DepositPlan plan, AccumulationPlan accumulation,
                                CashValuePlan cashValue, FrequencyLoading loading, PayoutPlan payout,
                                EligibilityBounds bounds) {
        if (plan == null || !plan.isDeposit()) {
            return;
        }
        if (!DEPOSIT_CATEGORIES.contains(category)) {
            fail("A " + category + " product cannot be a fixed-term deposit");
        }
        if (accumulation != null && accumulation.isAccount()) {
            fail("A fixed-term deposit sets its own account terms; send no savings-account block with it");
        }
        if (cashValue != null && cashValue.isPresent()) {
            fail("A version is valued either by a cash-value scale or as a fixed-term deposit, not both");
        }
        if (loading != null && (positive(loading.monthlyPercent()) || positive(loading.quarterlyPercent()))) {
            fail("A fixed-term deposit is paid once; it takes no frequency loading");
        }
        if (payout != null && !payout.rows().isEmpty()) {
            fail("A fixed-term deposit matures through its account; it carries no payout schedule");
        }
        Set<String> cells = new HashSet<>();
        for (DepositRateRow row : plan.rows()) {
            if (row.minAmount() == null || row.minAmount().signum() <= 0) {
                fail("A deposit band must start above zero");
            }
            if (row.termMonths() < 1 || row.termMonths() > 120) {
                fail("A deposit term must be between 1 and 120 months");
            }
            if (row.ratePercent() == null || row.ratePercent().signum() < 0 || row.ratePercent().compareTo(HUNDRED) > 0) {
                fail("A deposit rate must be between 0 and 100 percent");
            }
            String start = row.minAmount().stripTrailingZeros().toPlainString();
            if (!cells.add(start + "/" + row.termMonths())) {
                fail("The deposit rate grid has two rates for deposits from " + start + " over " + row.termMonths() + " months");
            }
        }
        List<Integer> terms = plan.terms();
        for (BigDecimal start : plan.bandStarts()) {
            for (int term : terms) {
                if (plan.rows().stream().noneMatch(r -> r.minAmount().compareTo(start) == 0 && r.termMonths() == term)) {
                    fail("The band from " + start.stripTrailingZeros().toPlainString() + " does not offer a " + term + "-month term");
                }
            }
        }
        BigDecimal minimum = bounds != null ? bounds.minSumAssured() : null;
        if (minimum != null && plan.bandStarts().get(0).compareTo(minimum) != 0) {
            fail("The lowest deposit band must start at the version's minimum sum assured ("
                + minimum.stripTrailingZeros().toPlainString() + ")");
        }
    }

    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }

    private static void fail(String message) { throw new InvalidProductVersionException(message); }
}
