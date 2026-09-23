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
import java.util.function.BiConsumer;

/**
 * The EFT rail's two-stage accrual, and the only listener on this platform that books an entry and
 * later reverses it.
 *
 * <p><b>Why this exists at all.</b> Every other payout completes within milliseconds of being
 * instructed — the mobile-money aggregator either accepts or declines while the request is still
 * warm — so recognising the claims expense once, on {@code claims.ClaimSettled}, loses nothing.
 * An EFT does not complete at all until a finance officer visits the bank, which may be days. For
 * that whole window an approved, irrevocable, multi-million-shilling obligation would be invisible
 * in the general ledger. So:
 * <ul>
 *   <li>{@code payment.EftDisbursementAwaitingExecution} → DR {@code 5100 Claims Expense} /
 *       CR {@code 2110 Claims Payable}. The obligation is on the books the moment it is real.</li>
 *   <li>{@code payment.EftDisbursementExecuted} → DR {@code 2110 Claims Payable} /
 *       CR {@code 5100 Claims Expense}, reversing it, immediately before the
 *       {@code payment.DisbursementCompleted} that the same transaction publishes drives
 *       {@code claims.ClaimSettled} and its existing DR Claims Expense / CR Cash.</li>
 * </ul>
 * Net once executed: expense recognised exactly once, cash credited once, {@code 2110} back to
 * zero. Getting the reversal wrong in either direction doubles or erases a claims expense, which
 * is why the reversal is a published event rather than something inferred here.
 *
 * <p><b>Scoped to CLAIM_SETTLEMENT, deliberately.</b> {@code 2110 Claims Payable} is a claims
 * account; an EFT raised for any other purpose has no business landing in it. Today claims is the
 * only publisher that ever asks for the EFT rail, so this guard is not reachable — it is here so
 * that when a second one appears, the failure is a log line naming the unmapped purpose rather
 * than a silently miscoded liability.
 *
 * <p>Mechanics and bean-naming rationale: see {@link BillingEventListener}.
 */
@Component("finaccountingPaymentEventListener")
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private static final String EVENT_PROCESSING_FAILED_COUNTER =
        "lifeplatform_finaccounting_event_processing_failed_total";

    private static final String CLAIM_SETTLEMENT = "CLAIM_SETTLEMENT";

    private final FinaccountingApiImpl finaccountingApiImpl;
    private final ChartOfAccountSeeder chartOfAccountSeeder;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PaymentEventListener(FinaccountingApiImpl finaccountingApiImpl,
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
            case "payment.EftDisbursementAwaitingExecution", "payment.EftDisbursementExecuted" ->
                withTenant(envelope, this::post);
            default -> { /* not finaccounting-relevant */ }
        }
    }

    private void withTenant(DomainEventEnvelope<?> envelope, BiConsumer<String, Map<String, Object>> handler) {
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            requiresNewTransactionTemplate.executeWithoutResult(
                status -> handler.accept(envelope.eventType(), payload));
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

    private void post(String eventType, Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String purpose = (String) payload.get("purpose");
        UUID disbursementId = (UUID) payload.get("disbursementId");
        if (!CLAIM_SETTLEMENT.equals(purpose)) {
            log.warn("{} for disbursement {} has purpose {}, which has no claims-payable treatment -- "
                + "nothing posted. Add a rule before routing this purpose to the EFT rail.",
                eventType, disbursementId, purpose);
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> money = (Map<String, Object>) payload.get("amount");
        BigDecimal amount = money == null ? null : new BigDecimal((String) money.get("amount"));
        String currency = money == null ? null : (String) money.get("currencyCode");
        // sourceRef is the claimId -- the same sourceRef claims.ClaimSettled posts under, so the
        // accrual, its reversal and the cash entry all reconcile to one claim. The journal entry's
        // own idempotency is per (eventType, sourceRef), which is why the reversal MUST carry a
        // different eventType rather than being a second entry under the same one.
        String sourceRef = (String) payload.get("sourceRef");

        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:" + eventType);
        Optional<JournalEntry> maybeEntry = GlPostingCalculator.calculate(tenantId, eventType, sourceRef,
            null, amount, currency, YearMonth.now().toString(), "system:" + eventType);
        if (maybeEntry.isEmpty()) {
            log.info("{} for disbursement {} produced no journal entry (already posted, no accounting "
                + "rule, or a non-positive amount)", eventType, disbursementId);
            return;
        }
        finaccountingApiImpl.postEntry(maybeEntry.get());
    }
}
