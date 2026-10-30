package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.policy.api.CreditLifeAgreementApi.AgreementInput;
import tz.co.nlolo.lifeplatform.policy.api.CreditLifeAgreementApi.AgreementView;
import tz.co.nlolo.lifeplatform.policy.api.CreditLifePremiumBasis;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A lender's agreement for one year of a credit-life scheme (policy V43, 2026-10-30). Never edited: a changed rate or
 * commission is next year's agreement, because every borrower already on cover was charged on this one.
 */
@Entity
@Table(name = "credit_life_agreement", schema = "policy")
public class CreditLifeAgreement {

    @Id
    @Column(name = "agreement_id")
    private UUID agreementId = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to", nullable = false)
    private LocalDate validTo;

    @Column(name = "premium_rate_percent", nullable = false)
    private BigDecimal premiumRatePercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "premium_basis", nullable = false)
    private CreditLifePremiumBasis premiumBasis;

    @Column(name = "minimum_premium", nullable = false)
    private BigDecimal minimumPremium;

    @Column(name = "max_age", nullable = false)
    private int maxAge;

    @Column(name = "min_term_months", nullable = false)
    private int minTermMonths;

    @Column(name = "max_term_months", nullable = false)
    private int maxTermMonths;

    @Column(name = "commission_percent", nullable = false)
    private BigDecimal commissionPercent;

    @Column(name = "cover_includes_interest", nullable = false)
    private boolean coverIncludesInterest;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    protected CreditLifeAgreement() {}

    public CreditLifeAgreement(UUID tenantId, String policyNumber, AgreementInput input, String createdBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.validFrom = input.validFrom();
        this.validTo = input.validTo();
        this.premiumRatePercent = input.premiumRatePercent();
        this.premiumBasis = input.premiumBasis();
        this.minimumPremium = input.minimumPremium() == null ? BigDecimal.ZERO : input.minimumPremium();
        this.maxAge = input.maxAge();
        this.minTermMonths = input.minTermMonths();
        this.maxTermMonths = input.maxTermMonths();
        this.commissionPercent = input.commissionPercent() == null ? BigDecimal.ZERO : input.commissionPercent();
        this.coverIncludesInterest = input.coverIncludesInterest();
        this.createdBy = createdBy;
    }

    public boolean covers(LocalDate day) {
        return !day.isBefore(validFrom) && !day.isAfter(validTo);
    }

    public boolean overlaps(LocalDate from, LocalDate to) {
        return !from.isAfter(validTo) && !to.isBefore(validFrom);
    }

    /** What one loan pays under this agreement: its rule, never below the minimum. */
    public BigDecimal premiumFor(BigDecimal sumAssured, int termMonths) {
        return CreditLifePremium.forLoan(sumAssured, termMonths, premiumRatePercent, premiumBasis, minimumPremium);
    }

    public AgreementView toView() {
        return new AgreementView(agreementId, policyNumber, validFrom, validTo, premiumRatePercent, premiumBasis,
            minimumPremium, maxAge, minTermMonths, maxTermMonths, commissionPercent, coverIncludesInterest, createdAt,
            createdBy);
    }

    public UUID getAgreementId() { return agreementId; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
    public int getMaxAge() { return maxAge; }
    public int getMinTermMonths() { return minTermMonths; }
    public int getMaxTermMonths() { return maxTermMonths; }
    public BigDecimal getCommissionPercent() { return commissionPercent; }
    public boolean isCoverIncludesInterest() { return coverIncludesInterest; }
}
