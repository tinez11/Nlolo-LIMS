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
 * Consumes the two {@code billing} events that make up this ledger's accrual pair for premium.
 *
 * <p><b>Why two entries at two different times, and why {@code 1200} nets to zero across them.</b>
 * {@code billing.PremiumInvoiceGenerated} fires when the obligation ARISES -- an invoice is due,
 * whether or not it is ever paid -- and books DR {@code 1200 Premium Receivable} / CR {@code 2200
 * Unearned Premium}: an asset (money owed to this insurer) against a liability (premium held in
 * advance of the coverage it pays for; see {@code PostingRule}'s class javadoc for why this is an
 * accrual ledger and neither leg touches income). Later, when cash actually arrives, {@code
 * billing.PremiumCollected} fires and books DR {@code 1000 Cash} / CR {@code 1200 Premium
 * Receivable}: cash rises, the receivable clears. For the SAME invoice, {@code 1200} is therefore
 * debited once (on generation) and credited once (on collection) for the identical amount --
 * across the pair it nets to zero, exactly as a receivable that has been fully invoiced and fully
 * collected should. {@link tz.co.nlolo.lifeplatform.finaccounting.PremiumPostingEndToEndTest}
 * proves this end to end against real rows, not just in-memory objects.
 *
 * <p>Mechanics copied from {@code reinsurance.application.PolicyEventListener}: {@code
 * AFTER_COMMIT} (billing's own write must be durable before finaccounting reacts), one reusable
 * {@code PROPAGATION_REQUIRES_NEW} {@link TransactionTemplate} (a plain {@code @Transactional}
 * called from an AFTER_COMMIT callback silently joins the already-committed producer transaction
 * and never commits -- empirically confirmed on this project), and {@code TenantContext}
 * save/set/restore rather than an unconditional clear, since this runs synchronously on the
 * producer's own thread.
 *
 * <p>Both handlers seed the tenant's chart of accounts (lazily, no-op if already seeded) before
 * posting -- a tenant's chart must exist before its first posting, and finaccounting has no
 * synchronous dependency on any other module's onboarding flow to have done this already.
 *
 * <p><b>Bean name is explicit</b> -- {@code billing} and {@code policy} each already declare a
 * {@code PolicyEventListener}, and this platform has already lost a task to a Spring bean-name
 * collision from an unqualified {@code @Component}.
 */
@Component("finaccountingBillingEventListener")
public class BillingEventListener {

    private static final Logger log = LoggerFactory.getLogger(BillingEventListener.class);

    /** Same naming convention as {@code reinsurance.application.PolicyEventListener}'s counter:
     * a swallowed exception here means a business event moved (or was about to move) money but no
     * journal entry was written, so the ledger silently falls out of sync with billing. */
    private static final String EVENT_PROCESSING_FAILED_COUNTER = "lifeplatform_finaccounting_event_processing_failed_total";

    private final FinaccountingApiImpl finaccountingApiImpl;
    private final ChartOfAccountSeeder chartOfAccountSeeder;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public BillingEventListener(FinaccountingApiImpl finaccountingApiImpl,
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
            case "billing.PremiumInvoiceGenerated" -> withTenant(envelope, p -> post("billing.PremiumInvoiceGenerated", p));
            case "billing.PremiumCollected" -> withTenant(envelope, p -> post("billing.PremiumCollected", p));
            case "billing.PremiumRefundDue" -> withTenant(envelope, this::postCredit);
            case "billing.PremiumInvoiceIncreased", "billing.PremiumInvoiceReduced" ->
                withTenant(envelope, p -> postRestatement(envelope.eventType(), p));
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

    /** {@code sourceRef} is the invoice id: the same invoice generates one entry here and, later,
     * one more when it is collected -- both keyed on the identical id, which is what makes the
     * "1200 nets to zero" invariant checkable per-invoice rather than only in aggregate. */
    private void post(String eventType, Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        UUID invoiceId = (UUID) payload.get("invoiceId");
        String policyNumber = (String) payload.get("policyNumber");
        @SuppressWarnings("unchecked")
        Map<String, Object> amountMap = (Map<String, Object>) payload.get("amount");
        BigDecimal amount = amountMap == null ? null : new BigDecimal((String) amountMap.get("amount"));
        String currency = amountMap == null ? null : (String) amountMap.get("currencyCode");

        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:" + eventType);
        Optional<JournalEntry> maybeEntry = GlPostingCalculator.calculate(tenantId, eventType,
            invoiceId.toString(), policyNumber, amount, currency, YearMonth.now().toString(), "system:" + eventType);
        if (maybeEntry.isEmpty()) {
            log.info("{} for invoice {} produced no journal entry (already posted, no accounting rule, "
                + "or a non-positive amount)", eventType, invoiceId);
            return;
        }
        finaccountingApiImpl.postEntry(maybeEntry.get());
    }

    /**
     * An instalment restated in place: the difference only. Keyed on the RESTATEMENT's id, not the
     * invoice's -- the same instalment can be restated more than once (a baby added, then the anniversary),
     * and keying on the invoice would treat the second as a repeat of the first and post nothing.
     */
    private void postRestatement(String eventType, Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String restatementId = (String) payload.get("restatementId");
        String policyNumber = (String) payload.get("policyNumber");
        @SuppressWarnings("unchecked")
        Map<String, Object> amountMap = (Map<String, Object>) payload.get("amount");
        BigDecimal amount = new BigDecimal((String) amountMap.get("amount"));
        String currency = (String) amountMap.get("currencyCode");

        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:" + eventType);
        Optional<JournalEntry> maybeEntry = GlPostingCalculator.calculate(tenantId, eventType,
            restatementId, policyNumber, amount, currency, YearMonth.now().toString(), "system:" + eventType);
        if (maybeEntry.isEmpty()) {
            log.info("{} for restatement {} produced no journal entry", eventType, restatementId);
            return;
        }
        finaccountingApiImpl.postEntry(maybeEntry.get());
    }

    /**
     * A premium credit: reverses the invoice's posting for the part given back.
     *
     * <p>Keyed on the CREDIT's id, not the invoice's: one invoice can carry a credit for every
     * borrower on its file, and each is its own movement. Keying on the invoice would treat the
     * second borrower's refund as a repeat of the first and post nothing.
     */
    private void postCredit(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String creditId = String.valueOf(payload.get("creditId"));
        String policyNumber = (String) payload.get("policyNumber");
        @SuppressWarnings("unchecked")
        Map<String, Object> amountMap = (Map<String, Object>) payload.get("amount");
        BigDecimal amount = new BigDecimal((String) amountMap.get("amount"));
        String currency = (String) amountMap.get("currencyCode");

        String eventType = "billing.PremiumRefundDue";
        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:" + eventType);
        Optional<JournalEntry> maybeEntry = GlPostingCalculator.calculate(tenantId, eventType,
            creditId, policyNumber, amount, currency, YearMonth.now().toString(), "system:" + eventType);
        if (maybeEntry.isEmpty()) {
            log.info("{} for credit {} produced no journal entry", eventType, creditId);
            return;
        }
        finaccountingApiImpl.postEntry(maybeEntry.get());
    }
}
