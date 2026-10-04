package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.underwriting.api.FuneralApplication;

import java.time.LocalDate;
import java.util.UUID;

/** One dependant on a funeral case (underwriting V16), in the order listed. */
@Entity
@Table(name = "funeral_application_life", schema = "underwriting")
public class FuneralApplicationLifeEntity {
    @Id @Column(name = "funeral_application_life_id") private UUID funeralApplicationLifeId = UUID.randomUUID();
    @Column(name = "case_id", nullable = false) private UUID caseId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(nullable = false) private String role;
    @Column(name = "full_name", nullable = false) private String fullName;
    @Column(name = "date_of_birth", nullable = false) private LocalDate dateOfBirth;
    @Column private String sex;
    @Column(name = "id_number") private String idNumber;
    @Column(nullable = false) private boolean student;
    @Column(nullable = false) private int position;

    protected FuneralApplicationLifeEntity() {}

    public FuneralApplicationLifeEntity(UUID tenantId, UUID caseId, int position, FuneralApplication.Life life) {
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.position = position;
        this.role = life.role().name();
        this.fullName = life.fullName();
        this.dateOfBirth = life.dateOfBirth();
        this.sex = life.sex();
        this.idNumber = life.idNumber();
        this.student = life.student();
    }

    public FuneralApplication.Life toLife() {
        return new FuneralApplication.Life(FuneralRole.valueOf(role), fullName, dateOfBirth, sex, idNumber, student);
    }
}
