package tz.co.nlolo.lifeplatform.distribution.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.distribution.api.StatementStatus;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionStatement;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.CommissionStatementRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Task 8: closes distribution's half of the request/confirm loop. Consumes {@code
 * payment.DisbursementCompleted}/{@code DisbursementFailed} and drives {@code
 * CommissionStatement.markPaid}/{@code markPayoutFailed}.
 *
 * <p>Mechanics are {@link PolicyEventListener}'s and {@link PremiumEventListener}'s, documented at
 * length on the former: AFTER_COMMIT, one {@code PROPAGATION_REQUIRES_NEW} {@link
 * TransactionTemplate} (a plain REQUIRED {@code @Transactional} call from an AFTER_COMMIT callback
 * silently joins the already-committed producer transaction and never commits), and {@code
 * TenantContext} save/set/restore rather than an unconditional clear.
 *
 * <p><b>ONE transaction per handler, unlike {@code claims.application.PaymentEventListener}.</b>
 * Claims needs phase separation because its second phase calls a FOREIGN module ({@code PolicyApi})
 * that can fail after money has already moved -- the M6 Critical. Nothing here calls another
 * module: both handlers only read and write this module's own {@code commission_statement}. So each
 * is correctly atomic as one local transaction.
 *
 * <p><b>Every handler filters on {@code purpose}, and must keep doing so.</b> {@code
 * payment.DisbursementCompleted} is a single event type carrying every module's payouts --
 * policyloan's {@code LOAN_DISBURSEMENT}, claims' {@code CLAIM_SETTLEMENT}, and this module's
 * {@code COMMISSION_PAYOUT}. Without the filter, distribution would try to resolve a loan id or a
 * claim id as a statement id and process another module's money movement as a commission. M6's
 * review found this guard missing on the claims side; it is written here from the start and pinned
 * by a test.
 *
 * <p><b>{@code DisbursementCompleted} and {@code DisbursementFailed} do NOT share a payload
 * shape.</b> Verified against {@code PaymentApiImpl}: Completed carries {@code gatewayReference},
 * {@code amount} and {@code completedAt}; Failed carries none of those, only {@code reason}. Both
 * do carry {@code purpose} and {@code sourceRef}.
 *
 * <p><b>{@code DisbursementFailed} is always a definitive rail decline, never "we do not
 * know."</b> An indeterminate outcome is recorded as {@code IN_DOUBT} and publishes NO event at all
 * ({@code PaymentApiImpl.markDisbursementInDoubt}; api/asyncapi-events.yaml records this explicitly
 * and warns against inventing a {@code DisbursementInDoubt}). A statement can therefore rest at
 * {@code PAYOUT_REQUESTED} indefinitely. That is not fixable from distribution and is not a missed
 * case -- {@code payment} already alerts on it via {@code lifeplatform_payment_in_doubt_total}.
 * Stated here as a known boundary, exactly as {@code claims} states its own.
 *
 * <p><b>Bean name is explicit.</b> {@code policyloan}, {@code billing} and {@code claims} each
 * already declare a class named {@code PaymentEventListener}; a fourth unqualified {@code
 * @Component} with the same simple name is a bean-name collision that fails context startup for the
 * whole suite.
 */
@Component("distributionPaymentEventListener")
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    /** distribution's own payout purpose. Every handler filters on it -- see the class javadoc. */
    private static final String COMMISSION_PAYOUT = "COMMISSION_PAYOUT";

    /** Follows the platform's {@code lifeplatform_<module>_<thing>_total} convention (claims'
     * settlement-failed counter is the nearest analogue). An operator needs to know an agent's
     * commission payout was declined and the statement is now on the staff retry worklist, because
     * distribution deliberately does not retry it automatically -- a retry needs a NEW idempotency
     * key, which only a human requesting payout again supplies. observability/alert-rules.yml
     * carries the matching rule, and AlertRuleMetricProducerTest pins that this class produces it. */
    private static final String PAYOUT_FAILED_COUNTER = "lifeplatform_distribution_payout_failed_total";

    private final CommissionStatementRepository commissionStatementRepository;
    private final tz.co.nlolo.lifeplatform.distribution.infrastructure.AgentProfileRepository agentProfileRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PaymentEventListener(CommissionStatementRepository commissionStatementRepository,
                                 tz.co.nlolo.lifeplatform.distribution.infrastructure.AgentProfileRepository agentProfileRepository,
                                 ApplicationEventPublisher eventPublisher,
                                 MeterRegistry meterRegistry,
                                 PlatformTransactionManager transactionManager) {
        this.commissionStatementRepository = commissionStatementRepository;
        this.agentProfileRepository = agentProfileRepository;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "payment.DisbursementCompleted" -> withTenant(envelope, this::handleCompleted);
            case "payment.DisbursementFailed" -> withTenant(envelope, this::handleFailed);
            default -> { /* not distribution-relevant */ }
        }
    }

    private void withTenant(DomainEventEnvelope<?> envelope, Consumer<Map<String, Object>> handler) {
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            handler.accept(payload);
        } catch (Exception e) {
            log.error("distribution failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    /**
     * PAYOUT_REQUESTED -> PAID, and {@code distribution.CommissionPaid} published <b>only when the
     * transition actually happened</b>.
     *
     * <p>{@code wasAlreadyPaid} is captured BEFORE {@code markPaid}, which is M6's I1 finding
     * applied preemptively rather than after the fact: {@code markPaid} is correctly idempotent and
     * returns quietly on a repeat, so publishing unconditionally afterwards would emit a SECOND
     * {@code CommissionPaid} for a redelivered {@code DisbursementCompleted}. The declared consumer
     * is {@code finaccounting}, where a duplicate is a double journal entry, not just a noisy log.
     */
    private void handleCompleted(Map<String, Object> payload) {
        if (!COMMISSION_PAYOUT.equals(payload.get("purpose"))) {
            return; // another module's payout rode the same event type
        }
        UUID tenantId = TenantContext.get();
        UUID statementId = UUID.fromString((String) payload.get("sourceRef"));

        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            CommissionStatement statement = commissionStatementRepository
                .findByStatementIdAndTenantId(statementId, tenantId)
                .orElseThrow(() -> new IllegalStateException(
                    "Commission statement " + statementId + " not found for tenant " + tenantId));

            boolean wasAlreadyPaid = statement.getStatus() == StatementStatus.PAID;
            statement.markPaid(Instant.now());
            commissionStatementRepository.save(statement);

            if (wasAlreadyPaid) {
                log.info("Ignoring redelivered DisbursementCompleted for already-PAID commission statement {} (tenant {})",
                    statementId, tenantId);
                return;
            }
            // M9: amount added. finaccounting was already declared as this event's consumer above
            // ("The declared consumer is finaccounting") but had nothing postable -- only
            // statementId and paidAt, no amount -- so it could not actually post commission
            // expense. statement is already loaded above; this is purely additive, and stays
            // inside the wasAlreadyPaid guard, so a redelivered DisbursementCompleted still emits
            // no second event.
            // IFRS 17 I3b (guide A-05): the gross clears the payable, the net left the bank, the tax withheld is owed
            // to the authority. The channel says which payable (2510 agents, 2520 brokers, 2530 bancassurance).
            String channel = agentProfileRepository.findByAgentIdAndTenantId(statement.getAgentId(), tenantId)
                .map(tz.co.nlolo.lifeplatform.distribution.domain.AgentProfile::getSalesChannel).orElse("AGENT");
            eventPublisher.publishEvent(DomainEventEnvelope.of("distribution.CommissionPaid", tenantId,
                Map.of("statementId", statementId, "paidAt", Instant.now().toString(),
                       "amount", Map.of("amount", statement.getTotalAmount().toPlainString(),
                                        "currencyCode", statement.getTotalCurrency()),
                       "withheldAmount", statement.getWithheldAmount().toPlainString(),
                       "paidAmount", statement.getNetAmount().toPlainString(),
                       "salesChannel", channel == null ? "AGENT" : channel)));
        });
    }

    /**
     * PAYOUT_REQUESTED -> PAYOUT_FAILED, reason preserved. No automatic retry: {@code
     * CommissionStatement.markPayoutRequested} requires a NEW idempotency key, because {@code
     * payment} dedupes on the old one and would drop a resubmission silently.
     *
     * <p>Reads only the fields {@code DisbursementFailed} actually carries -- it has no {@code
     * gatewayReference}, no {@code amount} and no timestamp.
     */
    private void handleFailed(Map<String, Object> payload) {
        if (!COMMISSION_PAYOUT.equals(payload.get("purpose"))) {
            return; // another module's payout rode the same event type
        }
        UUID tenantId = TenantContext.get();
        UUID statementId = UUID.fromString((String) payload.get("sourceRef"));
        String reason = (String) payload.get("reason");

        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            CommissionStatement statement = commissionStatementRepository
                .findByStatementIdAndTenantId(statementId, tenantId)
                .orElseThrow(() -> new IllegalStateException(
                    "Commission statement " + statementId + " not found for tenant " + tenantId));
            statement.markPayoutFailed(reason);
            commissionStatementRepository.save(statement);
        });
        meterRegistry.counter(PAYOUT_FAILED_COUNTER).increment();
        log.error("Commission payout FAILED for statement {} (tenant {}): {}. The statement is on the staff "
            + "retry worklist; a retry must request payout again with a NEW idempotency key.",
            statementId, tenantId, reason);
    }
}
