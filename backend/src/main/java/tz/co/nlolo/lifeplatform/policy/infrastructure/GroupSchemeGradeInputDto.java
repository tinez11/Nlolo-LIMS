package tz.co.nlolo.lifeplatform.policy.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.math.BigDecimal;

/**
 * One band on a GRADED scheme's benefit table, as supplied.
 *
 * <p>A bare decimal <b>string</b>, not a {@link MoneyDto} and not a JSON number. Not a
 * number because money on this platform never travels as one -- a decimal that survives
 * Postgres and Java exactly should not become a double on the last hop. Not a MoneyDto
 * because the scheme already declares one {@code currency} for everything on it, and a
 * per-grade currency code could only ever agree with it or contradict it.
 */
public record GroupSchemeGradeInputDto(
    @NotBlank @Size(max = 30) String gradeCode,
    @NotBlank @Pattern(regexp = MoneyAmounts.POSITIVE_AMOUNT) String benefitAmount) {

    public PolicyApi.GradeInput toApiInput() {
        return new PolicyApi.GradeInput(gradeCode, new BigDecimal(benefitAmount));
    }
}
