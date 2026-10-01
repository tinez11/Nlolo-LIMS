package tz.co.nlolo.lifeplatform.policy.application;

import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.infrastructure.PolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Expires termed policies whose maturity date has passed and which pay nothing at the end.
 *
 * <p><b>Why a Spring {@code @Scheduled} rather than pg_cron</b>, when this platform reserves cron
 * for its cross-tenant business-state sweeps: expiry has consumers. Moving a policy to EXPIRED must
 * terminate its billing schedule and reach audit, and only a published {@code policy.PolicyExpired}
 * event does that. {@code policy.sweep_expired_offers()} is the standing warning about the
 * alternative -- it closes offers with a raw UPDATE and is on record regretting the silence. So the
 * selection is SQL ({@code policy.policies_due_to_expire()}) and the transition is Java, exactly
 * the split {@code communication.OfferReminderDispatcher} uses.
 *
 * <p>Exactly-once comes from the aggregate, not from running in one place: {@code expirePolicy}
 * reads the policy, and its {@code @Version} makes the winning write exclusive while the guard on
 * {@code isClosed()} makes the loser a no-op that publishes nothing. Two instances draining the
 * same list is therefore safe.
 */
@Component
public class CoverExpiryDrain {

    private static final Logger log = LoggerFactory.getLogger(CoverExpiryDrain.class);

    private final PolicyRepository policyRepository;
    private final PolicyApi policyApi;

    // PolicyApi, not the concrete PolicyApiImpl: expirePolicy is on the published interface, and
    // injecting the interface lets a context that @MockBeans PolicyApi still create this bean --
    // the concrete type is not a candidate when the API is mocked or JDK-proxied.
    public CoverExpiryDrain(PolicyRepository policyRepository, PolicyApi policyApi) {
        this.policyRepository = policyRepository;
        this.policyApi = policyApi;
    }

    /**
     * Hourly by default, first run one interval after startup. The window is measured in days, so
     * this only ever moves an expiry a few hours earlier than a daily run would; hourly keeps it
     * cheap while surviving a missed run. The initial delay keeps it from firing inside a
     * short-lived test context, where the selection function may not be installed.
     */
    @Scheduled(fixedDelayString = "${policy.cover-expiry-drain-interval-ms:3600000}",
        initialDelayString = "${policy.cover-expiry-drain-interval-ms:3600000}")
    public void drainExpiredCover() {
        for (Object[] due : policyRepository.findDueToExpireAcrossTenants()) {
            String policyNumber = (String) due[0];
            UUID tenantId = (UUID) due[1];
            expireOne(policyNumber, tenantId);
        }
    }

    /**
     * Expire one policy under its own tenant context. The tenant is set FIRST and around
     * everything, so the read and the write below run under RLS against exactly this tenant's
     * rows even though the id arrived from a cross-tenant lookup. One bad row must not stop the
     * queue -- a policy that fails to expire should not cost every other its expiry.
     */
    void expireOne(String policyNumber, UUID tenantId) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            policyApi.expirePolicy(policyNumber);
        } catch (Exception e) {
            log.error("Failed to expire policy {} in tenant {}", policyNumber, tenantId, e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
