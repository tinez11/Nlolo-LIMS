package tz.co.nlolo.lifeplatform.party;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import tz.co.nlolo.lifeplatform.party.api.DuplicateRegistrationNumberException;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
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
}
