package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/**
 * One plan of a funeral version.
 *
 * @param groupMonthlyRate the group rate per member, charged per {@code groupRatePeriod} (the name predates yearly
 *                         plans); null on a plan not sold to groups
 * @param groupRatePeriod  MONTHLY unless the plan bills its schemes once a year (2026-10-08)
 */
public record FuneralPlanOption(String planCode, String name, BigDecimal groupMonthlyRate, GroupRatePeriod groupRatePeriod) {

    public FuneralPlanOption {
        groupRatePeriod = groupRatePeriod != null ? groupRatePeriod : GroupRatePeriod.MONTHLY;
    }

    /** A plan whose group rate is per month -- every plan written before yearly plans. */
    public FuneralPlanOption(String planCode, String name, BigDecimal groupMonthlyRate) {
        this(planCode, name, groupMonthlyRate, GroupRatePeriod.MONTHLY);
    }

    /** A plan sold to individuals only: no group rate. */
    public FuneralPlanOption(String planCode, String name) {
        this(planCode, name, null, GroupRatePeriod.MONTHLY);
    }
}
