package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/**
 * One plan (band) a customer can pick on a funeral version, e.g. "B", "Familia B".
 *
 * @param groupMonthlyRate what a group scheme on this plan pays per member per month, the member's whole family
 *                         included (group funeral schemes); null on a version not sold to groups
 */
public record FuneralPlanOption(String planCode, String name, BigDecimal groupMonthlyRate) {

    /** A plan sold to individuals only: no group rate. */
    public FuneralPlanOption(String planCode, String name) {
        this(planCode, name, null);
    }
}
