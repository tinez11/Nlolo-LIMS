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
 * Consumes {@code distribution.CommissionPaid} and books the commission expense against cash:
 * DR {@code 5100 Commission Expense} / CR {@code 1000 Cash}, keyed by {@code statementId}.
 *
 * <p><b>{@code policyNumber} is not on this event's payload today</b> ({@code
 * distribution.application.PaymentEventListener.handleCompleted} publishes only {@code
 * statementId} and {@code paidAt} -- a commission statement can aggregate several policies, so
 * there is no single policy number to attribute it to even in principle), and {@code
 * finaccounting} has no synchronous dependency on {@code distribution} to look one up. {@code
 * journal_entry.policy_number} is nullable precisely for cases like this one.
 *
 * <p><b>This event does not yet carry an {@code amount}</b> -- Task 7 (not yet run as of this
 * task) adds it. Until then, {@code payload.get("amount")} is null, {@link
 * GlPostingCalculator#calculate} treats a null amount as "no accounting consequence" by its own
 * contract, and this handler correctly posts nothing. The code below is written against the
 * event's intended eventual shape and requires no change once Task 7 lands.
 *
 * <p>Mechanics and bean-naming rationale: see {@link BillingEventListener}.
 */
@Component("finaccountingDistributionEventListener")
public class DistributionEventListener {

    private static final Logger log = LoggerFactory.getLogger(DistributionEventListener.class);

    private static final String EVENT_PROCESSING_FAILED_COUNTER = "lifeplatform_finaccounting_event_processing_failed_total";

    private final FinaccountingApiImpl finaccountingApiImpl;
    private final ChartOfAccountSeeder chartOfAccountSeeder;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public DistributionEventListener(FinaccountingApiImpl finaccountingApiImpl,
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
            case "distribution.CommissionPaid" -> withTenant(envelope, this::handleCommissionPaid);
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

    private void handleCommissionPaid(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        Object statementId = payload.get("statementId");
        String sourceRef = String.valueOf(statementId);
        @SuppressWarnings("unchecked")
        Map<String, Object> amountMap = (Map<String, Object>) payload.get("amount");
        BigDecimal amount = amountMap == null ? null : new BigDecimal((String) amountMap.get("amount"));
        String currency = amountMap == null ? null : (String) amountMap.get("currencyCode");

        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:distribution.CommissionPaid");
        Optional<JournalEntry> maybeEntry = GlPostingCalculator.calculate(tenantId, "distribution.CommissionPaid",
            sourceRef, null, amount, currency, YearMonth.now().toString(), "system:distribution.CommissionPaid");
        if (maybeEntry.isEmpty()) {
            log.info("distribution.CommissionPaid for statement {} produced no journal entry (already "
                + "posted, no accounting rule, or -- expected until Task 7 -- no amount on the payload yet)",
                statementId);
            return;
        }
        finaccountingApiImpl.postEntry(maybeEntry.get());
    }
}
