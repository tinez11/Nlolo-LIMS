package tz.co.nlolo.lifeplatform.claims.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.approvals.api.ActionSource;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.domain.Claim;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;

import java.util.Map;
import java.util.function.Function;

/** Claims' jobs for the bell (2026-11-03): a claim to assess, and an assessed one to decide. */
@Configuration
class ClaimActionSources {

    @Bean
    ActionSource claimToAssess(ClaimRepository claims, PartyApi parties) {
        return ActionSource.forStaff("claim-assess", "claims.assess", "Assess it", (viewer, limit) ->
            read(claims, parties, ClaimStatus.REGISTERED, "createdAt", limit, Claim::getCreatedAt, "registered"));
    }

    @Bean
    ActionSource claimToSettle(ClaimRepository claims, PartyApi parties) {
        return ActionSource.forStaff("claim-settle", "claims.settle", "Approve or repudiate it", (viewer, limit) ->
            // Assessed, waiting for the manager: UNDER_ASSESSMENT. SETTLEMENT_REQUESTED is approved and paying already.
            read(claims, parties, ClaimStatus.UNDER_ASSESSMENT, "updatedAt", limit, Claim::getUpdatedAt,
                "assessed"));
    }

    private static ActionSource.Pending read(ClaimRepository claims, PartyApi parties, ClaimStatus status, String sort,
                                             int limit, Function<Claim, java.time.Instant> since, String what) {
        Page<Claim> page = claims.findByTenantIdAndStatus(TenantContext.get(), status,
            PageRequest.of(0, limit, Sort.by(Sort.Order.desc(sort), Sort.Order.asc("claimId"))));
        Map<java.util.UUID, String> names = parties.displayNames(page.getContent().stream()
            .map(Claim::getClaimantPartyId).filter(java.util.Objects::nonNull).toList());
        return new ActionSource.Pending(page.getTotalElements(), page.getContent().stream()
            .map(c -> new ActionSource.Item(c.getClaimId().toString(), c.getPolicyNumber(),
                names.getOrDefault(c.getClaimantPartyId(), "A claimant"),
                "Claim on " + c.getPolicyNumber() + ", " + what, since.apply(c)))
            .toList());
    }
}
