package tz.co.nlolo.lifeplatform.reinsurance.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reinsurance.domain.Cession;
import tz.co.nlolo.lifeplatform.reinsurance.domain.CessionCalculator;
import tz.co.nlolo.lifeplatform.reinsurance.domain.PolicyProjection;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceTreaty;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.ReinsurancePolicyProjectionRepository;
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
 * Consumes {@code policy.PolicyIssued} and cedes risk to the applicable treaty.
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

    private final ReinsurancePolicyProjectionRepository policyProjectionRepository;
    private final ReinsuranceApiImpl reinsuranceApiImpl;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PolicyEventListener(ReinsurancePolicyProjectionRepository policyProjectionRepository,
                                ReinsuranceApiImpl reinsuranceApiImpl,
                                ApplicationEventPublisher eventPublisher,
                                PlatformTransactionManager transactionManager) {
        this.policyProjectionRepository = policyProjectionRepository;
        this.reinsuranceApiImpl = reinsuranceApiImpl;
        this.eventPublisher = eventPublisher;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "policy.PolicyIssued" -> withTenant(envelope, this::handlePolicyIssued);
            default -> { /* not reinsurance-relevant */ }
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
            log.error("reinsurance failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    private void handlePolicyIssued(Map<String, Object> payload) {
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
        if (policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber).isEmpty()) {
            policyProjectionRepository.save(new PolicyProjection(tenantId, policyNumber, productId,
                sumAssuredAmount, sumAssuredCurrency, premiumAmount, premiumCurrency, issueDate));
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
