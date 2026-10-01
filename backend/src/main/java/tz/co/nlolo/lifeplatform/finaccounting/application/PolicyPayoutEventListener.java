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
 * Books money policy pays out directly -- today a surrender value, on {@code policy.SurrenderPaid}
 * -- keyed by the surrender request id. On PAID, not on approval, for the reason
 * {@link ClaimsEventListener} gives: an approved payout can still fail or land IN_DOUBT.
 *
 * <p>Mechanics and bean-naming rationale: see {@link BillingEventListener}.
 */
@Component("finaccountingPolicyPayoutEventListener")
public class PolicyPayoutEventListener {

    private static final Logger log = LoggerFactory.getLogger(PolicyPayoutEventListener.class);

    private static final String EVENT_PROCESSING_FAILED_COUNTER = "lifeplatform_finaccounting_event_processing_failed_total";

    private final FinaccountingApiImpl finaccountingApiImpl;
    private final ChartOfAccountSeeder chartOfAccountSeeder;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PolicyPayoutEventListener(FinaccountingApiImpl finaccountingApiImpl,
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
        if ("policy.SurrenderPaid".equals(envelope.eventType())) {
            withTenant(envelope, this::handleSurrenderPaid);
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

    private void handleSurrenderPaid(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String surrenderRequestId = (String) payload.get("surrenderRequestId");
        String policyNumber = (String) payload.get("policyNumber");
        @SuppressWarnings("unchecked")
        Map<String, Object> paid = (Map<String, Object>) payload.get("paidAmount");
        BigDecimal amount = new BigDecimal((String) paid.get("amount"));
        String currency = (String) paid.get("currencyCode");

        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:policy.SurrenderPaid");
        Optional<JournalEntry> maybeEntry = GlPostingCalculator.calculate(tenantId, "policy.SurrenderPaid",
            surrenderRequestId, policyNumber, amount, currency, YearMonth.now().toString(), "system:policy.SurrenderPaid");
        if (maybeEntry.isEmpty()) {
            log.info("policy.SurrenderPaid for surrender request {} produced no journal entry (already posted, no "
                + "accounting rule, or a non-positive amount)", surrenderRequestId);
            return;
        }
        finaccountingApiImpl.postEntry(maybeEntry.get());
    }
}
