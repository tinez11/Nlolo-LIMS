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
