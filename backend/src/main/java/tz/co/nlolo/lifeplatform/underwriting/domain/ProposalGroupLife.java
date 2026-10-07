package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.underwriting.api.GroupProposal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One life on a group funeral proposal's opening schedule (underwriting V19): a main member or one of their family,
 * a name rather than a registered party. Lives of one family share the association's member reference. No benefit:
 * what a life is covered for follows from its role in the plan and is written at issuance.
 */
@Entity
@Table(name = "proposal_group_life", schema = "underwriting")
public class ProposalGroupLife {

    @Id @Column(name = "proposal_group_life_id") private UUID proposalGroupLifeId = UUID.randomUUID();
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "case_id", nullable = false) private UUID caseId;
    @Column(nullable = false) private int position;
    @Column(name = "member_reference", nullable = false) private String memberReference;
    @Column(nullable = false) private String role;
    @Column(name = "full_name", nullable = false) private String fullName;
    @Column(name = "date_of_birth", nullable = false) private LocalDate dateOfBirth;
    @Column private String sex;
    @Column(name = "id_number") private String idNumber;
    @Column(nullable = false) private boolean student;
    @Column(name = "beneficiary_name") private String beneficiaryName;
    @Column(name = "beneficiary_relationship") private String beneficiaryRelationship;
    @Column(name = "beneficiary_phone") private String beneficiaryPhone;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected ProposalGroupLife() {}

    public ProposalGroupLife(UUID tenantId, UUID caseId, int position, GroupProposal.LifeLine line) {
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.position = position;
        this.memberReference = line.memberReference();
        this.role = line.role().name();
        this.fullName = line.fullName();
        this.dateOfBirth = line.dateOfBirth();
        this.sex = line.sex();
        this.idNumber = line.idNumber();
        this.student = line.student();
        this.beneficiaryName = line.beneficiaryName();
        this.beneficiaryRelationship = line.beneficiaryRelationship();
        this.beneficiaryPhone = line.beneficiaryPhone();
    }

    public GroupProposal.LifeLine toLine() {
        return new GroupProposal.LifeLine(memberReference, FuneralRole.valueOf(role), fullName, dateOfBirth, sex, idNumber,
            student, beneficiaryName, beneficiaryRelationship, beneficiaryPhone);
    }
}
