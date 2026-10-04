package tz.co.nlolo.lifeplatform.annuity.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.util.Map;

/**
 * Policy's issue and free-look cancellation, into the annuity contract (product step 5). Filters on
 * the event type first; onIssued then asks product before touching this module's table.
 */
// Explicit bean name: several modules declare a PolicyEventListener, and a duplicate default name
// fails application startup.
@Component("annuityPolicyEventListener")
public class PolicyEventListener {

    private final AnnuityApiImpl api;
    private final AnnuityEnvelopeRunner runner;

    public PolicyEventListener(AnnuityApiImpl api, AnnuityEnvelopeRunner runner) {
        this.api = api;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        String type = envelope.eventType();
        if ("policy.PolicyIssued".equals(type)) {
            runner.run(envelope, e -> {
                Map<String, Object> p = Payloads.of(e.payload());
                api.onIssued((String) p.get("policyNumber"), Payloads.uuid(p.get("productVersionId")),
                    Payloads.uuid(p.get("underwritingCaseId")));
            });
        } else if ("policy.AnnuityEnded".equals(type)) {
            runner.run(envelope, e -> api.onPolicyEnded((String) Payloads.of(e.payload()).get("policyNumber")));
        } else if ("policy.PolicyCancelledFreeLook".equals(type)) {
            runner.run(envelope, e -> {
                String policyNumber = (String) Payloads.of(e.payload()).get("policyNumber");
                if (api.isAnnuity(policyNumber)) {
                    api.onFreeLookCancelled(policyNumber);
                }
            });
        } else if ("policy.PolicySurrendered".equals(type)) {
            // A surrender (D2). A settled claim closes a policy with this event too, carrying its
            // claimId; a death before vesting is the claim approval's to record, so that one is skipped.
            runner.run(envelope, e -> {
                Map<String, Object> p = Payloads.of(e.payload());
                if (p.get("claimId") == null) {
                    api.onSurrendered((String) p.get("policyNumber"));
                }
            });
        } else if ("policy.PolicyNotTakenUp".equals(type)) {
            runner.run(envelope, e -> api.onNotTakenUp((String) Payloads.of(e.payload()).get("policyNumber")));
        }
    }
}
