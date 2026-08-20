package tz.co.nlolo.lifeplatform.regreporting.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.regreporting.domain.ClaimDimension;
import tz.co.nlolo.lifeplatform.regreporting.domain.ClaimsMovement;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.ClaimDimensionRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.ClaimsMovementRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Maintains {@code regreporting.claim_dimension} and {@code regreporting.claims_movement} off the
 * claim lifecycle events (regreporting/V2 sections 5-6). Mechanics and bean-naming rationale: see
 * {@link PolicyEventListener}.
 *
 * <p><b>The {@code UNKNOWN} sentinel.</b> {@code ClaimApproved}/{@code Rejected}/{@code Settled}
 * carry only a {@code claimId} (never {@code claimType} -- verified against asyncapi-events.yaml),
 * so every movement they cause needs {@code claim_dimension}, a row written only by {@code
 * ClaimRegistered}. When that lookup misses, the movement is attributed to {@link
 * ProjectionSupport#UNKNOWN_CLAIM_TYPE} rather than dropped. {@code claim_dimension.claim_type}'s
 * CHECK does not admit {@code "UNKNOWN"} (regreporting/V2 section 5), which is exactly why the
 * sentinel is written to {@code claims_movement.claim_type} instead -- that column carries no such
 * CHECK, deliberately (verified against regreporting/V2's DDL before relying on it).
 */
@Component("regreportingClaimsEventListener")
public class ClaimsEventListener {

    private static final Logger log = LoggerFactory.getLogger(ClaimsEventListener.class);

    private static final String EVENT_PROCESSING_FAILED_COUNTER = "lifeplatform_regreporting_event_processing_failed_total";

    private final ClaimDimensionRepository claimDimensionRepository;
    private final ClaimsMovementRepository claimsMovementRepository;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ClaimsEventListener(ClaimDimensionRepository claimDimensionRepository,
                                ClaimsMovementRepository claimsMovementRepository,
                                MeterRegistry meterRegistry,
                                PlatformTransactionManager transactionManager) {
        this.claimDimensionRepository = claimDimensionRepository;
        this.claimsMovementRepository = claimsMovementRepository;
        this.meterRegistry = meterRegistry;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "claims.ClaimRegistered" -> withTenant(envelope, this::handleClaimRegistered);
            case "claims.ClaimApproved" -> withTenant(envelope, this::handleClaimApproved);
            case "claims.ClaimRejected" -> withTenant(envelope, this::handleClaimRejected);
            case "claims.ClaimSettled" -> withTenant(envelope, this::handleClaimSettled);
            default -> { /* not regreporting-relevant here */ }
        }
    }

    private void withTenant(DomainEventEnvelope<?> envelope, Consumer<Map<String, Object>> handler) {
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            requiresNewTransactionTemplate.executeWithoutResult(status -> handler.accept(payload));
        } catch (Exception e) {
            meterRegistry.counter(EVENT_PROCESSING_FAILED_COUNTER, "eventType", envelope.eventType()).increment();
            log.error("regreporting failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    private void handleClaimRegistered(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        UUID claimId = (UUID) payload.get("claimId");
        String policyNumber = (String) payload.get("policyNumber");
        String claimType = (String) payload.get("claimType");
        String dateOfEvent = (String) payload.get("dateOfEvent");

        // ClaimRegistered is the only event in the lifecycle that carries claimType -- the
        // dimension row every later lifecycle event's movement depends on.
        if (claimDimensionRepository.findByTenantIdAndClaimId(tenantId, claimId).isEmpty()) {
            claimDimensionRepository.save(new ClaimDimension(tenantId, claimId, claimType, policyNumber));
        }

        String period = ProjectionSupport.quarterOfDate(dateOfEvent);
        ClaimsMovement movement = claimsMovementRepository
            .findByTenantIdAndPeriodAndClaimType(tenantId, period, claimType)
            .orElseGet(() -> new ClaimsMovement(tenantId, period, claimType, ProjectionSupport.UNKNOWN_CURRENCY));
        movement.applyRegistered();
        claimsMovementRepository.save(movement);
    }

    private void handleClaimApproved(Map<String, Object> payload) {
        UUID claimId = (UUID) payload.get("claimId");
        @SuppressWarnings("unchecked")
        Map<String, Object> approvedAmount = (Map<String, Object>) payload.get("approvedAmount");
        BigDecimal amount = new BigDecimal((String) approvedAmount.get("amount"));
        String currency = (String) approvedAmount.get("currencyCode");
        // ClaimApproved carries no business timestamp (verified against asyncapi-events.yaml) --
        // today's quarter, per the period-derivation rule's fallback.
        applyAmountMovement(claimId, "claims.ClaimApproved", ProjectionSupport.currentQuarter(),
            amount, currency, ClaimsMovement::applyApproved);
    }

    private void handleClaimRejected(Map<String, Object> payload) {
        UUID claimId = (UUID) payload.get("claimId");
        // ClaimRejected carries no business timestamp and no amount at all -- today's quarter and
        // the UNKNOWN_CURRENCY fallback if a fresh row must be created.
        applyCountOnlyMovement(claimId, "claims.ClaimRejected", ProjectionSupport.currentQuarter());
    }

    private void handleClaimSettled(Map<String, Object> payload) {
        UUID claimId = (UUID) payload.get("claimId");
        @SuppressWarnings("unchecked")
        Map<String, Object> settledAmount = (Map<String, Object>) payload.get("settledAmount");
        BigDecimal amount = new BigDecimal((String) settledAmount.get("amount"));
        String currency = (String) settledAmount.get("currencyCode");
        String period = ProjectionSupport.quarterOfInstant((String) payload.get("settledAt"));
        applyAmountMovement(claimId, "claims.ClaimSettled", period, amount, currency, ClaimsMovement::applySettled);
    }

    private String resolveClaimType(UUID tenantId, UUID claimId, String eventType) {
        return claimDimensionRepository.findByTenantIdAndClaimId(tenantId, claimId)
            .map(ClaimDimension::getClaimType)
            .orElseGet(() -> {
                log.warn("{} for claim {} has no claim_dimension row -- attributing to the UNKNOWN "
                    + "claim type sentinel rather than dropping the movement", eventType, claimId);
                return ProjectionSupport.UNKNOWN_CLAIM_TYPE;
            });
    }

    private void applyAmountMovement(UUID claimId, String eventType, String period, BigDecimal amount,
                                      String currency, BiConsumer<ClaimsMovement, BigDecimal> applier) {
        UUID tenantId = TenantContext.get();
        String claimType = resolveClaimType(tenantId, claimId, eventType);
        ClaimsMovement movement = claimsMovementRepository
            .findByTenantIdAndPeriodAndClaimType(tenantId, period, claimType)
            .orElseGet(() -> new ClaimsMovement(tenantId, period, claimType, currency));
        applier.accept(movement, amount);
        claimsMovementRepository.save(movement);
    }

    private void applyCountOnlyMovement(UUID claimId, String eventType, String period) {
        UUID tenantId = TenantContext.get();
        String claimType = resolveClaimType(tenantId, claimId, eventType);
        ClaimsMovement movement = claimsMovementRepository
            .findByTenantIdAndPeriodAndClaimType(tenantId, period, claimType)
            .orElseGet(() -> new ClaimsMovement(tenantId, period, claimType, ProjectionSupport.UNKNOWN_CURRENCY));
        movement.applyRejected();
        claimsMovementRepository.save(movement);
    }
}
