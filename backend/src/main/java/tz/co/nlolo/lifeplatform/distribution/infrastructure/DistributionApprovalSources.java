package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalSource;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionPlanView;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionRuleView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;

import java.math.BigDecimal;
import java.util.stream.Collectors;

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

    /** A commission plan or an agent's own rate, waiting for a second person (2026-11-12, finance finding 190). */
    @Bean
    ApprovalSource commissionPlansToApprove(DistributionApi distribution) {
        return ApprovalSource.of("commission-plan", "Commission plans", "Approve", "commission.approve",
            limit -> ApprovalSource.Pending.of(distribution.proposedCommissionPlans().stream()
                .map(p -> new ApprovalSource.Item(p.commissionPlanId().toString(), null, summary(p), null, null,
                    p.proposedBy(), p.proposedAt(), rules(p)))
                .toList(), limit));
    }

    private static String summary(CommissionPlanView p) {
        String product = p.productName() == null ? "a product" : p.productName();
        return p.proposedForAgentId() == null
            ? "Commission plan for " + product
            : (p.agentName() == null ? "An agent's" : p.agentName() + "'s") + " own rate on " + product;
    }

    /** "First year 10%, renewal 3%": what is being approved, in one line. */
    static String rules(CommissionPlanView p) {
        return p.rules().stream().map(DistributionApprovalSources::rule).collect(Collectors.joining(", "));
    }

    private static String rule(CommissionRuleView r) {
        String tier = r.tierType().name().replace('_', ' ').toLowerCase();
        tier = Character.toUpperCase(tier.charAt(0)) + tier.substring(1);
        if (r.flatAmount() != null) return tier + " " + r.flatCurrency() + " " + r.flatAmount().toPlainString();
        return tier + " " + r.rate().multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
    }
}
