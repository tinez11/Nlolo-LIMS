package tz.co.nlolo.lifeplatform.product.api;

/**
 * How a FUNERAL version may be sold (group funeral schemes, 2026-10-07): to one family on an individual policy, priced
 * by the premium table; to an association or employer's members under one master policy, priced at each plan's group
 * rate per member per month; or both.
 */
public enum FuneralSoldAs {
    INDIVIDUAL, GROUP, BOTH;

    /** An individual policy may be sold on it: the premium table prices it. */
    public boolean individual() {
        return this != GROUP;
    }

    /** A group scheme may be set up on it: each plan carries a group rate. */
    public boolean group() {
        return this != INDIVIDUAL;
    }
}
