package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.policy.domain.GroupFuneralMember;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GroupFuneralMemberRepository extends JpaRepository<GroupFuneralMember, UUID> {

    List<GroupFuneralMember> findByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);

    Optional<GroupFuneralMember> findByTenantIdAndPolicyNumberAndAssociationReference(UUID tenantId, String policyNumber,
                                                                                    String associationReference);
}
