package tz.co.nlolo.lifeplatform.accumulation.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The policy lifecycle, as it affects an account. Task 7 adds the closing events. */
// Explicit bean name: several modules declare a PolicyEventListener, and a duplicate default name
// fails application startup.
@Component("accumulationPolicyEventListener")
public class PolicyEventListener {

    private final AccumulationApiImpl api;
    private final EnvelopeRunner runner;

    public PolicyEventListener(AccumulationApiImpl api, EnvelopeRunner runner) {
        this.api = api;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "policy.PolicyIssued" -> runner.run(envelope, p -> api.openIfAccountVersion(
                (String) p.get("policyNumber"), (UUID) p.get("productVersionId"),
                LocalDate.parse((String) p.get("issueDate"))));
            case "policy.PolicyReinstated" -> runner.run(envelope, p ->
                api.reopenOnReinstatement((String) p.get("policyNumber")));
            case "policy.AccountSurrenderApproved" -> runner.run(envelope, p -> api.closeForSurrender(
                (String) p.get("policyNumber"), UUID.fromString((String) p.get("surrenderRequestId")),
                (String) p.get("payeeRef"), new BigDecimal((String) p.get("surrenderChargePercent")),
                (String) p.get("approvedBy"),
                LocalDate.ofInstant(Instant.parse((String) p.get("approvedAt")), BillingEventListener.CIVIL_ZONE)));
            case "policy.PolicyCancelledFreeLook" -> runner.run(envelope, p ->
                api.closeForFreeLook((String) p.get("policyNumber"), (String) p.get("cancelledBy")));
            default -> { /* not ours */ }
        }
    }
}
