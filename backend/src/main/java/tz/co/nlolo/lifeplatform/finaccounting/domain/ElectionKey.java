package tz.co.nlolo.lifeplatform.finaccounting.domain;

import java.util.List;
import java.util.Optional;

/**
 * The accounting policy elections the register holds (IFRS 17 spec §3) and the values each permits. Scope is a
 * portfolio code (TERM, ULIP ...) for the per-portfolio elections, a measurement model for ACQUISITION_CASH_FLOWS,
 * and "*" for the company-wide ones.
 */
public enum ElectionKey {
    MEASUREMENT_MODEL(List.of("GMM", "VFA", "PAA", "IFRS9")),
    INVESTMENT_COMPONENT_RULE(List.of("NONE", "PREMIUMS_RETURNED", "SURRENDER_VALUE", "SURRENDER_VALUE_WITH_BONUSES",
        "FUND_VALUE", "WHOLE_BALANCE")),
    /** A comma-separated list of models a product version may override to, or NONE. */
    MODEL_OVERRIDE_ALLOWED(List.of()),
    POLICY_LOANS(List.of("INSIDE_CONTRACT", "OUTSIDE_CONTRACT")),
    ACQUISITION_CASH_FLOWS(List.of("SPREAD", "EXPENSE_WHEN_INCURRED")),
    OCI_OPTION(List.of("OFF", "ON")),
    RIDERS(List.of("HOST_GROUP", "SEPARATE")),
    PREMIUM_BILLING(List.of("ACCRUAL_AT_INVOICE")),
    CONTRACT_RECOGNITION(List.of("ISSUE_DATE")),
    COHORT(List.of("ANNUAL")),
    /**
     * IFRS 17 I3b, user decision 7: the withholding tax rate on commission paid (posting guide A-05), a percentage such
     * as 5 or 2.5, or NONE. Effective-dated and approved by a second person like any election. No election, or NONE,
     * means nothing is withheld -- never a default rate.
     */
    COMMISSION_WITHHOLDING_RATE(List.of()),
    /** IFRS 17 I3b, user decision 6: the premium levy rate (A-19), a percentage or NONE; none in force, no levy. */
    PREMIUM_LEVY_RATE(List.of());

    private static final List<String> MODELS = List.of("GMM", "VFA", "PAA", "IFRS9");

    private final List<String> values;

    ElectionKey(List<String> values) {
        this.values = values;
    }

    public static Optional<ElectionKey> of(String key) {
        try {
            return Optional.of(ElectionKey.valueOf(key));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    /** Whether {@code value} is one this election may take. */
    public boolean permits(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (isRate()) {
            return "NONE".equals(value) || rate(value).isPresent();
        }
        if (this == MODEL_OVERRIDE_ALLOWED) {
            if ("NONE".equals(value)) {
                return true;
            }
            for (String m : value.split(",")) {
                if (!MODELS.contains(m.trim())) {
                    return false;
                }
            }
            return true;
        }
        return values.contains(value);
    }

    public List<String> permittedValues() {
        if (isRate()) {
            return List.of("NONE");
        }
        return this == MODEL_OVERRIDE_ALLOWED ? List.of("NONE", "GMM", "VFA", "PAA", "IFRS9") : values;
    }

    /** A rate election: its value is NONE or a percentage. */
    public boolean isRate() {
        return this == COMMISSION_WITHHOLDING_RATE || this == PREMIUM_LEVY_RATE;
    }

    /**
     * A rate election's value as a fraction (5 -> 0.05): a percentage above 0 and at most 100, up to four decimal
     * places. Empty for NONE or anything else.
     */
    public static Optional<java.math.BigDecimal> rate(String value) {
        if (value == null || !value.matches("\\d{1,3}(\\.\\d{1,4})?")) {
            return Optional.empty();
        }
        java.math.BigDecimal percent = new java.math.BigDecimal(value);
        if (percent.signum() <= 0 || percent.compareTo(java.math.BigDecimal.valueOf(100)) > 0) {
            return Optional.empty();
        }
        return Optional.of(percent.movePointLeft(2));
    }
}
