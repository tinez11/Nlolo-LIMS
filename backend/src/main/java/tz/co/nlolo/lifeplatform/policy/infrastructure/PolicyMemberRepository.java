package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.policy.api.MemberUnderwritingStatus;
import tz.co.nlolo.lifeplatform.policy.domain.PolicyMember;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface PolicyMemberRepository extends JpaRepository<PolicyMember, UUID> {

    /**
     * One page of a scheme's members.
     *
     * <p>The caller supplies the sort and MUST make it total. A bulk schedule upload gives
     * every row the same {@code joinedOn}, so that column alone leaves ties, and paging an
     * unordered query can show one member twice while never showing another. On a 500-life
     * scheme that is a member silently missing from the roll -- the same defect shape
     * PLAN.md section 10 records finding four times, once deciding an agent's commission
     * rate.
     */
    Page<PolicyMember> findByTenantIdAndPolicyNumberAndStatus(
        UUID tenantId, String policyNumber, String status, Pageable pageable);

    /**
     * As above, with a null {@code status} meaning "every member, including those who left".
     *
     * <p>{@code memberPartyIds} is the name search, resolved to ids by the party module before
     * it gets here — a member row holds no name of its own. Null means "no name filter"; the
     * caller must never pass an EMPTY collection, because that is both a Postgres syntax error
     * (`in ()`) and the opposite of what it looks like. See {@code PolicyApiImpl.listMembers},
     * which short-circuits to an empty page instead.
     */
    /*
     * The name filter now has TWO halves, and a member matches on either.
     *
     * A PARTY member holds no name, so the party module resolves the search to ids. A
     * FREEFORM member holds nothing BUT a name, and no party module knows it exists.
     * Matching only the first half would make a credit-life roll -- where every member is
     * freeform -- completely unsearchable: 400 borrowers and no way to find one by name.
     *
     * Both halves are governed by the same `:nameQuery is null` guard so that "no search"
     * still means "the whole schedule".
     *
     * `:nameQuery` arrives already lower-cased and already wrapped in % signs. It must
     * NOT be passed through lower() or concat() here: when the parameter is null Postgres
     * cannot infer its type inside a function call and fails the whole statement with
     * "function lower(bytea) does not exist" -- which takes down every listing on this
     * repository, not only the searches.
     */
    @Query("""
        select m from PolicyMember m
         where m.tenantId = :tenantId and m.policyNumber = :policyNumber
           and (:status is null or m.status = :status)
           and (:nameQuery is null
                or (:memberPartyIds is not null and m.memberPartyId in :memberPartyIds)
                or (m.memberName is not null and lower(m.memberName) like :nameQuery))
        """)
    Page<PolicyMember> findMembers(@Param("tenantId") UUID tenantId,
                                    @Param("policyNumber") String policyNumber,
                                    @Param("status") String status,
                                    @Param("memberPartyIds") Collection<UUID> memberPartyIds,
                                    @Param("nameQuery") String nameQuery,
                                    Pageable pageable);

    Optional<PolicyMember> findByPolicyMemberIdAndTenantId(UUID policyMemberId, UUID tenantId);

    /** Backs the "already on this scheme" check behind ux_policy_member_active_party. */
    boolean existsByTenantIdAndPolicyNumberAndMemberPartyIdAndStatus(
        UUID tenantId, String policyNumber, UUID memberPartyId, String status);

    /**
     * The credit-life equivalent, behind {@code ux_policy_member_active_loan}.
     *
     * <p>Lets bulk enrolment judge a row as ALREADY_ENROLLED at submit rather than
     * discovering it as a constraint violation half-way through acceptance -- by which
     * point the lender has been told the row was acceptable.
     */
    boolean existsByTenantIdAndPolicyNumberAndLoanAccountNumberAndStatus(
        UUID tenantId, String policyNumber, String loanAccountNumber, String status);

    /**
     * The next member reference number.
     *
     * <p>A sequence rather than a counter column on the scheme: a counter would be a
     * read-modify-write, and two members added at once would both read the same value and
     * one reference would silently be lost. {@code nextval} cannot be raced.
     */
    @Query(value = "select nextval('policy.member_reference_seq')", nativeQuery = true)
    long nextMemberReferenceNumber();

    /**
     * Whether this scheme already covers this borrower for this loan.
     *
     * <p>The composite that replaces the lender-supplied key. A lender has no identifier
     * to give on a first file, so a resubmission is recognised by the facts of the loan
     * itself. Two loans to one person on the same day for the same amount are
     * indistinguishable here -- possible but rare, and it fails in the safe direction: a
     * rejection the lender can overturn, never silent double cover.
     */
    @Query("""
        select count(m) > 0 from PolicyMember m
         where m.tenantId = :tenantId and m.policyNumber = :policyNumber
           and m.status = 'ACTIVE'
           and lower(m.memberName) = lower(:memberName)
           and m.memberDateOfBirth = :dateOfBirth
           and m.loanDisbursementDate = :disbursementDate
           and m.loanPrincipalAmount = :principalAmount
        """)
    boolean existsMatchingLoan(@Param("tenantId") UUID tenantId,
                                @Param("policyNumber") String policyNumber,
                                @Param("memberName") String memberName,
                                @Param("dateOfBirth") java.time.LocalDate dateOfBirth,
                                @Param("disbursementDate") java.time.LocalDate disbursementDate,
                                @Param("principalAmount") java.math.BigDecimal principalAmount);

    Optional<PolicyMember> findByTenantIdAndMemberReference(UUID tenantId, String memberReference);

    long countByTenantIdAndPolicyNumberAndStatus(UUID tenantId, String policyNumber, String status);

    /**
     * Active members whose benefit exceeds the free cover limit with underwriting still
     * outstanding -- the queue a scheme administrator works, surfaced as a count so nobody
     * has to find them by eye down a 500-row schedule.
     */
    long countByTenantIdAndPolicyNumberAndStatusAndUnderwritingStatus(
        UUID tenantId, String policyNumber, String status, MemberUnderwritingStatus underwritingStatus);
}
