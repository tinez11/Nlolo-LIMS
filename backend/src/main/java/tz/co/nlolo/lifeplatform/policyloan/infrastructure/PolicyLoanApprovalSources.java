package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalSource;
import tz.co.nlolo.lifeplatform.policyloan.api.PolicyLoanApi;

/** A loan taken and waiting for a second person to release its payout (money movement step 4, 2026-11-04). */
@Configuration
class PolicyLoanApprovalSources {

    @Bean
    ApprovalSource loanPayoutsToApprove(PolicyLoanApi loans) {
        return ApprovalSource.of("loan-payout", "Loans to pay out", "Release", "payouts.approve",
            limit -> ApprovalSource.Pending.of(loans.listLoansAwaitingPayoutApproval().stream()
                .map(l -> new ApprovalSource.Item(l.loanId().toString(), l.policyNumber(), "Policy loan payout",
                    l.principalAmount(), l.principalCurrency(), null, null))
                .toList(), limit));
    }
}
