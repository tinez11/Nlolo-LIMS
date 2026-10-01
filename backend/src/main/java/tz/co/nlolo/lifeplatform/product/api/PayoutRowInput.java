package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/**
 * One authored payout.
 *
 * <p>{@code MATURITY} and {@code RETURN_OF_PREMIUM} leave the years and the frequency null: they
 * pay once, on the policy's own maturity date. The product does not fix the term -- each policy
 * does -- so a year stated here would disagree with half the policies sold on the version.
 *
 * <p>{@code amountValue} is the amount PER POLICY YEAR for {@code SURVIVAL} and {@code INCOME},
 * split across that year's instalments. "3% of the sum assured a year, paid monthly" is how the
 * guide (§14) and an actuary both state it.
 */
public record PayoutRowInput(PayoutKind kind, Integer fromPolicyYear, Integer toPolicyYear,
                             PayoutAmountBasis amountBasis, BigDecimal amountValue, PayoutFrequency frequency) {}
