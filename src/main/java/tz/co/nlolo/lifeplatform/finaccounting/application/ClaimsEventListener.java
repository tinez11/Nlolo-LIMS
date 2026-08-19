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
 * Consumes the M8-enriched {@code claims.ClaimSettled} and books the claims expense against cash:
 * DR {@code 5000 Claims Expense} / CR {@code 1000 Cash}, keyed by {@code claimId}.
 *
 * <p>Triggering on SETTLED rather than APPROVED mirrors {@code
 * reinsurance.application.ClaimEventListener}'s own rationale: approval precedes the payment
 * rail, and a settlement can still fail or land IN_DOUBT -- booking an expense against money that
 * never left would overstate the expense. {@code policyNumber} and {@code settledAmount} were
 * both added to this event by M8 for exactly this kind of downstream consumer.
 *
 * <p>Mechanics and bean-naming rationale: see {@link BillingEventListener}.
 */
@Component("finaccountingClaimsEventListener")
public class ClaimsEventListener {

    private static final Logger log = LoggerFactory.getLogger(ClaimsEventListener.class);

    private static final String EVENT_PROCESSING_FAILED_COUNTER = "lifeplatform_finaccounting_event_processing_failed_total";

    private final FinaccountingApiImpl finaccountingApiImpl;
    private final ChartOfAccountSeeder chartOfAccountSeeder;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ClaimsEventListener(FinaccountingApiImpl finaccountingApiImpl,
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
            case "claims.ClaimSettled" -> withTenant(envelope, this::handleClaimSettled);
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

    private void handleClaimSettled(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        UUID claimId = (UUID) payload.get("claimId");
        String policyNumber = (String) payload.get("policyNumber");
        @SuppressWarnings("unchecked")
        Map<String, Object> settled = (Map<String, Object>) payload.get("settledAmount");
        BigDecimal amount = settled == null ? null : new BigDecimal((String) settled.get("amount"));
        String currency = settled == null ? null : (String) settled.get("currencyCode");

        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:claims.ClaimSettled");
        Optional<JournalEntry> maybeEntry = GlPostingCalculator.calculate(tenantId, "claims.ClaimSettled",
            claimId.toString(), policyNumber, amount, currency, YearMonth.now().toString(), "system:claims.ClaimSettled");
        if (maybeEntry.isEmpty()) {
            log.info("claims.ClaimSettled for claim {} produced no journal entry (already posted, no "
                + "accounting rule, or a non-positive amount)", claimId);
            return;
        }
        finaccountingApiImpl.postEntry(maybeEntry.get());
    }
}
