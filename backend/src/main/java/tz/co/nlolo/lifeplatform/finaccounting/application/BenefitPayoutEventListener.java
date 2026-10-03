package tz.co.nlolo.lifeplatform.finaccounting.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPostingCalculator;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRule;
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
 * Books a benefit paid while the life assured lives, on {@code benefitpayout.PayoutPaid}, keyed by
 * the instalment id.
 *
 * <p>On PAID rather than on approval, for the reason {@link ClaimsEventListener} gives: an approved
 * payout can still fail or land IN_DOUBT, and booking an expense against money that never left
 * overstates it.
 *
 * <p>Mechanics and bean-naming rationale: see {@link BillingEventListener}.
 */
@Component("finaccountingBenefitPayoutEventListener")
public class BenefitPayoutEventListener {

    private static final Logger log = LoggerFactory.getLogger(BenefitPayoutEventListener.class);

    private static final String EVENT_PROCESSING_FAILED_COUNTER = "lifeplatform_finaccounting_event_processing_failed_total";
    private static final String EVENT = "benefitpayout.PayoutPaid";

    private final FinaccountingApiImpl finaccountingApiImpl;
    private final ChartOfAccountSeeder chartOfAccountSeeder;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public BenefitPayoutEventListener(FinaccountingApiImpl finaccountingApiImpl,
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
        if (EVENT.equals(envelope.eventType())) {
            withTenant(envelope, this::handlePayoutPaid);
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

    private void handlePayoutPaid(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String instalmentId = (String) payload.get("instalmentId");
        String policyNumber = (String) payload.get("policyNumber");
        @SuppressWarnings("unchecked")
        Map<String, Object> paid = (Map<String, Object>) payload.get("paidAmount");
        BigDecimal amount = new BigDecimal((String) paid.get("amount"));
        String currency = (String) paid.get("currencyCode");

        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:" + EVENT);

        // Tax withheld (product step 5): the expense is the GROSS, the bank paid the NET, and the
        // difference is owed to the tax authority -- three legs, balanced. Absent or zero withholding
        // (every payout before this step, and any with no rule in force) posts exactly as before.
        Object withheldField = payload.get("withheldAmount");
        if (withheldField instanceof Map<?, ?> withheldMoney) {
            BigDecimal withheld = new BigDecimal((String) withheldMoney.get("amount"));
            if (withheld.signum() > 0) {
                @SuppressWarnings("unchecked")
                Map<String, Object> grossMoney = (Map<String, Object>) payload.get("grossAmount");
                BigDecimal gross = new BigDecimal((String) grossMoney.get("amount"));
                // postEntry is idempotent on (tenant, event, instalment), so a redelivery posts nothing.
                JournalEntry entry = new JournalEntry(tenantId, EVENT, instalmentId, YearMonth.now().toString(),
                    policyNumber, "system:" + EVENT);
                entry.addLeg(PostingRule.CLAIMS_EXPENSE, PostingDirection.DR, gross, currency);
                entry.addLeg(PostingRule.CASH, PostingDirection.CR, amount, currency);
                entry.addLeg(PostingRule.WITHHOLDING_TAX_PAYABLE, PostingDirection.CR, withheld, currency);
                finaccountingApiImpl.postEntry(entry);
                return;
            }
        }

        Optional<JournalEntry> maybeEntry = GlPostingCalculator.calculate(tenantId, EVENT,
            instalmentId, policyNumber, amount, currency, YearMonth.now().toString(), "system:" + EVENT);
        if (maybeEntry.isEmpty()) {
            log.info("{} for instalment {} produced no journal entry (already posted, no accounting "
                + "rule, or a non-positive amount)", EVENT, instalmentId);
            return;
        }
        finaccountingApiImpl.postEntry(maybeEntry.get());
    }
}
