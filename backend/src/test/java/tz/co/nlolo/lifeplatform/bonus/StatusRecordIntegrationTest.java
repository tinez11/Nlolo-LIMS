package tz.co.nlolo.lifeplatform.bonus;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.DepositTestMigrations;
import tz.co.nlolo.lifeplatform.bonus.domain.StatusEvent;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.ParticipantRepository;
import tz.co.nlolo.lifeplatform.bonus.infrastructure.StatusEventRepository;
import tz.co.nlolo.lifeplatform.product.api.BonusPlan;
import tz.co.nlolo.lifeplatform.product.api.CashValuePlan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The status record: written for participating policies from issue on, never for the rest, once per event. */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(BonusTestFixtures.class)
class StatusRecordIntegrationTest {

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
            DepositTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();
    @Autowired private BonusTestFixtures fixtures;
    @Autowired private StatusEventRepository statusEvents;
    @Autowired private ParticipantRepository participants;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    @Test
    void aParticipatingPolicyIsRecordedFromIssueAndEachChangeAfter() {
        var issued = fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        // issuePolicy's PolicyIssued (PROPOSED) then activateOnFirstPremium's PolicyActivated (ACTIVE).
        var rows = asTenant(() -> statusEvents.findByPolicyNumberOrderByEffectiveAtAsc(issued.policyNumber()));
        assertThat(rows).extracting(StatusEvent::getStatus).containsExactly("PROPOSED", "ACTIVE");
        assertThat(rows.get(0).getSumAssured()).isEqualByComparingTo("1000000.00");
        assertThat(asTenant(() -> participants.findById(issued.policyNumber())).orElseThrow().getIssuedOn())
            .isEqualTo(LocalDate.now());
    }

    @Test
    void aNonParticipatingPolicyIsNotRecordedAtAll() {
        var issued = fixtures.issue(TENANT, BonusPlan.none(), CashValuePlan.none());
        assertThat(asTenant(() -> participants.existsById(issued.policyNumber()))).isFalse();
        assertThat(asTenant(() -> statusEvents.findByPolicyNumberOrderByEffectiveAtAsc(issued.policyNumber()))).isEmpty();
    }

    @Test
    void aRedeliveredEventIsRecordedOnce() {
        var issued = fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        var envelope = new DomainEventEnvelope<>(UUID.randomUUID(), "policy.PolicyLapsed", 1, TENANT, Instant.now(),
            null, Map.<String, Object>of("policyNumber", issued.policyNumber(), "lapsedAt", Instant.now().toString()));
        fixtures.publishEnvelopeTwice(envelope);
        assertThat(asTenant(() -> statusEvents.findByPolicyNumberOrderByEffectiveAtAsc(issued.policyNumber())))
            .extracting(StatusEvent::getStatus).containsExactly("PROPOSED", "ACTIVE", "LAPSED");
    }

    @Test
    void aPaidUpEventRecordsTheReducedSumAssuredFromItsPlainDecimal() {
        var issued = fixtures.issue(TENANT, BonusTestFixtures.COMPOUND_NONE, CashValuePlan.none());
        // The real payload shape: paidUpSumAssured is a plain decimal string, not a money object.
        fixtures.publish(TENANT, "policy.PolicyMadePaidUp",
            Map.of("policyNumber", issued.policyNumber(), "paidUpSumAssured", "400000.00"));
        var rows = asTenant(() -> statusEvents.findByPolicyNumberOrderByEffectiveAtAsc(issued.policyNumber()));
        assertThat(rows).extracting(StatusEvent::getStatus).containsExactly("PROPOSED", "ACTIVE", "PAID_UP");
        assertThat(rows.get(2).getSumAssured()).isEqualByComparingTo("400000.00");
    }
}
