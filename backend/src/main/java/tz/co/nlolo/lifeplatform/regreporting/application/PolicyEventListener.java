package tz.co.nlolo.lifeplatform.regreporting.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyDimension;
import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyMovement;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.PolicyDimensionRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.PolicyMovementRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Maintains {@code regreporting.policy_dimension} and {@code regreporting.policy_movement} off the
 * policy lifecycle events (regreporting/V2 sections 4-5). Mechanics copied from {@code
 * reinsurance.application.PolicyEventListener}: {@code AFTER_COMMIT} (policy's own write must be
 * durable before this projection reacts), one reusable {@code PROPAGATION_REQUIRES_NEW} {@link
 * TransactionTemplate} (a plain {@code @Transactional} called from an AFTER_COMMIT callback
 * silently joins the already-committed producer transaction and never commits -- empirically
 * confirmed on this project), and {@code TenantContext} save/set/restore rather than an
 * unconditional clear, since this runs synchronously on the producer's own thread.
 *
 * <p><b>{@code policy.PolicySuspended} is deliberately handled by nothing.</b> A suspended policy
 * is still in force -- it has not lapsed, matured, been claim-terminated, or been reinstated from a
 * lapse -- so no {@code policy_movement} column exists to record it against, and the cumulative
 * POLICIES_IN_FORCE figure must not move when a policy is merely suspended. This omission is a
 * decision, not a gap.
 *
 * <p><b>{@code policy.PolicySurrendered} despite its name fires for a SETTLED CLAIM</b>, not a
 * policyholder-initiated surrender ({@code PolicyApiImpl.terminateForSettledClaim} is its real
 * producer; see asyncapi-events.yaml's {@code PolicySurrenderedPayload} description) -- so it maps
 * to {@link PolicyMovement#applyClaimTerminated}, the measure that actually exists for it, not a
 * would-be "surrendered" column this table does not have.
 *
 * <p><b>The {@code UNKNOWN} sentinel.</b> {@code PolicyLapsed}/{@code Reinstated}/{@code Matured}/
 * {@code Surrendered} carry only a {@code policyNumber} and a timestamp -- never {@code productId}
 * or {@code sumAssured} (verified against asyncapi-events.yaml) -- so every movement they cause
 * needs {@code policy_dimension}, a row written only by {@code PolicyActivated}. When that lookup
 * misses, the movement is attributed to {@link ProjectionSupport#UNKNOWN_PRODUCT} with the sum
 * assured recorded as zero (the real figure is genuinely unknown) rather than dropped entirely -- a
 * visibly-unattributed figure is strictly better than one that silently vanished from a return.
 *
 * <p><b>Bean name is explicit</b> -- {@code billing}, {@code distribution} and {@code reinsurance}
 * each already declare an unqualified {@code PolicyEventListener}, and a fourth with the same
 * simple name is a bean-name collision that fails context startup for the whole suite.
 */
@Component("regreportingPolicyEventListener")
public class PolicyEventListener {

    private static final Logger log = LoggerFactory.getLogger(PolicyEventListener.class);

    /** Task 6 Step 6's alertable counter. Same naming convention as reinsurance's/finaccounting's
     * own {@code lifeplatform_<module>_event_processing_failed_total}. */
    private static final String EVENT_PROCESSING_FAILED_COUNTER = "lifeplatform_regreporting_event_processing_failed_total";

    private final PolicyDimensionRepository policyDimensionRepository;
    private final PolicyMovementRepository policyMovementRepository;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PolicyEventListener(PolicyDimensionRepository policyDimensionRepository,
                                PolicyMovementRepository policyMovementRepository,
                                MeterRegistry meterRegistry,
                                PlatformTransactionManager transactionManager) {
        this.policyDimensionRepository = policyDimensionRepository;
        this.policyMovementRepository = policyMovementRepository;
        this.meterRegistry = meterRegistry;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            // PolicyActivated, not PolicyIssued. PolicyIssued now means "the contract record
            // exists" -- an offer awaiting its first premium. Counting a proposal as new
            // business would report contracts to the regulator that the platform is not on risk
            // for, and around 15% of them are never taken up.
            //
            // policy_dimension is still written here, deliberately: it is what later LAPSED and
            // SURRENDERED movements look up, and a policy that never activated produces no such
            // movements to look anything up for.
            case "policy.PolicyActivated" -> withTenant(envelope, this::handlePolicyActivated);
            case "policy.PolicyLapsed" -> withTenant(envelope, this::handlePolicyLapsed);
            case "policy.PolicyMatured" -> withTenant(envelope, this::handlePolicyMatured);
            case "policy.PolicySurrendered" -> withTenant(envelope, this::handlePolicySurrendered);
            case "policy.PolicyReinstated" -> withTenant(envelope, this::handlePolicyReinstated);
            /*
              policy.PolicyCancelledFreeLook is NOT consumed, and that is an OPEN GAP rather than a
              decision -- recorded here because this switch is where somebody adding the next
              lifecycle event will look.

              The policy WAS counted as new business by handlePolicyActivated, and a free-look
              cancellation voids the contract from inception, so the return currently overstates
              in-force business by every policy a customer walked away from.

              It cannot be fixed by reusing a handler, for two reasons that both need an answer
              from someone who owns the return:
                - There is no honest measure. applyLapsed and applyMatured both add to
                  sumAssuredTerminated, which would report the contract as BOTH written and
                  terminated and inflate both sides; the actuarially usual treatment is to exclude
                  free-look business from new business entirely, which needs a new measure and a
                  migration.
                - It would belong in the period the ISSUANCE was counted in, not the period of the
                  cancellation, and a free-look window can cross a quarter boundary. Every other
                  movement here is keyed by the event's own period.
            */
            // Both member events say the same thing -- "this scheme's total cover is now X" -- so
            // one handler serves both. See its javadoc for why the projection is a delta.
            case "policy.GroupMemberAdded" -> withTenant(envelope, p -> handleSchemeTotalRestated(p, "policy.GroupMemberAdded"));
            case "policy.GroupMemberExited" -> withTenant(envelope, p -> handleSchemeTotalRestated(p, "policy.GroupMemberExited"));
            // A free cover limit moving changes the scheme's total with NO member joining or
            // leaving. Without this branch the running sum assured would quietly diverge from the
            // book on every amendment -- the same staleness member movement was added to close,
            // arriving through a door that did not exist when it was.
            case "policy.GroupSchemeFreeCoverLimitAmended" ->
                withTenant(envelope, p -> handleSchemeTotalRestated(p, "policy.GroupSchemeFreeCoverLimitAmended"));
            // The same door again: an underwriter granting a member's excess raises the total
            // with nobody joining or leaving.
            case "policy.GroupMemberEvidenceGranted" ->
                withTenant(envelope, p -> handleSchemeTotalRestated(p, "policy.GroupMemberEvidenceGranted"));
            // policy.PolicySuspended: deliberately no case -- see class javadoc.
            default -> { /* not regreporting-relevant here */ }
        }
    }

    private void withTenant(DomainEventEnvelope<?> envelope, Consumer<Map<String, Object>> handler) {
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            // The retry wraps the WHOLE transaction, not the handler body -- see
            // ProjectionSupport.withOptimisticLockRetry's javadoc for why that placement is
            // load-bearing rather than stylistic (M10 final review, C1).
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

    private void handlePolicyActivated(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String policyNumber = (String) payload.get("policyNumber");
        UUID productId = (UUID) payload.get("productId");
        @SuppressWarnings("unchecked")
        Map<String, Object> sumAssured = (Map<String, Object>) payload.get("sumAssured");
        BigDecimal sumAssuredAmount = new BigDecimal((String) sumAssured.get("amount"));
        String sumAssuredCurrency = (String) sumAssured.get("currencyCode");
        String issueDate = (String) payload.get("issueDate");

        // PolicyActivated is the only event in the lifecycle that carries productId and sumAssured --
        // the dimension row every later lifecycle event's movement depends on.
        if (policyDimensionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber).isEmpty()) {
            policyDimensionRepository.save(new PolicyDimension(tenantId, policyNumber, productId,
                sumAssuredAmount, sumAssuredCurrency, LocalDate.parse(issueDate)));
        }

        String period = ProjectionSupport.quarterOfDate(issueDate);
        PolicyMovement movement = policyMovementRepository
            .findByTenantIdAndPeriodAndProductId(tenantId, period, productId)
            .orElseGet(() -> new PolicyMovement(tenantId, period, productId, sumAssuredCurrency));
        movement.applyIssued(sumAssuredAmount);
        policyMovementRepository.save(movement);
    }

    private void handlePolicyLapsed(Map<String, Object> payload) {
        String policyNumber = (String) payload.get("policyNumber");
        String period = ProjectionSupport.quarterOfInstant((String) payload.get("lapsedAt"));
        applyTerminationMovement(policyNumber, period, "policy.PolicyLapsed", PolicyMovement::applyLapsed);
    }

    private void handlePolicyMatured(Map<String, Object> payload) {
        String policyNumber = (String) payload.get("policyNumber");
        String period = ProjectionSupport.quarterOfInstant((String) payload.get("maturedAt"));
        applyTerminationMovement(policyNumber, period, "policy.PolicyMatured", PolicyMovement::applyMatured);
    }

    /**
     * Usually fires for a settled DEATH/DISABILITY/CRITICAL_ILLNESS claim rather than a
     * policyholder surrender — see the class javadoc — but no longer always.
     *
     * <p>Since {@code PolicyApi.exitMember} existed, a credit-life scheme also closes when its
     * LAST borrower simply repays, refinances or is written off. No claim was paid, and
     * counting that as a claim termination would inflate the claims line of a TIRA return with
     * loans that were merely settled — a regulatory misstatement, not a cosmetic one.
     *
     * <p>The producer distinguishes them by OMITTING {@code claimId} when no claim was
     * involved, so the two are told apart here without a new event type.
     *
     * <p><b>{@code applyMatured} is an interim classification, not the right answer.</b>
     * {@code PolicyMovement} offers only lapsed, matured and claim-terminated, and a loan
     * repaid early is none of the three. Matured is the least wrong of them — the contract
     * reached the end of the population it insured — and, decisively, it is not a claim. A
     * movement type that actually says "the debt ended" is reporting work (spec 2.14) and
     * belongs with the rest of it.
     */
    private void handlePolicySurrendered(Map<String, Object> payload) {
        String policyNumber = (String) payload.get("policyNumber");
        String period = ProjectionSupport.quarterOfInstant((String) payload.get("surrenderedAt"));
        boolean aClaimWasPaid = payload.get("claimId") != null;
        applyTerminationMovement(policyNumber, period, "policy.PolicySurrendered",
            aClaimWasPaid ? PolicyMovement::applyClaimTerminated : PolicyMovement::applyMatured);
    }

    private void handlePolicyReinstated(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String policyNumber = (String) payload.get("policyNumber");
        String period = ProjectionSupport.quarterOfInstant((String) payload.get("reinstatedAt"));

        DimensionAttribution attribution = resolveDimension(tenantId, policyNumber, "policy.PolicyReinstated");
        PolicyMovement movement = policyMovementRepository
            .findByTenantIdAndPeriodAndProductId(tenantId, period, attribution.productId())
            .orElseGet(() -> new PolicyMovement(tenantId, period, attribution.productId(), attribution.currency()));
        movement.applyReinstated();
        policyMovementRepository.save(movement);
    }

    /** Shared by Lapsed/Matured/Surrendered: none of the three carries a productId or sumAssured
     * (verified against asyncapi-events.yaml), so both come from the policy_dimension row written
     * at issuance -- or the UNKNOWN sentinel if that lookup misses. */
    private void applyTerminationMovement(String policyNumber, String period, String eventType,
                                           BiConsumer<PolicyMovement, BigDecimal> applier) {
        UUID tenantId = TenantContext.get();
        DimensionAttribution attribution = resolveDimension(tenantId, policyNumber, eventType);
        PolicyMovement movement = policyMovementRepository
            .findByTenantIdAndPeriodAndProductId(tenantId, period, attribution.productId())
            .orElseGet(() -> new PolicyMovement(tenantId, period, attribution.productId(), attribution.currency()));
        applier.accept(movement, attribution.sumAssured());
        policyMovementRepository.save(movement);
    }

    /**
     * One handler for both member events, because they say the same thing: <b>this scheme's total
     * cover is now X</b>. What changed, and by how much, is the difference between that and the
     * total this module last recorded.
     *
     * <p><b>A delta, not the member's own cover.</b> Three reasons, and the third is the one that
     * matters. {@code GroupMemberExited} carries no {@code coveredAmount} to use at all. A
     * member's own cover is not necessarily the change in the scheme's total. And
     * {@code regreporting} has no de-duplication anywhere — these listeners are
     * {@code AFTER_COMMIT} with at-least-once delivery, and every other handler in this module
     * double-counts a redelivered event. A delta cannot: the second delivery finds the dimension
     * already equal to the total it carries and computes zero. The projection also self-heals,
     * correcting drift from a dropped event on the next one rather than carrying it forever.
     *
     * <p><b>A total of zero is skipped entirely, and that is load-bearing.</b> When the last
     * active member leaves, {@code PolicyApiImpl.exitOneMember} restates the scheme to zero and
     * then closes it, and the resulting {@code policy.PolicySurrendered} already terminates the
     * last recorded total through {@link #applyTerminationMovement}. Acting here as well would
     * write a zero into a column whose CHECK forbids it, and would remove cover the close event
     * is about to remove again — the whole scheme counted out twice.
     *
     * <p><b>A missing dimension is dropped here, unlike everywhere else in this class.</b>
     * {@link #resolveDimension}'s UNKNOWN-product fallback is right for a termination, whose
     * event carries no amount, so attributing a zero-valued movement to a sentinel loses nothing.
     * A member delta is meaningless without the previous total: falling back would book the
     * scheme's WHOLE total as a movement against a product that does not exist. Counted, not
     * silently dropped.
     *
     * <p>Opening-schedule members need no handling: {@code issueGroupScheme} publishes no
     * {@code GroupMemberAdded} for them, because their cover is already inside the
     * {@code sumAssured} on {@code PolicyActivated}. Verified, not assumed.
     */
    private void handleSchemeTotalRestated(Map<String, Object> payload, String eventType) {
        UUID tenantId = TenantContext.get();
        String policyNumber = (String) payload.get("policyNumber");
        @SuppressWarnings("unchecked")
        Map<String, Object> schemeTotal = (Map<String, Object>) payload.get("schemeTotalCovered");
        BigDecimal newTotal = new BigDecimal((String) schemeTotal.get("amount"));

        if (newTotal.signum() <= 0) {
            log.info("Scheme {} restated to {} -- its last member has left, so the close event owns "
                + "taking it out of force. No member movement recorded.", policyNumber, newTotal);
            return;
        }

        var maybeDimension = policyDimensionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber);
        if (maybeDimension.isEmpty()) {
            log.warn("{} on scheme {} has no policy_dimension row -- no previous total to measure a "
                + "delta against and no productId to attribute it to, so it is dropped",
                eventType, policyNumber);
            meterRegistry.counter(ProjectionSupport.UNATTRIBUTED_MOVEMENT_COUNTER, "eventType", eventType).increment();
            return;
        }
        PolicyDimension dimension = maybeDimension.get();
        BigDecimal delta = newTotal.subtract(dimension.getSumAssuredAmount());
        if (delta.signum() == 0) {
            return; // a redelivery, or a member whose cover was nil
        }

        // The period is the one the CHANGE falls in, not the scheme's issue quarter: a borrower
        // enrolled in a later quarter is that quarter's movement even on a scheme issued long
        // before, or every file a lender sends for the rest of the scheme's life would be
        // reported in the quarter it opened.
        String period = ProjectionSupport.quarterOfDate(changeDateOf(payload));
        PolicyMovement movement = policyMovementRepository
            .findByTenantIdAndPeriodAndProductId(tenantId, period, dimension.getProductId())
            .orElseGet(() -> new PolicyMovement(tenantId, period, dimension.getProductId(),
                dimension.getSumAssuredCurrency()));
        if (delta.signum() > 0) {
            movement.applyMemberCoverAdded(delta);
        } else {
            movement.applyMemberCoverExited(delta.negate());
        }
        policyMovementRepository.save(movement);

        dimension.restateSumAssured(newTotal);
        policyDimensionRepository.save(dimension);
    }

    /** {@code joinedOn} on an add, {@code leftOn} on an exit -- the date the cover actually
     * changed, which is what the period must be derived from. */
    private static String changeDateOf(Map<String, Object> payload) {
        Object joined = payload.get("joinedOn");
        return (String) (joined != null ? joined : payload.get("leftOn"));
    }

    private DimensionAttribution resolveDimension(UUID tenantId, String policyNumber, String eventType) {
        return policyDimensionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber)
            .map(d -> new DimensionAttribution(d.getProductId(), d.getSumAssuredAmount(), d.getSumAssuredCurrency()))
            .orElseGet(() -> {
                log.warn("{} for policy {} has no policy_dimension row -- attributing to the UNKNOWN "
                    + "product sentinel with sum assured recorded as zero, rather than dropping the movement",
                    eventType, policyNumber);
                // Counted, not merely logged (M10 final review, I3): a WARN nobody greps for is not
                // observability, and unattributed movements accumulating is exactly the pattern
                // that has to be visible before a regulator's figure is understated by it.
                meterRegistry.counter(ProjectionSupport.UNATTRIBUTED_MOVEMENT_COUNTER, "eventType", eventType).increment();
                return new DimensionAttribution(ProjectionSupport.UNKNOWN_PRODUCT, BigDecimal.ZERO, ProjectionSupport.UNKNOWN_CURRENCY);
            });
    }

    private record DimensionAttribution(UUID productId, BigDecimal sumAssured, String currency) {}
}
