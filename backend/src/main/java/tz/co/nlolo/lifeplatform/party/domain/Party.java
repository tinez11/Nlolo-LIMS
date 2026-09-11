package tz.co.nlolo.lifeplatform.party.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import tz.co.nlolo.lifeplatform.party.api.Address;
import tz.co.nlolo.lifeplatform.party.api.IdType;
import tz.co.nlolo.lifeplatform.party.api.IdentityDocument;
import tz.co.nlolo.lifeplatform.party.api.IndividualRegistration;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyType;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.party.api.SmokerStatus;

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

    // The individual person record (V2). Flat columns on purpose: the API groups these
    // into IdentityDocument and Address because that is the shape a caller wants, but
    // the table is flat and there is no reason to make persistence more elaborate than
    // the table. All nullable -- every party registered before V2 has none of them.
    @Enumerated(EnumType.STRING)
    @Column(name = "sex")
    private Sex sex;

    @Enumerated(EnumType.STRING)
    @Column(name = "smoker_status")
    private SmokerStatus smokerStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "id_type")
    private IdType idType;

    @Column(name = "id_number")
    private String idNumber;

    @Column(name = "occupation")
    private String occupation;

    @Column(name = "occupation_class")
    private String occupationClass;

    @Column(name = "employer_name")
    private String employerName;

    @Column(name = "nationality")
    private String nationality;

    @Column(name = "address_line")
    private String addressLine;

    @Column(name = "ward")
    private String ward;

    @Column(name = "district")
    private String district;

    @Column(name = "region")
    private String region;

    @Column(name = "postal_code")
    private String postalCode;

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

    /**
     * Who brought this client in, as a PARTY id (V4).
     *
     * <p>Set once, at registration, from the registering token's {@code party_id} claim — and
     * deliberately {@code updatable = false}, with no setter. The rule the business asked for is
     * that the registering agent ALWAYS earns the commission on this client's business, which an
     * editable field would make negotiable after the fact.
     *
     * <p>A party id rather than an agent id because party may not depend on distribution. Policy
     * resolves it to an agent at issuance; it is the only module allowed to see both.
     *
     * <p>NULL is a real answer — staff registered them, or they registered themselves.
     */
    @Column(name = "registered_by_party_id", updatable = false)
    private UUID registeredByPartyId;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected Party() {}

    /**
     * Registered by nobody in particular — staff, or the customer themselves.
     *
     * <p>An extra factory rather than a widened call at every construction site, which is the
     * same move {@code PolicyApi.IssueRequest} makes and for the same reason. Plain Java, no
     * proxy in the way, so delegation is safe.
     */
    public static Party newIndividual(UUID tenantId, IndividualRegistration registration, String createdBy) {
        return newIndividual(tenantId, registration, createdBy, null);
    }

    public static Party newIndividual(UUID tenantId, IndividualRegistration registration, String createdBy,
                                       UUID registeredByPartyId) {
        Party party = new Party();
        party.registeredByPartyId = registeredByPartyId;
        party.tenantId = tenantId;
        party.partyType = PartyType.INDIVIDUAL;
        party.displayName = registration.fullName();
        party.dateOfBirth = registration.dateOfBirth();
        party.phoneNumber = registration.phoneNumber();
        party.email = registration.email();

        party.sex = registration.sex();
        party.smokerStatus = registration.smokerStatus();
        party.idType = registration.identityDocument().type();
        party.idNumber = registration.identityDocument().number();
        party.occupation = registration.occupation();
        party.occupationClass = registration.occupationClass();
        party.employerName = registration.employerName();
        party.nationality = registration.nationality();

        Address address = registration.address();
        party.addressLine = address.line();
        party.ward = address.ward();
        party.district = address.district();
        party.region = address.region();
        party.postalCode = address.postalCode();

        party.kycStatus = KycStatus.PENDING;
        party.createdAt = Instant.now();
        party.createdBy = createdBy;
        return party;
    }

    /** @see #newIndividual(UUID, IndividualRegistration, String) for why this delegates. */
    public static Party newCorporate(UUID tenantId, String displayName, String registrationNumber,
                                      String phoneNumber, String email, String createdBy) {
        return newCorporate(tenantId, displayName, registrationNumber, phoneNumber, email, createdBy, null);
    }

    public static Party newCorporate(UUID tenantId, String displayName, String registrationNumber,
                                      String phoneNumber, String email, String createdBy,
                                      UUID registeredByPartyId) {
        Party party = new Party();
        party.registeredByPartyId = registeredByPartyId;
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

    /**
     * Correct this person's recorded details.
     *
     * <p><b>KYC STATUS IS NOT TOUCHED, and that is a decision rather than an omission.</b> KYC
     * here is what a passport is: a document verifying that this person is who they say they
     * are. Amending what the platform has recorded about them does not un-verify the document
     * that was checked, so a correction does not send a verified client back to PENDING and make
     * them prove themselves again over a misspelled street.
     *
     * <p>Every field is replaced by what is passed, including with null — this is an amendment
     * of the record, not a patch, and "clear the employer" has to be expressible. The caller is
     * responsible for sending the full picture; the request DTO is built that way.
     *
     * <p>Not amendable here: KYC status (its own endpoint, its own evidence), party type,
     * registration number, and who registered the client. The first three are what the record
     * IS rather than what it says, and the fourth decides who gets paid — an editable
     * attribution is an editable commission.
     */
    public void amendIndividualDetails(IndividualRegistration amended, String amendedBy) {
        if (partyType != PartyType.INDIVIDUAL) {
            throw new IllegalStateException("amendIndividualDetails is for an INDIVIDUAL; party "
                + partyId + " is " + partyType);
        }
        this.displayName = amended.fullName();
        this.dateOfBirth = amended.dateOfBirth();
        this.phoneNumber = amended.phoneNumber();
        this.email = amended.email();
        this.sex = amended.sex();
        this.smokerStatus = amended.smokerStatus();
        this.idType = amended.identityDocument().type();
        this.idNumber = amended.identityDocument().number();
        this.occupation = amended.occupation();
        this.occupationClass = amended.occupationClass();
        this.employerName = amended.employerName();
        this.nationality = amended.nationality();

        Address address = amended.address();
        this.addressLine = address.line();
        this.ward = address.ward();
        this.district = address.district();
        this.region = address.region();
        this.postalCode = address.postalCode();

        this.updatedAt = Instant.now();
        this.updatedBy = amendedBy;
    }

    /** @see #amendIndividualDetails for why KYC and party type are not amendable. */
    public void amendCorporateDetails(String displayName, String phoneNumber, String email,
                                       String amendedBy) {
        if (partyType == PartyType.INDIVIDUAL) {
            throw new IllegalStateException("amendCorporateDetails is for a CORPORATE or GROUP; party "
                + partyId + " is INDIVIDUAL");
        }
        this.displayName = displayName;
        this.phoneNumber = phoneNumber;
        this.email = email;
        this.updatedAt = Instant.now();
        this.updatedBy = amendedBy;
    }

    public UUID getRegisteredByPartyId() { return registeredByPartyId; }

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

    // The V2 person record. Read by getPartyDetail, and -- once Build 5 wires the quote
    // into issuance -- by whoever prices a proposal: sex and smokerStatus together with
    // dateOfBirth are exactly base_rate_table's key.
    public Sex getSex() { return sex; }
    public SmokerStatus getSmokerStatus() { return smokerStatus; }
    public IdentityDocument getIdentityDocument() { return new IdentityDocument(idType, idNumber); }
    public String getOccupation() { return occupation; }
    public String getOccupationClass() { return occupationClass; }
    public String getEmployerName() { return employerName; }
    public String getNationality() { return nationality; }
    public Address getAddress() { return new Address(addressLine, ward, district, region, postalCode); }

    public Instant getKycVerifiedAt() { return kycVerifiedAt; }
    public Instant getCreatedAt() { return createdAt; }
    /** The JWT subject that registered this party -- what the agents realm is scoped on. */
    public String getCreatedBy() { return createdBy; }
}
