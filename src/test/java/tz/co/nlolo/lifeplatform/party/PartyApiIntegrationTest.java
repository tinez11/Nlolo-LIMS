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
