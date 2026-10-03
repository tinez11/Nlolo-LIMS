package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.benefitpayout.infrastructure.PayoutStreamRepository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Keeps every open annuity stream expanded a year ahead (product step 5): an annuity has no maturity
 * date to expand to, so its instalments exist as rows only a horizon at a time.
 *
 * <p>Built like billing's InvoiceRollForward and for its reasons: the selection is SQL across
 * tenants, ids only; each stream is rolled under its own tenant with its row locked, so two runs
 * cannot both expand it; and the (policy, row, date) unique index turns a race into an error rather
 * than a second payment. The initial delay keeps it out of short test contexts (step 0's lesson).
 */
@Component
public class AnnuityRollForward {

    private static final Logger log = LoggerFactory.getLogger(AnnuityRollForward.class);
    private static final ZoneId CIVIL_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final PayoutStreamRepository streams;
    private final BenefitPayoutApiImpl api;

    public AnnuityRollForward(PayoutStreamRepository streams, BenefitPayoutApiImpl api) {
        this.streams = streams;
        this.api = api;
    }

    @Scheduled(fixedDelayString = "${benefitpayout.annuity-rollforward-interval-ms:86400000}",
        initialDelayString = "${benefitpayout.annuity-rollforward-interval-ms:86400000}")
    public void drain() {
        LocalDate horizon = LocalDate.now(CIVIL_ZONE).plusMonths(BenefitPayoutApiImpl.ANNUITY_HORIZON_MONTHS);
        for (Object[] row : streams.findAnnuityStreamsDueForRollForward(horizon)) {
            rollOne((UUID) row[0], (UUID) row[1], horizon);
        }
    }

    void rollOne(UUID streamId, UUID tenantId, LocalDate horizon) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            api.rollForward(streamId, horizon);
        } catch (Exception e) {
            log.error("Failed to roll annuity stream {} forward in tenant {}", streamId, tenantId, e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
