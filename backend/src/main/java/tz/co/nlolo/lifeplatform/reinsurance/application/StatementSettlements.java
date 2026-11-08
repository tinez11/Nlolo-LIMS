package tz.co.nlolo.lifeplatform.reinsurance.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.PayoutRoute;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.approvals.api.OwnWork;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceValidationException;
import tz.co.nlolo.lifeplatform.reinsurance.api.SettlementView;
import tz.co.nlolo.lifeplatform.reinsurance.api.StatementStateException;
import tz.co.nlolo.lifeplatform.reinsurance.api.StatementView;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Settling an approved reinsurance statement (2026-11-08, the user: "proceed with all"). The statement posts what the
 * quarter owes into the current account (1434), the profit commission receivable (1433) and funds withheld (2550);
 * nothing took them out again but a free-form manual journal, and paying a reinsurer had no place in "Payments to make".
 *
 * <p>Four movements, each against one statement and capped at what it still has open -- a waiting one counts, so the
 * same money is never recorded twice:
 * <ul>
 *   <li>RECEIVED: the reinsurer paid us. At most the current account's balance in our favour. Posts R-02.</li>
 *   <li>PAID: we pay the reinsurer, by bank transfer. At most the balance in its favour. Confirmed, it becomes an EFT
 *       under "Payments to make"; R-02-PAID posts when the transfer is made.</li>
 *   <li>PROFIT_COMMISSION: the reinsurer paid the profit commission the statement booked. Posts R-03-RECEIVED.</li>
 *   <li>WITHHELD_RELEASED: funds withheld handed back to the reinsurer, owed to it on the current account until paid.
 *       Posts R-04-RELEASED, and adds to what may be PAID.</li>
 * </ul>
 * Recorded by one finance person (reinsurance.manage), confirmed or refused by a second (reinsurance.approve) -- or by
 * the recorder holding reinsurance.approve-own, recorded as acting alone.
 */
@Service
public class StatementSettlements {

    static final String PURPOSE = "REINSURANCE_SETTLEMENT";
    private static final Set<String> KINDS = Set.of("RECEIVED", "PAID", "PROFIT_COMMISSION", "WITHHELD_RELEASED");
    private static final Set<String> RECEIPT_METHODS = Set.of("BANK_DEPOSIT", "CHEQUE");

    private final ReinsuranceStatements statements;
    private final SettlementStore store;
    private final ApplicationEventPublisher events;
    private final OwnWork ownWork;

    StatementSettlements(ReinsuranceStatements statements, SettlementStore store, ApplicationEventPublisher events,
                         OwnWork ownWork) {
        this.statements = statements;
        this.store = store;
        this.events = events;
        this.ownWork = ownWork;
    }

    @Transactional(readOnly = true)
    public SettlementView.Open open(UUID statementId) {
        UUID tenantId = TenantContext.get();
        StatementView s = statements.get(statementId);
        Balances b = balances(tenantId, s);
        List<SettlementView> rows = store.ofStatement(tenantId, statementId).stream().map(r -> view(r, s)).toList();
        return new SettlementView.Open(s.status(), s.currency(), b.theyOweUs(), b.weOweThem(), b.profitCommissionDue(),
            b.withheldHeld(), rows);
    }

    @Transactional
    public SettlementView record(UUID statementId, SettlementView.NewSettlement in, String by) {
        UUID tenantId = TenantContext.get();
        StatementView s = statements.get(statementId);
        if (!"APPROVED".equals(s.status())) {
            throw new StatementStateException("Only an approved statement is settled; this one is " + s.status());
        }
        String kind = in.kind() == null ? null : in.kind().trim().toUpperCase(java.util.Locale.ROOT);
        if (kind == null || !KINDS.contains(kind)) {
            throw new ReinsuranceValidationException("Say what this settles: money received, money paid, profit commission"
                + " received, or funds withheld released");
        }
        BigDecimal amount = in.amount();
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new ReinsuranceValidationException("An amount above zero, to at most two decimal places");
        }
        Balances b = balances(tenantId, s);
        BigDecimal open = switch (kind) {
            case "RECEIVED" -> b.theyOweUs();
            case "PAID" -> b.weOweThem();
            case "PROFIT_COMMISSION" -> b.profitCommissionDue();
            default -> b.withheldHeld();
        };
        if (amount.compareTo(open) > 0) {
            throw new ReinsuranceValidationException(over(kind, open, s.currency()));
        }
        SettlementView.NewSettlement clean = switch (kind) {
            case "RECEIVED", "PROFIT_COMMISSION" -> moneyIn(kind, amount, in);
            case "PAID" -> moneyOut(amount, in);
            default -> new SettlementView.NewSettlement(kind, amount, null, null, null, null, null, null, null,
                trim(in.note(), 500));
        };
        UUID id = UUID.randomUUID();
        store.insert(tenantId, id, statementId, s.treatyId(), s.currency(), clean, by);
        return view(store.get(tenantId, id).orElseThrow(), s);
    }

    /** Confirmed: posted to the ledger, or for a payment to the reinsurer, sent to "Payments to make". */
    @Transactional
    public SettlementView approve(UUID id, String by) {
        UUID tenantId = TenantContext.get();
        SettlementStore.Row row = decidable(tenantId, id, by, "confirm");
        StatementView s = statements.get(row.statementId());
        boolean payout = "PAID".equals(row.kind());
        if (!store.decide(tenantId, id, payout ? "REQUESTED" : "POSTED", by, null)) {
            throw new StatementStateException("This settlement is no longer waiting to be confirmed");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("settlementId", id.toString());
        payload.put("statementId", row.statementId().toString());
        payload.put("treatyId", row.treatyId().toString());
        payload.put("reinsurerName", s.reinsurerName());
        payload.put("quarter", s.quarter());
        payload.put("amount", Map.of("amount", row.amount().toPlainString(), "currencyCode", row.currency()));
        switch (row.kind()) {
            case "RECEIVED" -> {
                payload.put("receiptMethod", row.method());
                events.publishEvent(DomainEventEnvelope.of("reinsurance.SettlementReceived", tenantId, payload));
            }
            case "PROFIT_COMMISSION" -> {
                payload.put("receiptMethod", row.method());
                events.publishEvent(DomainEventEnvelope.of("reinsurance.ProfitCommissionReceived", tenantId, payload));
            }
            case "WITHHELD_RELEASED" ->
                events.publishEvent(DomainEventEnvelope.of("reinsurance.FundsWithheldReleased", tenantId, payload));
            default -> {
                // An EFT finance makes by hand, under "Payments to make"; payment answers when it is made.
                payload.put("idempotencyKey", "reinsurance-settlement:" + id);
                payload.put("purpose", PURPOSE);
                payload.putAll(PayoutRoute.of(PayoutRoute.EFT, null, row.payeeBank(), row.payeeBranch(),
                    row.payeeAccountName(), row.payeeAccountNumber()).toPayload());
                events.publishEvent(DomainEventEnvelope.of("reinsurance.SettlementPayoutRequested", tenantId, payload));
            }
        }
        return view(store.get(tenantId, id).orElseThrow(), s);
    }

    @Transactional
    public SettlementView refuse(UUID id, String reason, String by) {
        UUID tenantId = TenantContext.get();
        if (reason == null || reason.isBlank()) {
            throw new ReinsuranceValidationException("Say why the settlement is refused");
        }
        SettlementStore.Row row = decidable(tenantId, id, by, "refuse");
        if (!store.decide(tenantId, id, "REFUSED", by, trim(reason, 500))) {
            throw new StatementStateException("This settlement is no longer waiting to be confirmed");
        }
        return view(store.get(tenantId, id).orElseThrow(), statements.get(row.statementId()));
    }

    /** The transfer to the reinsurer was made, or failed: paid posts R-02-PAID; failed frees the amount again. */
    @Transactional
    public void payoutSettled(UUID id, boolean completed) {
        UUID tenantId = TenantContext.get();
        SettlementStore.Row row = store.get(tenantId, id).orElse(null);
        if (row == null || !store.settlePayout(tenantId, id, completed ? "PAID" : "FAILED") || !completed) {
            return;
        }
        events.publishEvent(DomainEventEnvelope.of("reinsurance.SettlementPaid", tenantId, Map.of(
            "settlementId", id.toString(),
            "statementId", row.statementId().toString(),
            "treatyId", row.treatyId().toString(),
            "amount", Map.of("amount", row.amount().toPlainString(), "currencyCode", row.currency()))));
    }

    /** Every settlement waiting for a second person, oldest first -- the Approvals queue. */
    @Transactional(readOnly = true)
    public List<SettlementView> waiting() {
        UUID tenantId = TenantContext.get();
        return store.waiting(tenantId).stream().map(r -> view(r, statements.get(r.statementId()))).toList();
    }

    // ---- the statement's open balances ---------------------------------------------------------------------------

    private record Balances(BigDecimal theyOweUs, BigDecimal weOweThem, BigDecimal profitCommissionDue,
                            BigDecimal withheldHeld) {}

    /**
     * The current account nets what the statement left in it (owed to us less owed by us) with every settlement
     * against it: money received takes from what they owe us, money paid and funds released add to what we owe them.
     */
    private Balances balances(UUID tenantId, StatementView s) {
        BigDecimal received = store.counted(tenantId, s.statementId(), "RECEIVED");
        BigDecimal paid = store.counted(tenantId, s.statementId(), "PAID");
        BigDecimal released = store.counted(tenantId, s.statementId(), "WITHHELD_RELEASED");
        BigDecimal pcReceived = store.counted(tenantId, s.statementId(), "PROFIT_COMMISSION");
        BigDecimal net = nz(s.owedToUs()).subtract(nz(s.owedByUs())).subtract(received).add(paid).subtract(released);
        return new Balances(net.max(BigDecimal.ZERO), net.negate().max(BigDecimal.ZERO),
            nz(s.profitCommission()).subtract(pcReceived).max(BigDecimal.ZERO),
            nz(s.fundsWithheld()).subtract(released).max(BigDecimal.ZERO));
    }

    private static String over(String kind, BigDecimal open, String currency) {
        String left = open.toPlainString() + " " + currency;
        return switch (kind) {
            case "RECEIVED" -> open.signum() == 0 ? "The reinsurer owes nothing more on this statement"
                : "More than the reinsurer still owes on this statement (" + left + ")";
            case "PAID" -> open.signum() == 0 ? "Nothing more is owed to the reinsurer on this statement"
                : "More than is still owed to the reinsurer on this statement (" + left + ")";
            case "PROFIT_COMMISSION" -> open.signum() == 0 ? "No profit commission is left to receive on this statement"
                : "More than the profit commission still due on this statement (" + left + ")";
            default -> open.signum() == 0 ? "No funds withheld are left to release on this statement"
                : "More than the funds still withheld on this statement (" + left + ")";
        };
    }

    // ---- what each kind must say ---------------------------------------------------------------------------------

    private static SettlementView.NewSettlement moneyIn(String kind, BigDecimal amount, SettlementView.NewSettlement in) {
        String method = in.method() == null ? null : in.method().trim().toUpperCase(java.util.Locale.ROOT);
        if (method == null || !RECEIPT_METHODS.contains(method)) {
            throw new ReinsuranceValidationException("Say how it came in: a bank deposit or a cheque");
        }
        String reference = trim(in.reference(), 100);
        if (reference == null) {
            throw new ReinsuranceValidationException("Give the bank or cheque reference");
        }
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Dar_es_Salaam"));
        if (in.receivedOn() == null || in.receivedOn().isAfter(today)) {
            throw new ReinsuranceValidationException("Say the day the money was received; it cannot be later than today");
        }
        return new SettlementView.NewSettlement(kind, amount, method, reference, in.receivedOn(), null, null, null, null,
            trim(in.note(), 500));
    }

    private static SettlementView.NewSettlement moneyOut(BigDecimal amount, SettlementView.NewSettlement in) {
        PayoutRoute route;
        try {
            route = PayoutRoute.of(PayoutRoute.EFT, null, in.payeeBank(), in.payeeBranch(), in.payeeAccountName(),
                in.payeeAccountNumber());
        } catch (IllegalArgumentException e) {
            throw new ReinsuranceValidationException(e.getMessage());
        }
        return new SettlementView.NewSettlement("PAID", amount, PayoutRoute.EFT, null, null, route.bank(), route.branch(),
            route.accountName(), route.accountNumber(), trim(in.note(), 500));
    }

    /** A waiting settlement, and a decider who is not its recorder -- unless they may act alone, recorded. */
    private SettlementStore.Row decidable(UUID tenantId, UUID id, String by, String action) {
        SettlementStore.Row row = store.get(tenantId, id)
            .orElseThrow(() -> new StatementStateException("No settlement " + id));
        if (!"AWAITING_APPROVAL".equals(row.status())) {
            throw new StatementStateException("Only a waiting settlement can be " + action + "ed; this one is "
                + row.status());
        }
        if (row.recordedBy().equals(by)) {
            if (!ownWork.mayActAlone("reinsurance.approve-own")) {
                throw new StatementStateException("You recorded this settlement; a second person must " + action + " it");
            }
            ownWork.record("Reinsurance settlement " + action + "ed by its recorder", id.toString(), null, by);
        }
        return row;
    }

    private static SettlementView view(SettlementStore.Row r, StatementView s) {
        return new SettlementView(r.id(), r.statementId(), r.treatyId(), s.reinsurerName(), s.quarter(), r.kind(),
            r.amount(), r.currency(), r.method(), r.reference(), r.receivedOn(), r.payeeBank(), r.payeeBranch(),
            r.payeeAccountName(), r.payeeAccountNumber(), r.note(), r.status(), r.recordedBy(), r.recordedAt(),
            r.decidedBy(), r.decidedAt(), r.decisionReason(), r.actedAlone());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String trim(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        return v.length() <= max ? v : v.substring(0, max);
    }
}
