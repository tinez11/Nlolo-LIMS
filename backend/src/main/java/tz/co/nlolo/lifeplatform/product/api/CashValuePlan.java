package tz.co.nlolo.lifeplatform.product.api;

import java.time.LocalDate;
import java.util.List;

/**
 * A version's cash-value table and its actuarial sign-off (product step 1). {@link #none()} for a
 * pure-protection version, which has no cash value at all.
 */
public record CashValuePlan(String basisReference, LocalDate basisDate, String paidUpBasis,
                            Integer minYearsForValue, List<CashValueRowInput> rows) {

    public CashValuePlan {
        rows = rows != null ? List.copyOf(rows) : List.of();
    }

    public static CashValuePlan none() { return new CashValuePlan(null, null, null, null, List.of()); }

    public boolean isPresent() { return !rows.isEmpty() || basisReference != null; }
}
