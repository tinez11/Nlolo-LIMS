package tz.co.nlolo.lifeplatform.party.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "party", schema = "party")
public class Party {

    @Id
    @GeneratedValue
    @Column(name = "party_id")
    private UUID partyId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "party_type", nullable = false)
    private PartyType partyType;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "registration_number")
    private String registrationNumber;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "email")
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false)
    private KycStatus kycStatus;

    @Column(name = "kyc_verified_at")
    private Instant kycVerifiedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected Party() {}

    public static Party newIndividual(UUID tenantId, String displayName, LocalDate dateOfBirth,
                                       String phoneNumber, String email, String createdBy) {
        Party party = new Party();
        party.tenantId = tenantId;
        party.partyType = PartyType.INDIVIDUAL;
        party.displayName = displayName;
        party.dateOfBirth = dateOfBirth;
        party.phoneNumber = phoneNumber;
        party.email = email;
        party.kycStatus = KycStatus.PENDING;
        party.createdAt = Instant.now();
        party.createdBy = createdBy;
        return party;
    }

    public static Party newCorporate(UUID tenantId, String displayName, String registrationNumber,
                                      String phoneNumber, String email, String createdBy) {
        Party party = new Party();
        party.tenantId = tenantId;
        party.partyType = PartyType.CORPORATE;
        party.displayName = displayName;
        party.registrationNumber = registrationNumber;
        party.phoneNumber = phoneNumber;
        party.email = email;
        party.kycStatus = KycStatus.PENDING;
        party.createdAt = Instant.now();
        party.createdBy = createdBy;
        return party;
    }

    public static Party newGroup(UUID tenantId, String displayName, String createdBy) {
        Party party = new Party();
        party.tenantId = tenantId;
        party.partyType = PartyType.GROUP;
        party.displayName = displayName;
        party.kycStatus = KycStatus.PENDING;
        party.createdAt = Instant.now();
        party.createdBy = createdBy;
        return party;
    }

    public void applyKycDecision(KycStatus newStatus, String decidedBy) {
        this.kycStatus = newStatus;
        this.kycVerifiedAt = Instant.now();
        this.updatedAt = Instant.now();
        this.updatedBy = decidedBy;
    }

    public UUID getPartyId() { return partyId; }
    public UUID getTenantId() { return tenantId; }
    public PartyType getPartyType() { return partyType; }
    public String getDisplayName() { return displayName; }
    public KycStatus getKycStatus() { return kycStatus; }
    public String getRegistrationNumber() { return registrationNumber; }

    // Read by PartyApiImpl.getPartyDetail alone. These columns have been stored since V1 and were
    // simply never exposed -- PartyView carries four fields, so a registered date of birth, phone
    // number or email could not be read back through any endpoint on the platform.
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public String getPhoneNumber() { return phoneNumber; }
    public String getEmail() { return email; }
    public Instant getKycVerifiedAt() { return kycVerifiedAt; }
    public Instant getCreatedAt() { return createdAt; }
    /** The JWT subject that registered this party -- what the agents realm is scoped on. */
    public String getCreatedBy() { return createdBy; }
}
