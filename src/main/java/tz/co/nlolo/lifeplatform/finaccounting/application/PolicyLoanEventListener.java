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
 * Consumes the two {@code policyloan} events with an accounting consequence, mirroring how
 * {@code reinsurance}'s own {@code PolicyEventListener}/{@code ClaimEventListener} pair handles
 * one event each via a single switch.
 *
 * <ul>
 *   <li>{@code policyloan.LoanDisbursed} -- books DR {@code 1400 Policy Loan Receivable} / CR
 *   {@code 1000 Cash}, keyed by {@code loanId}.
 *   <b>This event does not yet carry an {@code amount}</b> -- {@code
 *   PolicyLoanApiImpl.markDisbursed} publishes only {@code loanId} and {@code disbursedAt} today;
 *   Task 7 (not yet run as of this task) adds it. Until then, {@code payload.get("amount")} is
 *   null, {@link GlPostingCalculator#calculate} treats a null amount as "no accounting
 *   consequence" by its own contract, and this handler correctly posts nothing. The code below is
 *   written against the event's intended eventual shape and requires no change once Task 7
 *   lands.</li>
 *   <li>{@code policyloan.LoanRepaid} -- books DR {@code 1000 Cash} / CR {@code 1400 Policy Loan
 *   Receivable}, keyed by {@code loanId}, amount already present on the payload today.</li>
 * </ul>
 *
 * <p><b>{@code policyNumber} is not on either event's payload.</b> A policy loan is keyed by
 * {@code loanId} against the {@code policy_loan} aggregate, not the policy number directly, and
 * neither {@code PolicyLoanApiImpl.markDisbursed} nor {@code
 * PolicyLoanApiImpl.recordRepayment}'s publish carries one; {@code finaccounting} has no
 * synchronous dependency on {@code policyloan} to look one up. {@code null} is passed, which
 * {@code journal_entry.policy_number} allows by design.
 *
 * <p>Mechanics and bean-naming rationale: see {@link BillingEventListener}.
 */
@Component("finaccountingPolicyLoanEventListener")
public class PolicyLoanEventListener {

    private static final Logger log = LoggerFactory.getLogger(PolicyLoanEventListener.class);

    private static final String EVENT_PROCESSING_FAILED_COUNTER = "lifeplatform_finaccounting_event_processing_failed_total";

    private final FinaccountingApiImpl finaccountingApiImpl;
    private final ChartOfAccountSeeder chartOfAccountSeeder;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PolicyLoanEventListener(FinaccountingApiImpl finaccountingApiImpl,
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
            case "policyloan.LoanDisbursed" -> withTenant(envelope, this::handleLoanDisbursed);
            case "policyloan.LoanRepaid" -> withTenant(envelope, this::handleLoanRepaid);
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

    private void handleLoanDisbursed(Map<String, Object> payload) {
        Object loanId = payload.get("loanId");
        @SuppressWarnings("unchecked")
        Map<String, Object> amountMap = (Map<String, Object>) payload.get("amount");
        BigDecimal amount = amountMap == null ? null : new BigDecimal((String) amountMap.get("amount"));
        String currency = amountMap == null ? null : (String) amountMap.get("currencyCode");

        post("policyloan.LoanDisbursed", String.valueOf(loanId), amount, currency);
    }

    private void handleLoanRepaid(Map<String, Object> payload) {
        Object loanId = payload.get("loanId");
        @SuppressWarnings("unchecked")
        Map<String, Object> amountMap = (Map<String, Object>) payload.get("amount");
        BigDecimal amount = amountMap == null ? null : new BigDecimal((String) amountMap.get("amount"));
        String currency = amountMap == null ? null : (String) amountMap.get("currencyCode");

        post("policyloan.LoanRepaid", String.valueOf(loanId), amount, currency);
    }

    private void post(String eventType, String sourceRef, BigDecimal amount, String currency) {
        UUID tenantId = TenantContext.get();
        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:" + eventType);
        Optional<JournalEntry> maybeEntry = GlPostingCalculator.calculate(tenantId, eventType,
            sourceRef, null, amount, currency, YearMonth.now().toString(), "system:" + eventType);
        if (maybeEntry.isEmpty()) {
            log.info("{} for loan {} produced no journal entry (already posted, no accounting rule, "
                + "or no amount on the payload yet)", eventType, sourceRef);
            return;
        }
        finaccountingApiImpl.postEntry(maybeEntry.get());
    }
}
