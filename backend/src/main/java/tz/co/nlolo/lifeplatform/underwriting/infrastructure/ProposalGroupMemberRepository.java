package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.domain.ProposalGroupMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProposalGroupMemberRepository extends JpaRepository<ProposalGroupMember, UUID> {

    /**
     * A proposal's opening schedule, oldest first.
     *
     * <p>A TOTAL order, and deliberately so: a bulk schedule gives every row the same created_at
     * to the millisecond, so createdAt alone has ties, and paging an order with ties can show
     * one life twice while omitting another. On a member roll that is a person who believes
     * they are insured and is missing from the page nobody scrolled twice — the same defect
     * shape policy/V9's own member listing was fixed for.
     */
    List<ProposalGroupMember> findByTenantIdAndCaseIdOrderByCreatedAtAscProposalGroupMemberIdAsc(
        UUID tenantId, UUID caseId);
}
