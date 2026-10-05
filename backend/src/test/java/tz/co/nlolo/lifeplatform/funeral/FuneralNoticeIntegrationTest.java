package tz.co.nlolo.lifeplatform.funeral;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.payment.domain.PaymentGatewayPort;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.dependant;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.family;

/** The main member hears when a life joins or ends and when the premium changes -- and nothing for a death. */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({FuneralTestFixtures.class, AnnuityTestFixtures.class})
class FuneralNoticeIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String[] COMMUNICATION = {
        "db-migrations/communication/V1__create_communication_schema.sql",
        "db-migrations/communication/V2__template_identity.sql",
        "db-migrations/communication/V3__seed_offer_templates.sql",
        "db-migrations/communication/V4__dispatch_reason_and_policy.sql",
        "db-migrations/communication/V5__dispatch_claimed_status.sql",
        "db-migrations/communication/V6__grants_and_rls.sql",
        "db-migrations/communication/V7__null_safe_rls_and_pending_reminders.sql",
        "db-migrations/communication/V8__platform_default_templates.sql",
        "db-migrations/communication/V9__payment_received_template.sql",
        "db-migrations/communication/V10__account_statement_template.sql",
        "db-migrations/communication/V11__vesting_reminder_template.sql",
        "db-migrations/communication/V12__funeral_templates.sql",
    };

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            Stream.concat(Stream.of(FuneralTestMigrations.ALL), Stream.of(COMMUNICATION)).toArray(String[]::new));
    }

    private static final UUID TENANT = UUID.randomUUID();

    @Autowired private FuneralTestFixtures fixtures;
    @Autowired private AnnuityTestFixtures annuityFixtures;
    @Autowired private PolicyApi policyApi;
    @Autowired private ClaimsApi claimsApi;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private PaymentGatewayPort gateway;

    @BeforeEach
    void railAccepts() {
        when(gateway.submitDisbursement(any())).thenReturn(new PaymentGatewayPort.GatewayResult(true, "MM-FUNERAL", null));
    }

    private int sent(String policyNumber, String templateKey) {
        return jdbc.queryForObject("SELECT count(*) FROM communication.notification_dispatch"
            + " WHERE policy_number = ? AND template_key = ?", Integer.class, policyNumber, templateKey);
    }

    private String familyInForce() {
        var product = fixtures.publishFamilia(TENANT);
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);
        return fixtures.issueFamilyInForce(TENANT, product, juma, family(), annuityFixtures);
    }

    @Test
    void addingALifeSendsTheNoticeAndTheNewPremium() {
        String policyNumber = familyInForce();

        asTenant(TENANT, () -> policyApi.addCoveredLife(policyNumber, dependant(FuneralRole.CHILD, "Imani", 0), "staff-one"));

        assertThat(sent(policyNumber, "FUNERAL_LIFE_ADDED")).isPositive();
        assertThat(sent(policyNumber, "FUNERAL_PREMIUM_CHANGED")).isPositive();
    }

    @Test
    void aRemovalThatTakesEffectSendsTheCoverEndedNotice() {
        String policyNumber = familyInForce();
        var asha = asTenant(TENANT, () -> policyApi.coveredLives(policyNumber)).stream()
            .filter(l -> l.fullName().equals("Asha")).findFirst().orElseThrow();
        var removing = asTenant(TENANT, () -> policyApi.removeCoveredLife(policyNumber, asha.coveredLifeId(), null, "staff-one"));

        sweepOn(policyNumber, removing.coverEnd());

        assertThat(sent(policyNumber, "FUNERAL_LIFE_ENDED")).isPositive();
    }

    @Test
    void aDeathSendsNoCoverEndedNotice() {
        String policyNumber = familyInForce();
        jdbc.update("UPDATE policy.covered_life SET cover_start = cover_start - interval '7 months'"
            + " WHERE tenant_id = ? AND policy_number = ?", TENANT, policyNumber);
        var neema = asTenant(TENANT, () -> policyApi.coveredLives(policyNumber)).stream()
            .filter(l -> l.fullName().equals("Neema")).findFirst().orElseThrow();
        UUID juma = asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).policyholderPartyId();
        UUID claimId = asTenant(TENANT, () -> claimsApi.registerClaim(new ClaimsApi.RegisterClaimRequest(policyNumber, null, juma,
            ClaimType.DEATH, TODAY, new DeathClaimDetails("Malaria", "Dar es Salaam", TODAY, "Dr. Test", null),
            neema.coveredLifeId(), false), UUID.randomUUID().toString(), "claims-clerk").claimId());
        asTenant(TENANT, () -> {
            claimsApi.submitAssessment(claimId, "Death certificate seen", null, null, false, "claims-assessor", null);
            claimsApi.decideSettlement(claimId, true, new BigDecimal("1000000.00"), "TZS", null, "+255700000777",
                UUID.randomUUID().toString(), "claims-manager");
            return null;
        });

        assertThat(asTenant(TENANT, () -> policyApi.coveredLives(policyNumber)).stream()
            .filter(l -> l.fullName().equals("Neema")).findFirst().orElseThrow().endReason()).isEqualTo("DECEASED");
        assertThat(sent(policyNumber, "FUNERAL_LIFE_ENDED")).isZero();
    }

    @Autowired private tz.co.nlolo.lifeplatform.policy.application.CoveredLifeSweep sweep;

    private void sweepOn(String policyNumber, java.time.LocalDate day) {
        sweep.sweepOne(policyNumber, TENANT, day);
    }
}
