package tz.co.nlolo.lifeplatform.audit;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.audit.api.AuditApi;
import tz.co.nlolo.lifeplatform.audit.api.AuditEntryView;
import tz.co.nlolo.lifeplatform.audit.api.DateRange;
import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(DomainEventAuditListenerIntegrationTest.ProbeConfig.class)
class DomainEventAuditListenerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigration() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");
    }

    @Autowired
    private EventPublishingProbe probe;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AuditApi auditApi;

    @Test
    void publishedEventLandsInAuditLogAfterCommit() {
        UUID tenantId = UUID.randomUUID();
        Instant before = Instant.now();

        probe.publishAndCommit(new DomainEventEnvelope<>(
            UUID.randomUUID(), "test.SyntheticEvent", 1, tenantId, before, null, Map.of("hello", "world")));

        List<?> rows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "test.SyntheticEvent", before.minusSeconds(5), before.plusSeconds(5));
        assertThat(rows).hasSize(1);
    }

    // ---- M13: the tenant-wide read the module never exposed --------------------------

    /**
     * `audit` has recorded every domain event since M1 with no way to read it from
     * outside. These cover the feed that closes that, including the two properties a
     * compliance reader depends on: tenant isolation, and newest-first ordering.
     */
    @Test
    void listEventsReturnsTheTenantsFeedNewestFirst() {
        UUID tenantId = UUID.randomUUID();
        Instant base = Instant.now().minusSeconds(60);
        probe.publishAndCommit(new DomainEventEnvelope<>(
            UUID.randomUUID(), "policy.PolicyIssued", 1, tenantId, base, null, Map.of("n", 1)));
        probe.publishAndCommit(new DomainEventEnvelope<>(
            UUID.randomUUID(), "claims.ClaimSettled", 1, tenantId, base.plusSeconds(30), null, Map.of("n", 2)));

        TenantContext.set(tenantId);
        try {
            Page<AuditEntryView> feed = auditApi.listEvents(null, new DateRange(null, null),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "occurredAt")));

            assertThat(feed.getTotalElements()).isEqualTo(2);
            // Newest first: the later event leads.
            assertThat(feed.getContent().get(0).eventType()).isEqualTo("claims.ClaimSettled");
            // The payload comes back raw and unparsed, by design.
            assertThat(feed.getContent().get(0).payloadJson()).contains("\"n\"");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void listEventsFiltersByModulePrefixAndDateRange() {
        UUID tenantId = UUID.randomUUID();
        Instant old = Instant.now().minusSeconds(3600);
        Instant recent = Instant.now().minusSeconds(30);
        probe.publishAndCommit(new DomainEventEnvelope<>(
            UUID.randomUUID(), "policy.PolicyIssued", 1, tenantId, old, null, Map.of()));
        probe.publishAndCommit(new DomainEventEnvelope<>(
            UUID.randomUUID(), "policy.PolicyLapsed", 1, tenantId, recent, null, Map.of()));
        probe.publishAndCommit(new DomainEventEnvelope<>(
            UUID.randomUUID(), "claims.ClaimSettled", 1, tenantId, recent, null, Map.of()));

        TenantContext.set(tenantId);
        try {
            Pageable page = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "occurredAt"));
            // eventType is `module.EventName`, so a prefix filters by module.
            assertThat(auditApi.listEvents("policy.", new DateRange(null, null), page).getTotalElements()).isEqualTo(2);
            assertThat(auditApi.listEvents("claims.", new DateRange(null, null), page).getTotalElements()).isEqualTo(1);
            // Bounds are inclusive, and bounding matters: unbounded scans every partition.
            assertThat(auditApi.listEvents(null, new DateRange(recent.minusSeconds(5), null), page).getTotalElements())
                .isEqualTo(2);
            assertThat(auditApi.listEvents("policy.", new DateRange(recent.minusSeconds(5), null), page).getTotalElements())
                .isEqualTo(1);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void listEventsNeverCrossesTenants() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        probe.publishAndCommit(new DomainEventEnvelope<>(
            UUID.randomUUID(), "policy.PolicyIssued", 1, tenantA, Instant.now(), null, Map.of()));

        TenantContext.set(tenantB);
        try {
            assertThat(auditApi.listEvents(null, new DateRange(null, null),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "occurredAt"))).getTotalElements()).isZero();
        } finally {
            TenantContext.clear();
        }
    }

    // Registered via a nested @TestConfiguration @Bean method, explicitly wired in with
    // @Import above -- Spring Boot's automatic nested-@TestConfiguration detection only
    // fires when @SpringBootTest is given NO explicit `classes` attribute; since this
    // test passes classes = Application.class, that auto-detection path is skipped
    // (confirmed empirically: without @Import, no probe bean existed at all, whether
    // declared as a plain @Service or via this @TestConfiguration). @Import makes the
    // wiring explicit rather than relying on scanning a nested class inside a test.
    @TestConfiguration
    static class ProbeConfig {
        @Bean
        EventPublishingProbe eventPublishingProbe(ApplicationEventPublisher publisher) {
            return new EventPublishingProbe(publisher);
        }
    }

    static class EventPublishingProbe {
        private final ApplicationEventPublisher publisher;

        EventPublishingProbe(ApplicationEventPublisher publisher) {
            this.publisher = publisher;
        }

        @Transactional
        public void publishAndCommit(DomainEventEnvelope<?> envelope) {
            publisher.publishEvent(envelope);
        }
    }
}
