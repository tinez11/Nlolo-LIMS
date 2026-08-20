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
import java.time.Instant;
import java.time.ZoneOffset;
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
            // ClaimRegistered's period is the envelope's OWN occurredAt (when the claim was really
            // registered/published), not the payload's dateOfEvent (when the underlying loss
            // happened) -- see handleClaimRegistered's javadoc for why those two dates differ and
            // which one this movement must use.
            case "claims.ClaimRegistered" -> withTenant(envelope, payload -> handleClaimRegistered(payload, envelope.occurredAt()));
            // ClaimApproved/ClaimRejected carry NO business timestamp of their own (verified
            // against asyncapi-events.yaml), so they take the envelope's own occurredAt for the
            // same reason ClaimRegistered does -- never LocalDate.now() (M10 final review, I1).
            case "claims.ClaimApproved" -> withTenant(envelope, payload -> handleClaimApproved(payload, envelope.occurredAt()));
            case "claims.ClaimRejected" -> withTenant(envelope, payload -> handleClaimRejected(payload, envelope.occurredAt()));
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
            // Bounded optimistic-lock retry around the WHOLE transaction -- see
            // ProjectionSupport.withOptimisticLockRetry (M10 final review, C1).
            ProjectionSupport.withOptimisticLockRetry(envelope.eventType(), () ->
                requiresNewTransactionTemplate.executeWithoutResult(status -> handler.accept(payload)));
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

    /**
     * {@code period} is derived from {@code envelope.occurredAt()} -- when this event was actually
     * published, i.e. when the claim was administratively registered -- NOT from the payload's own
     * {@code dateOfEvent}, which is the date of the underlying insured loss (e.g. date of death)
     * and can legitimately precede registration by more than one quarter for a late-reported claim.
     * Attributing {@code CLAIMS_REGISTERED} to {@code dateOfEvent} would silently misattribute a
     * late-reported claim's movement to an earlier, possibly already-generated reporting period --
     * a real regulatory-reporting correctness gap, not merely a stylistic difference (final review
     * finding). {@code ClaimSettled} keeps its own derivation because it genuinely has a settlement
     * timestamp on the payload ({@code settledAt}); {@code ClaimApproved}/{@code ClaimRejected}
     * carry no timestamp at all and so were extended to use this same {@code occurredAt} in the
     * final review (I1), replacing a {@code LocalDate.now()} fallback that would have refiled a
     * replayed movement into the replay's own quarter.
     */
    private void handleClaimRegistered(Map<String, Object> payload, Instant occurredAt) {
        UUID tenantId = TenantContext.get();
        UUID claimId = (UUID) payload.get("claimId");
        String policyNumber = (String) payload.get("policyNumber");
        String claimType = (String) payload.get("claimType");

        // ClaimRegistered is the only event in the lifecycle that carries claimType -- the
        // dimension row every later lifecycle event's movement depends on.
        if (claimDimensionRepository.findByTenantIdAndClaimId(tenantId, claimId).isEmpty()) {
            claimDimensionRepository.save(new ClaimDimension(tenantId, claimId, claimType, policyNumber));
        }

        String period = ProjectionSupport.quarterOf(occurredAt.atZone(ZoneOffset.UTC).toLocalDate());
        ClaimsMovement movement = claimsMovementRepository
            .findByTenantIdAndPeriodAndClaimType(tenantId, period, claimType)
            .orElseGet(() -> new ClaimsMovement(tenantId, period, claimType, ProjectionSupport.UNKNOWN_CURRENCY));
        movement.applyRegistered();
        claimsMovementRepository.save(movement);
    }

    /**
     * {@code period} comes from {@code envelope.occurredAt()}, NOT {@code LocalDate.now()}
     * (M10 final review, I1). {@code ClaimApproved} carries no business timestamp of its own
     * (verified against asyncapi-events.yaml), and the envelope's occurredAt is the closest
     * truthful stand-in: it is UTC, deterministic, and -- decisively -- STABLE UNDER REPROCESSING.
     * The alert rule for this module names "replay/rebuild the projection" as the recovery remedy
     * for a failed projection, and a replay deriving its period from "today" would file the
     * movement in the REPLAY's quarter instead of the original one, silently corrupting history.
     */
    private void handleClaimApproved(Map<String, Object> payload, Instant occurredAt) {
        UUID claimId = (UUID) payload.get("claimId");
        @SuppressWarnings("unchecked")
        Map<String, Object> approvedAmount = (Map<String, Object>) payload.get("approvedAmount");
        BigDecimal amount = new BigDecimal((String) approvedAmount.get("amount"));
        String currency = (String) approvedAmount.get("currencyCode");
        applyAmountMovement(claimId, "claims.ClaimApproved", ProjectionSupport.quarterOfOccurrence(occurredAt),
            amount, currency, ClaimsMovement::applyApproved);
    }

    /** {@code ClaimRejected} carries neither a business timestamp nor an amount -- the envelope's
     * own {@code occurredAt} (see {@link #handleClaimApproved}) and the {@code UNKNOWN_CURRENCY}
     * fallback if a fresh row must be created. */
    private void handleClaimRejected(Map<String, Object> payload, Instant occurredAt) {
        UUID claimId = (UUID) payload.get("claimId");
        applyCountOnlyMovement(claimId, "claims.ClaimRejected", ProjectionSupport.quarterOfOccurrence(occurredAt));
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
                // Counted as well as logged (M10 final review, I3) -- see PolicyEventListener's
                // equivalent branch. This one matters most: CL-03 in the seeded placeholder
                // definition filters CLAIMS_SETTLED_AMOUNT on claim_type = 'DEATH', so every
                // UNKNOWN-attributed settlement is a shilling missing from that line.
                meterRegistry.counter(ProjectionSupport.UNATTRIBUTED_MOVEMENT_COUNTER, "eventType", eventType).increment();
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
