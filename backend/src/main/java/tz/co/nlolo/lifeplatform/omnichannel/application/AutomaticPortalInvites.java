package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.omnichannel.api.PortalAccessRefusedException;
import tz.co.nlolo.lifeplatform.omnichannel.infrastructure.PortalAccessRepository;

import java.util.Map;
import java.util.UUID;

/**
 * A new client's portal login, sent the moment their first premium starts their cover (2026-11-08, the user: phase 3
 * of the public quote link, for every new client) -- by SMS first, as every invite now goes.
 *
 * <p>Only an offer paid for: cover that started at issue (a migrated or converted book) is not a new client, and is
 * left to staff. Nobody already holding a login, open or revoked, is invited again -- a revoked login was closed on
 * purpose. A client the invite cannot reach (no phone or email, a company) is skipped and logged; staff can still
 * invite them by hand. Never fails the payment that started the cover: it runs after that has committed.
 */
@Component("omnichannelAutomaticPortalInvites")
public class AutomaticPortalInvites {

    private static final Logger log = LoggerFactory.getLogger(AutomaticPortalInvites.class);
    /** Who the access record says invited them. */
    public static final String INVITED_BY = "automatic: first premium";

    private final PortalAccessService portalAccess;
    private final PortalAccessRepository accesses;
    private final TransactionTemplate requiresNew;
    private final boolean enabled;

    public AutomaticPortalInvites(PortalAccessService portalAccess, PortalAccessRepository accesses,
                                  PlatformTransactionManager transactionManager,
                                  @org.springframework.beans.factory.annotation.Value("${app.portal.auto-invite:true}") boolean enabled) {
        this.enabled = enabled;
        this.portalAccess = portalAccess;
        this.accesses = accesses;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!enabled || !"policy.PolicyActivated".equals(envelope.eventType())) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.payload();
        if (!Boolean.TRUE.equals(payload.get("byFirstPremium")) || Boolean.TRUE.equals(payload.get("groupScheme"))) {
            return;
        }
        UUID partyId = (UUID) payload.get("policyholderPartyId");
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            requiresNew.executeWithoutResult(status -> {
                if (accesses.findByTenantIdAndPartyId(envelope.tenantId(), partyId).isPresent()) {
                    return;
                }
                portalAccess.invite(partyId, INVITED_BY);
                log.info("Portal invite sent to party {} on the first premium of {}", partyId, payload.get("policyNumber"));
            });
        } catch (PortalAccessRefusedException e) {
            log.info("No automatic portal invite for party {}: {}", partyId, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Automatic portal invite failed for party {} on {}", partyId, payload.get("policyNumber"), e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
