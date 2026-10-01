package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.time.LocalDate;

/**
 * A death claim ends the living benefits.
 *
 * <p>Every payout this module schedules is paid <em>while the life assured lives</em> -- that is
 * what makes it a survival, income, maturity or premium-return benefit rather than a claim. Once a
 * death claim is approved the contract pays its death benefit instead, and anything still ahead on
 * the schedule is no longer owed. Leaving it would send a survival benefit to a person the insurer
 * has just been told is dead.
 *
 * <p>Dated from the death, not from today: a claim registered months later must not leave the
 * instalments that fell due in between standing. Cancellation stops at APPROVED, so money that has
 * already gone out is reported as paid rather than rewritten.
 */
// An explicit bean name: claims, finaccounting and regreporting each declare a ClaimEventListener,
// and two beans sharing one default name fail application startup outright.
@Component("benefitpayoutClaimEventListener")
public class ClaimEventListener {

    private final BenefitPayoutApiImpl api;
    private final EnvelopeRunner runner;

    public ClaimEventListener(BenefitPayoutApiImpl api, EnvelopeRunner runner) {
        this.api = api;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"claims.ClaimApproved".equals(envelope.eventType())) {
            return;
        }
        runner.run(envelope, p -> {
            if (!"DEATH".equals(p.get("claimType"))) {
                return;
            }
            api.cancelFuture((String) p.get("policyNumber"),
                LocalDate.parse((String) p.get("dateOfEvent")),
                "Death claim approved");
        });
    }
}
