package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalSource;

/** A surrender waiting for its second signature (2026-10-27): before the approvals screen, visible only on its policy. */
@Configuration
class PolicyApprovalSources {

    @Bean
    ApprovalSource surrenderApprovals(SurrenderRequestRepository surrenders) {
        return ApprovalSource.of("surrender", "Surrenders", "Approve", "policies.surrender-approve",
            limit -> ApprovalSource.Pending.of(
                surrenders.findByTenantIdAndStatusOrderByRequestedAtAsc(TenantContext.get(), "REQUESTED").stream()
                    .map(r -> new ApprovalSource.Item(r.getSurrenderRequestId().toString(), r.getPolicyNumber(),
                        "Surrender, paid to " + r.getPayeeRef(), r.getQuotedValueAmount(), r.getQuotedValueCurrency(),
                        r.getRequestedBy(), r.getRequestedAt()))
                    .toList(), limit));
    }
}
