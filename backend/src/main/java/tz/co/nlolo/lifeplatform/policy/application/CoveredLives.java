package tz.co.nlolo.lifeplatform.policy.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.domain.InstalmentDates;
import tz.co.nlolo.lifeplatform.product.api.FuneralLifeInput;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuoteRefusedException;
import tz.co.nlolo.lifeplatform.product.api.PremiumFrequency;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.policy.api.CoveredLifeView;
import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;
import tz.co.nlolo.lifeplatform.policy.domain.CoveredLife;
import tz.co.nlolo.lifeplatform.policy.domain.FuneralPolicy;
import tz.co.nlolo.lifeplatform.policy.domain.Policy;
import tz.co.nlolo.lifeplatform.policy.infrastructure.CoveredLifeRepository;
import tz.co.nlolo.lifeplatform.policy.infrastructure.FuneralPolicyRepository;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlan;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuote;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuoteLine;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.underwriting.api.FuneralApplication;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Every operation on a funeral policy's lives (family funeral cover): written at issue, read for the
 * policy screen and claims. Its own component so PolicyApiImpl does not grow past its 3,300 lines; called
 * only from inside PolicyApiImpl's transactions and only once the product says FUNERAL.
 */
@Component
class CoveredLives {

    private static final ZoneId CIVIL_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final CoveredLifeRepository lives;
    private final FuneralPolicyRepository funeralPolicies;
    private final ProductApi productApi;
    private final PartyApi partyApi;
    private final ApplicationEventPublisher eventPublisher;

    CoveredLives(CoveredLifeRepository lives, FuneralPolicyRepository funeralPolicies, ProductApi productApi,
                 PartyApi partyApi, ApplicationEventPublisher eventPublisher) {
        this.lives = lives;
        this.funeralPolicies = funeralPolicies;
        this.productApi = productApi;
        this.partyApi = partyApi;
        this.eventPublisher = eventPublisher;
    }

    boolean isFuneral(Policy policy) {
        return productApi.resolveFuneralPlan(policy.getProductVersionId()).funeral();
    }

    /**
     * The family the case was accepted on, one row per life: the main member (the life assured, a party)
     * first, then each dependant from the quote's lines in the order listed. Every life's cover starts with
     * the policy's.
     */
    void recordAtIssue(Policy policy, FuneralApplication application, String recordedBy) {
        UUID tenantId = TenantContext.get();
        if (funeralPolicies.findById(policy.getPolicyNumber()).isPresent()) {
            throw new InvalidPolicyStateException("Policy " + policy.getPolicyNumber() + " already has its covered lives");
        }
        FuneralQuote quote = application.quote();
        if (quote == null) {
            throw new InvalidPolicyStateException("The family on case " + policy.getUnderwritingCaseId()
                + " no longer prices on plan " + application.planCode() + "; re-record the application");
        }
        funeralPolicies.saveAndFlush(new FuneralPolicy(tenantId, policy.getPolicyNumber(), application.planCode()));
        LocalDate coverStart = policy.getCommencementDate() != null ? policy.getCommencementDate() : policy.getIssueDate();
        UUID mainMemberId = policy.getLifeAssuredPartyId() != null ? policy.getLifeAssuredPartyId() : policy.getPolicyholderPartyId();
        PartyDetailView mainMember = partyApi.getPartyDetail(mainMemberId);

        // The quote lists the main member first, then the dependants in the application's order.
        List<FuneralQuoteLine> lines = quote.lines();
        FuneralQuoteLine main = lines.get(0);
        lives.save(new CoveredLife(tenantId, policy.getPolicyNumber(), FuneralRole.MAIN_MEMBER.name(), mainMember.displayName(),
            mainMember.dateOfBirth(), mainMember.sex() != null ? mainMember.sex().name() : null, null, false, mainMemberId,
            main.benefit(), main.yearlyPremium(), main.age(), coverStart, recordedBy));
        for (int i = 0; i < application.dependants().size(); i++) {
            FuneralApplication.Life dependant = application.dependants().get(i);
            FuneralQuoteLine line = lines.get(i + 1);
            lives.save(new CoveredLife(tenantId, policy.getPolicyNumber(), dependant.role().name(), dependant.fullName(),
                dependant.dateOfBirth(), dependant.sex(), dependant.idNumber(), dependant.student(), null,
                line.benefit(), line.yearlyPremium(), line.age(), coverStart, recordedBy));
        }
    }

    /**
     * A life joins from the next premium date, checked by the product's own rules (role covered, the role's
     * count, entry age on that date) and priced on the family's plan. The premium is restated from then.
     */
    CoveredLifeView add(Policy policy, FuneralApplication.Life life, String addedBy) {
        requireInForce(policy);
        UUID tenantId = TenantContext.get();
        FuneralPolicy funeral = funeralPolicy(policy);
        LocalDate effective = InstalmentDates.nextAfter(policy.getIssueDate(), policy.getPremiumFrequency(), today());
        if (life.fullName() == null || life.fullName().isBlank()) {
            throw new InvalidPolicyStateException("A covered life needs a full name");
        }
        int alreadyInRole = (int) remainingOn(policy, effective).stream()
            .filter(l -> life.role() != null && l.getRole().equals(life.role().name())).count();
        FuneralQuoteLine line;
        try {
            line = productApi.admitFuneralLife(policy.getProductVersionId(), funeral.getPlanCode(),
                new FuneralLifeInput(life.role(), life.fullName(), life.dateOfBirth(), life.student()), alreadyInRole, effective);
        } catch (FuneralQuoteRefusedException e) {
            throw new InvalidPolicyStateException(e.getMessage());
        }
        CoveredLife added = lives.save(new CoveredLife(tenantId, policy.getPolicyNumber(), life.role().name(),
            life.fullName(), life.dateOfBirth(), life.sex(), life.idNumber(), life.student(), null, line.benefit(),
            line.yearlyPremium(), line.age(), effective, addedBy));
        publishLife("policy.CoveredLifeAdded", policy, added, Map.of("coverStart", effective.toString()));
        restate(policy, effective, "A life was added");
        return toView(added, productApi.resolveFuneralPlan(policy.getProductVersionId()));
    }

    /**
     * A dependant comes off cover at the next premium date -- covered to the period already paid for -- and
     * the premium falls from then. The main member is never removed: ending the policy is a different act.
     */
    CoveredLifeView remove(Policy policy, UUID coveredLifeId, String reason, String removedBy) {
        requireInForce(policy);
        CoveredLife life = lifeOn(policy, coveredLifeId);
        if (FuneralRole.MAIN_MEMBER.name().equals(life.getRole())) {
            throw new InvalidPolicyStateException("The main member cannot be removed; end the policy instead");
        }
        if (!life.isActive() || life.getCoverEnd() != null) {
            throw new InvalidPolicyStateException(life.getFullName() + " is already coming off cover");
        }
        LocalDate effective = InstalmentDates.nextAfter(policy.getIssueDate(), policy.getPremiumFrequency(), today());
        life.scheduleEnd(effective, "REMOVED");
        lives.save(life);
        restate(policy, effective, reason == null || reason.isBlank() ? "A life was removed" : "A life was removed: " + reason);
        return toView(life, productApi.resolveFuneralPlan(policy.getProductVersionId()));
    }

    /**
     * The ONE place a funeral premium is recomputed after issue: the yearly premiums of the lives still covered
     * on {@code effective}, turned into an instalment by the product's own arithmetic, and published for
     * billing to restate from that date. Nothing is published when the instalment does not change.
     */
    void restate(Policy policy, LocalDate effective, String reason) {
        java.math.BigDecimal yearly = remainingOn(policy, effective).stream().map(CoveredLife::getYearlyPremium)
            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        if (yearly.signum() == 0) {
            return; // nobody left from then on: the policy closes, and billing ends with it
        }
        java.math.BigDecimal instalment = productApi.funeralInstalment(policy.getProductVersionId(), yearly,
            PremiumFrequency.valueOf(policy.getPremiumFrequency()));
        if (instalment.compareTo(policy.getPremiumAmount()) == 0) {
            return;
        }
        policy.restateFuneralPremium(instalment);
        Map<String, Object> payload = new HashMap<>();
        payload.put("policyNumber", policy.getPolicyNumber());
        payload.put("policyholderPartyId", policy.getPolicyholderPartyId());
        payload.put("productVersionId", policy.getProductVersionId());
        payload.put("premiumAmount", Map.of("amount", instalment.toPlainString(), "currencyCode", policy.getPremiumCurrency()));
        payload.put("effectiveFrom", effective.toString());
        payload.put("reason", reason);
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.PremiumRestated", TenantContext.get(), payload));
    }

    /** Lives that will still be covered on {@code day}: active, and not scheduled off by then. */
    private List<CoveredLife> remainingOn(Policy policy, LocalDate day) {
        return lives.findByPolicy(TenantContext.get(), policy.getPolicyNumber()).stream()
            .filter(l -> l.isActive() && (l.getCoverEnd() == null || l.getCoverEnd().isAfter(day))).toList();
    }

    CoveredLife lifeOn(Policy policy, UUID coveredLifeId) {
        return lives.findByCoveredLifeIdAndTenantId(coveredLifeId, TenantContext.get())
            .filter(l -> l.getPolicyNumber().equals(policy.getPolicyNumber()))
            .orElseThrow(() -> new InvalidPolicyStateException("Covered life " + coveredLifeId + " is not on policy "
                + policy.getPolicyNumber()));
    }

    FuneralPolicy funeralPolicy(Policy policy) {
        return funeralPolicies.findByPolicyNumberAndTenantId(policy.getPolicyNumber(), TenantContext.get())
            .orElseThrow(() -> new InvalidPolicyStateException("Policy " + policy.getPolicyNumber() + " has no covered lives"));
    }

    void publishLife(String eventType, Policy policy, CoveredLife life, Map<String, Object> extra) {
        Map<String, Object> payload = new HashMap<>(extra);
        payload.put("policyNumber", policy.getPolicyNumber());
        payload.put("policyholderPartyId", policy.getPolicyholderPartyId());
        payload.put("coveredLifeId", life.getCoveredLifeId());
        payload.put("fullName", life.getFullName());
        payload.put("role", life.getRole());
        eventPublisher.publishEvent(DomainEventEnvelope.of(eventType, TenantContext.get(), payload));
    }

    private static void requireInForce(Policy policy) {
        if (!"ACTIVE".equals(policy.getStatus()) && !"REINSTATED".equals(policy.getStatus())) {
            throw new InvalidPolicyStateException("Policy " + policy.getPolicyNumber() + " is " + policy.getStatus()
                + "; lives are added and removed only while it is in force");
        }
    }

    static LocalDate today() {
        return LocalDate.now(CIVIL_ZONE);
    }

    List<CoveredLifeView> list(Policy policy) {
        FuneralPlan plan = productApi.resolveFuneralPlan(policy.getProductVersionId());
        return lives.findByPolicy(TenantContext.get(), policy.getPolicyNumber()).stream()
            .map(life -> toView(life, plan)).toList();
    }

    static CoveredLifeView toView(CoveredLife life, FuneralPlan plan) {
        LocalDate waitingEnds = plan.waitingPeriodMonths() != null ? life.getCoverStart().plusMonths(plan.waitingPeriodMonths()) : null;
        return new CoveredLifeView(life.getCoveredLifeId(), FuneralRole.valueOf(life.getRole()), life.getFullName(),
            life.getDateOfBirth(), life.getSex(), life.getIdNumber(), life.isStudent(), life.getPartyId(), life.getBenefit(),
            life.getYearlyPremium(), life.getPricedAtAge(), life.getCoverStart(), waitingEnds, life.getCoverEnd(),
            life.getStatus(), life.getEndReason(), life.getEndedOn());
    }
}
