package tz.co.nlolo.lifeplatform.annuity.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.util.Map;

/**
 * An approved DEATH claim on an annuity (product step 5): survivor, guarantee or end. By envelope --
 * annuity may not depend on claims, which depends on annuity for the claim's ceiling.
 */
@Component("annuityClaimEventListener")
public class ClaimEventListener {

    private final AnnuityApiImpl api;
    private final AnnuityEnvelopeRunner runner;

    public ClaimEventListener(AnnuityApiImpl api, AnnuityEnvelopeRunner runner) {
        this.api = api;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        String type = envelope.eventType();
        // A death reported before vesting holds the vesting until it is decided (D2, plan R6).
        if ("claims.ClaimRegistered".equals(type) || "claims.ClaimRejected".equals(type)) {
            runner.run(envelope, e -> {
                Map<String, Object> p = Payloads.of(e.payload());
                String policyNumber = (String) p.get("policyNumber");
                if (!"DEATH".equals(p.get("claimType")) || !api.isAnnuity(policyNumber)) {
                    return;
                }
                if ("claims.ClaimRegistered".equals(type)) {
                    api.onDeathReported(policyNumber, Payloads.uuid(p.get("claimId")));
                } else {
                    api.onDeathRejected(policyNumber, Payloads.uuid(p.get("claimId")));
                }
            });
            return;
        }
        if (!"claims.ClaimApproved".equals(type)) {
            return;
        }
        runner.run(envelope, e -> {
            Map<String, Object> p = Payloads.of(e.payload());
            if (!"DEATH".equals(p.get("claimType"))) {
                return;
            }
            String policyNumber = (String) p.get("policyNumber");
            if (api.isAnnuity(policyNumber)) {
                api.onDeathApproved(policyNumber, Payloads.uuid(p.get("deceasedPartyId")), Payloads.date(p.get("dateOfEvent")),
                    Payloads.uuid(p.get("claimId")));
            }
        });
    }
}
