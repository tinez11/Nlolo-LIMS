package tz.co.nlolo.lifeplatform.product.api;

/**
 * How a payout row's {@code amountValue} becomes money: a percentage of the sum assured, a fixed
 * sum, or a percentage of the premiums actually collected (a return-of-premium term policy, and
 * nothing else -- see {@code payout_row_basis}).
 */
public enum PayoutAmountBasis { PERCENT_OF_SA, FIXED, PERCENT_OF_PREMIUMS }
