package tz.co.nlolo.lifeplatform.policy.api;

import java.util.List;
import java.util.UUID;

/**
 * What claims needs to know about the covered life a funeral claim names (plan R9): whose death it is and
 * who may file for it.
 *
 * <p>Strings, not {@code FuneralRole} / {@code DependantClaimPayee}: claims may not reference a product
 * type (ModularityTests), the reason {@code claimableCover} takes the benefit type as a name.
 *
 * @param role                 MAIN_MEMBER, SPOUSE, CHILD, PARENT or EXTENDED
 * @param dependantClaimPayee  MAIN_MEMBER or MAIN_MEMBER_BENEFICIARY: who files when a dependant dies
 * @param beneficiaryPartyIds  the policy's active beneficiaries who are registered parties
 * @param groupScheme          a group funeral scheme's life (2026-10-07): the policyholder is the association, so the
 *                             family's main member and the beneficiary they named (below) stand where an individual
 *                             policy's policyholder and beneficiaries do
 * @param mainMemberPartyId    on a scheme, the family's main member once registered as a party (at claim); else null
 * @param mainMemberName       on a scheme, the family's main member
 * @param beneficiaryName      on a scheme, the beneficiary the main member named, if any
 */
public record FuneralClaimFacts(String role, UUID policyholderPartyId, String dependantClaimPayee,
                                List<UUID> beneficiaryPartyIds, boolean groupScheme, UUID mainMemberPartyId,
                                String mainMemberName, String beneficiaryName) {

    public FuneralClaimFacts {
        beneficiaryPartyIds = beneficiaryPartyIds != null ? List.copyOf(beneficiaryPartyIds) : List.of();
    }

    /** An individual funeral policy's facts. */
    public FuneralClaimFacts(String role, UUID policyholderPartyId, String dependantClaimPayee,
                             List<UUID> beneficiaryPartyIds) {
        this(role, policyholderPartyId, dependantClaimPayee, beneficiaryPartyIds, false, null, null, null);
    }

    public boolean mainMember() {
        return "MAIN_MEMBER".equals(role);
    }
}
