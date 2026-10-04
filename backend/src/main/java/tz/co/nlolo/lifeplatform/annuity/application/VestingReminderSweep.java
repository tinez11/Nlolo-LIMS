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
 * Reminds every pension holder whose vesting date is within 90 days (product step 5 D2). Daily; a
 * minute on the local profile. Selection across tenants, ids only, each reminded under its own
 * tenant -- the vesting sweep's shape.
 */
@Component
public class VestingReminderSweep {

    private static final Logger log = LoggerFactory.getLogger(VestingReminderSweep.class);

    private final VestingRepository vestings;
    private final VestingReminders reminders;

    public VestingReminderSweep(VestingRepository vestings, VestingReminders reminders) {
        this.vestings = vestings;
        this.reminders = reminders;
    }

    @Scheduled(fixedDelayString = "${annuity.vesting-reminder-interval-ms:86400000}",
        initialDelayString = "${annuity.vesting-reminder-interval-ms:86400000}")
    public void drain() {
        LocalDate today = LocalDate.now(AnnuityApiImpl.CIVIL_ZONE);
        for (Object[] row : vestings.remindersDue(today)) {
            String policyNumber = (String) row[0];
            UUID previous = TenantContext.getOrNull();
            TenantContext.set((UUID) row[1]);
            try {
                reminders.remind(policyNumber, today);
            } catch (Exception e) {
                log.error("Failed to remind pension {} of its vesting", policyNumber, e);
            } finally {
                if (previous != null) {
                    TenantContext.set(previous);
                } else {
                    TenantContext.clear();
                }
            }
        }
    }
}
