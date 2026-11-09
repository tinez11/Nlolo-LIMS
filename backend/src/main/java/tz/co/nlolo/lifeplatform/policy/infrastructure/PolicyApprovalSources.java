package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalSource;

/** A surrender waiting for its second signature (2026-10-27): before the approvals screen, visible only on its policy. */
@Configuration
class PolicyApprovalSources {

    /**
     * Whose policy each waiting row is on, for the approvals page (2026-11-09, the user: it "doesn't show more
     * details"). One read of the policies and one of the names, whatever the number of rows.
     */
    @Bean
    tz.co.nlolo.lifeplatform.approvals.api.PolicyHolders policyHolderNames(PolicyRepository policies,
            tz.co.nlolo.lifeplatform.party.api.PartyApi parties) {
        return policyNumbers -> {
            java.util.UUID tenantId = TenantContext.get();
            java.util.Map<String, java.util.UUID> holders = new java.util.HashMap<>();
            policies.findAllById(policyNumbers).stream()
                .filter(p -> tenantId.equals(p.getTenantId()) && p.getPolicyholderPartyId() != null)
                .forEach(p -> holders.put(p.getPolicyNumber(), p.getPolicyholderPartyId()));
            java.util.Map<java.util.UUID, String> names = parties.displayNames(new java.util.HashSet<>(holders.values()));
            java.util.Map<String, String> out = new java.util.HashMap<>();
            holders.forEach((policy, party) -> {
                String name = names.get(party);
                if (name != null) out.put(policy, name);
            });
            return out;
        };
    }

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
