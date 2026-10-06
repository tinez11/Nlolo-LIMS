package tz.co.nlolo.lifeplatform.product.api;

/**
 * The expected profitability of the contracts a product version sells (IFRS 17 para 16), set by the actuary when the
 * version is published. Each bucket is its own group of contracts; {@link #groupSuffix} names it in a group key.
 */
public enum ProfitabilityBucket {
    /** Onerous at initial recognition. */
    ONEROUS("ONER"),
    /** No significant possibility of becoming onerous. */
    NO_SIGNIFICANT_RISK("NSR"),
    /** The remaining contracts. */
    REMAINING("REM");

    private final String groupSuffix;

    ProfitabilityBucket(String groupSuffix) {
        this.groupSuffix = groupSuffix;
    }

    public String groupSuffix() {
        return groupSuffix;
    }
}
