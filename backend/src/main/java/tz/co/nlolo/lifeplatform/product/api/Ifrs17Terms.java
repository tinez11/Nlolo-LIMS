package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/**
 * What the actuary signs off for IFRS 17 when a version is published (IFRS 17 spec §6): the expected profitability of
 * its contracts, and a measurement-model override or none. An override counts only where the accounting policy
 * register in force on the issue date allows it for the product's portfolio.
 *
 * <p>IFRS 17 I3b (user decision 4): the share of each survival benefit or income instalment that is an investment
 * component (paid in all circumstances, posting guide D-01/D-05), a percentage the actuary sets per version. Null
 * where it has not been set: the whole instalment is an insurance service expense (5115) until it is.
 */
public record Ifrs17Terms(ProfitabilityBucket bucket, Ifrs17Model modelOverride,
                          BigDecimal survivalInvestmentComponentPercent) {

    /** Remaining contracts, no override: the register decides the model. */
    public static final Ifrs17Terms DEFAULT = new Ifrs17Terms(ProfitabilityBucket.REMAINING, null, null);

    public Ifrs17Terms {
        bucket = bucket == null ? ProfitabilityBucket.REMAINING : bucket;
        if (survivalInvestmentComponentPercent != null && (survivalInvestmentComponentPercent.signum() < 0
                || survivalInvestmentComponentPercent.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new IllegalArgumentException("The survival benefit's investment component is a percentage from 0 to 100");
        }
    }

    public Ifrs17Terms(ProfitabilityBucket bucket, Ifrs17Model modelOverride) {
        this(bucket, modelOverride, null);
    }
}
