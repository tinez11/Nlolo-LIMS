package tz.co.nlolo.lifeplatform.bonus.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.DeclarationRepository;

import java.util.UUID;

/**
 * Attaches every approved declaration whose valuation date has arrived, across tenants, each under
 * its own. MonthEndDrain's shape: SQL selects ids (declarations_due()), Java decides. A declaration
 * approved AFTER its valuation date is picked up on the next run, judged on the status record as at
 * that date -- which is why the record exists.
 */
@Component("bonusDeclarationDrain")
public class DeclarationDrain {

    private static final Logger log = LoggerFactory.getLogger(DeclarationDrain.class);

    private final DeclarationRepository declarations;
    private final BonusApiImpl api;

    public DeclarationDrain(DeclarationRepository declarations, BonusApiImpl api) {
        this.declarations = declarations;
        this.api = api;
    }

    @Scheduled(fixedDelayString = "${bonus.declaration-interval-ms:3600000}",
        initialDelayString = "${bonus.declaration-interval-ms:3600000}")
    public void drain() {
        for (Object[] row : declarations.findDueAcrossTenants()) {
            drainOne((UUID) row[0], (UUID) row[1]);
        }
    }

    /** One declaration to completion, whatever its date: drain() is what applies "due". */
    public void drainOne(UUID declarationId, UUID tenantId) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            // Batches until a batch decides nothing new: then either it completed, or every
            // remaining policy is failing and is logged, and the next run tries again.
            while (api.drainDeclaration(declarationId) > 0) { /* next batch */ }
        } catch (Exception e) {
            log.error("Bonus declaration {} failed in tenant {}", declarationId, tenantId, e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
