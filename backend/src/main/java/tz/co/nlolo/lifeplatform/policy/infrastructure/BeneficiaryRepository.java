package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.BeneficiaryOfView;
import tz.co.nlolo.lifeplatform.policy.domain.Beneficiary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, UUID> {
    List<Beneficiary> findByPolicyNumberAndActiveTrue(String policyNumber);

    /**
     * The reverse direction: every policy naming this party. Joined to {@code Policy} so the caller
     * gets the policy's status in one query rather than N follow-up reads — a party named on twenty
     * policies would otherwise cost twenty-one.
     *
     * <p>Tenant-scoped explicitly even though RLS also covers it, matching the convention the newer
     * repositories on this platform follow: RLS is the backstop, not the only guard.
     *
     * <p>Ordered, and totally: {@code policyNumber} is unique per tenant, so this cannot fall back
     * to scan order the way four other queries on this platform silently did.
     */
    @Query("SELECT new tz.co.nlolo.lifeplatform.policy.api.BeneficiaryOfView("
        + "b.policyNumber, p.status, b.sharePercent, b.revocable) "
        + "FROM Beneficiary b JOIN Policy p ON p.policyNumber = b.policyNumber AND p.tenantId = b.tenantId "
        + "WHERE b.tenantId = :tenantId AND b.partyId = :partyId AND b.active = true "
        + "ORDER BY b.policyNumber")
    List<BeneficiaryOfView> findActiveBeneficiaryOf(@Param("tenantId") UUID tenantId,
                                                      @Param("partyId") UUID partyId);
}
