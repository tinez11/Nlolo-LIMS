package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.policy.domain.GroupSchemeGrade;

import java.util.List;
import java.util.UUID;

public interface GroupSchemeGradeRepository extends JpaRepository<GroupSchemeGrade, UUID> {
    List<GroupSchemeGrade> findByTenantIdAndPolicyNumberOrderByGradeCode(UUID tenantId, String policyNumber);
}
