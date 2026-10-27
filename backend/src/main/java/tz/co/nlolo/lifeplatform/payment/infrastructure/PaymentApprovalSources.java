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
        return ApprovalSource.of("receipt", "Receipts to confirm", "Confirm", "collections.approve",
            limit -> ApprovalSource.Pending.of(receipts.awaitingApproval().stream()
                .map(r -> new ApprovalSource.Item(r.receiptId().toString(), r.policyNumber(),
                    Receipts.words(tz.co.nlolo.lifeplatform.payment.api.ReceiptMethod.valueOf(r.method())) + " " + r.reference(),
                    r.amount(), r.currency(), r.recordedBy(), r.recordedAt()))
                .toList(), limit));
    }
}
