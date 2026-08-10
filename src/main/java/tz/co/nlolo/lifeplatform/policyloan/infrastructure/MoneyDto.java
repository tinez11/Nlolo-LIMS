package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Mirrors policy.infrastructure.MoneyDto's regex (Task 4 precedent) -- the wire-shape
 * translation layer between openapi-common.yaml's Money schema and this module's flattened
 * LoanView (see LoanResponseDto).
 *
 * <p>Deliberately DIVERGES from policy.infrastructure.MoneyDto by adding {@code @DecimalMin}:
 * the shared Money schema's {@code -?} is correct for signed ledger contexts, but every field
 * this record's amount ever binds to on this module's HTTP boundary --
 * OriginateLoanRequestDto.requestedAmount and RepaymentRequestDto.amount -- is a positive
 * quantity by definition. Without a floor, a negative requestedAmount passes
 * PolicyApiImpl.reserveLoanValue's upper-bound-only guard (amount.compareTo(available) > 0 is
 * false for a negative amount), then PolicyAccount.increaseEncumbrance's unguarded .add() drives
 * loan_encumbrance_amount negative, which *increases* the customer's own available loan value
 * (Task 7 review C1, fix round 1). A negative repayment amount is the same hole in reverse: it
 * inflates outstandingBalance instead of reducing it. @DecimalMin(0.01) closes both at the wire
 * boundary, the designated input-validation layer for this DTO. Response DTOs are never run
 * through the validator, so LoanResponseDto (which reuses this same MoneyDto) is unaffected. */
public record MoneyDto(
    @NotBlank @Pattern(regexp = "^-?\\d+(\\.\\d{1,2})?$") @DecimalMin(value = "0.01") String amount,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode) {}
