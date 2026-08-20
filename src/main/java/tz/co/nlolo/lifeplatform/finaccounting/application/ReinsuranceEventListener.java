package tz.co.nlolo.lifeplatform.finaccounting.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPostingCalculator;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountSeeder;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Consumes the two {@code reinsurance} events with an accounting consequence, mirroring how
 * {@code reinsurance}'s own {@code PolicyEventListener}/{@code ClaimEventListener} pair handles
 * one event each via a single switch.
 *
 * <ul>
 *   <li>{@code reinsurance.CessionRecorded} -- risk ceded at issuance. Books DR {@code 5200
 *   Reinsurance Ceded Premium} / CR {@code 2300 Reinsurance Payable}, keyed by {@code cessionId},
 *   amount from the payload's {@code cededAmount}. {@code policyNumber} IS on this payload
 *   ({@code reinsurance.application.PolicyEventListener} publishes it field-for-field), so it is
 *   read and passed through.</li>
 *   <li>{@code reinsurance.RecoveryConfirmed} -- the reinsurer has actually paid out a recovery.
 *   Books DR {@code 1300 Reinsurance Recoverable} / CR {@code 5000 Claims Expense} (offsetting the
 *   expense claims booked earlier), keyed by {@code recoveryId}.
 *   <b>{@code policyNumber} is NOT on this payload</b> ({@code
 *   ReinsuranceApiImpl.confirmRecovery} publishes only {@code recoveryId} and {@code
 *   confirmedAt}), and {@code finaccounting} has no synchronous dependency on {@code reinsurance}
 *   to look one up -- {@code null} is passed, which {@code journal_entry.policy_number} allows by
 *   design.
 *   <p><b>{@code amount} was added by Task 7</b> ({@code ReinsuranceApiImpl.confirmRecovery} now
 *   also publishes {@code recovery.getRecoverableAmount()}/{@code getRecoverableCurrency()}, purely
 *   additively). Before Task 7 ran, {@code payload.get("amount")} was null and {@link
 *   GlPostingCalculator#calculate} -- which treats a null amount as "no accounting consequence" by
 *   its own contract -- correctly posted nothing; this handler needed no code change once Task 7
 *   landed, only a real amount to act on.</li>
 * </ul>
 *
 * <p>Mechanics and bean-naming rationale: see {@link BillingEventListener}.
 */
@Component("finaccountingReinsuranceEventListener")
public class ReinsuranceEventListener {

    private static final Logger log = LoggerFactory.getLogger(ReinsuranceEventListener.class);

    private static final String EVENT_PROCESSING_FAILED_COUNTER = "lifeplatform_finaccounting_event_processing_failed_total";

    private final FinaccountingApiImpl finaccountingApiImpl;
    private final ChartOfAccountSeeder chartOfAccountSeeder;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ReinsuranceEventListener(FinaccountingApiImpl finaccountingApiImpl,
                                     ChartOfAccountSeeder chartOfAccountSeeder,
                                     MeterRegistry meterRegistry,
                                     PlatformTransactionManager transactionManager) {
        this.finaccountingApiImpl = finaccountingApiImpl;
        this.chartOfAccountSeeder = chartOfAccountSeeder;
        this.meterRegistry = meterRegistry;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "reinsurance.CessionRecorded" -> withTenant(envelope, this::handleCessionRecorded);
            case "reinsurance.RecoveryConfirmed" -> withTenant(envelope, this::handleRecoveryConfirmed);
            default -> { /* not finaccounting-relevant */ }
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
            log.error("finaccounting failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    private void handleCessionRecorded(Map<String, Object> payload) {
        // (UUID) cast, not String.valueOf (M9 final review, finding M2): a missing id must blow up
        // into onDomainEvent's catch-all -- incrementing the failure counter and firing
        // FinaccountingEventProcessingFailed -- rather than quietly posting the literal sourceRef
        // "null", which would additionally collide with any other id-less event of the same type and
        // make the second one look like a redelivery of the first. Matches
        // BillingEventListener/ClaimsEventListener, which already cast.
        UUID cessionId = (UUID) payload.get("cessionId");
        String policyNumber = (String) payload.get("policyNumber");
        @SuppressWarnings("unchecked")
        Map<String, Object> cededAmount = (Map<String, Object>) payload.get("cededAmount");
        BigDecimal amount = cededAmount == null ? null : new BigDecimal((String) cededAmount.get("amount"));
        String currency = cededAmount == null ? null : (String) cededAmount.get("currencyCode");

        post("reinsurance.CessionRecorded", cessionId.toString(), policyNumber, amount, currency);
    }

    private void handleRecoveryConfirmed(Map<String, Object> payload) {
        UUID recoveryId = (UUID) payload.get("recoveryId");   // (UUID) cast: see handleCessionRecorded
        @SuppressWarnings("unchecked")
        Map<String, Object> amountMap = (Map<String, Object>) payload.get("amount");
        BigDecimal amount = amountMap == null ? null : new BigDecimal((String) amountMap.get("amount"));
        String currency = amountMap == null ? null : (String) amountMap.get("currencyCode");

        post("reinsurance.RecoveryConfirmed", recoveryId.toString(), null, amount, currency);
    }

    private void post(String eventType, String sourceRef, String policyNumber, BigDecimal amount, String currency) {
        UUID tenantId = TenantContext.get();
        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:" + eventType);
        Optional<JournalEntry> maybeEntry = GlPostingCalculator.calculate(tenantId, eventType,
            sourceRef, policyNumber, amount, currency, YearMonth.now().toString(), "system:" + eventType);
        if (maybeEntry.isEmpty()) {
            log.info("{} for {} produced no journal entry (already posted, no accounting rule, or no "
                + "amount on the payload yet)", eventType, sourceRef);
            return;
        }
        finaccountingApiImpl.postEntry(maybeEntry.get());
    }
}
