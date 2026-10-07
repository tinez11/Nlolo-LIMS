package tz.co.nlolo.lifeplatform.reinsurance.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reinsurance.domain.Cession;
import tz.co.nlolo.lifeplatform.reinsurance.domain.CessionCalculator;
import tz.co.nlolo.lifeplatform.reinsurance.domain.PolicyProjection;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceTreaty;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.ReinsurancePolicyProjectionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Consumes {@code policy.PolicyActivated} and cedes risk to the applicable treaty.
 *
 * <p>{@code policy} is not in this module's {@code allowedDependencies}, so everything here comes
 * from the event payload plus this module's own {@code policy_projection}. Mechanics copied from
 * {@code distribution/application/PolicyEventListener}: {@code AFTER_COMMIT} (policy's write must
 * be durable before reinsurance reacts), one reusable {@code PROPAGATION_REQUIRES_NEW}
 * {@link TransactionTemplate} (a plain {@code @Transactional} called from an AFTER_COMMIT callback
 * silently joins the already-committed producer transaction and never commits -- empirically
 * confirmed on this project), and {@code TenantContext} save/set/restore rather than an
 * unconditional clear, since this runs synchronously on the producer's own thread.
 *
 * <p>ONE transaction per handler: nothing here calls another module, so there is no foreign-module
 * failure to phase-separate against (contrast {@code claims.application.PaymentEventListener},
 * whose two-phase split exists because a {@code PolicyApi} call could otherwise roll back a
 * settled claim).
 *
 * <p><b>Bean name is explicit</b> -- {@code billing} and {@code distribution} each already declare
 * a {@code PolicyEventListener}, and a third unqualified {@code @Component} with the same simple
 * name is a bean-name collision that fails context startup for the whole suite.
 */
@Component("reinsurancePolicyEventListener")
public class PolicyEventListener {

    private static final Logger log = LoggerFactory.getLogger(PolicyEventListener.class);

    /** Final review (I5): the catch-all in {@link #withTenant} used to be log-only, so a listener
     * failure (a malformed payload, an unexpected runtime exception) was invisible to anything but
     * someone reading logs after the fact. Same naming convention as {@code
     * claims.application.PaymentEventListener}'s counters; tagged with the event type so a failure
     * can be attributed to a specific producer without grepping logs first. */
    private static final String EVENT_PROCESSING_FAILED_COUNTER = "lifeplatform_reinsurance_event_processing_failed_total";

    private final ReinsurancePolicyProjectionRepository policyProjectionRepository;
    private final ReinsuranceApiImpl reinsuranceApiImpl;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTransactionTemplate;
    private final CoverPeriods coverPeriods;

    private static final java.time.ZoneId CIVIL = java.time.ZoneId.of("Africa/Dar_es_Salaam");

    public PolicyEventListener(ReinsurancePolicyProjectionRepository policyProjectionRepository,
                                ReinsuranceApiImpl reinsuranceApiImpl,
                                ApplicationEventPublisher eventPublisher,
                                MeterRegistry meterRegistry,
                                PlatformTransactionManager transactionManager,
                                CoverPeriods coverPeriods) {
        this.coverPeriods = coverPeriods;
        this.policyProjectionRepository = policyProjectionRepository;
        this.reinsuranceApiImpl = reinsuranceApiImpl;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            // PolicyActivated, not PolicyIssued. PolicyIssued now means "the contract record
            // exists" -- an offer awaiting its first premium. Ceding a proposal would put risk
            // the platform is not carrying onto a treaty, and pay reinsurance premium for it.
            case "policy.PolicyActivated" -> withTenant(envelope, this::handlePolicyActivated);
            // IFRS 17 I3c: when a ceded policy is on risk, and paying, decides what each monthly bordereau charges.
            case "policy.PolicyLapsed" -> withTenant(envelope, p -> closeCover(p, "lapsedAt"));
            // A settled death claim closes a policy as surrendered, so this is also a death.
            case "policy.PolicySurrendered" -> withTenant(envelope, p -> closeCover(p, "surrenderedAt"));
            case "policy.PolicyMatured" -> withTenant(envelope, p -> closeCover(p, "maturedAt"));
            case "policy.PolicyExpired" -> withTenant(envelope, p -> closeCover(p, "expiredAt"));
            case "policy.AnnuityEnded" -> withTenant(envelope, p -> closeCover(p, "endedAt"));
            case "policy.PolicyCancelledFreeLook" -> withTenant(envelope, this::voidCover);
            case "policy.PolicyReinstated" -> withTenant(envelope, this::reopenCover);
            case "policy.PolicyMadePaidUp" -> withTenant(envelope, p -> endPremiums(p, "madePaidUpAt"));
            case "policy.PremiumsEnded" -> withTenant(envelope, p -> endPremiums(p, "after"));
            case "policy.PremiumRestated" -> withTenant(envelope, this::restatePremium);
            default -> { /* not reinsurance-relevant */ }
        }
    }

    private void closeCover(Map<String, Object> payload, String dateKey) {
        coverPeriods.close(TenantContext.get(), (String) payload.get("policyNumber"), civilDate(payload.get(dateKey)));
    }

    private void voidCover(Map<String, Object> payload) {
        coverPeriods.voidAll(TenantContext.get(), (String) payload.get("policyNumber"));
    }

    private void reopenCover(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String policyNumber = (String) payload.get("policyNumber");
        Optional<PolicyProjection> projection = policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber);
        if (projection.isEmpty() || projection.get().isScheme()) {
            return;
        }
        coverPeriods.open(tenantId, policyNumber, civilDate(payload.get("reinstatedAt")));
        projection.get().resumePremiums();
        policyProjectionRepository.save(projection.get());
    }

    private void endPremiums(Map<String, Object> payload, String dateKey) {
        policyProjectionRepository.findByTenantIdAndPolicyNumber(TenantContext.get(), (String) payload.get("policyNumber"))
            .ifPresent(projection -> {
                projection.endPremiums(civilDate(payload.get(dateKey)));
                policyProjectionRepository.save(projection);
            });
    }

    private void restatePremium(Map<String, Object> payload) {
        if (!(payload.get("premiumAmount") instanceof Map<?, ?> premium)) {
            return;
        }
        policyProjectionRepository.findByTenantIdAndPolicyNumber(TenantContext.get(), (String) payload.get("policyNumber"))
            .ifPresent(projection -> {
                projection.restatePremium(new BigDecimal(String.valueOf(premium.get("amount"))));
                policyProjectionRepository.save(projection);
            });
    }

    /** policy's events carry their dates as a day or as an instant; either way, the day in Dar es Salaam. */
    static LocalDate civilDate(Object value) {
        if (value == null) {
            return LocalDate.now(CIVIL);
        }
        String s = value.toString();
        if (s.length() == 10) {
            return LocalDate.parse(s);
        }
        try {
            return java.time.Instant.parse(s).atZone(CIVIL).toLocalDate();
        } catch (java.time.format.DateTimeParseException e) {
            return java.time.OffsetDateTime.parse(s).atZoneSameInstant(CIVIL).toLocalDate();
        }
    }

    private void withTenant(DomainEventEnvelope<?> envelope, Consumer<Map<String, Object>> handler) {
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            requiresNewTransactionTemplate.executeWithoutResult(status -> handler.accept(payload));
        } catch (Exception e) {
            meterRegistry.counter(EVENT_PROCESSING_FAILED_COUNTER, "eventType", envelope.eventType()).increment();
            log.error("reinsurance failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    private void handlePolicyActivated(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String policyNumber = (String) payload.get("policyNumber");
        UUID productId = (UUID) payload.get("productId");
        LocalDate issueDate = LocalDate.parse((String) payload.get("issueDate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> sumAssured = (Map<String, Object>) payload.get("sumAssured");
        BigDecimal sumAssuredAmount = new BigDecimal((String) sumAssured.get("amount"));
        String sumAssuredCurrency = (String) sumAssured.get("currencyCode");
        @SuppressWarnings("unchecked")
        Map<String, Object> premium = (Map<String, Object>) payload.get("premium");
        BigDecimal premiumAmount = new BigDecimal((String) premium.get("amount"));
        String premiumCurrency = (String) premium.get("currencyCode");

        // The projection is written FIRST and unconditionally: it is the only place this module
        // ever learns this policy's sum assured and premium, and recovery (ClaimEventListener)
        // needs it later even if no treaty applies today.
        // A group funeral scheme (2026-10-07) is category FUNERAL with an association's families behind its sum assured:
        // recorded here as GROUP_FUNERAL so every scheme guard in this module -- cession, bordereau, recovery -- holds.
        String productCategory = "FUNERAL".equals(payload.get("productCategory"))
                && Boolean.TRUE.equals(payload.get("groupScheme"))
            ? PolicyProjection.GROUP_FUNERAL : (String) payload.get("productCategory");
        if (policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber).isEmpty()) {
            policyProjectionRepository.save(new PolicyProjection(tenantId, policyNumber, productId,
                sumAssuredAmount, sumAssuredCurrency, premiumAmount, premiumCurrency, issueDate,
                // STORED, not merely logged by the guard below. That guard protects this listener
                // only. ClaimEventListener has its own route to a treaty -- XOL, which needs no
                // cession -- and treated "has a projection row" as "was in scope", which a scheme
                // always satisfies. See db-migrations/reinsurance/V4.
                productCategory,
                // IFRS 17 I3c: what turns the premium into the month's ceded premium.
                (String) payload.get("premiumFrequency")));
        }

        // A GROUP SCHEME IS NOT ONE RISK, AND MUST NOT BE CEDED AS ONE.
        //
        // Everything below this point treats sumAssured as a single life's cover and tests it
        // against the treaty's retention. On a scheme that figure is the TOTAL of a schedule --
        // four hundred borrowers, or a whole employer's staff -- so ceding it as one risk would
        // cede a 600,000,000 total against a retention meant for one person, when the real
        // exposure is four hundred independent lives of about 10,000,000 each. Surplus treaties
        // retain and cede PER LIFE; nothing here knows how to do that.
        //
        // Until 2026-09-22 a scheme never reached this listener at all: issueGroupScheme
        // activated the policy without publishing PolicyActivated, which is the defect whose fix
        // routes schemes here for the first time. That fix was about commission, and it must not
        // quietly start ceding reinsurance on a basis nobody has chosen.
        //
        // THE CLIENT ANSWERED THIS ON 2026-09-22, and the answer is why the skip stays rather
        // than being replaced by a calculation. Three things they said, none of which this
        // module can currently express:
        //
        //   1. A treaty specifies the CLASSES OF BUSINESS COVERED, its exclusions, limits and
        //      retention arrangements. ReinsuranceTreaty carries a reinsurer, a type, a
        //      retention limit, a cession percent and two dates -- there is no class of
        //      business and no exclusions, so "is group business even covered by this treaty?"
        //      cannot be asked, let alone answered.
        //   2. A real treaty may carry SPECIAL PROVISIONS FOR GROUP SCHEMES: free cover limits,
        //      automatic acceptance limits, a maximum exposure per scheme, aggregation rules.
        //      None of those exist here. Ceding a scheme today would apply an individual-life
        //      retention to a book total with no per-scheme cap and no aggregation at all.
        //   3. Where a treaty cedes a PROPORTION of the risk, the ceded amount FOLLOWS THE
        //      INSURED AMOUNT. Credit-life cover declines every month, and a Cession is one
        //      immutable row written once at activation with a fixed cededAmount. Following a
        //      declining sum assured is not a parameter this model is missing; it is a
        //      different shape of record -- derived on demand the way claimableCover is, or
        //      restated over time.
        //
        // So the hold is the correct behaviour, not a temporary convenience: the platform
        // cannot represent the treaty terms that would govern this cession. Enabling it needs
        // the treaty model extended first.
        if (!PolicyProjection.isScheme(productCategory)) {
            // IFRS 17 I3c: on risk from activation -- the month it falls in is the first a bordereau charges.
            coverPeriods.open(tenantId, policyNumber, payload.get("activatedAt") == null ? issueDate
                : civilDate(payload.get("activatedAt")));
        }
        if (PolicyProjection.isScheme(productCategory)) {
            log.info("Policy {} is a {} scheme -- its sum assured is the total of a member "
                + "schedule, not one life, so it is NOT ceded. Group cession needs a per-life "
                + "basis this module does not have.", policyNumber, productCategory);
            return;
        }

        Optional<ReinsuranceTreaty> maybeTreaty = reinsuranceApiImpl.selectApplicableTreaty(tenantId, issueDate);
        if (maybeTreaty.isEmpty()) {
            log.info("Policy {} issued with no ACTIVE reinsurance treaty covering {} -- nothing ceded",
                policyNumber, issueDate);
            return;
        }
        ReinsuranceTreaty treaty = maybeTreaty.get();

        Optional<CessionCalculator.CededAmounts> maybeAmounts = CessionCalculator.calculate(
            treaty, sumAssuredAmount, sumAssuredCurrency, premiumAmount, premiumCurrency);
        if (maybeAmounts.isEmpty()) {
            log.info("Treaty {} ({}) cedes nothing for policy {} -- an XOL treaty, a sum assured within "
                + "retention, or a currency mismatch", treaty.getTreatyId(), treaty.getTreatyType(), policyNumber);
            return;
        }

        reinsuranceApiImpl.persistCession(tenantId, policyNumber, treaty, maybeAmounts.get())
            .ifPresent(cession -> publishCessionRecorded(tenantId, cession));
    }

    /** Matches asyncapi-events.yaml's CessionRecordedPayload. LinkedHashMap, not Map.of:
     * cededPremium is legitimately null when a ceded premium rounds to zero. */
    private void publishCessionRecorded(UUID tenantId, Cession cession) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cessionId", cession.getCessionId());
        payload.put("policyNumber", cession.getPolicyNumber());
        payload.put("treatyId", cession.getTreatyId());
        payload.put("cededAmount", Map.of("amount", cession.getCededAmount().toPlainString(),
                                           "currencyCode", cession.getCededCurrency()));
        payload.put("cededPremium", cession.getCededPremiumAmount() == null ? null
            : Map.of("amount", cession.getCededPremiumAmount().toPlainString(),
                     "currencyCode", cession.getCededPremiumCurrency()));
        eventPublisher.publishEvent(DomainEventEnvelope.of("reinsurance.CessionRecorded", tenantId, payload));
    }
}
