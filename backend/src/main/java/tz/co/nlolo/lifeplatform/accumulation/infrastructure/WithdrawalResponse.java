package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import tz.co.nlolo.lifeplatform.accumulation.api.WithdrawalView;

public record WithdrawalResponse(String withdrawalId, String policyNumber, MoneyResponse amount, String payeeRef,
                                 String status, String requestedBy, String requestedAt, String approvedBy,
                                 String approvedAt, String method, String payeeBank, String payeeBranch,
                                 String payeeAccountName, String payeeAccountNumber) {
    static WithdrawalResponse from(WithdrawalView w) {
        return new WithdrawalResponse(w.withdrawalId().toString(), w.policyNumber(), MoneyResponse.of(w.amount(), w.currency()),
            w.payeeRef(), w.status(), w.requestedBy(), w.requestedAt().toString(), w.approvedBy(),
            w.approvedAt() != null ? w.approvedAt().toString() : null, w.route().method(), w.route().bank(),
            w.route().branch(), w.route().accountName(), w.route().accountNumber());
    }
}
