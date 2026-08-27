package tz.co.nlolo.lifeplatform.product.api;

/**
 * A rating dimension of the base rate table, alongside age band and smoker status.
 *
 * Deliberately narrow and deliberately named `Sex`, not `Gender`: this exists only
 * because mortality differs measurably between the two, which is a biological
 * rating input, not an identity field. Nothing on this platform displays it and
 * no party record carries it.
 *
 * That last point is the honest gap: `party.individual` records `dateOfBirth` but
 * NOT sex, so a premium quote takes this as an asserted input from the caller
 * rather than resolving it from the client record — the same position as
 * occupation class. Recorded in the M13 design spec's open items.
 */
public enum Sex {
    FEMALE,
    MALE
}
