package tz.co.nlolo.lifeplatform.product.api;

/**
 * What a funeral plan's group rate is charged per (2026-10-08): per member per month -- billed monthly, the member
 * list moving as people join and leave -- or per member per year: one bill for the year, the member list fixed
 * while the scheme is in force (the policyholder alone is liable for it).
 */
public enum GroupRatePeriod {
    MONTHLY, YEARLY;

    /** The premium frequency a scheme on this plan is billed at. */
    public String premiumFrequency() {
        return this == YEARLY ? "ANNUALLY" : "MONTHLY";
    }
}
