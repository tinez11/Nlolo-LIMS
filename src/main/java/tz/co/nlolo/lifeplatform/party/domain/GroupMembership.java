package tz.co.nlolo.lifeplatform.party.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Deliverable 3 Rev 2 §7.1 (Pa1): its own entity/repository, paginated,
 * queried independently -- NEVER loaded as a collection on Party. A
 * 10,000-member SACCO group would blow up the aggregate-size principle
 * everything else in this design relies on.
 */
@Entity
@Table(name = "group_membership", schema = "party")
public class GroupMembership {

    @Id
    @GeneratedValue
    @Column(name = "group_membership_id")
    private UUID groupMembershipId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "group_party_id", nullable = false)
    private UUID groupPartyId;

    @Column(name = "member_party_id", nullable = false)
    private UUID memberPartyId;

    @Column(name = "join_date", nullable = false)
    private LocalDate joinDate;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GroupMembership() {}

    public GroupMembership(UUID tenantId, UUID groupPartyId, UUID memberPartyId) {
        this.tenantId = tenantId;
        this.groupPartyId = groupPartyId;
        this.memberPartyId = memberPartyId;
        this.joinDate = LocalDate.now();
        this.status = "ACTIVE";
        this.createdAt = Instant.now();
    }

    public UUID getGroupPartyId() { return groupPartyId; }
    public UUID getMemberPartyId() { return memberPartyId; }
    public LocalDate getJoinDate() { return joinDate; }
    public String getStatus() { return status; }
}
