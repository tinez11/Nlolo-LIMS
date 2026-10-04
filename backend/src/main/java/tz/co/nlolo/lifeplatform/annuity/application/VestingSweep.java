package tz.co.nlolo.lifeplatform.annuity.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.annuity.infrastructure.VestingRepository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Vests every pension due on or before today (product step 5 D2): on its target date, or on the date
 * staff instructed. Built like benefitpayout's AnnuityRollForward and for its reasons: selection is SQL
 * across tenants, ids only; each pension is vested under its own tenant in its own transaction. A held
 * one stays ACCUMULATING and is retried the next day. The initial delay keeps it out of short test
 * contexts (step 0's lesson); application-local.yml runs it every minute.
 */
@Component
public class VestingSweep {

    private static final Logger log = LoggerFactory.getLogger(VestingSweep.class);

    private final VestingRepository vestings;
    private final AnnuityVesting vesting;

    public VestingSweep(VestingRepository vestings, AnnuityVesting vesting) {
        this.vestings = vestings;
        this.vesting = vesting;
    }

    @Scheduled(fixedDelayString = "${annuity.vesting-sweep-interval-ms:86400000}",
        initialDelayString = "${annuity.vesting-sweep-interval-ms:86400000}")
    public void drain() {
        LocalDate today = LocalDate.now(AnnuityApiImpl.CIVIL_ZONE);
        for (Object[] row : vestings.vestingsDue(today)) {
            vestOne((String) row[0], (UUID) row[1], today);
        }
    }

    /** One pension, under its tenant. A failure after the account was touched rolled back; it is held here. */
    void vestOne(String policyNumber, UUID tenantId, LocalDate today) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            String outcome = vesting.vest(policyNumber, today);
            log.info("Vesting sweep: {} {}", policyNumber, outcome);
        } catch (Exception e) {
            log.error("Vesting of {} failed and was rolled back", policyNumber, e);
            try {
                vesting.hold(policyNumber, "Vesting failed and was rolled back: " + e.getMessage());
            } catch (Exception holdFailure) {
                log.error("Could not record the hold on {}", policyNumber, holdFailure);
            }
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
