package tz.co.nlolo.lifeplatform.finaccounting.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.PolicyClassificationView;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Keeps finaccounting's IFRS 17 classification of every contract (I2, spec §7.2) -- from the events, so posting never
 * has to ask another module.
 *
 * <ul>
 *   <li>{@code policy.PolicyIssued}: classified at issue, from the sale facts policy stamped on the contract. An event
 *       from before I2 carries no portfolio and is left alone (the development backfill classified those).</li>
 *   <li>{@code policy.AnnuityVested}: a pension classified IFRS 9 in deferral becomes a NEW contract at vesting (G-08)
 *       -- an immediate annuity, in the cohort of the vesting year, with the same bucket, channel and branch. Any
 *       other contract keeps the classification it was issued with.</li>
 * </ul>
 *
 * <p>Mechanics and bean-naming rationale: see {@link BillingEventListener}.
 */
@Component("finaccountingPolicyClassificationEventListener")
public class PolicyClassificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(PolicyClassificationEventListener.class);
    private static final String EVENT_PROCESSING_FAILED_COUNTER = "lifeplatform_finaccounting_event_processing_failed_total";

    private final PolicyClassifier classifier;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNew;

    public PolicyClassificationEventListener(PolicyClassifier classifier, MeterRegistry meterRegistry,
                                             PlatformTransactionManager transactionManager) {
        this.classifier = classifier;
        this.meterRegistry = meterRegistry;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "policy.PolicyIssued" -> withTenant(envelope, this::onIssued);
            case "policy.AnnuityVested" -> withTenant(envelope, this::onVested);
            default -> { }
        }
    }

    private void withTenant(DomainEventEnvelope<?> envelope, Consumer<Map<String, Object>> handler) {
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            requiresNew.executeWithoutResult(status -> handler.accept(payload));
        } catch (Exception e) {
            meterRegistry.counter(EVENT_PROCESSING_FAILED_COUNTER, "eventType", envelope.eventType()).increment();
            log.error("finaccounting failed to classify on {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    private void onIssued(Map<String, Object> payload) {
        String portfolio = string(payload.get("portfolioCode"));
        String policyNumber = string(payload.get("policyNumber"));
        if (portfolio == null) {
            log.info("policy.PolicyIssued for {} carries no portfolio (issued before IFRS 17 I2); not classified",
                policyNumber);
            return;
        }
        LocalDate issued = LocalDate.parse(string(payload.get("issueDate")));
        Object cohort = payload.get("cohortYear");
        classifier.classify(new PolicyClassifier.Input(TenantContext.get(), policyNumber, "ISSUE", issued, portfolio,
            cohort != null ? Integer.parseInt(cohort.toString()) : issued.getYear(),
            string(payload.get("profitabilityBucket")), string(payload.get("measurementModelOverride")),
            uuid(payload.get("productId")), uuid(payload.get("productVersionId")),
            string(payload.get("salesChannel")), string(payload.get("branchCode"))));
    }

    private void onVested(Map<String, Object> payload) {
        String policyNumber = string(payload.get("policyNumber"));
        LocalDate vestedOn = LocalDate.parse(string(payload.get("vestedOn")));
        Optional<PolicyClassificationView> atIssue = classifier.atIssue(TenantContext.get(), policyNumber);
        if (atIssue.isEmpty() || !"IFRS9".equals(atIssue.get().measurementModel())) {
            return;   // not a pension in deferral: the contract keeps the classification it was issued with
        }
        PolicyClassificationView issued = atIssue.get();
        classifier.classify(new PolicyClassifier.Input(TenantContext.get(), policyNumber, "VESTING", vestedOn, "IANN",
            vestedOn.getYear(), issued.profitabilityBucket(), null, null, null, issued.salesChannel(),
            issued.branchCode()));
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuid(Object value) {
        return value == null ? null : value instanceof UUID u ? u : UUID.fromString(value.toString());
    }
}
