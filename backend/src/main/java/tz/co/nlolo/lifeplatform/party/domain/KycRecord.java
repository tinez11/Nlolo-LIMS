package tz.co.nlolo.lifeplatform.party.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One KYC decision, as taken: the status, the evidence it was taken against, and who took it.
 *
 * <p>A row per decision, never updated — {@code party.kyc_status} holds only the latest, so this
 * is the only record that a client was once rejected and is now verified, and against which
 * document each of those was decided.
 *
 * <p>Until 2026-10-14 it was written and never read: no getters, a bare {@code JpaRepository},
 * and no endpoint. The getters below exist to serve {@code GET /parties/{id}/kyc-decisions}.
 */
@Entity
@Table(name = "kyc_record", schema = "party")
public class KycRecord {

    @Id
    @GeneratedValue
    @Column(name = "kyc_record_id")
    private UUID kycRecordId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "party_id", nullable = false)
    private UUID partyId;

    @Column(name = "evidence_document_ref", nullable = false)
    private String evidenceDocumentRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private KycStatus status;

    @Column(name = "verified_by")
    private String verifiedBy;

    /**
     * How to show {@link #verifiedBy} to a person, captured from their token at the time (V7).
     * Null on every row written before V7 and on any decision taken without a token.
     */
    @Column(name = "verified_by_name")
    private String verifiedByName;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected KycRecord() {}

    public KycRecord(UUID tenantId, UUID partyId, String evidenceDocumentRef, KycStatus status,
                     String verifiedBy, String verifiedByName) {
        this.tenantId = tenantId;
        this.partyId = partyId;
        this.evidenceDocumentRef = evidenceDocumentRef;
        this.status = status;
        this.verifiedBy = verifiedBy;
        this.verifiedByName = verifiedByName;
        this.verifiedAt = Instant.now();
        this.createdAt = Instant.now();
    }

    public UUID getKycRecordId() { return kycRecordId; }

    public UUID getPartyId() { return partyId; }

    public String getEvidenceDocumentRef() { return evidenceDocumentRef; }

    public KycStatus getStatus() { return status; }

    public String getVerifiedBy() { return verifiedBy; }

    public String getVerifiedByName() { return verifiedByName; }

    /**
     * When the decision was recorded.
     *
     * <p>{@code created_at} rather than {@code verified_at}, although the constructor sets both to
     * the same instant: {@code created_at} is NOT NULL in the schema and {@code verified_at} is
     * nullable, so a row written by any other path could carry a null there — and a decision trail
     * whose dates are sometimes blank is worse than one that reads a column that cannot be.
     */
    public Instant getDecidedAt() { return createdAt; }
}
