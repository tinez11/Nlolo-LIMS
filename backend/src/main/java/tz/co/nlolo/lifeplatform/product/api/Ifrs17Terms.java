package tz.co.nlolo.lifeplatform.product.api;

/**
 * What the actuary signs off for IFRS 17 when a version is published (IFRS 17 spec §6): the expected profitability of
 * its contracts, and a measurement-model override or none. An override counts only where the accounting policy
 * register in force on the issue date allows it for the product's portfolio.
 */
public record Ifrs17Terms(ProfitabilityBucket bucket, Ifrs17Model modelOverride) {

    /** Remaining contracts, no override: the register decides the model. */
    public static final Ifrs17Terms DEFAULT = new Ifrs17Terms(ProfitabilityBucket.REMAINING, null);

    public Ifrs17Terms {
        bucket = bucket == null ? ProfitabilityBucket.REMAINING : bucket;
    }
}
