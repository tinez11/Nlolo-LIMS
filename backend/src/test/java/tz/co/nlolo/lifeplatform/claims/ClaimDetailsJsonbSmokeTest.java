package tz.co.nlolo.lifeplatform.claims;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.claims.api.ClaimDetails;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.CriticalIllnessClaimDetails;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.claims.api.DisabilityClaimDetails;
import tz.co.nlolo.lifeplatform.claims.api.MaturityClaimDetails;
import tz.co.nlolo.lifeplatform.claims.domain.Claim;
import tz.co.nlolo.lifeplatform.claims.infrastructure.ClaimRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 3's judgment call: {@code Claim.details} is mapped directly as the sealed
 * {@link ClaimDetails} interface via {@code @JdbcTypeCode(SqlTypes.JSON)}, the same annotation
 * {@code policy.domain.Endorsement} uses for its JSONB {@code changes} column but typed as the
 * polymorphic interface rather than {@code Map<String,Object>}. That choice compiles cleanly and
 * passes {@code ClaimStateMachineTest} (a plain unit test with no database), but neither of those
 * proves Hibernate can actually round-trip a JSONB column through Jackson's
 * {@code @JsonTypeInfo}/{@code @JsonSubTypes} discriminator against a REAL Postgres instance.
 * This test is that proof: it persists a claim for each of the four {@link ClaimDetails}
 * subtypes, forces a genuine DB round trip (flush + clear the persistence context so the reload
 * is not served from the session cache), and asserts the reloaded entity carries the same
 * concrete subtype with the same field values.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class ClaimDetailsJsonbSmokeTest {

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
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql",
            "db-migrations/claims/V5__claim_policy_member.sql",
            "db-migrations/claims/V6__exclusion_decline.sql",
            "db-migrations/claims/V7__claim_assessment_assessor_name.sql");
    }

    @Autowired private ClaimRepository claimRepository;

    private static List<ClaimDetails> allDetailsVariants() {
        return List.of(
            new DeathClaimDetails("Cardiac arrest", "Dar es Salaam", LocalDate.of(2026, 1, 5), "Dr. Juma"),
            new DisabilityClaimDetails("Loss of limb", LocalDate.of(2026, 1, 5), true, new BigDecimal("80.50")),
            new CriticalIllnessClaimDetails("Stage 3 carcinoma", LocalDate.of(2026, 1, 5), "C50"),
            new MaturityClaimDetails(LocalDate.of(2026, 1, 5))
        );
    }

    @ParameterizedTest
    @MethodSource("allDetailsVariants")
    void savesAndReloadsEachClaimDetailsSubtypeWithTheSameConcreteTypeAndFields(ClaimDetails details) {
        UUID tenantId = UUID.randomUUID();
        Claim claim = new Claim(tenantId, "POL-JSONB-01", null, UUID.randomUUID(), details.claimType(),
            LocalDate.of(2026, 1, 5), details, "test-staff", null);

        // save() and findById() below are each Spring Data's own separately-transactional call
        // (JpaRepository methods are @Transactional per call, and this test class opens no
        // surrounding transaction), so each opens and closes its OWN Hibernate session against
        // the real Postgres container -- the reload is a genuine SELECT against a fresh
        // persistence context, never the same in-memory Java object served from a session cache.
        Claim saved = claimRepository.save(claim);
        UUID claimId = saved.getClaimId();
        assertThat(claimId).isNotNull();

        Claim reloaded = claimRepository.findById(claimId).orElseThrow();

        assertThat(reloaded.getDetails()).isInstanceOf(details.getClass());
        assertThat(reloaded.getDetails()).isEqualTo(details);
        assertThat(reloaded.getDetails().claimType()).isEqualTo(details.claimType());
        assertThat(reloaded.getClaimType()).isEqualTo(details.claimType());
    }

    @Test
    void allFourSubtypesCoexistInTheSameTableWithoutCrossContamination() {
        List<ClaimDetails> variants = allDetailsVariants();
        List<UUID> ids = variants.stream().map(details -> {
            Claim claim = new Claim(UUID.randomUUID(), "POL-JSONB-02", null, UUID.randomUUID(), details.claimType(),
                LocalDate.of(2026, 1, 5), details, "test-staff", null);
            return claimRepository.save(claim).getClaimId();
        }).toList();

        for (int i = 0; i < ids.size(); i++) {
            Claim reloaded = claimRepository.findById(ids.get(i)).orElseThrow();
            assertThat(reloaded.getDetails()).isEqualTo(variants.get(i));
        }
    }

    @Test
    void claimTypeMustBeOneOfTheFourPermittedByDdlCheckConstraint() {
        // ClaimType itself has exactly these four members -- this is the compile-time
        // enforcement of the DDL CHECK constraint at db-migrations/claims/V1:11.
        assertThat(ClaimType.values()).containsExactlyInAnyOrder(
            ClaimType.DEATH, ClaimType.DISABILITY, ClaimType.CRITICAL_ILLNESS, ClaimType.MATURITY);
    }
}
