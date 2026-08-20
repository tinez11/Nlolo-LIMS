package tz.co.nlolo.lifeplatform.regreporting.domain;

import tz.co.nlolo.lifeplatform.regreporting.api.MetricKind;

/**
 * The seventeen metrics this module computes -- the complete, enumerated answer to "what can a
 * return line ask for?" (design spec §5).
 *
 * <p>This enum is the single point where the registry, the {@code metric_name} strings seeded into
 * {@code return_definition_line}, and Task 4's readers are held together. A definition naming
 * something absent from this enum must fail loudly at generation time, never emit a null line.
 *
 * <p>Deliberately NOT data. Making metric definitions a table (fact table + column + aggregation,
 * with SQL assembled from those values) was considered and rejected: over four fact tables with
 * these measures already exposed, it buys only "sum a column nobody asked for yet" without a
 * deploy, and it costs dynamically-assembled SQL and an injection surface on the one module a
 * REGULATOR can read, plus the loss of compile-time safety on every metric. See the spec's §5.
 */
public enum MetricName {

    POLICIES_IN_FORCE(MetricKind.STOCK, false),
    SUM_ASSURED_IN_FORCE(MetricKind.STOCK, true),

    POLICIES_ISSUED(MetricKind.FLOW, false),
    POLICIES_REINSTATED(MetricKind.FLOW, false),
    POLICIES_LAPSED(MetricKind.FLOW, false),
    POLICIES_MATURED(MetricKind.FLOW, false),
    POLICIES_CLAIM_TERMINATED(MetricKind.FLOW, false),
    NEW_BUSINESS_SUM_ASSURED(MetricKind.FLOW, true),

    CLAIMS_REGISTERED(MetricKind.FLOW, false),
    CLAIMS_APPROVED(MetricKind.FLOW, false),
    CLAIMS_REJECTED(MetricKind.FLOW, false),
    CLAIMS_SETTLED(MetricKind.FLOW, false),
    CLAIMS_APPROVED_AMOUNT(MetricKind.FLOW, true),
    CLAIMS_SETTLED_AMOUNT(MetricKind.FLOW, true),

    PREMIUM_COLLECTED(MetricKind.FLOW, true),

    REINSURANCE_CEDED_RISK(MetricKind.FLOW, true),
    REINSURANCE_CEDED_PREMIUM(MetricKind.FLOW, true);

    private final MetricKind kind;
    private final boolean monetary;

    MetricName(MetricKind kind, boolean monetary) {
        this.kind = kind;
        this.monetary = monetary;
    }

    public MetricKind kind() { return kind; }

    /** True when the value is money and therefore carries a currency; false for a count. */
    public boolean isMonetary() { return monetary; }
}
