package tz.co.nlolo.lifeplatform.product.domain;

import tz.co.nlolo.lifeplatform.product.api.CashValuePlan;
import tz.co.nlolo.lifeplatform.product.api.CashValueRowInput;
import tz.co.nlolo.lifeplatform.product.api.InvalidProductVersionException;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a cash-value table must carry to be published (product step 1, plan §3 and decision Q6).
 * Pure, so it is unit-tested without Spring. Mirrors V17's CHECKs and adds what a CHECK cannot say:
 * the sign-off, the category, the TABLE basis's paid-up values, and non-overlapping age bands.
 */
public final class CashValuePlanValidator {

    /** The savings products step 1 unlocks. Pure protection has no cash value. */
    private static final Set<ProductCategory> CASH_VALUE_CATEGORIES = EnumSet.of(
        ProductCategory.ENDOWMENT, ProductCategory.WHOLE_LIFE, ProductCategory.EDUCATION_SAVINGS);

    private CashValuePlanValidator() {}

    public static void validate(ProductCategory category, CashValuePlan plan) {
        if (plan == null || !plan.isPresent()) {
            return;
        }
        if (!CASH_VALUE_CATEGORIES.contains(category)) {
            fail("A " + category + " product cannot carry a cash-value table");
        }
        if (plan.basisReference() == null || plan.basisReference().isBlank() || plan.basisDate() == null) {
            fail("A cash-value table needs the actuarial basis reference and date it was signed off under");
        }
        if (!"PROPORTIONATE".equals(plan.paidUpBasis()) && !"TABLE".equals(plan.paidUpBasis())) {
            fail("The paid-up basis must be PROPORTIONATE or TABLE");
        }
        Integer minYears = plan.minYearsForValue();
        if (minYears == null || minYears < 2 || minYears > 3) {
            fail("A cash-value table needs the minimum years before any value exists, 2 or 3");
        }
        List<CashValueRowInput> rows = plan.rows();
        if (rows.isEmpty()) {
            fail("A cash-value table needs at least one row");
        }
        for (CashValueRowInput row : rows) {
            checkRow(row, "TABLE".equals(plan.paidUpBasis()));
        }
        rejectOverlaps(rows);
    }

    private static void checkRow(CashValueRowInput row, boolean tableBasis) {
        if (row.policyYear() < 1) {
            fail("A cash-value row's policy year must be 1 or more");
        }
        if ((row.ageFrom() == null) != (row.ageTo() == null)) {
            fail("A cash-value age band needs both a from and a to age, or neither");
        }
        if (row.ageFrom() != null && (row.ageFrom() < 0 || row.ageTo() < row.ageFrom())) {
            fail("Cash-value age band " + row.ageFrom() + "-" + row.ageTo() + " is not a range");
        }
        if (row.cashValuePerMille() == null || row.cashValuePerMille().signum() < 0) {
            fail("A cash value per 1,000 cannot be negative");
        }
        if (row.paidUpPerMille() != null && row.paidUpPerMille().signum() < 0) {
            fail("A paid-up value per 1,000 cannot be negative");
        }
        if (tableBasis && row.paidUpPerMille() == null) {
            fail("A TABLE paid-up basis needs a paid-up value on every row (policy year " + row.policyYear() + " has none)");
        }
    }

    /** Two rows for one policy year collide when their age bands meet; an unbanded row meets every band. */
    private static void rejectOverlaps(List<CashValueRowInput> rows) {
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                CashValueRowInput a = rows.get(i);
                CashValueRowInput b = rows.get(j);
                if (a.policyYear() != b.policyYear()) {
                    continue;
                }
                boolean overlap = a.ageFrom() == null || b.ageFrom() == null
                    || (a.ageFrom() <= b.ageTo() && b.ageFrom() <= a.ageTo());
                if (overlap) {
                    fail("Cash-value rows for policy year " + a.policyYear() + ", " + band(a) + " and " + bandOnly(b)
                        + ", overlap -- an entry age in both would be valued differently depending on row order");
                }
            }
        }
    }

    private static String band(CashValueRowInput r) {
        return r.ageFrom() == null ? "all ages" : "ages " + r.ageFrom() + "-" + r.ageTo();
    }

    private static String bandOnly(CashValueRowInput r) {
        return r.ageFrom() == null ? "all ages" : r.ageFrom() + "-" + r.ageTo();
    }

    private static void fail(String message) {
        throw new InvalidProductVersionException(Objects.requireNonNull(message));
    }
}
