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
 */
public record FuneralClaimFacts(String role, UUID policyholderPartyId, String dependantClaimPayee,
                                List<UUID> beneficiaryPartyIds) {

    public FuneralClaimFacts {
        beneficiaryPartyIds = beneficiaryPartyIds != null ? List.copyOf(beneficiaryPartyIds) : List.of();
    }

    public boolean mainMember() {
        return "MAIN_MEMBER".equals(role);
    }
}
