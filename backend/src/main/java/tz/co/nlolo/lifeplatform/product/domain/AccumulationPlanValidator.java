package tz.co.nlolo.lifeplatform.product.domain;

import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Everything a CHECK cannot say about an account version: which categories may use one (decision
 * Q9), that it is not also a scale, and that its charges cover every policy year exactly once.
 * Pure and static, like {@link PayoutPlanValidator}, so the whole rule set runs in milliseconds.
 */
public final class AccumulationPlanValidator {

    /** Q9. ANNUITY waits for D: a pension must not be sellable before it can vest. */
    private static final Set<ProductCategory> ACCOUNT_CATEGORIES =
        EnumSet.of(ProductCategory.ENDOWMENT, ProductCategory.WHOLE_LIFE, ProductCategory.EDUCATION_SAVINGS);

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private AccumulationPlanValidator() {}

    public static void validate(ProductCategory category, AccumulationPlan plan, CashValuePlan cashValue) {
        if (plan == null || !plan.isAccount()) {
            return;
        }
        if (!ACCOUNT_CATEGORIES.contains(category)) {
            fail("A " + category + " product cannot use an account value basis");
        }
        if (cashValue != null && cashValue.isPresent()) {
            fail("A version is valued either by a cash-value scale or by an account, not both");
        }
        BigDecimal rate = plan.guaranteedRatePercent();
        if (rate == null || rate.signum() < 0 || rate.compareTo(HUNDRED) > 0) {
            fail("An account-based version needs a guaranteed interest rate between 0 and 100 percent");
        }
        if (plan.minimumBalance() == null || plan.minimumBalance().signum() < 0) {
            fail("An account-based version needs a minimum balance for withdrawals, zero or more");
        }
        checkCharges(plan.charges());
    }

    private static void checkCharges(List<AccumulationChargeRow> rows) {
        if (rows.isEmpty()) {
            fail("An account-based version needs at least one row of charges");
        }
        List<AccumulationChargeRow> sorted = rows.stream()
            .sorted(Comparator.comparingInt(AccumulationChargeRow::fromPolicyYear)).toList();
        if (sorted.get(0).fromPolicyYear() != 1) {
            fail("Account charges must start at policy year 1");
        }
        for (int i = 0; i < sorted.size(); i++) {
            AccumulationChargeRow row = sorted.get(i);
            checkRow(row);
            boolean last = i == sorted.size() - 1;
            if (row.toPolicyYear() == null && !last) {
                fail("Only the last account charge row may be open-ended");
            }
            if (last && row.toPolicyYear() != null) {
                fail("The last account charge row must be open-ended, so every policy year has a charge");
            }
            if (!last) {
                AccumulationChargeRow next = sorted.get(i + 1);
                int nextExpected = row.toPolicyYear() + 1;
                if (next.fromPolicyYear() < nextExpected) {
                    fail("Account charges for policy years " + describe(row) + " and " + describe(next) + " overlap");
                }
                if (next.fromPolicyYear() > nextExpected) {
                    fail("Account charges leave policy year " + nextExpected + " uncovered");
                }
            }
        }
    }

    private static void checkRow(AccumulationChargeRow row) {
        if (row.toPolicyYear() != null && row.toPolicyYear() < row.fromPolicyYear()) {
            fail("An account charge row ends before it begins (" + describe(row) + ")");
        }
        for (BigDecimal pct : java.util.Arrays.asList(row.contributionAllocationPercent(), row.transferAllocationPercent())) {
            if (pct == null || pct.signum() < 0 || pct.compareTo(HUNDRED) > 0) {
                fail("An allocation charge must be between 0 and 100 percent");
            }
        }
        if (row.monthlyPolicyFee() == null || row.monthlyPolicyFee().signum() < 0) {
            fail("A monthly policy fee cannot be negative");
        }
    }

    private static String describe(AccumulationChargeRow row) {
        return row.toPolicyYear() == null ? row.fromPolicyYear() + " onwards" : row.fromPolicyYear() + "-" + row.toPolicyYear();
    }

    private static void fail(String message) {
        throw new InvalidProductVersionException(message);
    }
}
