package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The policy lifecycle, as it affects what is owed: issuance makes the schedule exist, a lapse
 * withdraws it, reinstatement brings back what is still ahead, a paid-up conversion shrinks it and
 * a surrender ends it.
 *
 * <p>Envelope-only, so {@code billing}, {@code claims} and {@code payment} never become
 * compile-time dependencies of this module.
 */
// An explicit bean name because five other modules declare a PolicyEventListener, and two beans
// sharing one default name fail application startup outright.
@Component("benefitpayoutPolicyEventListener")
public class PolicyEventListener {

    private final BenefitPayoutApiImpl api;
    private final EnvelopeRunner runner;

    public PolicyEventListener(BenefitPayoutApiImpl api, EnvelopeRunner runner) {
        this.api = api;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "policy.PolicyIssued" -> withTenant(envelope, p -> api.expandForIssuedPolicy(
                (String) p.get("policyNumber"),
                (UUID) p.get("productVersionId"),
                LocalDate.parse((String) p.get("issueDate")),
                p.get("premiumPayingUntil") != null ? LocalDate.parse((String) p.get("premiumPayingUntil")) : null,
                (String) p.get("premiumFrequency")));

            // A lapsed policy is off risk, so nothing dated from the lapse onwards is owed. The
            // rows are CANCELLED rather than deleted, because reinstatement brings them back and
            // because a customer asking why a benefit never arrived deserves an answer.
            case "policy.PolicyLapsed" -> withTenant(envelope, p -> api.cancelFuture(
                (String) p.get("policyNumber"), day(p, "lapsedAt"), BenefitPayoutApiImpl.LAPSE_REASON));

            case "policy.PolicyReinstated" -> withTenant(envelope, p -> api.restoreAfterReinstatement(
                (String) p.get("policyNumber"), day(p, "reinstatedAt")));

            // Reduced cover, not ended cover: the schedule survives, every figure on it shrinks by
            // the same proportion the sum assured did.
            case "policy.PolicyMadePaidUp" -> withTenant(envelope, p -> api.restateForPaidUp(
                (String) p.get("policyNumber"),
                money(p, "paidUpSumAssured"),
                money(p, "originalSumAssured")));

            // The contract is bought back in full. Nothing further is owed under it, including a
            // maturity the customer would otherwise have reached.
            case "policy.PolicySurrendered" -> withTenant(envelope, p -> api.cancelFuture(
                (String) p.get("policyNumber"), BenefitPayoutApiImpl.BEGINNING,
                "Policy surrendered"));

            default -> { /* not ours */ }
        }
    }

    /** An {@code Instant} on the envelope, read as the calendar day the schedule is dated in. */
    private static LocalDate day(Map<String, Object> payload, String key) {
        return Instant.parse((String) payload.get(key)).atZone(ZoneOffset.UTC).toLocalDate();
    }

    /** A {@code {amount, currencyCode}} figure. The currency is the policy's throughout, so only
     *  the amount is needed here -- a restatement is a ratio, not a conversion. */
    private static BigDecimal money(Map<String, Object> payload, String key) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) payload.get(key);
        return new BigDecimal((String) m.get("amount"));
    }

    private void withTenant(DomainEventEnvelope<?> envelope, Consumer<Map<String, Object>> handler) {
        runner.run(envelope, handler);
    }
}
