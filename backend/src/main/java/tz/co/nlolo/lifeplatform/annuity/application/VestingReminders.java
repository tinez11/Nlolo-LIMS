package tz.co.nlolo.lifeplatform.annuity.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.annuity.api.ContractStatus;
import tz.co.nlolo.lifeplatform.annuity.domain.Vesting;
import tz.co.nlolo.lifeplatform.annuity.domain.VestingInstruction;
import tz.co.nlolo.lifeplatform.annuity.infrastructure.AnnuityContractRepository;
import tz.co.nlolo.lifeplatform.annuity.infrastructure.VestingInstructionRepository;
import tz.co.nlolo.lifeplatform.annuity.infrastructure.VestingRepository;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * The reminders for one pension (product step 5 D2): 90 and 30 days before its vesting date, once
 * each per date -- a deferral re-arms them. The flag and the event commit together, so the AFTER_COMMIT
 * SMS goes out only if the flag stuck. A pension sold within 30 days of vesting gets the 30-day one only.
 */
@Service
public class VestingReminders {

    private final AnnuityContractRepository contracts;
    private final VestingRepository vestings;
    private final VestingInstructionRepository instructions;
    private final PolicyApi policyApi;
    private final ApplicationEventPublisher events;

    public VestingReminders(AnnuityContractRepository contracts, VestingRepository vestings,
                            VestingInstructionRepository instructions, PolicyApi policyApi, ApplicationEventPublisher events) {
        this.contracts = contracts;
        this.vestings = vestings;
        this.instructions = instructions;
        this.policyApi = policyApi;
        this.events = events;
    }

    /** Returns how many reminders were sent (0 or 1). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int remind(String policyNumber, LocalDate today) {
        if (contracts.findById(policyNumber).filter(c -> c.status() == ContractStatus.ACCUMULATING).isEmpty()) {
            return 0;
        }
        Vesting v = vestings.findById(policyNumber).orElse(null);
        if (v == null) {
            return 0;
        }
        LocalDate vestingDate = instructions.findByPolicyNumberAndCurrentTrue(policyNumber)
            .map(VestingInstruction::getVestingDate).orElse(v.getTargetDate());
        long daysAway = ChronoUnit.DAYS.between(today, vestingDate);
        if (daysAway < 0 || daysAway > 90) {
            return 0;
        }
        // The nearer reminder wins: within 30 days only the 30-day one is due, even if the 90 never went.
        int daysBefore = daysAway <= 30 ? 30 : 90;
        if (!v.remind(daysBefore, vestingDate)) {
            return 0;
        }
        vestings.save(v);
        events.publishEvent(DomainEventEnvelope.of("annuity.VestingReminderDue", TenantContext.get(), Map.of(
            "policyNumber", policyNumber,
            "policyholderPartyId", policyApi.getPolicy(policyNumber).policyholderPartyId(),
            "vestingDate", vestingDate.toString(),
            "daysBefore", daysBefore)));
        return 1;
    }
}
