package tz.co.nlolo.lifeplatform.finaccounting.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRule;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountSeeder;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Unit-linked business in the ledger (product step 6, spec §8). 2150 carries what is owed in units and is always
 * units in issue x current price: premiums go into it, charges and exits come out of it, and every approved price
 * trues it up through 5600. One balanced entry per event, idempotent on (event, source).
 *
 * <pre>
 *   UnitsAllocated   DR 2140 premium        / CR 2150 allocated, CR 4310 allocation charge
 *   ChargesTaken     DR 2150 fee + coi      / CR 4310
 *   FundRevalued     DR 5600 / CR 2150 a rise; the reverse a fall
 *   ExitPriced       DR 2150 proceeds       / CR 5100   (the payout's own posting is DR 5100 / CR cash)
 *   PremiumReturned  DR 2140 premium        / CR 5100 returned, CR 4310 its allocation charge
 *   ChargeRefunded   DR 4310 / CR 5100
 *   UnitsReinvested  DR 5100 / CR 2150
 *   PayoutPaid       DR 5100 / CR cash  an exit's payout (surrender, maturity, lapse), keyed on the disbursement;
 *                    DR 2130 / CR cash  an adjustment paid to the customer
 *   AdjustmentCollected  DR cash / CR 1230   owed by the customer, collected outside the platform
 *   AdjustmentWaived     DR 2130 / CR 5100 owed to them; DR 5100 / CR 1230 owed by them
 *   PriceCorrected   per re-run exit sale: the liability's difference against 5100; where the exit was already
 *                    paid, the difference owed to the customer (DR 5100 / CR 2130) or by them (DR 1230 / CR 5100)
 * </pre>
 */
@Component
public class UnitLinkedEventListener {

    private static final Logger log = LoggerFactory.getLogger(UnitLinkedEventListener.class);
    private static final String FAILED = "lifeplatform_finaccounting_event_processing_failed_total";
    private static final String BY = "system:unitlinked";

    private final FinaccountingApiImpl finaccounting;
    private final ChartOfAccountSeeder seeder;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNew;

    public UnitLinkedEventListener(FinaccountingApiImpl finaccounting, ChartOfAccountSeeder seeder, MeterRegistry meterRegistry,
                                   PlatformTransactionManager transactionManager) {
        this.finaccounting = finaccounting;
        this.seeder = seeder;
        this.meterRegistry = meterRegistry;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        String type = envelope.eventType();
        if (!type.startsWith("unitlinked.")) {
            return;
        }
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) envelope.payload();
            requiresNew.executeWithoutResult(status -> post(type, p));
        } catch (Exception e) {
            meterRegistry.counter(FAILED, "eventType", type).increment();
            log.error("finaccounting failed to post {} for tenant {}", type, envelope.tenantId(), e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }

    private void post(String type, Map<String, Object> p) {
        UUID tenantId = TenantContext.get();
        String policy = (String) p.get("policyNumber");
        String currency = (String) p.getOrDefault("currencyCode", "TZS");
        switch (type) {
            case "unitlinked.UnitsAllocated" -> {
                BigDecimal premium = money(p.get("premium"));
                BigDecimal allocated = money(p.get("allocated"));
                BigDecimal charge = money(p.get("allocationCharge"));
                JournalEntry e = entry(tenantId, type, (String) p.get("sourceRef"), policy);
                e.addLeg(PostingRule.UNEARNED_PREMIUM, PostingDirection.DR, premium, currency);
                if (allocated.signum() > 0) e.addLeg(PostingRule.UNIT_LINKED_LIABILITY, PostingDirection.CR, allocated, currency);
                if (charge.signum() > 0) e.addLeg(PostingRule.UNIT_LINKED_CHARGES_INCOME, PostingDirection.CR, charge, currency);
                postIfAny(e, premium);
            }
            case "unitlinked.ChargesTaken" -> {
                BigDecimal taken = money(p.get("policyFee")).add(money(p.get("costOfInsurance")));
                pair(tenantId, type, (String) p.get("sourceRef"), policy, PostingRule.UNIT_LINKED_LIABILITY,
                    PostingRule.UNIT_LINKED_CHARGES_INCOME, taken, currency);
            }
            case "unitlinked.FundRevalued" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> delta = (Map<String, Object>) p.get("delta");
                BigDecimal d = money(delta.get("amount"));
                String deltaCurrency = (String) delta.getOrDefault("currencyCode", currency);
                String ref = p.get("priceId") + ":" + p.get("carried");
                if (d.signum() > 0) {
                    pair(tenantId, type, ref, null, PostingRule.CHANGE_IN_UNIT_LINKED_LIABILITY,
                        PostingRule.UNIT_LINKED_LIABILITY, d, deltaCurrency);
                } else {
                    pair(tenantId, type, ref, null, PostingRule.UNIT_LINKED_LIABILITY,
                        PostingRule.CHANGE_IN_UNIT_LINKED_LIABILITY, d.negate(), deltaCurrency);
                }
            }
            case "unitlinked.ExitPriced" -> pair(tenantId, type, (String) p.get("sourceRef"), policy,
                PostingRule.UNIT_LINKED_LIABILITY, PostingRule.CLAIMS_EXPENSE, money(p.get("proceeds")), currency);
            case "unitlinked.PremiumReturned" -> {
                BigDecimal returned = money(p.get("returned"));
                BigDecimal charge = money(p.get("allocationCharge"));
                JournalEntry e = entry(tenantId, type, (String) p.get("sourceRef"), policy);
                e.addLeg(PostingRule.UNEARNED_PREMIUM, PostingDirection.DR, returned.add(charge), currency);
                if (returned.signum() > 0) e.addLeg(PostingRule.CLAIMS_EXPENSE, PostingDirection.CR, returned, currency);
                if (charge.signum() > 0) e.addLeg(PostingRule.UNIT_LINKED_CHARGES_INCOME, PostingDirection.CR, charge, currency);
                postIfAny(e, returned.add(charge));
            }
            case "unitlinked.ChargeRefunded" -> pair(tenantId, type, (String) p.get("sourceRef"), policy,
                PostingRule.UNIT_LINKED_CHARGES_INCOME, PostingRule.CLAIMS_EXPENSE, money(p.get("amount")), currency);
            case "unitlinked.UnitsReinvested" -> pair(tenantId, type, (String) p.get("sourceRef"), policy,
                PostingRule.CLAIMS_EXPENSE, PostingRule.UNIT_LINKED_LIABILITY, money(p.get("amount")), currency);
            case "unitlinked.PriceCorrected" -> corrected(tenantId, type, p, currency);
            case "unitlinked.PayoutPaid" -> pair(tenantId, type, (String) p.get("sourceRef"), policy,
                "PRICE_CORRECTION_PAYOUT".equals(p.get("purpose")) ? PostingRule.POLICYHOLDER_BENEFITS_PAYABLE : PostingRule.CLAIMS_EXPENSE,
                PostingRule.CASH, money(p.get("amount")), currency);
            case "unitlinked.AdjustmentCollected" -> pair(tenantId, type, (String) p.get("sourceRef"), policy,
                PostingRule.CASH, PostingRule.OTHER_RECEIVABLES, money(p.get("amount")), currency);
            case "unitlinked.AdjustmentWaived" -> {
                boolean owedToCustomer = "OWED_TO_CUSTOMER".equals(p.get("direction"));
                pair(tenantId, type, (String) p.get("sourceRef"), policy,
                    owedToCustomer ? PostingRule.POLICYHOLDER_BENEFITS_PAYABLE : PostingRule.CLAIMS_EXPENSE,
                    owedToCustomer ? PostingRule.CLAIMS_EXPENSE : PostingRule.OTHER_RECEIVABLES, money(p.get("amount")), currency);
            }
            default -> { /* not a ledger event */ }
        }
    }

    private void corrected(UUID tenantId, String type, Map<String, Object> p, String currency) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> movements = (List<Map<String, Object>>) p.get("movements");
        String ref = (String) p.get("correctedPriceId");
        int i = 0;
        for (Map<String, Object> m : movements) {
            i++;
            String entryType = (String) m.get("entryType");
            if (!entryType.endsWith("_SALE")) {
                continue; // a buy or a charge moves units, not money: the true-up's revaluation carries it
            }
            // Sale amounts are negative: what the customer is owed more (or less) is the corrected one less the original.
            BigDecimal d = money(m.get("originalAmount")).subtract(money(m.get("correctedAmount")));
            if (d.signum() == 0) {
                continue;
            }
            String policy = (String) m.get("policyNumber");
            String mref = ref + ":" + i;
            if (d.signum() > 0) {
                pair(tenantId, type, mref + ":liability", policy, PostingRule.UNIT_LINKED_LIABILITY, PostingRule.CLAIMS_EXPENSE, d, currency);
            } else {
                pair(tenantId, type, mref + ":liability", policy, PostingRule.CLAIMS_EXPENSE, PostingRule.UNIT_LINKED_LIABILITY, d.negate(), currency);
            }
            if (Boolean.TRUE.equals(m.get("paidAlready"))) {
                if (d.signum() > 0) {
                    pair(tenantId, type, mref + ":owed", policy, PostingRule.CLAIMS_EXPENSE,
                        PostingRule.POLICYHOLDER_BENEFITS_PAYABLE, d, currency);
                } else {
                    pair(tenantId, type, mref + ":owed", policy, PostingRule.OTHER_RECEIVABLES, PostingRule.CLAIMS_EXPENSE,
                        d.negate(), currency);
                }
            }
        }
    }

    private void pair(UUID tenantId, String type, String ref, String policy, String debit, String credit, BigDecimal amount,
                      String currency) {
        if (amount.signum() <= 0) {
            return;
        }
        JournalEntry e = entry(tenantId, type, ref, policy);
        e.addLeg(debit, PostingDirection.DR, amount, currency);
        e.addLeg(credit, PostingDirection.CR, amount, currency);
        finaccounting.postEntry(e);
    }

    private JournalEntry entry(UUID tenantId, String type, String ref, String policy) {
        seeder.seedIfAbsent(tenantId, BY);
        return new JournalEntry(tenantId, type, ref, YearMonth.now().toString(), policy, BY);
    }

    private void postIfAny(JournalEntry e, BigDecimal total) {
        if (total.signum() > 0) {
            finaccounting.postEntry(e);
        }
    }

    private static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }
}
