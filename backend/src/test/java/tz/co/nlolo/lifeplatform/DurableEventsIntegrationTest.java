package tz.co.nlolo.lifeplatform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Durable events (2026-11-14): a delivery its listener does not finish stays recorded and is delivered again, with
 * every payload value back in its own type -- a UUID as a UUID, money as a BigDecimal, a date as a LocalDate.
 */
@Testcontainers
@SpringBootTest(classes = {Application.class, DurableEventsIntegrationTest.Probe.class})
class DurableEventsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");
    }

    /** Fails its first delivery of a probe event, then records what it was given. */
    @TestConfiguration
    static class Probe {
        static final AtomicInteger ATTEMPTS = new AtomicInteger();
        static final List<Map<String, Object>> RECEIVED = new ArrayList<>();

        @Bean
        ProbeListener probeListener() {
            return new ProbeListener();
        }
    }

    static class ProbeListener {
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        @SuppressWarnings("unchecked")
        public void on(DomainEventEnvelope<?> envelope) {
            if (!"test.DurableProbe".equals(envelope.eventType())) return;
            if (Probe.ATTEMPTS.incrementAndGet() == 1) {
                throw new IllegalStateException("the first delivery fails, as a crash or an outage would");
            }
            Probe.RECEIVED.add((Map<String, Object>) envelope.payload());
        }
    }

    @Autowired private ApplicationEventPublisher events;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private IncompleteEventPublications incomplete;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aDeliveryItsListenerDidNotFinishIsDeliveredAgainWithItsValuesInTheirOwnTypes() {
        UUID policyVersion = UUID.randomUUID();
        Map<String, Object> payload = Map.of("productVersionId", policyVersion, "amount", new BigDecimal("1500.50"),
            "dueDate", LocalDate.of(2026, 12, 10), "policyNumber", "POL-DURABLE", "count", 3,
            "nested", Map.of("paid", new BigDecimal("0.00")));
        new TransactionTemplate(transactions).executeWithoutResult(status ->
            events.publishEvent(DomainEventEnvelope.of("test.DurableProbe", UUID.randomUUID(), payload)));

        assertThat(Probe.ATTEMPTS.get()).isEqualTo(1);
        assertThat(Probe.RECEIVED).isEmpty();
        assertThat(open()).as("the failed delivery stays recorded").isEqualTo(1);

        incomplete.resubmitIncompletePublications(publication -> true);

        assertThat(Probe.ATTEMPTS.get()).isEqualTo(2);
        assertThat(Probe.RECEIVED).singleElement().satisfies(received -> {
            assertThat(received.get("productVersionId")).isInstanceOf(UUID.class).isEqualTo(policyVersion);
            assertThat(received.get("amount")).isInstanceOf(BigDecimal.class).isEqualTo(new BigDecimal("1500.50"));
            assertThat(received.get("dueDate")).isEqualTo(LocalDate.of(2026, 12, 10));
            assertThat(received.get("count")).isEqualTo(3);
            assertThat(((Map<?, ?>) received.get("nested")).get("paid")).isEqualTo(new BigDecimal("0.00"));
        });
        assertThat(open()).as("delivered, so no longer open").isZero();
    }

    @Test
    void anEventIsRecordedOnlyForTheListenersWhoseConditionTakesIt() {
        new TransactionTemplate(transactions).executeWithoutResult(status ->
            events.publishEvent(DomainEventEnvelope.of("test.NobodyListensForThis", UUID.randomUUID(), Map.of())));

        java.util.List<String> listeners = jdbc.queryForList("SELECT listener_id FROM event_publication"
            + " WHERE serialized_event LIKE '%test.NobodyListensForThis%'", String.class);
        // The audit journal, the ledger and reporting read every event, and so does this test's probe; the fifty
        // listeners that name their events are not recorded at all.
        assertThat(listeners).hasSize(4)
            .anySatisfy(id -> assertThat(id).contains("DomainEventAuditListener"))
            .anySatisfy(id -> assertThat(id).contains("LedgerEventListener"))
            .anySatisfy(id -> assertThat(id).contains("ReportingEventListener"));
    }

    private long open() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE completion_date IS NULL"
            + " AND serialized_event LIKE '%test.DurableProbe%'", Long.class);
        return n == null ? 0 : n;
    }
}
