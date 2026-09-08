package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.domain.ProposalBeneficiary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProposalBeneficiaryRepository extends JpaRepository<ProposalBeneficiary, UUID> {

    /**
     * Every nomination taken on a case, oldest first.
     *
     * <p>Tenant-scoped rather than by case id alone: this feeds the beneficiaries of a real
     * contract at issuance, and a read that leans on row-level security alone to keep tenants
     * apart is not the place to economise.
     */
    List<ProposalBeneficiary> findByTenantIdAndCaseIdOrderByCreatedAtAsc(UUID tenantId, UUID caseId);
}
