package tz.co.nlolo.lifeplatform.billing.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Mirrors policy.application.UnderwritingDecisionEventListener's mechanics exactly: AFTER_COMMIT
 * (the producer's write must be durable before billing reacts), a brand-new REQUIRES_NEW
 * transaction (a plain REQUIRED call here would silently join the already-committed producer
 * transaction and never actually commit), and TenantContext save/set/restore (this listener runs
 * synchronously on the SAME thread as whatever called policy.PolicyApiImpl, so an unconditional
 * clear() would wipe a caller's own still-in-use context).
 */
@Component
public class PolicyEventListener {

    private static final Logger log = LoggerFactory.getLogger(PolicyEventListener.class);

    private final BillingApiImpl billingApiImpl;
    private final PolicyApi policyApi;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PolicyEventListener(BillingApiImpl billingApiImpl, PolicyApi policyApi, PlatformTransactionManager transactionManager) {
        this.billingApiImpl = billingApiImpl;
        this.policyApi = policyApi;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "policy.PolicyIssued" -> withTenant(envelope, this::handlePolicyIssued);
            case "policy.PolicyEndorsed" -> withTenant(envelope, this::handlePolicyEndorsed);
            case "policy.PolicySuspended" -> withTenant(envelope, this::handlePolicySuspended);
            case "policy.PolicyResumed" -> withTenant(envelope, this::handlePolicyResumed);
            case "policy.EnrolmentAccepted" -> withTenant(envelope, this::handleEnrolmentAccepted);
            default -> { /* not billing-relevant */ }
        }
    }

    private void withTenant(DomainEventEnvelope<?> envelope, java.util.function.Consumer<Map<String, Object>> handler) {
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            requiresNewTransactionTemplate.executeWithoutResult(status -> handler.accept(payload));
        } catch (Exception e) {
            log.error("billing failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    private void handlePolicyIssued(Map<String, Object> payload) {
        String policyNumber = (String) payload.get("policyNumber");
        UUID productVersionId = (UUID) payload.get("productVersionId");
        LocalDate issueDate = LocalDate.parse((String) payload.get("issueDate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> premium = (Map<String, Object>) payload.get("premium");
        BigDecimal premiumAmount = new BigDecimal((String) premium.get("amount"));
        String premiumCurrency = (String) premium.get("currencyCode");
        String premiumFrequency = (String) payload.get("premiumFrequency");

        // A single-premium contract is not billed on a cycle, so it gets no schedule and no
        // invoices generated ahead of it.
        //
        // Without this guard a credit-life master policy produced a BillingSchedule and twelve
        // PremiumInvoice rows for whatever premium figure the caller of issueGroupScheme
        // happened to type. Those then fell due, aged into arrears, and dunned the lender for
        // money the contract never asked for. Its real premium arrives per accepted enrolment
        // file -- see the policy.EnrolmentAccepted branch below.
        //
        // Keyed on the FREQUENCY and not on the product category, deliberately. Billing has no
        // business knowing what credit life is, and a category test would miss the next
        // single-premium product while this catches it.
        if ("SINGLE".equals(premiumFrequency)) {
            log.info("Policy {} is single-premium -- no billing schedule; its premium is raised "
                + "when there is something to charge for", policyNumber);
            return;
        }

        billingApiImpl.generateScheduleForNewPolicy(TenantContext.get(), policyNumber, productVersionId,
            issueDate, premiumAmount, premiumCurrency, premiumFrequency);
    }

    private void handlePolicyEndorsed(Map<String, Object> payload) {
        String policyNumber = (String) payload.get("policyNumber");
        // See BillingApiImpl.regenerateScheduleForEndorsement's own comment: no M3 endorsement
        // type changes premium terms, so this is a documented no-op call for M4, not a missing
        // feature -- the listener structure is complete and correct for when one does.
        billingApiImpl.regenerateScheduleForEndorsement(TenantContext.get(), policyNumber, null, null);
    }

    private void handlePolicySuspended(Map<String, Object> payload) {
        String policyNumber = (String) payload.get("policyNumber");
        billingApiImpl.pauseScheduleForSuspension(TenantContext.get(), policyNumber);
    }

    /**
     * One accepted enrolment file, one invoice, for the sum of the borrowers it enrolled.
     *
     * <p>This is the whole money side of credit life. The master policy is never billed (see
     * the SINGLE guard in {@code handlePolicyIssued}); its premium arrives file by file, and
     * the file-to-invoice correspondence is what a reconciliation argument with a lender is
     * actually about.
     */
    private void handleEnrolmentAccepted(Map<String, Object> payload) {
        UUID submissionId = (UUID) payload.get("submissionId");
        String policyNumber = (String) payload.get("policyNumber");
        @SuppressWarnings("unchecked")
        Map<String, Object> premium = (Map<String, Object>) payload.get("premium");
        BigDecimal amount = new BigDecimal((String) premium.get("amount"));

        // A file whose every row was rejected enrols nobody and earns nothing. It must not
        // raise a zero invoice: chk_premium_invoice_amount_positive would refuse it, and a
        // zero charge is not a thing to send a lender in any case.
        if (amount.signum() == 0) {
            log.info("Enrolment submission {} on policy {} enrolled nobody -- no invoice raised",
                submissionId, policyNumber);
            return;
        }

        // Derived from the submission's OWN acceptance instant, never from this consumer's
        // clock. ux_premium_invoice_per_submission must include due_date because
        // premium_invoice is partitioned on it, so the guarantee against double-charging on a
        // redelivered event holds only if a redelivery recomputes the identical date.
        LocalDate acceptedOn = Instant.parse((String) payload.get("acceptedAt"))
            .atZone(ZoneOffset.UTC).toLocalDate();

        billingApiImpl.raiseSinglePremiumInvoice(TenantContext.get(), policyNumber, submissionId,
            amount, (String) premium.get("currencyCode"), acceptedOn);
    }

    private void handlePolicyResumed(Map<String, Object> payload) {
        String policyNumber = (String) payload.get("policyNumber");
        PolicyView policyView = policyApi.getPolicy(policyNumber);
        billingApiImpl.resumeScheduleAfterSuspension(TenantContext.get(), policyNumber, policyView.productVersionId());
    }
}
