package tz.co.nlolo.lifeplatform.omnichannel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One application through the public quote link (2026-11-08): who, which case, the phone a texted code confirmed, the
 * consent wording agreed to word for word, and anyone already on the books who may be the same person. See omnichannel
 * V3. Written once; never changed.
 */
@Entity
@Table(name = "public_application", schema = "omnichannel")
public class PublicApplication {

    @Id @Column(name = "public_application_id") private UUID publicApplicationId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "party_id", nullable = false) private UUID partyId;
    @Column(name = "case_id", nullable = false) private UUID caseId;
    @Column(name = "phone_number", nullable = false) private String phoneNumber;
    @Column(name = "consent_version", nullable = false) private String consentVersion;
    @Column(name = "consent_text", nullable = false) private String consentText;
    @Column(nullable = false) private String language;
    @Column(name = "remote_address") private String remoteAddress;
    @Column(name = "possible_matches", nullable = false) private String possibleMatches;
    @Column(name = "applied_at", nullable = false) private Instant appliedAt;

    protected PublicApplication() {}

    public PublicApplication(UUID tenantId, UUID partyId, UUID caseId, String phoneNumber, String consentVersion,
                             String consentText, String language, String remoteAddress, Collection<UUID> possibleMatches,
                             Instant appliedAt) {
        this.publicApplicationId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.partyId = partyId;
        this.caseId = caseId;
        this.phoneNumber = phoneNumber;
        this.consentVersion = consentVersion;
        this.consentText = consentText;
        this.language = language;
        this.remoteAddress = remoteAddress;
        this.possibleMatches = possibleMatches.stream().map(UUID::toString).collect(Collectors.joining(","));
        this.appliedAt = appliedAt;
    }

    public UUID getPartyId() { return partyId; }
    public UUID getCaseId() { return caseId; }
    public String getPhoneNumber() { return phoneNumber; }
    public String getConsentVersion() { return consentVersion; }
    public String getConsentText() { return consentText; }
    public String getLanguage() { return language; }
    public Instant getAppliedAt() { return appliedAt; }

    public List<UUID> getPossibleMatches() {
        return possibleMatches.isBlank() ? List.of()
            : Arrays.stream(possibleMatches.split(",")).map(UUID::fromString).toList();
    }
}
