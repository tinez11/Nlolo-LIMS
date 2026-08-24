package tz.co.nlolo.lifeplatform.audit;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @Autowired
    private EventPublishingProbe probe;

    @Autowired
    private AuditLogRepository auditLogRepository;

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
