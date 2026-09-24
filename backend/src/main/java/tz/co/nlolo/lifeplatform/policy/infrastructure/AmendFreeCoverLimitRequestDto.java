package tz.co.nlolo.lifeplatform.policy.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * What a scheme's free cover limit is being moved to, and why.
 *
 * <p>{@code fclAmount} is OMITTED for a scheme with no limit at all, exactly as on issuance. It is
 * never zero: a limit of zero would send every borrower to underwriting, which is a different
 * scheme and not an absent limit. The pattern refuses zero rather than accepting and
 * reinterpreting it.
 *
 * <p>{@code reason} is required. A limit is a term agreed with a lender, and an amendment with no
 * stated reason is an unexplained change to what every borrower on the book is covered for.
 */
public record AmendFreeCoverLimitRequestDto(
    @Pattern(regexp = MoneyAmounts.POSITIVE_AMOUNT) String fclAmount,
    @NotBlank String reason) {}
