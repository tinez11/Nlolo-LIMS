package tz.co.nlolo.lifeplatform.payment.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalSource;
import tz.co.nlolo.lifeplatform.payment.api.PaymentApi;
import tz.co.nlolo.lifeplatform.payment.application.Receipts;

/** A payout instructed by bank transfer, waiting for somebody to make it in the bank (2026-10-27). */
@Configuration
class PaymentApprovalSources {

    @Bean
    ApprovalSource bankTransfersToMake(PaymentApi payments) {
        return ApprovalSource.of("bank-transfer", "Bank transfers to make", "Make the transfer", "payouts.manage", false,
            limit -> ApprovalSource.Pending.of(payments.listAwaitingEftExecution().stream()
                .map(d -> new ApprovalSource.Item(d.disbursementId().toString(), null,
                    "Transfer to " + d.payeeRef(), d.amount(), d.currency(), null, d.createdAt()))
                .toList(), limit));
    }

    /** A receipt one finance officer recorded, waiting for another to confirm it (2026-10-27). */
    @Bean
    ApprovalSource receiptApprovals(Receipts receipts) {
        return ApprovalSource.of("receipt", "Deposits to confirm", "Confirm", "collections.approve",
            limit -> ApprovalSource.Pending.of(receipts.awaitingApproval().stream()
                .map(r -> new ApprovalSource.Item(r.receiptId().toString(), r.policyNumber(),
                    capital(Receipts.words(r.method())) + " " + r.reference() + (r.loanId() != null ? " · loan repayment"
                        : "ACCOUNT_TOP_UP".equals(r.purpose()) ? " · savings deposit"
                        : "UL_TOP_UP".equals(r.purpose()) ? " · fund top-up" : ""),
                    r.amount(), r.currency(), r.recordedBy(), r.recordedAt(), depositLine(r)))
                .toList(), limit));
    }

    /**
     * Cash an agent collected that nobody has recorded as received yet, overdue first (2026-10-27; here, not in billing,
     * since 2026-11-11). Cash with a receipt already waiting for its second person is left out: it is in Deposits to
     * confirm, and listing it here too asked finance to record the same money twice.
     */
    @Bean
    ApprovalSource agentCashToRecord(tz.co.nlolo.lifeplatform.billing.api.BillingApi billing, Receipts receipts) {
        return ApprovalSource.of("field-receipt", "Agent cash to record as received", "Record as received",
            "collections.manage", false, limit -> {
                java.util.Set<java.util.UUID> waiting = receipts.agentCashWaiting().keySet();
                // Every open one, not a page: the ones already recorded must come out of the count, not just the rows.
                org.springframework.data.domain.PageRequest all = org.springframework.data.domain.PageRequest.of(0, 1000);
                java.util.List<ApprovalSource.Item> rows = new java.util.ArrayList<>();
                billing.searchFieldReceipts("RECONCILIATION_OVERDUE", all).forEach(r -> {
                    if (!waiting.contains(r.receiptId())) rows.add(agentCashItem(r, "Still with the agent past its deadline"));
                });
                billing.searchFieldReceipts("PENDING_RECONCILIATION", all).forEach(r -> {
                    if (!waiting.contains(r.receiptId())) rows.add(agentCashItem(r, null));
                });
                return ApprovalSource.Pending.of(rows, limit);
            });
    }

    /** Short on the row; the agent's slip reference, when given, is what finance matches against the bank statement. */
    private static ApprovalSource.Item agentCashItem(tz.co.nlolo.lifeplatform.billing.api.FieldReceiptView r, String detail) {
        String banked = r.bankedReference() == null ? null : "Agent says banked under " + r.bankedReference();
        String line = detail == null ? banked : banked == null ? detail : detail + " · " + banked;
        return new ApprovalSource.Item(r.receiptId().toString(), r.policyNumber(), "Agent cash", r.amount(), r.currency(),
            null, r.capturedAtServer(), line);
    }

    /** "Mobile money", not "mobile money", at the head of a row (2026-11-09). */
    static String capital(String words) {
        return words == null || words.isEmpty() ? "" : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** Who paid, when it was received, and the recorder's note -- the second line of a deposit to confirm (2026-11-09). */
    static String depositLine(tz.co.nlolo.lifeplatform.payment.api.ReceiptView r) {
        StringBuilder s = new StringBuilder();
        if (r.payerRef() != null && !r.payerRef().isBlank()) s.append("From ").append(r.payerRef());
        if (r.receivedOn() != null) s.append(s.isEmpty() ? "Received " : " · received ").append(ApprovalSource.day(r.receivedOn()));
        if (r.note() != null && !r.note().isBlank()) s.append(s.isEmpty() ? "" : " · ").append("“").append(r.note().trim()).append("”");
        return s.isEmpty() ? null : s.toString();
    }
}
