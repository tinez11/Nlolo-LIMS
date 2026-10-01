package tz.co.nlolo.lifeplatform.product.api;

/**
 * How a version's value is defined (product step 3, decision Q1).
 *
 * <p>{@code SCALE} is step 1's: sum assured x a per-mille scale at completed policy years -- a
 * traditional endowment's guaranteed surrender value. {@code ACCOUNT} is a ledger owned by the
 * {@code accumulation} module: contributions in, charges out, interest credited. Both end up in
 * {@code PolicyAccount.cashValueAmount}, which is why surrender and loans need no change.
 */
public enum ValueBasis { SCALE, ACCOUNT }
