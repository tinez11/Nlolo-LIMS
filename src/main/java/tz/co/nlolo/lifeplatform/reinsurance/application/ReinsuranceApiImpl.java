package tz.co.nlolo.lifeplatform.reinsurance.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reinsurance.api.CessionView;
import tz.co.nlolo.lifeplatform.reinsurance.api.ClaimRecoveryView;
import tz.co.nlolo.lifeplatform.reinsurance.api.RecoveryNotFoundException;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceValidationException;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyNotFoundException;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyStatus;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyView;
import tz.co.nlolo.lifeplatform.reinsurance.domain.Cession;
import tz.co.nlolo.lifeplatform.reinsurance.domain.CessionCalculator;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ClaimRecovery;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceTreaty;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.CessionRepository;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.ClaimRecoveryRepository;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.ReinsuranceTreatyRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@link ReinsuranceApi}, and additionally exposes the package-private cession/recovery
 * plumbing that {@code PolicyEventListener} and {@code ClaimEventListener} call directly (injecting
 * this concrete class rather than the interface -- the same shape {@code distribution}'s listeners
 * use against {@code DistributionApiImpl}). Those methods are not part of the published surface;
 * they exist so the listeners reuse this class's already-wired repositories instead of duplicating
 * selection and persistence logic.
 */
@Service
public class ReinsuranceApiImpl implements ReinsuranceApi {

    private final ReinsuranceTreatyRepository treatyRepository;
    private final CessionRepository cessionRepository;
    private final ClaimRecoveryRepository claimRecoveryRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ReinsuranceApiImpl(ReinsuranceTreatyRepository treatyRepository,
                               CessionRepository cessionRepository,
                               ClaimRecoveryRepository claimRecoveryRepository,
                               ApplicationEventPublisher eventPublisher) {
        this.treatyRepository = treatyRepository;
        this.cessionRepository = cessionRepository;
        this.claimRecoveryRepository = claimRecoveryRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public TreatyView createTreaty(CreateTreatyRequest request, String createdBy) {
        UUID tenantId = TenantContext.get();

        if (request.reinsurerName() == null || request.reinsurerName().isBlank()) {
            throw new ReinsuranceValidationException("A reinsurer name is required");
        }
        if (request.reinsurerName().length() > 200) {
            throw new ReinsuranceValidationException("Reinsurer name is " + request.reinsurerName().length()
                + " characters; the maximum is 200");
        }
        if (request.retentionLimitAmount() == null || request.retentionLimitAmount().signum() < 0) {
            throw new ReinsuranceValidationException("Retention limit must be zero or positive");
        }
        // Mirrors V2's treaty_cession_percent_required_for_quota_share exactly, so a violation is
        // a clean 422 rather than an integrity violation surfacing as a 500.
        boolean isQuotaShare = request.treatyType() == TreatyType.QUOTA_SHARE;
        if (isQuotaShare && request.cessionPercent() == null) {
            throw new ReinsuranceValidationException("A QUOTA_SHARE treaty requires a cession percent");
        }
        if (!isQuotaShare && request.cessionPercent() != null) {
            throw new ReinsuranceValidationException(
                "A " + request.treatyType() + " treaty must not carry a cession percent: SURPLUS cedes by "
                + "retention limit, and XOL does not cede at issuance at all");
        }
        if (request.cessionPercent() != null
                && (request.cessionPercent().signum() <= 0
                    || request.cessionPercent().compareTo(new BigDecimal("100")) > 0)) {
            throw new ReinsuranceValidationException("Cession percent must be greater than 0 and at most 100");
        }
        if (request.effectiveFrom() == null) {
            throw new ReinsuranceValidationException("An effective-from date is required");
        }
        if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw new ReinsuranceValidationException("Effective-to " + request.effectiveTo()
                + " is before effective-from " + request.effectiveFrom());
        }

        ReinsuranceTreaty treaty = new ReinsuranceTreaty(tenantId, request.reinsurerName().trim(),
            request.treatyType(), request.retentionLimitAmount(), request.retentionLimitCurrency(),
            request.cessionPercent(), request.effectiveFrom(), request.effectiveTo(), createdBy);
        treatyRepository.save(treaty);
        return toTreatyView(treaty);
    }

    @Override
    public TreatyView getTreaty(UUID treatyId) {
        UUID tenantId = TenantContext.get();
        return treatyRepository.findByTreatyIdAndTenantId(treatyId, tenantId)
            .map(this::toTreatyView)
            .orElseThrow(() -> new TreatyNotFoundException("Treaty " + treatyId + " not found"));
    }

    @Override
    public List<TreatyView> listTreaties(TreatyStatus status) {
        UUID tenantId = TenantContext.get();
        List<ReinsuranceTreaty> treaties = status == null
            ? treatyRepository.findByTenantIdOrderByEffectiveFromDesc(tenantId)
            : treatyRepository.findByTenantIdAndStatusOrderByEffectiveFromDesc(tenantId, status);
        return treaties.stream().map(this::toTreatyView).toList();
    }

    @Override
    public List<CessionView> listCessionsForPolicy(String policyNumber) {
        UUID tenantId = TenantContext.get();
        return cessionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber).stream()
            .map(this::toCessionView).toList();
    }

    @Override
    public List<ClaimRecoveryView> listRecoveriesForClaim(UUID claimId) {
        UUID tenantId = TenantContext.get();
        return claimRecoveryRepository.findByTenantIdAndClaimId(tenantId, claimId).stream()
            .map(this::toRecoveryView).toList();
    }

    @Override
    @Transactional
    public ClaimRecoveryView confirmRecovery(UUID recoveryId, String confirmedBy) {
        UUID tenantId = TenantContext.get();
        ClaimRecovery recovery = claimRecoveryRepository.findByRecoveryIdAndTenantId(recoveryId, tenantId)
            .orElseThrow(() -> new RecoveryNotFoundException("Recovery " + recoveryId + " not found"));

        // ClaimRecovery.confirm throws InvalidRecoveryStateException on a repeat, which the
        // boundary maps to 409. That is what makes the publish below safe to run unconditionally:
        // control only reaches it on a genuine transition, so a double-confirm can never emit a
        // second RecoveryConfirmed -- M6's I1 finding, where a duplicate reached finaccounting as
        // a double journal entry.
        recovery.confirm(Instant.now(), confirmedBy);
        claimRecoveryRepository.save(recovery);

        eventPublisher.publishEvent(DomainEventEnvelope.of("reinsurance.RecoveryConfirmed", tenantId,
            Map.of("recoveryId", recoveryId, "confirmedAt", recovery.getConfirmedAt().toString())));
        return toRecoveryView(recovery);
    }

    /**
     * Exactly one ACTIVE treaty whose effective window covers {@code issueDate}. Where several
     * match, the newest {@code effective_from} wins and a tie on that is broken by {@code treatyId}
     * so selection is fully deterministic rather than dependent on row order -- an invented rule,
     * flagged, and the reason multi-treaty layering is deferred (see the design spec).
     */
    Optional<ReinsuranceTreaty> selectApplicableTreaty(UUID tenantId, LocalDate issueDate) {
        return treatyRepository.findByTenantIdAndStatusOrderByEffectiveFromDesc(tenantId, TreatyStatus.ACTIVE)
            .stream()
            .filter(t -> t.isActiveOn(issueDate))
            .max(Comparator.comparing(ReinsuranceTreaty::getEffectiveFrom)
                .thenComparing(ReinsuranceTreaty::getTreatyId));
    }

    /** @return the persisted cession, or empty if one already exists for this (policy, treaty) --
     * making a redelivered PolicyIssued a no-op. {@code ux_cession_once} is the real backstop. */
    Optional<Cession> persistCession(UUID tenantId, String policyNumber, ReinsuranceTreaty treaty,
                                      CessionCalculator.CededAmounts amounts) {
        if (cessionRepository.existsByTenantIdAndPolicyNumberAndTreatyId(tenantId, policyNumber, treaty.getTreatyId())) {
            return Optional.empty();
        }
        Cession cession = new Cession(tenantId, policyNumber, treaty.getTreatyId(),
            amounts.cededRisk(), amounts.riskCurrency(), amounts.cededPremium(), amounts.premiumCurrency());
        cessionRepository.save(cession);
        return Optional.of(cession);
    }

    /** @return the persisted recovery, or empty if one already exists for this (claim, treaty). */
    Optional<ClaimRecovery> persistRecovery(UUID tenantId, UUID claimId, UUID treatyId,
                                             BigDecimal amount, String currency, String createdBy) {
        if (claimRecoveryRepository.existsByTenantIdAndClaimIdAndTreatyId(tenantId, claimId, treatyId)) {
            return Optional.empty();
        }
        ClaimRecovery recovery = new ClaimRecovery(tenantId, claimId, treatyId, amount, currency, createdBy);
        claimRecoveryRepository.save(recovery);
        return Optional.of(recovery);
    }

    private TreatyView toTreatyView(ReinsuranceTreaty t) {
        return new TreatyView(t.getTreatyId(), t.getReinsurerName(), t.getTreatyType(), t.getStatus(),
            t.getRetentionLimitAmount(), t.getRetentionLimitCurrency(), t.getCessionPercent(),
            t.getEffectiveFrom(), t.getEffectiveTo());
    }

    private CessionView toCessionView(Cession c) {
        return new CessionView(c.getCessionId(), c.getPolicyNumber(), c.getTreatyId(),
            c.getCededAmount(), c.getCededCurrency(), c.getCededPremiumAmount(), c.getCededPremiumCurrency());
    }

    private ClaimRecoveryView toRecoveryView(ClaimRecovery r) {
        return new ClaimRecoveryView(r.getRecoveryId(), r.getClaimId(), r.getTreatyId(),
            r.getRecoverableAmount(), r.getRecoverableCurrency(), r.getConfirmedAt());
    }
}
