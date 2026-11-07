package tz.co.nlolo.lifeplatform.payment.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceStatus;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;
import tz.co.nlolo.lifeplatform.payment.api.PaymentNotFoundException;
import tz.co.nlolo.lifeplatform.payment.api.ReceiptMethod;
import tz.co.nlolo.lifeplatform.payment.api.ReceiptRefusedException;
import tz.co.nlolo.lifeplatform.payment.api.ReceiptView;
import tz.co.nlolo.lifeplatform.payment.domain.PaymentTransaction;
import tz.co.nlolo.lifeplatform.payment.infrastructure.PaymentTransactionRepository;
import tz.co.nlolo.lifeplatform.policyloan.api.LoanStatus;
import tz.co.nlolo.lifeplatform.policyloan.api.LoanView;
import tz.co.nlolo.lifeplatform.policyloan.api.PolicyLoanApi;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Receipts a person records for premium the gateway did not pull (2026-10-27): cash, a bank deposit, a cheque, a
 * Lipa-number payment or a TANQR scan.
 *
 * <p>Finance records one against an invoice; a second finance person approves it (user decision). Only then does it
 * count: approval publishes {@code payment.PaymentConfirmed} exactly as the gateway's callback does, so billing pays
 * the invoice and the ledger posts it by the same road -- carrying the method, so the money lands in the account it
 * actually reached (petty cash, the premium bank account, the mobile-money wallet).
 */
@Service
public class Receipts {

    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");
    private static final String REJECTED = "REJECTED";

    private final PaymentTransactionRepository transactions;
    private final BillingApi billing;
    private final PolicyLoanApi loans;
    private final ApplicationEventPublisher events;

    Receipts(PaymentTransactionRepository transactions, BillingApi billing, PolicyLoanApi loans,
             ApplicationEventPublisher events, tz.co.nlolo.lifeplatform.approvals.api.OwnWork ownWork,
             ReceiptNotices receiptNotices) {
        this.receiptNotices = receiptNotices;
        this.transactions = transactions;
        this.billing = billing;
        this.loans = loans;
        this.events = events;
        this.ownWork = ownWork;
    }

    /** Confirming one's own receipt, for a company with one finance person (2026-11-04). */
    private final tz.co.nlolo.lifeplatform.approvals.api.OwnWork ownWork;
    /** Tells the customer under the receipt's number once it is confirmed (receipts step 1). */
    private final ReceiptNotices receiptNotices;

    private void recordIfAlone(PaymentTransaction receipt, String how, String by) {
        if (receipt.isActedAlone()) {
            ownWork.record("Receipt " + how + " by the person who recorded it",
                receipt.getPaymentTransactionId().toString(), receipt.getPolicyNumber(), by);
        }
    }

    /**
     * What a person records: for an invoice, for a loan (money movement step 1, 2026-11-04) or onto a policy's balance
     * (step 5, design 7.2) -- one of the three. {@code fieldReceiptId} when it is an agent's cash being brought in.
     */
    public record NewReceipt(UUID invoiceId, UUID loanId, ReceiptMethod method, String reference, BigDecimal amount,
                             LocalDate receivedOn, String payerRef, String note, UUID fieldReceiptId, String policyNumber) {
        public NewReceipt(UUID invoiceId, UUID loanId, ReceiptMethod method, String reference, BigDecimal amount,
                          LocalDate receivedOn, String payerRef, String note, UUID fieldReceiptId) {
            this(invoiceId, loanId, method, reference, amount, receivedOn, payerRef, note, fieldReceiptId, null);
        }
    }

    private static final java.util.Set<InvoiceStatus> OPEN =
        java.util.EnumSet.of(InvoiceStatus.DUE, InvoiceStatus.IN_GRACE, InvoiceStatus.OVERDUE, InvoiceStatus.PARTIALLY_PAID);

    @Transactional
    public ReceiptView record(NewReceipt in, String recordedBy) {
        UUID tenantId = TenantContext.get();
        String reference = in.reference() == null ? "" : in.reference().trim();
        if (in.method() == null) throw new ReceiptRefusedException("Say how the money came in");
        if (reference.isEmpty()) {
            throw new ReceiptRefusedException("A receipt needs the method's own reference, so the same money cannot be "
                + "recorded twice");
        }
        if (in.amount() == null || in.amount().signum() <= 0) {
            throw new ReceiptRefusedException("A receipt is for an amount above zero");
        }
        LocalDate today = LocalDate.now(CIVIL);
        if (in.receivedOn() == null || in.receivedOn().isAfter(today)) {
            throw new ReceiptRefusedException("Say the day the money was received; it cannot be later than today");
        }
        boolean forPolicy = in.policyNumber() != null && !in.policyNumber().isBlank();
        int named = (in.invoiceId() != null ? 1 : 0) + (in.loanId() != null ? 1 : 0) + (forPolicy ? 1 : 0);
        if (named != 1) {
            throw new ReceiptRefusedException("Say what this pays: a policy's balance, an invoice or a loan, one of them");
        }
        if (in.loanId() != null) {
            return recordForLoan(in, reference, recordedBy, tenantId);
        }
        if (forPolicy) {
            return recordForPolicy(in, reference, recordedBy, tenantId);
        }

        InvoiceView invoice = billing.getInvoice(in.invoiceId());
        if (invoice.status() == InvoiceStatus.PAID || invoice.status() == InvoiceStatus.WAIVED) {
            throw new ReceiptRefusedException("Invoice due " + invoice.dueDate() + " is " + invoice.status()
                .name().toLowerCase() + ": there is nothing left to receive on it");
        }
        // On an insurance policy, more than the invoice owes is welcome (money movement step 2): the deposit lands on the
        // policy's balance, pays what is due oldest first, and the rest pays the next premiums. Only a savings-type
        // policy keeps the cap, and the "already waiting" check that went with it, until "For: Savings".
        boolean capped = billing.isSavingsType(invoice.policyNumber());
        BigDecimal owed = invoice.balanceDue() == null ? invoice.amount() : invoice.balanceDue();
        if (capped && in.amount().compareTo(owed) > 0) {
            throw new ReceiptRefusedException("This is more than is owed on the invoice (" + owed.toPlainString() + " "
                + invoice.currency() + "). Record what it pays, and the rest against the next invoice");
        }
        if (transactions.existsByTenantIdAndMethodAndGatewayReferenceAndStatusNot(tenantId, in.method().name(),
                reference, REJECTED)) {
            throw new ReceiptRefusedException("A receipt with this " + words(in.method()) + " reference (" + reference
                + ") is already recorded");
        }
        if (in.fieldReceiptId() != null
                && transactions.existsByTenantIdAndFieldReceiptIdAndStatusNot(tenantId, in.fieldReceiptId(), REJECTED)) {
            throw new ReceiptRefusedException("This agent's receipt is already recorded");
        }
        // What is already waiting on this invoice counts against what is owed (2026-10-28): POL-C0B3B81A's 20,000,000
        // invoice took two 20,000,000 receipts, because a waiting one showed nowhere and nothing refused the second.
        List<PaymentTransaction> waiting = transactions.findByTenantIdAndSourceRefAndStatusOrderByCreatedAtAsc(tenantId,
            invoice.invoiceId().toString(), "AWAITING_APPROVAL");
        BigDecimal waitingTotal = waiting.stream().map(PaymentTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (capped && !waiting.isEmpty() && waitingTotal.add(in.amount()).compareTo(owed) > 0) {
            PaymentTransaction first = waiting.get(0);
            throw new ReceiptRefusedException(waiting.size() == 1
                ? "A " + words(first.getMethod()) + " receipt (" + first.getGatewayReference() + ") of "
                    + first.getAmount().toPlainString() + " " + invoice.currency() + " is already waiting to be confirmed on "
                    + "this invoice. Confirm or refuse it under Approvals before recording another"
                : waiting.size() + " receipts totalling " + waitingTotal.toPlainString() + " " + invoice.currency()
                    + " are already waiting to be confirmed on this invoice. Confirm or refuse them under Approvals "
                    + "before recording another");
        }

        PaymentTransaction receipt = PaymentTransaction.manualReceipt(tenantId, "receipt:" + UUID.randomUUID(),
            in.method().name(), reference, blankToNull(in.payerRef()) == null ? "—" : in.payerRef().trim(), in.amount(),
            invoice.currency(), invoice.invoiceId(), invoice.policyNumber(), in.receivedOn(), blankToNull(in.note()),
            in.fieldReceiptId(), recordedBy);
        transactions.save(receipt);
        return view(receipt);
    }

    /**
     * A deposit onto a policy's balance (money movement step 5, design 7.2): no invoice is named, and none need be owed
     * -- what has fallen due is paid oldest first once it is confirmed, and the rest waits for the next premiums. A
     * savings-type policy keeps the invoice road (design D12, until "For: Savings"): its deposit pays the oldest open
     * invoice, with that road's cap.
     */
    private ReceiptView recordForPolicy(NewReceipt in, String reference, String recordedBy, UUID tenantId) {
        String policyNumber = in.policyNumber().trim().toUpperCase();
        List<InvoiceView> invoices = billing.listInvoices(policyNumber, null);
        if (invoices.isEmpty()) {
            throw new ReceiptRefusedException("No policy " + policyNumber + " is billed here");
        }
        if (billing.isSavingsType(policyNumber)) {
            InvoiceView oldest = invoices.stream().filter(i -> OPEN.contains(i.status())).findFirst()
                .orElseThrow(() -> new ReceiptRefusedException("Nothing is owed on " + policyNumber
                    + ": every invoice is paid or waived"));
            return record(new NewReceipt(oldest.invoiceId(), null, in.method(), in.reference(), in.amount(),
                in.receivedOn(), in.payerRef(), in.note(), in.fieldReceiptId()), recordedBy);
        }
        if (transactions.existsByTenantIdAndMethodAndGatewayReferenceAndStatusNot(tenantId, in.method().name(),
                reference, REJECTED)) {
            throw new ReceiptRefusedException("A receipt with this " + words(in.method()) + " reference (" + reference
                + ") is already recorded");
        }
        if (in.fieldReceiptId() != null
                && transactions.existsByTenantIdAndFieldReceiptIdAndStatusNot(tenantId, in.fieldReceiptId(), REJECTED)) {
            throw new ReceiptRefusedException("This agent's receipt is already recorded");
        }
        PaymentTransaction deposit = PaymentTransaction.manualDeposit(tenantId, "receipt:" + UUID.randomUUID(),
            in.method().name(), reference, blankToNull(in.payerRef()) == null ? "—" : in.payerRef().trim(), in.amount(),
            invoices.get(0).currency(), policyNumber, in.receivedOn(), blankToNull(in.note()), in.fieldReceiptId(),
            recordedBy);
        transactions.save(deposit);
        return view(deposit);
    }

    /**
     * A deposit For: Loan (money movement step 1): capped at what the loan owes, less what is already waiting on it --
     * a typed deposit or a mobile-money request -- so a loan is never repaid twice over while one waits.
     */
    private ReceiptView recordForLoan(NewReceipt in, String reference, String recordedBy, UUID tenantId) {
        LoanView loan = loans.getLoan(in.loanId());
        if (loan.status() != LoanStatus.DISBURSED && loan.status() != LoanStatus.REPAYING) {
            throw new ReceiptRefusedException("This loan is " + loan.status().name().toLowerCase()
                + ": there is nothing to repay on it");
        }
        BigDecimal waiting = transactions.findByTenantIdAndSourceRefAndStatusOrderByCreatedAtAsc(tenantId,
                in.loanId().toString(), "AWAITING_APPROVAL").stream()
            .map(PaymentTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal room = loan.outstandingBalance().subtract(waiting).max(BigDecimal.ZERO);
        if (in.amount().compareTo(room) > 0) {
            throw new ReceiptRefusedException("The loan owes " + loan.outstandingBalance().toPlainString() + " "
                + loan.outstandingCurrency() + (waiting.signum() > 0 ? ", and " + waiting.toPlainString()
                + " is already waiting to be confirmed on it" : "") + ". Record at most " + room.toPlainString());
        }
        if (transactions.existsByTenantIdAndMethodAndGatewayReferenceAndStatusNot(tenantId, in.method().name(),
                reference, REJECTED)) {
            throw new ReceiptRefusedException("A receipt with this " + words(in.method()) + " reference (" + reference
                + ") is already recorded");
        }
        PaymentTransaction receipt = PaymentTransaction.manualLoanReceipt(tenantId, "receipt:" + UUID.randomUUID(),
            in.method().name(), reference, blankToNull(in.payerRef()) == null ? "—" : in.payerRef().trim(), in.amount(),
            loan.outstandingCurrency(), in.loanId(), loan.policyNumber(), in.receivedOn(), blankToNull(in.note()),
            recordedBy);
        transactions.save(receipt);
        return view(receipt);
    }

    /** The second person confirms it: the invoice or loan is paid from it and the ledger posts it, by the method. */
    @Transactional
    public ReceiptView approve(UUID receiptId, String by) {
        PaymentTransaction receipt = find(receiptId);
        if (ownWork.mayActAlone("collections.approve-own")) receipt.allowActingAlone();
        receipt.approveReceipt(by);
        recordIfAlone(receipt, "confirmed", by);
        transactions.saveAndFlush(receipt);
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentRequestId", receipt.getPaymentTransactionId());
        // Given by the database as the row turned CONFIRMED (receipts step 1); the customer's SMS quotes it, and the
        // answer carries it -- the entity's own copy is only read when the row is next loaded.
        String number = transactions.receiptNumberOf(receipt.getPaymentTransactionId());
        payload.put("receiptNumber", java.util.Objects.requireNonNullElse(number, ""));
        payload.put("idempotencyKey", receipt.getIdempotencyKey());
        payload.put("sourceRef", receipt.getSourceRef());
        payload.put("gatewayReference", receipt.getGatewayReference());
        payload.put("amount", Map.of("amount", receipt.getAmount().toPlainString(), "currencyCode", receipt.getCurrency()));
        payload.put("confirmedAt", Instant.now().toString());
        payload.put("purpose", receipt.getPurpose());
        payload.put("payerRef", receipt.getPayerRef());
        payload.put("method", receipt.getMethod());
        payload.put("policyNumber", receipt.getPolicyNumber());
        if (receipt.getFieldReceiptId() != null) payload.put("fieldReceiptId", receipt.getFieldReceiptId());
        payload.put("approvedBy", by);
        events.publishEvent(DomainEventEnvelope.of("payment.PaymentConfirmed", TenantContext.get(), payload));
        receiptNotices.issued(receipt);
        return view(receipt, number);
    }

    @Transactional
    public ReceiptView reject(UUID receiptId, String by, String reason) {
        if (reason == null || reason.isBlank()) throw new ReceiptRefusedException("Say why the receipt is refused");
        PaymentTransaction receipt = find(receiptId);
        if (ownWork.mayActAlone("collections.approve-own")) receipt.allowActingAlone();
        receipt.rejectReceipt(by, reason.trim());
        recordIfAlone(receipt, "refused", by);
        transactions.save(receipt);
        if (receipt.isRequested() || receipt.isSavingsDeposit()) {
            // A request the platform made, or a savings deposit waiting in accumulation (2026-11-07): whoever asked for
            // the money hears it never came, exactly as a declined request -- a loan repayment or a top-up unwinds on it.
            events.publishEvent(DomainEventEnvelope.of("payment.PaymentFailed", TenantContext.get(),
                Map.of("paymentRequestId", receipt.getPaymentTransactionId(),
                       "idempotencyKey", receipt.getIdempotencyKey(),
                       "sourceRef", receipt.getSourceRef(),
                       "reason", reason.trim(),
                       "purpose", receipt.getPurpose())));
        }
        return view(receipt);
    }

    /**
     * Any amount received, by its id -- one recorded by hand or one the gateway pulled (receipts step 1): every amount
     * received has its receipt, so every one can be opened. Deciding one stays with the recorded kind ({@link #find}).
     */
    @Transactional(readOnly = true)
    public ReceiptView get(UUID receiptId) {
        return view(transactions.findByPaymentTransactionIdAndTenantId(receiptId, TenantContext.get())
            .orElseThrow(() -> new PaymentNotFoundException("Receipt " + receiptId + " not found")));
    }

    /** A receipt by its number, as printed on the PDF and sent by SMS; the case and spaces a person types are forgiven. */
    @Transactional(readOnly = true)
    public ReceiptView byNumber(String receiptNumber) {
        String number = receiptNumber.trim().toUpperCase(java.util.Locale.ROOT);
        return view(transactions.findByTenantIdAndReceiptNumber(TenantContext.get(), number)
            .orElseThrow(() -> new PaymentNotFoundException("Receipt " + number + " not found")));
    }

    /**
     * One invoice's receipts still waiting for their second signature, oldest first -- shown on the invoice, so a
     * recorded receipt is visible where it was recorded and not only to somebody else's inbox (2026-10-28).
     */
    @Transactional(readOnly = true)
    public List<ReceiptView> waitingOn(UUID invoiceId) {
        return transactions.findByTenantIdAndSourceRefAndStatusOrderByCreatedAtAsc(TenantContext.get(), invoiceId.toString(),
            "AWAITING_APPROVAL").stream().map(Receipts::view).toList();
    }

    /**
     * One policy's deposits still waiting for their second signature, oldest first -- shown on the policy's balance,
     * since a deposit naming only the policy has no invoice to show it on (money movement).
     */
    @Transactional(readOnly = true)
    public List<ReceiptView> waitingOnPolicy(String policyNumber) {
        return transactions.findByTenantIdAndPolicyNumberAndPurposeAndStatusOrderByCreatedAtAsc(TenantContext.get(),
            policyNumber, "DEPOSIT", "AWAITING_APPROVAL").stream().map(Receipts::view).toList();
    }

    /** Receipts waiting for their second signature, oldest first: the approvals inbox's rows. */
    @Transactional(readOnly = true)
    public List<ReceiptView> awaitingApproval() {
        return transactions.findByTenantIdAndStatusOrderByCreatedAtAsc(TenantContext.get(), "AWAITING_APPROVAL").stream()
            .map(Receipts::view).toList();
    }

    private PaymentTransaction find(UUID receiptId) {
        PaymentTransaction t = transactions.findByPaymentTransactionIdAndTenantId(receiptId, TenantContext.get())
            .orElseThrow(() -> new PaymentNotFoundException("Receipt " + receiptId + " not found"));
        if (t.getRecordedBy() == null) throw new PaymentNotFoundException("Receipt " + receiptId + " not found");
        return t;
    }

    static ReceiptView view(PaymentTransaction t) {
        return view(t, t.getReceiptNumber());
    }

    /** With the receipt number as the database has it, when the entity's copy predates the confirming flush. */
    static ReceiptView view(PaymentTransaction t, String receiptNumber) {
        // The source is an invoice only for a premium and a loan only for a loan repayment. A savings top-up recorded by
        // hand is a receipt too, and its source is a top-up id -- named neither, rather than shown as an invoice.
        UUID source = UUID.fromString(t.getSourceRef());
        UUID invoiceId = "PREMIUM".equals(t.getPurpose()) ? source : null;
        UUID loanId = "LOAN_REPAYMENT".equals(t.getPurpose()) ? source : null;
        return new ReceiptView(t.getPaymentTransactionId(), t.getPurpose(), t.getMethod(), t.getGatewayReference(),
            t.getPayerRef(), t.getAmount(), t.getCurrency(), invoiceId, loanId,
            t.getPolicyNumber(), t.getReceivedOn(), t.getNote(), t.getFieldReceiptId(), t.getStatus(), t.getRecordedBy(),
            t.getCreatedAt(), t.getDecidedBy(), t.getDecidedAt(), t.getDecisionReason(), receiptNumber);
    }

    public static String words(ReceiptMethod method) {
        return switch (method) {
            case CASH -> "cash";
            case BANK_DEPOSIT -> "bank deposit";
            case CHEQUE -> "cheque";
            case LIPA_NUMBER -> "Lipa number";
            case TANQR -> "TANQR";
        };
    }

    /** As {@link #words(ReceiptMethod)}, for a stored method -- which may also be a mobile-money request. */
    public static String words(String method) {
        return "MOBILE_MONEY".equals(method) ? "mobile money" : words(ReceiptMethod.valueOf(method));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
