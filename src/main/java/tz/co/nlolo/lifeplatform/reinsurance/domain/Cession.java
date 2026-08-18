package tz.co.nlolo.lifeplatform.reinsurance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code reinsurance.cession}. Write-once: a cession records what was ceded at issuance and
 * is never mutated afterwards, which is why V2 deliberately gives it no {@code version} column.
 * {@code ux_cession_once} on (tenant, policy, treaty) is what makes a redelivered PolicyIssued a
 * no-op rather than a double-count.
 */
@Entity
@Table(name = "cession", schema = "reinsurance")
public class Cession {

    @Id
    @GeneratedValue
    @Column(name = "cession_id")
    private UUID cessionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Opaque ref into `policy` -- never an FK (docs/06:37). */
    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "treaty_id", nullable = false)
    private UUID treatyId;

    @Column(name = "ceded_amount", nullable = false)
    private BigDecimal cededAmount;

    @Column(name = "ceded_currency", nullable = false)
    private String cededCurrency;

    /** Null together with its currency when a treaty cedes risk without a modelled premium
     * share -- V2's cession_ceded_premium_paired. */
    @Column(name = "ceded_premium_amount")
    private BigDecimal cededPremiumAmount;

    @Column(name = "ceded_premium_currency")
    private String cededPremiumCurrency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Cession() {}

    public Cession(UUID tenantId, String policyNumber, UUID treatyId, BigDecimal cededAmount,
                    String cededCurrency, BigDecimal cededPremiumAmount, String cededPremiumCurrency) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.treatyId = treatyId;
        this.cededAmount = cededAmount;
        this.cededCurrency = cededCurrency;
        this.cededPremiumAmount = cededPremiumAmount;
        this.cededPremiumCurrency = cededPremiumCurrency;
    }

    public UUID getCessionId() { return cessionId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public UUID getTreatyId() { return treatyId; }
    public BigDecimal getCededAmount() { return cededAmount; }
    public String getCededCurrency() { return cededCurrency; }
    public BigDecimal getCededPremiumAmount() { return cededPremiumAmount; }
    public String getCededPremiumCurrency() { return cededPremiumCurrency; }
    public Instant getCreatedAt() { return createdAt; }
}
