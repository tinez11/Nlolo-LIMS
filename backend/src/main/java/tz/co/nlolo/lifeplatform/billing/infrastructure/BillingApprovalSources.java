package tz.co.nlolo.lifeplatform.billing.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalSource;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.FieldReceiptView;
import tz.co.nlolo.lifeplatform.billing.application.BalanceWithdrawals;
import tz.co.nlolo.lifeplatform.billing.domain.BalanceWithdrawal;

import java.util.ArrayList;
import java.util.List;

/** Cash an agent collected and nobody has matched yet, overdue first (2026-10-27). */
@Configuration
class BillingApprovalSources {

    @Bean
    ApprovalSource fieldReceiptsToMatch(BillingApi billing) {
        return ApprovalSource.of("field-receipt", "Field receipts to match", "Match", "collections.manage", false,
            limit -> {
                PageRequest page = PageRequest.of(0, Math.max(1, limit));
                Page<FieldReceiptView> overdue = billing.searchFieldReceipts("RECONCILIATION_OVERDUE", page);
                Page<FieldReceiptView> pending = billing.searchFieldReceipts("PENDING_RECONCILIATION", page);
                List<ApprovalSource.Item> rows = new ArrayList<>();
                overdue.forEach(r -> rows.add(item(r, "Cash collected by an agent, past its matching deadline")));
                pending.forEach(r -> rows.add(item(r, "Cash collected by an agent")));
                return new ApprovalSource.Pending(overdue.getTotalElements() + pending.getTotalElements(),
                    rows.stream().limit(limit).toList());
            });
    }

    /** Money asked back off a policy's balance, waiting for its second person (money movement step 3a). */
    @Bean
    ApprovalSource balanceWithdrawalsToApprove(BalanceWithdrawals withdrawals) {
        return ApprovalSource.of("balance-withdrawal", "Withdrawals to approve", "Approve", "payouts.approve",
            limit -> ApprovalSource.Pending.of(withdrawals.waiting("REQUESTED").stream()
                .map(w -> withdrawalItem(w, "Withdrawal from the policy's balance")).toList(), limit));
    }

    /** A policy ended with money on its balance: finance names who receives it (money movement step 3a). */
    @Bean
    ApprovalSource balanceWithdrawalsAwaitingPayee(BalanceWithdrawals withdrawals) {
        return ApprovalSource.of("balance-withdrawal-payee", "Withdrawals waiting for a payee", "Name the payee",
            "payouts.manage", false, limit -> ApprovalSource.Pending.of(withdrawals.waiting("AWAITING_PAYEE").stream()
                .map(w -> withdrawalItem(w, "The policy ended with money on its balance")).toList(), limit));
    }

    /** A deposit whose money never arrived, waiting for its second person to reverse it (money movement step 3b). */
    @Bean
    ApprovalSource depositReversalsToConfirm(tz.co.nlolo.lifeplatform.billing.application.DepositReversals reversals) {
        return ApprovalSource.of("deposit-reversal", "Deposit reversals to confirm", "Confirm", "collections.approve",
            limit -> ApprovalSource.Pending.of(reversals.waiting().stream()
                .map(r -> new ApprovalSource.Item(r.getReversalId().toString(), r.getPolicyNumber(),
                    "Reverse a deposit: " + r.getReason(), r.getAmount(), r.getCurrency(), r.getRequestedBy(),
                    r.getRequestedAt())).toList(), limit));
    }

    private static ApprovalSource.Item withdrawalItem(BalanceWithdrawal w, String what) {
        return new ApprovalSource.Item(w.getWithdrawalId().toString(), w.getPolicyNumber(), what, w.getAmount(),
            w.getCurrency(), "system".equals(w.getRequestedBy()) ? null : w.getRequestedBy(), w.getRequestedAt());
    }

    private static ApprovalSource.Item item(FieldReceiptView r, String what) {
        return new ApprovalSource.Item(r.receiptId().toString(), r.policyNumber(), what, r.amount(), r.currency(), null,
            r.capturedAtServer());
    }
}
