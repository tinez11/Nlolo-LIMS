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
    private final ApplicationEventPublisher events;

    Receipts(PaymentTransactionRepository transactions, BillingApi billing, ApplicationEventPublisher events) {
        this.transactions = transactions;
        this.billing = billing;
        this.events = events;
    }

    /** What a person records. {@code fieldReceiptId} when it is an agent's cash being brought in. */
    public record NewReceipt(UUID invoiceId, ReceiptMethod method, String reference, BigDecimal amount,
                             LocalDate receivedOn, String payerRef, String note, UUID fieldReceiptId) {}

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

        InvoiceView invoice = billing.getInvoice(in.invoiceId());
        if (invoice.status() == InvoiceStatus.PAID || invoice.status() == InvoiceStatus.WAIVED) {
            throw new ReceiptRefusedException("Invoice due " + invoice.dueDate() + " is " + invoice.status()
                .name().toLowerCase() + ": there is nothing left to receive on it");
        }
        BigDecimal owed = invoice.balanceDue() == null ? invoice.amount() : invoice.balanceDue();
        if (in.amount().compareTo(owed) > 0) {
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
        if (!waiting.isEmpty() && waitingTotal.add(in.amount()).compareTo(owed) > 0) {
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

    /** The second person confirms it: the invoice is paid from it and the ledger posts it, by the method. */
    @Transactional
    public ReceiptView approve(UUID receiptId, String by) {
        PaymentTransaction receipt = find(receiptId);
        receipt.approveReceipt(by);
        transactions.save(receipt);
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentRequestId", receipt.getPaymentTransactionId());
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
        return view(receipt);
    }

    @Transactional
    public ReceiptView reject(UUID receiptId, String by, String reason) {
        if (reason == null || reason.isBlank()) throw new ReceiptRefusedException("Say why the receipt is refused");
        PaymentTransaction receipt = find(receiptId);
        receipt.rejectReceipt(by, reason.trim());
        transactions.save(receipt);
        if (receipt.isRequested()) {
            // A request the platform made, not a slip someone typed: whoever asked for the money hears it never came,
            // exactly as a declined request -- a loan repayment or a top-up unwinds on it.
            events.publishEvent(DomainEventEnvelope.of("payment.PaymentFailed", TenantContext.get(),
                Map.of("paymentRequestId", receipt.getPaymentTransactionId(),
                       "idempotencyKey", receipt.getIdempotencyKey(),
                       "sourceRef", receipt.getSourceRef(),
                       "reason", reason.trim(),
                       "purpose", receipt.getPurpose())));
        }
        return view(receipt);
    }

    @Transactional(readOnly = true)
    public ReceiptView get(UUID receiptId) {
        return view(find(receiptId));
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
        return new ReceiptView(t.getPaymentTransactionId(), t.getMethod(), t.getGatewayReference(), t.getPayerRef(),
            t.getAmount(), t.getCurrency(), UUID.fromString(t.getSourceRef()), t.getPolicyNumber(), t.getReceivedOn(),
            t.getNote(), t.getFieldReceiptId(), t.getStatus(), t.getRecordedBy(), t.getCreatedAt(), t.getDecidedBy(),
            t.getDecidedAt(), t.getDecisionReason());
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
