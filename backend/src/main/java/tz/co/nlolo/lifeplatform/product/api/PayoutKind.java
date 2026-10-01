package tz.co.nlolo.lifeplatform.product.api;

/**
 * What a payout schedule row pays for (guide §6, §7, §14).
 *
 * <p>{@code SURVIVAL} and {@code INCOME} differ in cadence and in what they need of the payer, not
 * in who is paid: a survival benefit is the occasional lump the guide's money-back plans pay at
 * year 5, 10 and 15, while an income stream runs monthly or quarterly for years and so is approved
 * once and then paid in batches. Both require proof the life assured is alive; neither pays after
 * a death.
 */
public enum PayoutKind { SURVIVAL, MATURITY, INCOME, RETURN_OF_PREMIUM }
