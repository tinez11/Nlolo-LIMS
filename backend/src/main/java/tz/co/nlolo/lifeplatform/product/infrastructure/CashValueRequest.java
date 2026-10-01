package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import tz.co.nlolo.lifeplatform.product.api.CashValuePlan;
import tz.co.nlolo.lifeplatform.product.api.CashValueRowInput;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A version's cash-value table on the wire (openapi-product.yaml CashValueTable). Every rule lives
 * in CashValuePlanValidator so the HTTP and internal paths refuse identically; only the row shape
 * Jackson needs is constrained here.
 */
public record CashValueRequest(String basisReference, LocalDate basisDate, String paidUpBasis,
                               Integer minYearsForValue, @Valid List<Row> rows) {

    public record Row(@NotNull Integer policyYear, Integer ageFrom, Integer ageTo,
                      @NotNull BigDecimal cashValuePerMille, BigDecimal paidUpPerMille) {}

    public CashValuePlan toPlan() {
        return new CashValuePlan(basisReference, basisDate, paidUpBasis, minYearsForValue,
            rows == null ? List.of() : rows.stream()
                .map(r -> new CashValueRowInput(r.policyYear(), r.ageFrom(), r.ageTo(), r.cashValuePerMille(), r.paidUpPerMille()))
                .toList());
    }
}
