package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationApi;
import tz.co.nlolo.lifeplatform.accumulation.api.AwaitingPayeeView;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalSource;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.time.ZoneId;
import java.util.List;

/**
 * What a savings account waits on (2026-10-27): a withdrawal or an adjustment for its second signature, and a matured
 * deposit or term account for a payee. Before the approvals screen each was visible only on its own policy.
 */
@Configuration
class AccumulationApprovalSources {

    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");

    @Bean
    ApprovalSource accountWithdrawalApprovals(WithdrawalRequestRepository withdrawals, TransactionTemplate tx) {
        return ApprovalSource.of("account-withdrawal", "Account withdrawals", "Approve", "accounts.approve",
            limit -> tx.execute(s -> ApprovalSource.Pending.of(
                withdrawals.findByStatusOrderByRequestedAtAsc("REQUESTED").stream()
                    .map(w -> new ApprovalSource.Item(w.getWithdrawalId().toString(), w.getPolicyNumber(),
                        "Withdrawal to " + w.getPayeeRef(), w.getAmount(), w.getCurrency(), w.getRequestedBy(),
                        w.getRequestedAt()))
                    .toList(), limit)));
    }

    @Bean
    ApprovalSource accountAdjustmentApprovals(AdjustmentRequestRepository adjustments, TransactionTemplate tx) {
        return ApprovalSource.of("account-adjustment", "Account adjustments", "Approve", "accounts.approve",
            limit -> tx.execute(s -> ApprovalSource.Pending.of(
                adjustments.findByStatusOrderByProposedAtAsc("PROPOSED").stream()
                    .map(a -> new ApprovalSource.Item(a.getAdjustmentId().toString(), a.getPolicyNumber(),
                        "Adjustment: " + a.getReason(), a.getAmount(), null, a.getProposedBy(), a.getProposedAt()))
                    .toList(), limit)));
    }

    @Bean
    ApprovalSource maturedDepositPayees(AccumulationApi accounts) {
        return ApprovalSource.of("deposit-payee", "Matured deposits without a payee", "Name the payee",
            "payouts.manage", false, limit -> ApprovalSource.Pending.of(payees(accounts.listAwaitingPayee(),
                "Deposit matured"), limit));
    }

    @Bean
    ApprovalSource maturedTermAccountPayees(AccumulationApi accounts) {
        return ApprovalSource.of("term-account-payee", "Matured term accounts without a payee", "Name the payee",
            "payouts.manage", false, limit -> ApprovalSource.Pending.of(payees(accounts.listTermAccountsAwaitingPayee(),
                "Term account matured"), limit));
    }

    /** A credited rate for a savings product, waiting for its second signature. */
    @Bean
    ApprovalSource rateDeclarationApprovals(RateDeclarationRepository declarations, ProductApi products) {
        return ApprovalSource.of("rate-declaration", "Credited interest rates", "Approve", "products.declare-approve",
            limit -> ApprovalSource.Pending.of(declarations.findByStatusOrderByProposedAtAsc("PROPOSED").stream()
                .map(d -> new ApprovalSource.Item(d.getProductId().toString(), null,
                    products.getProduct(d.getProductId()).productName() + ": "
                        + d.getRatePercent().stripTrailingZeros().toPlainString() + "% from " + ApprovalSource.day(d.getEffectiveFrom()),
                    null, null, d.getProposedBy(), d.getProposedAt()))
                .toList(), limit));
    }

    private static List<ApprovalSource.Item> payees(List<AwaitingPayeeView> awaiting, String what) {
        return awaiting.stream()
            .map(a -> new ApprovalSource.Item(a.policyNumber(), a.policyNumber(), what + " with nobody to pay",
                a.balance(), a.currency(), null, a.maturedOn() == null ? null
                    : a.maturedOn().atStartOfDay(CIVIL).toInstant()))
            .toList();
    }
}
