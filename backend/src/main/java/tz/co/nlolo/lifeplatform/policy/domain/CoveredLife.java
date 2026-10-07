package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One life on a funeral policy (policy V35). Its benefit is stored when cover is set and is what a claim
 * pays; its premium is re-priced at each anniversary; its waiting period runs from its OWN cover start.
 */
@Entity
@Table(name = "covered_life", schema = "policy")
public class CoveredLife {
    @Id @Column(name = "covered_life_id") private UUID coveredLifeId = UUID.randomUUID();
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private String role;
    @Column(name = "full_name", nullable = false) private String fullName;
    @Column(name = "date_of_birth", nullable = false) private LocalDate dateOfBirth;
    @Column private String sex;
    @Column(name = "id_number") private String idNumber;
    @Column(nullable = false) private boolean student;
    @Column(name = "party_id") private UUID partyId;
    @Column(nullable = false) private BigDecimal benefit;
    @Column(name = "yearly_premium", nullable = false) private BigDecimal yearlyPremium;
    @Column(name = "priced_at_age", nullable = false) private int pricedAtAge;
    @Column(name = "cover_start", nullable = false) private LocalDate coverStart;
    @Column(name = "cover_end") private LocalDate coverEnd;
    @Column(name = "pending_end_reason") private String pendingEndReason;
    @Column(nullable = false) private String status = "ACTIVE";
    @Column(name = "end_reason") private String endReason;
    @Column(name = "ended_on") private LocalDate endedOn;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "created_by", nullable = false) private String createdBy;
    /** Group funeral (V39): the main member whose family this life is in. Null on an individual funeral policy. */
    @Column(name = "policy_member_id") private UUID policyMemberId;

    protected CoveredLife() {}

    /** This life belongs to a scheme member's family. */
    public CoveredLife inFamilyOf(UUID policyMemberId) {
        this.policyMemberId = policyMemberId;
        return this;
    }

    public UUID getPolicyMemberId() { return policyMemberId; }

    public CoveredLife(UUID tenantId, String policyNumber, String role, String fullName, LocalDate dateOfBirth,
                       String sex, String idNumber, boolean student, UUID partyId, BigDecimal benefit,
                       BigDecimal yearlyPremium, int pricedAtAge, LocalDate coverStart, String createdBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.role = role;
        this.fullName = fullName;
        this.dateOfBirth = dateOfBirth;
        this.sex = sex;
        this.idNumber = idNumber;
        this.student = student;
        this.partyId = partyId;
        this.benefit = benefit;
        this.yearlyPremium = yearlyPremium;
        this.pricedAtAge = pricedAtAge;
        this.coverStart = coverStart;
        this.createdBy = createdBy;
    }

    public boolean isActive() { return "ACTIVE".equals(status); }

    /** Covered on {@code day}: started, and not yet ended -- a scheduled end covers up to (not on) its date. */
    public boolean coveredOn(LocalDate day) {
        if (day.isBefore(coverStart)) {
            return false;
        }
        if (isActive()) {
            return coverEnd == null || day.isBefore(coverEnd);
        }
        return day.isBefore(endedOn);
    }

    /** Cover stays to {@code on}, then the sweep ends the life with {@code reason}. */
    public void scheduleEnd(LocalDate on, String reason) {
        requireActive();
        this.coverEnd = on;
        this.pendingEndReason = reason;
    }

    public void end(String reason, LocalDate on) {
        requireActive();
        this.status = "ENDED";
        this.endReason = reason;
        this.endedOn = on;
        this.coverEnd = null;
        this.pendingEndReason = null;
    }

    public void reprice(BigDecimal yearlyPremium, int age) {
        this.yearlyPremium = yearlyPremium;
        this.pricedAtAge = age;
    }

    /** The spouse becomes the main member at takeover (plan R8), priced at the main member's rate. */
    public void becomeMainMember(UUID partyId, BigDecimal yearlyPremium, int age) {
        requireActive();
        this.role = "MAIN_MEMBER";
        this.partyId = partyId;
        reprice(yearlyPremium, age);
    }

    public void promoteToParty(UUID partyId) {
        this.partyId = partyId;
    }

    private void requireActive() {
        if (!isActive()) {
            throw new IllegalStateException("Covered life " + coveredLifeId + " has already ended");
        }
    }

    public UUID getCoveredLifeId() { return coveredLifeId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getRole() { return role; }
    public String getFullName() { return fullName; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public String getSex() { return sex; }
    public String getIdNumber() { return idNumber; }
    public boolean isStudent() { return student; }
    public UUID getPartyId() { return partyId; }
    public BigDecimal getBenefit() { return benefit; }
    public BigDecimal getYearlyPremium() { return yearlyPremium; }
    public int getPricedAtAge() { return pricedAtAge; }
    public LocalDate getCoverStart() { return coverStart; }
    public LocalDate getCoverEnd() { return coverEnd; }
    public String getPendingEndReason() { return pendingEndReason; }
    public String getStatus() { return status; }
    public String getEndReason() { return endReason; }
    public LocalDate getEndedOn() { return endedOn; }
}
