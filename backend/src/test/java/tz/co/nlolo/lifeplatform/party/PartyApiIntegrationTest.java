package tz.co.nlolo.lifeplatform.party;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import tz.co.nlolo.lifeplatform.party.api.Address;
import tz.co.nlolo.lifeplatform.party.api.DuplicateIdentityDocumentException;
import tz.co.nlolo.lifeplatform.party.api.DuplicateRegistrationNumberException;
import tz.co.nlolo.lifeplatform.party.api.IdType;
import tz.co.nlolo.lifeplatform.party.api.IdentityDocument;
import tz.co.nlolo.lifeplatform.party.api.IndividualRegistration;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.party.api.SmokerStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = Application.class)
class PartyApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/party/V4__registered_by_agent.sql",
            "db-migrations/party/V5__registered_by_name.sql",
            "db-migrations/party/V6__client_reference.sql",
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @Autowired
    private PartyApi partyApi;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private UUID tenantId;

    @BeforeEach
    void setTenant() {
        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void registeringAnIndividualPublishesEventThatReachesAuditLog() {
        Instant before = Instant.now();

        var view = partyApi.registerIndividual("Amina Hassan", LocalDate.of(1990, 5, 12),
            "+255712345678", "amina@example.tz", "test-agent");

        assertThat(view.partyId()).isNotNull();
        assertThat(view.displayName()).isEqualTo("Amina Hassan");

        List<?> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "party.PartyRegistered", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
    }

    @Test
    void duplicateCorporateRegistrationNumberIsRejected() {
        partyApi.registerCorporate("Acme SACCO", "REG-001", "+255712345000", "acme@example.tz", "test-agent");

        Assertions.assertThrows(DuplicateRegistrationNumberException.class,
            () -> partyApi.registerCorporate("Acme SACCO Duplicate", "REG-001", "+255712345001", "acme2@example.tz", "test-agent"));
    }

    /**
     * registerCorporate reported EVERY integrity violation as a duplicate registration
     * number, so a value-too-long on the registered name announced a clash on a number
     * that was never used -- sending the reader to look for a corporate party that does
     * not exist. Same bug class as M7's onboardAgent.
     */
    @Test
    void anOverlongNameIsNotReportedAsADuplicateRegistrationNumber() {
        String tooLong = "A".repeat(300); // display_name is VARCHAR(255)

        Throwable thrown = Assertions.assertThrows(Throwable.class,
            () -> partyApi.registerCorporate(tooLong, "REG-LONGNAME", "+255712345900",
                "long@example.tz", "test-agent"));

        Assertions.assertFalse(thrown instanceof DuplicateRegistrationNumberException,
            "a value-too-long was reported as a duplicate registration number: " + thrown.getMessage());
    }

    @Test
    void thePersonRecordRoundTripsThroughRegistrationAndDetailRead() {
        var registered = partyApi.registerIndividual(new IndividualRegistration(
            "Neema Mushi", LocalDate.of(1988, 2, 9), "+255713111222", "neema@example.tz",
            Sex.FEMALE, SmokerStatus.NON_SMOKER,
            new IdentityDocument(IdType.NATIONAL_ID, "19880209-11111-00001-22"),
            "Secondary school teacher", "PROF_1", "Ilala Secondary School", "tz",
            new Address("Plot 44, Uhuru Road", "Upanga", "Ilala", "Dar es Salaam", "11101")),
            "test-agent");

        var detail = partyApi.getPartyDetail(registered.partyId());

        assertThat(detail.sex()).isEqualTo(Sex.FEMALE);
        assertThat(detail.smokerStatus()).isEqualTo(SmokerStatus.NON_SMOKER);
        assertThat(detail.identityDocument().type()).isEqualTo(IdType.NATIONAL_ID);
        assertThat(detail.identityDocument().number()).isEqualTo("19880209-11111-00001-22");
        assertThat(detail.occupation()).isEqualTo("Secondary school teacher");
        assertThat(detail.occupationClass()).isEqualTo("PROF_1");
        assertThat(detail.employerName()).isEqualTo("Ilala Secondary School");
        // Upper-cased on the way in, so a quote or a return never has to case-fold it.
        assertThat(detail.nationality()).isEqualTo("TZ");
        assertThat(detail.address().region()).isEqualTo("Dar es Salaam");
        assertThat(detail.address().postalCode()).isEqualTo("11101");
    }

    /**
     * The legacy five-argument overload must still open a transaction.
     *
     * <p>Not a redundant duplicate of the audit-log test above: this asserts the shape of
     * the record it produces, and the two together are what pin the overload's behaviour.
     * It was briefly a {@code default} method on the interface, which made its delegation
     * a self-invocation that never re-entered the Spring proxy -- the row still saved, so
     * only the audit assertion noticed the missing transaction.
     */
    @Test
    void theLegacyOverloadRegistersAPersonRecordOfNulls() {
        var registered = partyApi.registerIndividual("Juma Legacy", LocalDate.of(1979, 6, 3),
            "+255713111333", null, "test-agent");

        var detail = partyApi.getPartyDetail(registered.partyId());

        assertThat(detail.displayName()).isEqualTo("Juma Legacy");
        // Null, NOT a defaulted UNKNOWN: nobody asked, and that has to stay
        // distinguishable from an applicant who was asked and declined.
        assertThat(detail.sex()).isNull();
        assertThat(detail.smokerStatus()).isNull();
        assertThat(detail.identityDocument().recorded()).isFalse();
        assertThat(detail.address().recorded()).isFalse();
    }

    @Test
    void theSameIdentityDocumentCannotBeRegisteredTwice() {
        partyApi.registerIndividual(registrationWithNationalId("First Registration", "NIDA-DUP-001"),
            "test-agent");

        Assertions.assertThrows(DuplicateIdentityDocumentException.class,
            () -> partyApi.registerIndividual(
                registrationWithNationalId("Second Registration", "NIDA-DUP-001"), "test-agent"));
    }

    @Test
    void theSameNumberUnderADifferentDocumentTypeIsNotADuplicate() {
        partyApi.registerIndividual(registrationWithNationalId("National ID Holder", "SHARED-123"),
            "test-agent");

        var passportHolder = partyApi.registerIndividual(new IndividualRegistration(
            "Passport Holder", LocalDate.of(1990, 1, 1), null, null, null, null,
            new IdentityDocument(IdType.PASSPORT, "SHARED-123"),
            null, null, null, null, Address.none()), "test-agent");

        assertThat(passportHolder.partyId()).isNotNull();
    }

    /** A type without a number, or a number without a type, is rejected before it can be stored. */
    @Test
    void anIncompleteIdentityDocumentIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new IdentityDocument(IdType.PASSPORT, null));
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new IdentityDocument(null, "A1234567"));
    }

    /**
     * A CORRECTION DOES NOT UN-VERIFY A PERSON.
     *
     * <p>The rule the business stated: KYC is a passport — a document verifying that this person
     * is who they say they are. Amending what the platform has recorded about them does not
     * invalidate that document, so a verified client is not sent back to PENDING and made to
     * prove themselves again over a corrected street name.
     *
     * <p>Asserted on an IDENTITY field, not merely a contact one, because that is the case where
     * an implementation would most plausibly decide to be clever and reset.
     */
    @Test
    void amendingAVerifiedClientLeavesTheirKycAlone() {
        var registered = partyApi.registerIndividual(
            registrationWithNationalId("Kyc Survivor", "19900101-22222-00001-11"), "test-agent");
        partyApi.submitKycEvidence(registered.partyId(), KycStatus.VERIFIED, "doc-ref-1", "kyc-officer");

        var amended = partyApi.amendIndividual(registered.partyId(),
            new IndividualRegistration("Kyc Survivor Corrected", LocalDate.of(1990, 1, 2),
                "+255713999888", "corrected@example.tz", Sex.FEMALE, SmokerStatus.NON_SMOKER,
                new IdentityDocument(IdType.NATIONAL_ID, "19900101-22222-00001-11"),
                "Teacher", "PROF_1", "Ilala Secondary", "TZ", Address.none()),
            "staff-1");

        assertThat(amended.displayName()).isEqualTo("Kyc Survivor Corrected");
        assertThat(amended.dateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 2));
        assertThat(amended.phoneNumber()).isEqualTo("+255713999888");
        assertThat(amended.occupation()).isEqualTo("Teacher");
        assertThat(amended.kycStatus())
            .as("a correction is not a reason to make a verified client prove themselves again")
            .isEqualTo(KycStatus.VERIFIED);
    }

    /**
     * A full replacement, not a patch: a null clears the field.
     *
     * <p>Stated as its own test because the alternative reading is the tempting one — treat null
     * as "leave alone" — and it would make "remove the employer I recorded by mistake"
     * impossible to express through the only endpoint that edits a client.
     */
    @Test
    void amendingClearsAFieldThatIsSentEmpty() {
        var registered = partyApi.registerIndividual(new IndividualRegistration(
            "Employed Person", LocalDate.of(1990, 1, 1), null, null, null, null,
            new IdentityDocument(IdType.NATIONAL_ID, "19900101-22222-00002-11"),
            "Driver", "PROF_2", "Some Employer Ltd", "TZ", Address.none()), "test-agent");

        var amended = partyApi.amendIndividual(registered.partyId(), new IndividualRegistration(
            "Employed Person", LocalDate.of(1990, 1, 1), null, null, null, null,
            new IdentityDocument(IdType.NATIONAL_ID, "19900101-22222-00002-11"),
            "Driver", "PROF_2", null, "TZ", Address.none()), "staff-1");

        assertThat(amended.employerName()).isNull();
    }

    /**
     * Amending must not collide with the party's OWN identity document.
     *
     * <p>The difference between amending and registering, in one test: registration refuses a
     * document already in the tenant, and the naive reuse of that check here would refuse every
     * correction that left the document untouched — which is nearly all of them.
     */
    @Test
    void amendingDoesNotConflictWithThePartysOwnIdentityDocument() {
        var registered = partyApi.registerIndividual(
            registrationWithNationalId("Self Collision", "19900101-33333-00001-11"), "test-agent");

        var amended = partyApi.amendIndividual(registered.partyId(),
            registrationWithNationalId("Self Collision Corrected", "19900101-33333-00001-11"),
            "staff-1");

        assertThat(amended.displayName()).isEqualTo("Self Collision Corrected");
    }

    /** But somebody ELSE's document is still a conflict — that check is what it is for. */
    @Test
    void amendingOntoAnotherPartysIdentityDocumentIsRefused() {
        partyApi.registerIndividual(
            registrationWithNationalId("Document Owner", "19900101-44444-00001-11"), "test-agent");
        var other = partyApi.registerIndividual(
            registrationWithNationalId("Document Borrower", "19900101-44444-00002-11"), "test-agent");

        Assertions.assertThrows(DuplicateIdentityDocumentException.class,
            () -> partyApi.amendIndividual(other.partyId(),
                registrationWithNationalId("Document Borrower", "19900101-44444-00001-11"),
                "staff-1"));
    }

    /**
     * The registering agent is recorded, and is NOT editable.
     *
     * <p>It decides who gets paid — commission accrues off the agent of record, which policy
     * binds from this field at issuance. An attribution that an edit form could rewrite is a
     * commission that an edit form could reassign, months after the agent did the work. The
     * column is {@code updatable = false} and the aggregate exposes no setter; this test is what
     * stops a future amend method from quietly growing one.
     */
    @Test
    void theRegisteringAgentIsRecordedAndSurvivesAnAmendment() {
        UUID agentPartyId = UUID.randomUUID();
        var registered = partyApi.registerIndividual(
            registrationWithNationalId("Agent Brought Me In", "19900101-55555-00001-11"),
            "agent-subject", agentPartyId);

        assertThat(partyApi.getPartyDetail(registered.partyId()).registeredByPartyId())
            .isEqualTo(agentPartyId);

        var amended = partyApi.amendIndividual(registered.partyId(),
            registrationWithNationalId("Renamed Entirely", "19900101-55555-00001-11"), "staff-1");

        assertThat(amended.registeredByPartyId())
            .as("who brought this client in is not something an edit form may reassign")
            .isEqualTo(agentPartyId);
    }

    /** Nobody brought in a client staff registered, and null says exactly that. */
    @Test
    void aClientRegisteredWithoutAnAgentHasNoRegisteringAgent() {
        var registered = partyApi.registerIndividual(
            registrationWithNationalId("Walked In", "19900101-66666-00001-11"), "staff-1");

        assertThat(partyApi.getPartyDetail(registered.partyId()).registeredByPartyId()).isNull();
    }

    private static IndividualRegistration registrationWithNationalId(String fullName, String idNumber) {
        return new IndividualRegistration(fullName, LocalDate.of(1990, 1, 1), null, null,
            null, null, new IdentityDocument(IdType.NATIONAL_ID, idNumber),
            null, null, null, null, Address.none());
    }

    @Test
    void invalidPhoneNumberIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> partyApi.registerIndividual("Bad Phone", LocalDate.of(1990, 1, 1), "0712345678", null, "test-agent"));
    }

    /**
     * Real concurrency, not two sequential calls: two threads race to registerCorporate with
     * the SAME (tenantId, registrationNumber), synchronized via CountDownLatch so both plausibly
     * clear the findByTenantIdAndRegistrationNumber pre-check before either transaction commits.
     * Whichever thread's saveAndFlush() (or, if the other already committed by the time this one
     * runs its pre-check, whichever thread's pre-check) loses must see DuplicateRegistrationNumberException
     * -- never a raw DataIntegrityViolationException leaking out. Exactly one of the two must succeed.
     */
    @Test
    void concurrentDuplicateCorporateRegistrationsYieldExactlyOneSuccess() throws Exception {
        UUID sharedTenantId = tenantId;
        String registrationNumber = "REG-RACE-001";
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Object> attempt = () -> {
            TenantContext.set(sharedTenantId);
            try {
                readyLatch.countDown();
                startLatch.await(5, TimeUnit.SECONDS);
                return partyApi.registerCorporate("Race Corp", registrationNumber, "+255712345900", null, "test-agent");
            } catch (Exception e) {
                return e;
            } finally {
                TenantContext.clear();
            }
        };

        try {
            Future<Object> first = executor.submit(attempt);
            Future<Object> second = executor.submit(attempt);

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            List<Object> results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));

            long successes = results.stream().filter(r -> r instanceof PartyView).count();
            long duplicateRejections = results.stream().filter(r -> r instanceof DuplicateRegistrationNumberException).count();
            long anythingElse = results.size() - successes - duplicateRejections;

            assertThat(successes).isEqualTo(1);
            assertThat(duplicateRejections).isEqualTo(1);
            assertThat(anythingElse).as("no other exception type (e.g. a raw DataIntegrityViolationException) should leak").isEqualTo(0);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void submitKycEvidenceUpdatesStatusAndPublishesAuditedEvent() {
        var registered = partyApi.registerIndividual("Juma Mwita", LocalDate.of(1985, 3, 20),
            "+255712345002", "juma@example.tz", "test-agent");

        Instant before = Instant.now();
        partyApi.submitKycEvidence(registered.partyId(), KycStatus.VERIFIED, "doc-ref-123", "kyc-officer");

        PartyView updated = partyApi.getParty(registered.partyId());
        assertThat(updated.kycStatus()).isEqualTo(KycStatus.VERIFIED);

        List<?> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "party.PartyKycStatusChanged", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
    }

    @Test
    void addGroupMemberAndListGroupMembers() {
        var group = partyApi.registerGroup("Umoja SACCO Group", "test-agent");
        var member = partyApi.registerIndividual("Fatuma Ally", LocalDate.of(1992, 7, 1),
            "+255712345003", "fatuma@example.tz", "test-agent");

        partyApi.addGroupMember(group.partyId(), member.partyId());

        Page<?> members = partyApi.listGroupMembers(group.partyId(), PageRequest.of(0, 50));
        assertThat(members.getContent()).hasSize(1);
    }

    @Test
    void addingGroupMemberToNonGroupPartyIsRejected() {
        var notAGroup = partyApi.registerIndividual("Not A Group", LocalDate.of(1980, 1, 1),
            "+255712345004", null, "test-agent");
        var member = partyApi.registerIndividual("Some Member", LocalDate.of(1995, 1, 1),
            "+255712345005", null, "test-agent");

        Assertions.assertThrows(IllegalArgumentException.class,
            () -> partyApi.addGroupMember(notAGroup.partyId(), member.partyId()));
    }

    @Test
    void crossTenantAccessIsTreatedAsPartyNotFound() {
        var partyUnderTenantA = partyApi.registerIndividual("Tenant A's Party", LocalDate.of(1990, 1, 1),
            "+255712345006", null, "test-agent");
        var groupUnderTenantA = partyApi.registerGroup("Tenant A's Group", "test-agent");

        TenantContext.set(UUID.randomUUID());

        Assertions.assertThrows(PartyNotFoundException.class, () -> partyApi.getParty(partyUnderTenantA.partyId()));
        Assertions.assertThrows(PartyNotFoundException.class,
            () -> partyApi.submitKycEvidence(partyUnderTenantA.partyId(), KycStatus.VERIFIED, "doc-ref", "kyc-officer"));
        Assertions.assertThrows(PartyNotFoundException.class,
            () -> partyApi.addGroupMember(groupUnderTenantA.partyId(), partyUnderTenantA.partyId()));
        Assertions.assertThrows(PartyNotFoundException.class,
            () -> partyApi.listGroupMembers(groupUnderTenantA.partyId(), PageRequest.of(0, 50)));
    }

    // ---- the insurer's own reference -----------------------------------------

    @Test
    void theClientReferenceRoundTripsThroughRegistrationAndDetailRead() {
        var registered = partyApi.registerIndividual(new IndividualRegistration(
            "Referenced Client", LocalDate.of(1985, 4, 2), "+255713444555", null,
            Sex.MALE, null, new IdentityDocument(IdType.NATIONAL_ID, "19850402-44455-00001-11"),
            null, null, null, "TZ", null, "CLT-000412"), "test-agent");

        assertThat(partyApi.getPartyDetail(registered.partyId()).clientReference())
            .isEqualTo("CLT-000412");
    }

    @Test
    void aClientWithNoReferenceIsCompleteRatherThanIncomplete() {
        // Optional by design: it exists to reconcile against a book the business already keeps,
        // and an agent who has no such number is registering a whole client.
        var registered = partyApi.registerIndividual("Unreferenced Client",
            LocalDate.of(1985, 4, 2), "+255713444666", null, "test-agent");

        assertThat(partyApi.getPartyDetail(registered.partyId()).clientReference()).isNull();
    }

    @Test
    void aBlankReferenceIsStoredAsAbsentRatherThanAsAnEmptyString() {
        // Otherwise "" occupies ux_party_client_reference and refuses the blank to everybody
        // else, which is a confusing way to discover a typo.
        var registered = partyApi.registerIndividual(new IndividualRegistration(
            "Blank Reference", LocalDate.of(1985, 4, 2), "+255713444777", null,
            Sex.MALE, null, new IdentityDocument(IdType.NATIONAL_ID, "19850402-44477-00001-11"),
            null, null, null, "TZ", null, "   "), "test-agent");

        assertThat(partyApi.getPartyDetail(registered.partyId()).clientReference()).isNull();
    }

    @Test
    void twoClientsMayNotShareOneReference() {
        // The whole point of the column is that it identifies one client. Two sharing it would
        // reconcile to both, which is worse than reconciling to neither.
        partyApi.registerIndividual(new IndividualRegistration(
            "First Holder", LocalDate.of(1985, 4, 2), "+255713444888", null,
            Sex.MALE, null, new IdentityDocument(IdType.NATIONAL_ID, "19850402-44488-00001-11"),
            null, null, null, "TZ", null, "CLT-DUPLICATE"), "test-agent");

        Assertions.assertThrows(Exception.class, () -> partyApi.registerIndividual(
            new IndividualRegistration("Second Holder", LocalDate.of(1986, 5, 3),
                "+255713444999", null, Sex.FEMALE, null,
                new IdentityDocument(IdType.NATIONAL_ID, "19860503-44499-00001-11"),
                null, null, null, "TZ", null, "CLT-DUPLICATE"), "test-agent"));
    }
}
