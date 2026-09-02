package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * What an applicant declared, and who wrote it down.
 *
 * <p>This entity and its repository existed from M4 with ZERO call sites: the table was created
 * so a future milestone could populate it without a migration, and the spec once advertised a
 * {@code medicalDisclosure} field on {@code OpenCaseRequest} that the request object never had,
 * so a client sending one got a 201 and had it silently discarded. Meanwhile
 * {@code Claim.requiresContestabilityReview} is computed and shown on the claims screen —
 * a review with nothing to review, because nothing on this platform held any evidence of what
 * had been disclosed.
 *
 * <p>{@code questionResponseSet} is the JSONB column, holding a JSON array of
 * {@code DisclosureAnswer}. It stays opaque at the database because the question set is
 * product-specific — the platform records what was asked rather than owning a canonical list.
 *
 * <p>Deliberately NOT an input to the decision. {@code RiskProfile}'s own javadoc requires that
 * new rating data extend the profile and {@code SimpleRulesEngine} together rather than being
 * quietly ignored, and mapping "treated for hypertension since 2019" to a risk score is
 * actuarial policy this codebase has none of — SimpleRulesEngine is an explicit placeholder with
 * no validated thresholds. Recording the evidence is the half that has an honest answer; rating
 * on it is the half that needs an actuary.
 */
@Entity
@Table(name = "medical_disclosure", schema = "underwriting")
public class MedicalDisclosure {

    @Id
    @UuidGenerator
    @Column(name = "medical_disclosure_id")
    private UUID medicalDisclosureId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    /**
     * {@code @JdbcTypeCode(JSON)} is required, not decoration. {@code columnDefinition} only
     * affects DDL generation, which this platform does not use -- migrations own the schema --
     * so without it Hibernate binds this String as {@code varchar} and Postgres refuses:
     * "column is of type jsonb but expression is of type character varying". The column has been
     * jsonb since V1; nothing ever wrote to it before, so nothing had ever hit this.
     */
    @Column(name = "question_response_set", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String questionResponseSet;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** The JWT subject of whoever took the proposal — see V3. */
    @Column(name = "recorded_by")
    private String recordedBy;

    protected MedicalDisclosure() {}

    public MedicalDisclosure(UUID tenantId, UUID caseId, String questionResponseSet, String recordedBy) {
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.questionResponseSet = questionResponseSet;
        this.recordedBy = recordedBy;
    }

    public UUID getMedicalDisclosureId() { return medicalDisclosureId; }
    public UUID getCaseId() { return caseId; }
    public String getQuestionResponseSet() { return questionResponseSet; }
    public String getRecordedBy() { return recordedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
