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
            case "policy.PolicyExpired" -> withTenant(envelope, this::handlePolicyExpired);
            case "policy.PolicyMadePaidUp" -> withTenant(envelope, this::handlePolicyMadePaidUp);
            case "policy.PolicySurrendered" -> withTenant(envelope, this::handlePolicySurrendered);
            // A free-look cancellation ends the billing schedule as a surrender does, but from INCEPTION: every
            // unsettled invoice is waived, not only the future ones. What the customer is paid back is benefitpayout's.
            case "policy.PolicyCancelledFreeLook" -> withTenant(envelope, this::handlePolicyCancelledFreeLook);
            case "policy.PolicyMatured" -> withTenant(envelope, this::handlePolicyMatured);
            case "policy.EnrolmentAccepted" -> withTenant(envelope, this::handleEnrolmentAccepted);
            case "policy.GroupMemberExited" -> withTenant(envelope, this::handleGroupMemberExited);
            // A deferred annuity (product step 5 D2): vesting stops contributions; a deferral may extend them.
            case "policy.AnnuityVested" -> withTenant(envelope, this::handleAnnuityVested);
            case "policy.PremiumPayingTermRestated" -> withTenant(envelope, this::handlePremiumPayingTermRestated);
            // Family funeral cover: a life added or ended, or the anniversary, changed the premium.
            case "policy.PremiumRestated" -> withTenant(envelope, this::handlePremiumRestated);
            case "policy.PremiumsEnded" -> withTenant(envelope, p -> billingApiImpl.endBillingAfter(TenantContext.get(),
                (String) p.get("policyNumber"), LocalDate.parse((String) p.get("after")), (String) p.get("reason")));
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

        // A single-premium contract is not billed on a cycle, so it never gets a schedule --
        // billing_schedule's own CHECK admits only MONTHLY, QUARTERLY and ANNUALLY. What it
        // gets INSTEAD depends on where its premium comes from, and those are two different
        // contracts that share one frequency.
        //
        // Without this branch a credit-life master policy produced a BillingSchedule and twelve
        // PremiumInvoice rows for whatever premium figure the caller of issueGroupScheme
        // happened to type. Those then fell due, aged into arrears, and dunned the lender for
        // money the contract never asked for. Its real premium arrives per accepted enrolment
        // file -- see the policy.EnrolmentAccepted branch below.
        //
        // Keyed on the FREQUENCY and not on the product category, deliberately. Billing has no
        // business knowing what credit life is, and a category test would miss the next
        // single-premium product while this catches it.
        if ("SINGLE".equals(premiumFrequency)) {
            // Where the premium COMES FROM, which is the whole distinction, and a structural
            // fact about the contract rather than a product name -- which is why policy states
            // it on the event and billing reads it instead of inferring a category it should
            // not know about.
            //
            // Not "is it a group scheme". That was this flag's first shape and it was wrong: a
            // family or employer scheme paid once for a year of cover is a group scheme whose
            // premium does NOT arrive file by file, and keying on group-ness would have issued
            // it and then charged nobody -- the same silence this branch exists to end.
            boolean premiumPerEnrolment = Boolean.TRUE.equals(payload.get("premiumPerEnrolment"));
            if (premiumPerEnrolment) {
                log.info("Policy {} is billed per enrolment file -- no schedule and no charge "
                    + "against the policy itself", policyNumber);
                return;
            }

            // A RETAIL single premium, which nothing charged until now. The policy was written,
            // the premium was rated and stored on it, and no invoice was ever raised against
            // it: the customer owed money the platform never asked for, and the cover ran
            // regardless. One charge, due the day cover begins.
            // Its cover runs to the day before maturity; the ledger earns a PAA premium over it (IFRS 17 I3a).
            String maturityRaw = (String) payload.get("maturityDate");
            LocalDate coverEndsOn = maturityRaw != null ? LocalDate.parse(maturityRaw).minusDays(1) : null;
            billingApiImpl.raisePolicyInceptionInvoice(TenantContext.get(), policyNumber,
                productVersionId, issueDate, premiumAmount, premiumCurrency, coverEndsOn);
            return;
        }

        // The end of the contract, so billing stops there instead of a fixed twelve months in.
        // Absent or null on a policy that does not term -- whole life, an annually renewable
        // scheme -- which the schedule reads as "no end".
        String payingUntilRaw = (String) payload.get("premiumPayingUntil");
        LocalDate premiumPayingUntil = payingUntilRaw != null ? LocalDate.parse(payingUntilRaw) : null;

        billingApiImpl.generateScheduleForNewPolicy(TenantContext.get(), policyNumber, productVersionId,
            issueDate, premiumAmount, premiumCurrency, premiumFrequency, premiumPayingUntil);
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

    /*
     * A policy that ends stops being billed, and the invoices rolling billing already raised for cover it will no
     * longer give are waived (and their receivable reversed): due on or after the day it ended. Until 2026-10-05 only
     * the schedule was terminated, so a surrendered policy kept up to a year of future invoices DUE.
     */

    private void handlePolicyExpired(Map<String, Object> payload) {
        String policyNumber = (String) payload.get("policyNumber");
        Object maturityDate = payload.get("maturityDate");
        LocalDate from = maturityDate != null ? LocalDate.parse((String) maturityDate) : civilDate(payload.get("expiredAt"));
        billingApiImpl.endBillingForTermination(TenantContext.get(), policyNumber, from,
            "The policy expired on " + from + "; no further premium is due");
    }

    private void handlePolicyMadePaidUp(Map<String, Object> payload) {
        String policyNumber = (String) payload.get("policyNumber");
        // Paid-up means no premium falls due again.
        LocalDate from = civilDate(payload.get("madePaidUpAt"));
        billingApiImpl.endBillingForTermination(TenantContext.get(), policyNumber, from,
            "The policy was made paid-up on " + from + "; no further premium is due");
    }

    private void handlePolicyMatured(Map<String, Object> payload) {
        String policyNumber = (String) payload.get("policyNumber");
        LocalDate from = civilDate(payload.get("maturedAt"));
        billingApiImpl.endBillingForTermination(TenantContext.get(), policyNumber, from,
            "The policy matured on " + from + "; no further premium is due");
    }

    private void handlePolicyCancelledFreeLook(Map<String, Object> payload) {
        billingApiImpl.endBillingForTermination(TenantContext.get(), (String) payload.get("policyNumber"), null,
            "The policy was cancelled in its free-look period, from inception; no premium is due");
    }

    /** The East Africa civil date of an event's instant -- never its UTC date, which is a day early from 00:00 to 03:00. */
    private static LocalDate civilDate(Object instant) {
        java.time.Instant at = instant != null ? java.time.Instant.parse(String.valueOf(instant)) : java.time.Instant.now();
        return at.atZone(java.time.ZoneId.of("Africa/Dar_es_Salaam")).toLocalDate();
    }

    private void handleAnnuityVested(Map<String, Object> payload) {
        billingApiImpl.endForVesting(TenantContext.get(), (String) payload.get("policyNumber"),
            LocalDate.parse((String) payload.get("vestedOn")));
    }

    private void handlePremiumPayingTermRestated(Map<String, Object> payload) {
        billingApiImpl.restatePremiumPayingUntil(TenantContext.get(), (String) payload.get("policyNumber"),
            LocalDate.parse((String) payload.get("premiumPayingUntil")));
    }

    private void handlePremiumRestated(Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> premium = (Map<String, Object>) payload.get("premiumAmount");
        billingApiImpl.restatePremium(TenantContext.get(), (String) payload.get("policyNumber"),
            new BigDecimal((String) premium.get("amount")), LocalDate.parse((String) payload.get("effectiveFrom")),
            (String) payload.get("reason"));
    }

    private void handlePolicySurrendered(Map<String, Object> payload) {
        String policyNumber = (String) payload.get("policyNumber");
        // A surrendered policy is off risk; no further premium is due. Fires for a claim-terminated policy too,
        // which should equally stop being invoiced.
        LocalDate from = civilDate(payload.get("surrenderedAt"));
        billingApiImpl.endBillingForTermination(TenantContext.get(), policyNumber, from,
            "The policy ended on " + from + "; no further premium is due");
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

        // Each borrower's cover, as policy stated it: passed through to the invoice event for the ledger (IFRS 17 I3a).
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> covers = payload.get("covers") instanceof java.util.List<?> list
            ? (java.util.List<Map<String, Object>>) list : java.util.List.of();
        billingApiImpl.raiseSinglePremiumInvoice(TenantContext.get(), policyNumber, submissionId,
            amount, (String) premium.get("currencyCode"), acceptedOn, covers);
    }

    /**
     * A loan ended early, so the premium it was charged for cover it never got comes back.
     *
     * <p>Billing does no arithmetic here, deliberately. The unearned share is a function of the
     * loan's term and what that loan was actually charged, both of which live in {@code policy}
     * — so policy computes it and puts it on the event, and this raises the credit.
     *
     * <p>Every decision about WHETHER to refund is therefore made upstream too, and shows up
     * here as the simple absence of {@code premiumUnearned}: a settled claim earned its premium
     * in full, an opening-schedule member was never charged, and a loan that ran its full term
     * owes nothing back. All three are ordinary, none is an error, and none produces a zero
     * credit — {@code chk} on amount would refuse one anyway.
     */
    private void handleGroupMemberExited(Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> unearned = (Map<String, Object>) payload.get("premiumUnearned");
        if (unearned == null) {
            return;
        }
        billingApiImpl.creditUnearnedPremium(TenantContext.get(),
            (String) payload.get("policyNumber"),
            (UUID) payload.get("policyMemberId"),
            (UUID) payload.get("enrolmentSubmissionId"),
            new BigDecimal((String) unearned.get("amount")),
            (String) unearned.get("currencyCode"),
            (String) payload.get("reason"),
            LocalDate.parse((String) payload.get("leftOn")));
    }

    private void handlePolicyResumed(Map<String, Object> payload) {
        String policyNumber = (String) payload.get("policyNumber");
        PolicyView policyView = policyApi.getPolicy(policyNumber);
        billingApiImpl.resumeScheduleAfterSuspension(TenantContext.get(), policyNumber, policyView.productVersionId());
    }
}
