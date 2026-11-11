package tz.co.nlolo.lifeplatform.billing.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.billing.api.OpenPremiumView;
import tz.co.nlolo.lifeplatform.billing.domain.ArrearsCase;
import tz.co.nlolo.lifeplatform.billing.domain.CollectionAction;
import tz.co.nlolo.lifeplatform.billing.domain.FieldReceipt;
import tz.co.nlolo.lifeplatform.billing.domain.PremiumCredit;
import tz.co.nlolo.lifeplatform.billing.domain.PremiumInvoice;
import tz.co.nlolo.lifeplatform.billing.infrastructure.ArrearsCaseRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.BalanceEntryRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.CollectionActionRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.FieldReceiptRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.PremiumCreditRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.PremiumInvoiceRepository;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The premiums still owed, or falling due by a day, with what was done to collect them (2026-11-11, premiums to follow
 * up). Billing's own facts only: who the client is, their phone, the agent and branch are reporting's to add.
 *
 * <p>Left out: a premium nothing is owed on; one not yet due that the policy's balance will pay on its due date (the
 * balance drain pays it then); one whose policy has open agent cash at least equal to what is owed (savings and annuity
 * cash waits for banking, decision 2026-11-11). Open agent cash below what is owed is shown, not hidden.
 */
@Service
public class OpenPremiums {

    static final Set<String> OPEN = Set.of("DUE", "IN_GRACE", "OVERDUE", "PARTIALLY_PAID");
    private static final Set<String> AGENT_CASH_OPEN = Set.of("PENDING_RECONCILIATION", "RECONCILIATION_OVERDUE");
    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");
    private static final int ACTIONS_SHOWN = 5;

    private final PremiumInvoiceRepository invoices;
    private final PremiumCreditRepository credits;
    private final BalanceEntryRepository balances;
    private final FieldReceiptRepository fieldReceipts;
    private final ArrearsCaseRepository arrears;
    private final CollectionActionRepository actions;
    private final ReferenceDataApi referenceData;

    OpenPremiums(PremiumInvoiceRepository invoices, PremiumCreditRepository credits, BalanceEntryRepository balances,
                 FieldReceiptRepository fieldReceipts, ArrearsCaseRepository arrears, CollectionActionRepository actions,
                 ReferenceDataApi referenceData) {
        this.invoices = invoices;
        this.credits = credits;
        this.balances = balances;
        this.fieldReceipts = fieldReceipts;
        this.arrears = arrears;
        this.actions = actions;
        this.referenceData = referenceData;
    }

    @Transactional(readOnly = true)
    public List<OpenPremiumView> list(LocalDate dueOnOrBefore) {
        UUID tenantId = TenantContext.get();
        LocalDate today = LocalDate.now(CIVIL);
        List<PremiumInvoice> candidates = invoices
            .findByTenantIdAndStatusInAndDueDateLessThanEqualOrderByDueDateAsc(tenantId, OPEN, dueOnOrBefore);
        if (candidates.isEmpty()) return List.of();

        Set<String> policies = candidates.stream().map(PremiumInvoice::getPolicyNumber).collect(Collectors.toSet());
        Map<String, List<FieldReceipt>> cashByPolicy = fieldReceipts
            .findByTenantIdAndPolicyNumberInAndStatusIn(tenantId, policies, AGENT_CASH_OPEN).stream()
            .collect(Collectors.groupingBy(FieldReceipt::getPolicyNumber));
        Map<String, BigDecimal> balanceByPolicy = new HashMap<>();
        Integer lapseDays = lapseLevelDays();
        Map<UUID, BigDecimal> credited = creditedAgainst(tenantId, candidates);

        List<PremiumInvoice> owed = new ArrayList<>();
        Map<UUID, BigDecimal> owedById = new HashMap<>();
        for (PremiumInvoice invoice : candidates) {
            BigDecimal due = invoice.balanceDue(credited.getOrDefault(invoice.getInvoiceId(), BigDecimal.ZERO));
            if (due.signum() <= 0) continue;
            if (invoice.getDueDate().isAfter(today)) {
                BigDecimal balance = balanceByPolicy.computeIfAbsent(invoice.getPolicyNumber(),
                    p -> balances.balanceOf(tenantId, p));
                if (balance != null && balance.compareTo(due) >= 0) continue;
            }
            BigDecimal held = held(cashByPolicy.get(invoice.getPolicyNumber()));
            if (held != null && held.compareTo(due) >= 0) continue;
            owed.add(invoice);
            owedById.put(invoice.getInvoiceId(), due);
        }
        if (owed.isEmpty()) return List.of();

        List<UUID> ids = owed.stream().map(PremiumInvoice::getInvoiceId).toList();
        Map<UUID, List<CollectionAction>> actionsById = actions
            .findByTenantIdAndInvoiceIdInOrderByRecordedAtDesc(tenantId, ids).stream()
            .collect(Collectors.groupingBy(CollectionAction::getInvoiceId));
        Map<UUID, ArrearsCase> openCases = arrears.findByTenantIdAndInvoiceIdInAndResolvedAtIsNull(tenantId, ids).stream()
            .collect(Collectors.toMap(ArrearsCase::getInvoiceId, c -> c, (a, b) -> a));

        return owed.stream().map(invoice -> {
            List<FieldReceipt> cash = cashByPolicy.get(invoice.getPolicyNumber());
            ArrearsCase arrearsCase = openCases.get(invoice.getInvoiceId());
            LocalDate lapseOn = arrearsCase == null || lapseDays == null ? null
                : LocalDate.ofInstant(arrearsCase.getOpenedAt(), CIVIL).plusDays(lapseDays);
            return new OpenPremiumView(invoice.getInvoiceId(), invoice.getPolicyNumber(), invoice.getDueDate(),
                owedById.get(invoice.getInvoiceId()), invoice.getCurrency().trim(), invoice.getStatus(), lapseOn,
                held(cash), mostHeldBy(cash),
                actionsById.getOrDefault(invoice.getInvoiceId(), List.of()).stream().limit(ACTIONS_SHOWN)
                    .map(OpenPremiums::view).toList());
        }).toList();
    }

    static OpenPremiumView.ActionView view(CollectionAction a) {
        return new OpenPremiumView.ActionView(a.getKind(), a.getOutcome(), a.getPromisedOn(), a.getNote(),
            a.getRecordedBy(), a.getRecordedAt());
    }

    /** What was credited back against each of these invoices, in one read: [invoiceId, credited]. */
    Map<UUID, BigDecimal> creditedAgainst(UUID tenantId, List<PremiumInvoice> invoices) {
        if (invoices.isEmpty()) return Map.of();
        return credits.findByTenantIdAndOriginalInvoiceIdIn(tenantId,
                invoices.stream().map(PremiumInvoice::getInvoiceId).toList()).stream()
            .collect(Collectors.toMap(PremiumCredit::getOriginalInvoiceId, PremiumCredit::getAmount, BigDecimal::add));
    }

    private static BigDecimal held(List<FieldReceipt> cash) {
        if (cash == null || cash.isEmpty()) return null;
        return cash.stream().map(FieldReceipt::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static UUID mostHeldBy(List<FieldReceipt> cash) {
        if (cash == null || cash.isEmpty()) return null;
        return cash.stream().collect(Collectors.groupingBy(FieldReceipt::getAgentId,
                Collectors.reducing(BigDecimal.ZERO, FieldReceipt::getAmount, BigDecimal::add)))
            .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /**
     * The days from an arrears case opening to its lapse level -- the sweep's own rule
     * (configure-billing-sweep.sql step 4: DUNNING_ESCALATION_DAYS / LEVEL_5). Null when it is not seeded: the page
     * then shows no lapse date rather than a guessed one.
     */
    private Integer lapseLevelDays() {
        try {
            return referenceData.getCodes("DUNNING_ESCALATION_DAYS").stream()
                .filter(c -> "LEVEL_5".equals(c.code())).findFirst()
                .map(c -> Integer.valueOf(c.value().trim())).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
