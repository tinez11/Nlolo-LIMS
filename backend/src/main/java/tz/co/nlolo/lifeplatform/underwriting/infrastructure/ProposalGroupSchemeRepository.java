package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.domain.ProposalGroupScheme;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProposalGroupSchemeRepository extends JpaRepository<ProposalGroupScheme, UUID> {

    /**
     * The proposed scheme on a case, if it is one.
     *
     * <p>Tenant-scoped rather than by case id alone, matching {@code ProposalBeneficiary}'s own
     * read: this feeds the terms of a real contract at issuance, and a read that leans on
     * row-level security alone to keep tenants apart is not the place to economise.
     */
    Optional<ProposalGroupScheme> findByCaseIdAndTenantId(UUID caseId, UUID tenantId);

    /** "Is this case a scheme" -- a row here IS that statement, so presence is the whole answer. */
    boolean existsByCaseIdAndTenantId(UUID caseId, UUID tenantId);
}
