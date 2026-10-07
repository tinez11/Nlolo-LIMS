package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;

import java.time.LocalDate;
import java.util.List;

/** The bodies of the group funeral family endpoints (2026-10-07). Unannotated: the service refuses in the plan's words. */
final class GroupFuneralRequests {
    private GroupFuneralRequests() {}

    record Life(String memberReference, FuneralRole role, String fullName, LocalDate dateOfBirth, String sex,
                String idNumber, Boolean student, String beneficiaryName, String beneficiaryRelationship,
                String beneficiaryPhone) {
        PolicyApi.GroupFuneralLifeInput toInput() {
            return new PolicyApi.GroupFuneralLifeInput(memberReference, role, fullName, dateOfBirth, sex, idNumber,
                Boolean.TRUE.equals(student), beneficiaryName, beneficiaryRelationship, beneficiaryPhone);
        }
    }

    /** A family joining: its lives (one main member, all sharing the member reference) and the day cover starts. */
    record Join(LocalDate joinedOn, List<Life> lives) {
        List<PolicyApi.GroupFuneralLifeInput> toInputs() {
            return lives == null ? List.of() : lives.stream().map(Life::toInput).toList();
        }
    }

    /** A member leaving: the day they go (covered to that month's end) and why. */
    record Leave(LocalDate leftOn, tz.co.nlolo.lifeplatform.policy.api.ExitReason reason) {}

    record Remove(String reason) {}
}
