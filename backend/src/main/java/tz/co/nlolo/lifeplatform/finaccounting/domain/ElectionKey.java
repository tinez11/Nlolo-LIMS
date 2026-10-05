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
    COHORT(List.of("ANNUAL"));

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
        return this == MODEL_OVERRIDE_ALLOWED ? List.of("NONE", "GMM", "VFA", "PAA", "IFRS9") : values;
    }
}
