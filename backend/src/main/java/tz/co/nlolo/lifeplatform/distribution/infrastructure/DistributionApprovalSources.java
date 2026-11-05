package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalSource;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;

/** A commission statement asked to be paid and waiting for a second person (money movement step 4, 2026-11-04). */
@Configuration
class DistributionApprovalSources {

    @Bean
    ApprovalSource commissionPayoutsToApprove(DistributionApi distribution) {
        return ApprovalSource.of("commission-payout", "Commission payouts", "Release", "payouts.approve",
            limit -> ApprovalSource.Pending.of(distribution.listStatementsAwaitingPayoutApproval().stream()
                // agentId/statementId: unique per row, and the agent's page is where the payout is released (step 5).
                .map(s -> new ApprovalSource.Item(s.agentId() + "/" + s.statementId(), null, "Commission payout, " + s.period(),
                    s.totalAmount(), s.totalCurrency(), s.payoutRequestedBy(), null))
                .toList(), limit));
    }
}
