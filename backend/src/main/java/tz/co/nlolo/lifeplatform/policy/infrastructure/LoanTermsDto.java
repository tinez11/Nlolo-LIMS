package tz.co.nlolo.lifeplatform.policy.infrastructure;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import tz.co.nlolo.lifeplatform.policy.api.LoanTerms;
import tz.co.nlolo.lifeplatform.policy.api.RepaymentFrequency;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The loan a credit-life member IS.
 *
 * <p>On this product a member is not a person with a loan attached — the member is the loan, and
 * these are its terms. The amount insured is derived from them, declining straight-line over the
 * term, so nothing here is optional decoration: a schedule with no principal or no term cannot be
 * valued at all.
 *
 * <p>Money arrives as a string and is converted once here, the platform-wide rule: a JSON number
 * cannot carry 8500000.00 without a float somewhere in the chain rounding it.
 *
 * <p>{@code annualInterestRatePercent} is {@code PositiveOrZero} rather than positive. Zero is the
 * normal case: cover declines straight-line, so no rate is ever read to value a claim, and both
 * real lender files charge premium on the disbursed amount. A rate of zero is a true statement
 * about such a loan rather than a missing value.
 */
public record LoanTermsDto(
    @NotNull @Pattern(regexp = MoneyAmounts.POSITIVE_AMOUNT) String principalAmount,
    @NotNull @PositiveOrZero BigDecimal annualInterestRatePercent,
    @NotNull Integer termMonths,
    @NotNull RepaymentFrequency repaymentFrequency,
    @NotNull LocalDate disbursementDate,
    @NotNull LocalDate firstRepaymentDate) {

    public LoanTerms toApiInput() {
        return new LoanTerms(new BigDecimal(principalAmount), annualInterestRatePercent,
            termMonths, repaymentFrequency, disbursementDate, firstRepaymentDate);
    }
}
