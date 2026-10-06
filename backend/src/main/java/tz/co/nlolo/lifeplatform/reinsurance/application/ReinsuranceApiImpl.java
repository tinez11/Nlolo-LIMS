package tz.co.nlolo.lifeplatform.reinsurance.application;

import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reinsurance.api.BordereauNotFoundException;
import tz.co.nlolo.lifeplatform.reinsurance.api.BordereauView;
import tz.co.nlolo.lifeplatform.reinsurance.api.CessionView;
import tz.co.nlolo.lifeplatform.reinsurance.api.ClaimRecoveryView;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceValidationException;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyNotFoundException;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyStatus;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyUtilisationView;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyView;
import tz.co.nlolo.lifeplatform.reinsurance.domain.Cession;
import tz.co.nlolo.lifeplatform.reinsurance.domain.CessionCalculator;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ClaimRecovery;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceTreaty;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.CessionRepository;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.ClaimRecoveryRepository;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.ReinsuranceTreatyRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
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
    private final Bordereaux bordereaux;
    private final BordereauJob bordereauJob;

    public ReinsuranceApiImpl(ReinsuranceTreatyRepository treatyRepository,
                               CessionRepository cessionRepository,
                               ClaimRecoveryRepository claimRecoveryRepository,
                               Bordereaux bordereaux,
                               BordereauJob bordereauJob) {
        this.treatyRepository = treatyRepository;
        this.cessionRepository = cessionRepository;
        this.claimRecoveryRepository = claimRecoveryRepository;
        this.bordereaux = bordereaux;
        this.bordereauJob = bordereauJob;
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
        // IFRS 17 I3c (user answer Q3): every treaty states its commission not contingent on claims; 0 is a real answer.
        if (request.commissionPercent() == null) {
            throw new ReinsuranceValidationException("State the treaty's commission not contingent on claims, as a "
                + "percent of the ceded premium -- 0 when the reinsurer pays none");
        }
        if (request.commissionPercent().signum() < 0 || request.commissionPercent().compareTo(new BigDecimal("100")) > 0) {
            throw new ReinsuranceValidationException("Commission percent must be from 0 to 100");
        }
        if (request.xolAnnualPremium() != null) {
            if (request.treatyType() != TreatyType.XOL) {
                throw new ReinsuranceValidationException("Only an XOL treaty carries a flat annual premium; a "
                    + request.treatyType() + " treaty's premium is its share of each policy's premium");
            }
            if (request.xolAnnualPremium().signum() <= 0) {
                throw new ReinsuranceValidationException("An XOL annual premium must be greater than 0");
            }
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
            request.cessionPercent(), request.commissionPercent(), request.xolAnnualPremium(),
            request.effectiveFrom(), request.effectiveTo(), createdBy);
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
        return cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber).stream()
            .map(this::toCessionView).toList();
    }

    @Override
    public Page<CessionView> listCessionsForTreaty(UUID treatyId, Pageable pageable) {
        UUID tenantId = TenantContext.get();
        // 404s cross-tenant before anything is listed, so a treaty in another tenant cannot be
        // probed for its cession count -- the same anti-enumeration order every per-entity read
        // on this platform uses.
        getTreaty(treatyId);
        return cessionRepository.findByTenantIdAndTreatyIdOrderByCreatedAtDesc(tenantId, treatyId, pageable)
            .map(this::toCessionView);
    }

    @Override
    public TreatyUtilisationView getTreatyUtilisation(UUID treatyId) {
        UUID tenantId = TenantContext.get();
        TreatyView treaty = getTreaty(treatyId);

        CessionRepository.TreatyTotals totals = cessionRepository.totalsForTreaty(tenantId, treatyId);
        long count = totals.getCessionCount();
        BigDecimal ceded = totals.getCededAmount();
        BigDecimal cededPremium = totals.getCededPremium();

        // The currency comes from the TREATY's own retention limit, not from a cession row: with
        // no cessions there is no row to read one off, and a treaty with no currency on screen
        // reads as a failure to load rather than as "nothing ceded yet". Every cession against a
        // treaty is in that treaty's currency by construction.
        String currency = treaty.retentionLimitCurrency();
        return new TreatyUtilisationView(treatyId, count, ceded, currency, cededPremium, currency);
    }

    @Override
    public List<ClaimRecoveryView> listRecoveriesForClaim(UUID claimId) {
        UUID tenantId = TenantContext.get();
        return claimRecoveryRepository.findByTenantIdAndClaimId(tenantId, claimId).stream()
            .map(this::toRecoveryView).toList();
    }

    @Override
    public List<BordereauView> listBordereaux(UUID treatyId) {
        getTreaty(treatyId);
        return bordereaux.list(TenantContext.get(), treatyId);
    }

    @Override
    public BordereauView getBordereau(UUID bordereauId) {
        return bordereaux.find(TenantContext.get(), bordereauId)
            .orElseThrow(() -> new BordereauNotFoundException("Bordereau " + bordereauId + " not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public BordereauView previewBordereau(UUID treatyId, YearMonth period) {
        UUID tenantId = TenantContext.get();
        ReinsuranceTreaty treaty = treatyRepository.findByTreatyIdAndTenantId(treatyId, tenantId)
            .orElseThrow(() -> new TreatyNotFoundException("Treaty " + treatyId + " not found"));
        return bordereauJob.preview(tenantId, treaty, period);
    }

    /**
     * Exactly one ACTIVE treaty whose effective window covers {@code issueDate}. Where several
     * match, the newest {@code effective_from} wins and a tie on that is broken by {@code
     * createdAt} (most recently authored wins) so selection is fully deterministic rather than
     * dependent on row order -- an invented rule, flagged, and the reason multi-treaty layering is
     * deferred (see the design spec). {@code createdAt} is a semantically meaningful tie-break
     * ("most recently authored wins"); the treaty's random UUID primary key, used previously, has
     * no relation to authoring order and was arbitrary.
     */
    Optional<ReinsuranceTreaty> selectApplicableTreaty(UUID tenantId, LocalDate issueDate) {
        return treatyRepository.findByTenantIdAndStatusOrderByEffectiveFromDesc(tenantId, TreatyStatus.ACTIVE)
            .stream()
            .filter(t -> t.isActiveOn(issueDate))
            .max(Comparator.comparing(ReinsuranceTreaty::getEffectiveFrom)
                .thenComparing(ReinsuranceTreaty::getCreatedAt));
    }

    /** @return the persisted cession, or empty if one already exists for this policy -- making a
     * redelivered PolicyIssued a no-op. {@code ux_cession_once} (keyed on (tenant, policy) only,
     * matching the one-treaty-per-policy design) is the real backstop. */
    Optional<Cession> persistCession(UUID tenantId, String policyNumber, ReinsuranceTreaty treaty,
                                      CessionCalculator.CededAmounts amounts) {
        if (cessionRepository.existsByTenantIdAndPolicyNumber(tenantId, policyNumber)) {
            return Optional.empty();
        }
        Cession cession = new Cession(tenantId, policyNumber, treaty.getTreatyId(),
            amounts.cededRisk(), amounts.riskCurrency(), amounts.cededPremium(), amounts.premiumCurrency(),
            amounts.premiumShare());
        cessionRepository.save(cession);
        return Optional.of(cession);
    }

    /** @return the persisted recovery, or empty if one already exists for this claim. {@code
     * ux_recovery_once} (keyed on (tenant, claim) only, matching the one-treaty-per-policy design)
     * is the real backstop. */
    Optional<ClaimRecovery> persistRecovery(UUID tenantId, UUID claimId, UUID treatyId,
                                             BigDecimal amount, String currency, String createdBy) {
        if (claimRecoveryRepository.existsByTenantIdAndClaimId(tenantId, claimId)) {
            return Optional.empty();
        }
        ClaimRecovery recovery = new ClaimRecovery(tenantId, claimId, treatyId, amount, currency, createdBy);
        claimRecoveryRepository.save(recovery);
        return Optional.of(recovery);
    }

    private TreatyView toTreatyView(ReinsuranceTreaty t) {
        return new TreatyView(t.getTreatyId(), t.getReinsurerName(), t.getTreatyType(), t.getStatus(),
            t.getRetentionLimitAmount(), t.getRetentionLimitCurrency(), t.getCessionPercent(),
            t.getEffectiveFrom(), t.getEffectiveTo(), t.getCommissionPercent(), t.getXolAnnualPremium());
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
