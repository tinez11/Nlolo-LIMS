package tz.co.nlolo.lifeplatform.policy.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.api.BenefitBasis;
import tz.co.nlolo.lifeplatform.policy.api.CreditLifeAgreementApi;
import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;
import tz.co.nlolo.lifeplatform.policy.api.PolicyNotFoundException;
import tz.co.nlolo.lifeplatform.policy.domain.CreditLifeAgreement;
import tz.co.nlolo.lifeplatform.policy.domain.GroupScheme;
import tz.co.nlolo.lifeplatform.policy.infrastructure.CreditLifeAgreementRepository;
import tz.co.nlolo.lifeplatform.policy.infrastructure.GroupSchemeRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A credit-life scheme's yearly agreements (policy V43, 2026-10-30). Adding one locks the scheme, so two staff adding a
 * year at once cannot leave two agreements in force on one day -- which would price a loan two ways.
 */
@Service
public class CreditLifeAgreements implements CreditLifeAgreementApi {

    /** TIRA's cap on credit-life commission (Circular 029-2015 addendum, March 2025). */
    static final BigDecimal COMMISSION_CAP = new BigDecimal("15");

    private final CreditLifeAgreementRepository agreements;
    private final GroupSchemeRepository schemes;

    public CreditLifeAgreements(CreditLifeAgreementRepository agreements, GroupSchemeRepository schemes) {
        this.agreements = agreements;
        this.schemes = schemes;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgreementView> agreements(String policyNumber) {
        UUID tenantId = TenantContext.get();
        creditLifeScheme(policyNumber, tenantId);
        return agreements.findByTenantIdAndPolicyNumberOrderByValidFromAsc(tenantId, policyNumber).stream()
            .map(CreditLifeAgreement::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgreementView> inForce(String policyNumber, LocalDate day) {
        return entityInForce(policyNumber, day).map(CreditLifeAgreement::toView);
    }

    /** The agreement itself, for pricing a loan in this module. */
    Optional<CreditLifeAgreement> entityInForce(String policyNumber, LocalDate day) {
        return agreements.findByTenantIdAndPolicyNumberOrderByValidFromAsc(TenantContext.get(), policyNumber).stream()
            .filter(a -> a.covers(day)).findFirst();
    }

    /** All of the scheme's agreements, read once for a whole file rather than once per borrower. */
    List<CreditLifeAgreement> entities(String policyNumber) {
        return agreements.findByTenantIdAndPolicyNumberOrderByValidFromAsc(TenantContext.get(), policyNumber);
    }

    @Override
    @Transactional
    public AgreementView add(String policyNumber, AgreementInput input, String createdBy) {
        UUID tenantId = TenantContext.get();
        creditLifeScheme(policyNumber, tenantId);
        schemes.lockScheme(policyNumber, tenantId);
        refuseUnlessSound(input);
        for (CreditLifeAgreement existing : agreements.findByTenantIdAndPolicyNumberOrderByValidFromAsc(tenantId, policyNumber)) {
            if (existing.overlaps(input.validFrom(), input.validTo())) {
                throw new InvalidPolicyStateException("An agreement already runs from " + existing.getValidFrom() + " to "
                    + existing.getValidTo() + " on " + policyNumber + ". A new year starts after it ends.");
            }
        }
        return agreements.save(new CreditLifeAgreement(tenantId, policyNumber, input, createdBy)).toView();
    }

    private GroupScheme creditLifeScheme(String policyNumber, UUID tenantId) {
        GroupScheme scheme = schemes.findByPolicyNumberAndTenantId(policyNumber, tenantId)
            .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
        if (scheme.getBenefitBasis() != BenefitBasis.AMORTISING_LOAN) {
            throw new InvalidPolicyStateException(policyNumber + " is not a credit-life scheme; only a lender's scheme has a"
                + " yearly agreement");
        }
        return scheme;
    }

    private static void refuseUnlessSound(AgreementInput input) {
        if (input.validFrom() == null || input.validTo() == null || input.validTo().isBefore(input.validFrom())) {
            throw new InvalidPolicyStateException("An agreement needs a start date and an end date on or after it");
        }
        if (input.premiumRatePercent() == null || input.premiumRatePercent().signum() <= 0) {
            throw new InvalidPolicyStateException("Give the premium rate the lender agreed");
        }
        if (input.premiumBasis() == null) {
            throw new InvalidPolicyStateException("Choose how the rate is applied to a loan");
        }
        if (input.minimumPremium() != null && input.minimumPremium().signum() < 0) {
            throw new InvalidPolicyStateException("The minimum premium cannot be negative");
        }
        if (input.minAge() < 18) {
            throw new InvalidPolicyStateException("The youngest borrower must be at least 18");
        }
        if (input.maxAge() < input.minAge() || input.maxAge() > 100) {
            throw new InvalidPolicyStateException("The oldest borrower must be no younger than the youngest, and at"
                + " most 100");
        }
        if (input.minTermMonths() < 1 || input.maxTermMonths() < input.minTermMonths() || input.maxTermMonths() > 480) {
            throw new InvalidPolicyStateException("The shortest loan must be at least a month and the longest no shorter"
                + " than it, and no longer than 480 months");
        }
        if (input.commissionPercent() != null
                && (input.commissionPercent().signum() < 0 || input.commissionPercent().compareTo(COMMISSION_CAP) > 0)) {
            throw new InvalidPolicyStateException("A lender's commission on credit life may not exceed 15% of premium"
                + " (TIRA Circular 029-2015, as amended March 2025)");
        }
    }
}
