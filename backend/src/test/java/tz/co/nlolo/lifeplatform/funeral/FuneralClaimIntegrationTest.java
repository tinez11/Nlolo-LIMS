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
import tz.co.nlolo.lifeplatform.claims.api.ClaimDeclineReason;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimValidationException;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.claims.api.InvalidClaimStateException;
import tz.co.nlolo.lifeplatform.party.api.IdType;
import tz.co.nlolo.lifeplatform.party.api.IdentityDocument;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.payment.domain.PaymentGatewayPort;
import tz.co.nlolo.lifeplatform.policy.api.CoveredLifeView;
import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PromoteMemberRequest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.family;

/**
 * A death claim on one covered life of a funeral plan, through the real chain -- registered, assessed,
 * approved, paid by the (stubbed) rail, settled -- and what it does to the policy: the life ends, the family
 * stays covered, the premium falls. Plus the waiting period (R7), who may file (R9), one claim per life.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({FuneralTestFixtures.class, AnnuityTestFixtures.class})
class FuneralClaimIntegrationTest {

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
            FuneralTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();

    @Autowired private FuneralTestFixtures fixtures;
    @Autowired private AnnuityTestFixtures annuityFixtures;
    @Autowired private PolicyApi policyApi;
    @Autowired private ClaimsApi claimsApi;
    @Autowired private JdbcTemplate jdbc;
    /** The mobile-money rail accepts every payout, so a settlement completes through the real chain. */
    @MockBean private PaymentGatewayPort gateway;

    private UUID juma;

    @BeforeEach
    void railAccepts() {
        when(gateway.submitDisbursement(any())).thenReturn(new PaymentGatewayPort.GatewayResult(true, "MM-FUNERAL", null));
    }

    private String familyInForce() {
        var product = fixtures.publishFamilia(TENANT);
        juma = fixtures.person(TENANT, 40, Sex.MALE);
        return fixtures.issueFamilyInForce(TENANT, product, juma, family(), annuityFixtures);
    }

    /** Every life's cover started {@code months} ago, so the waiting period has run. */
    private void coverStartedMonthsAgo(String policyNumber, int months) {
        jdbc.update("UPDATE policy.covered_life SET cover_start = cover_start - make_interval(months => ?)"
            + " WHERE tenant_id = ? AND policy_number = ?", months, TENANT, policyNumber);
    }

    private CoveredLifeView life(String policyNumber, String name) {
        return asTenant(TENANT, () -> policyApi.coveredLives(policyNumber)).stream()
            .filter(l -> l.fullName().equals(name)).findFirst().orElseThrow();
    }

    private ClaimView register(String policyNumber, UUID coveredLifeId, UUID claimant, boolean accidental) {
        return asTenant(TENANT, () -> claimsApi.registerClaim(new ClaimsApi.RegisterClaimRequest(policyNumber, null, claimant,
            ClaimType.DEATH, TODAY, new DeathClaimDetails(accidental ? "Road accident" : "Malaria", "Dar es Salaam", TODAY,
                "Dr. Test", null), coveredLifeId, accidental), UUID.randomUUID().toString(), "claims-clerk"));
    }

    private void assess(UUID claimId) {
        asTenant(TENANT, () -> claimsApi.submitAssessment(claimId, "Death certificate seen", null, null, false,
            "claims-assessor", null));
    }

    private void approve(UUID claimId, String amount) {
        asTenant(TENANT, () -> {
            claimsApi.decideSettlement(claimId, true, new BigDecimal(amount), "TZS", null, "+255700000777",
                UUID.randomUUID().toString(), "claims-manager");
            return null;
        });
    }

    private ClaimStatus status(UUID claimId) {
        return asTenant(TENANT, () -> claimsApi.getClaim(claimId)).status();
    }

    @Test
    void aChildsDeathAfterTheWaitingPeriodPaysTheChildsBenefitAndThePolicyCarriesOn() {
        String policyNumber = familyInForce();
        coverStartedMonthsAgo(policyNumber, 7);
        UUID neema = life(policyNumber, "Neema").coveredLifeId();

        ClaimView claim = register(policyNumber, neema, juma, false);
        assertThat(claim.coveredLifeId()).isEqualTo(neema);
        assertThat(asTenant(TENANT, () -> claimsApi.claimableCover(claim.claimId())).amount()).isEqualByComparingTo("1000000");
        assess(claim.claimId());
        approve(claim.claimId(), "1000000.00");

        assertThat(status(claim.claimId())).isEqualTo(ClaimStatus.SETTLED);
        CoveredLifeView ended = life(policyNumber, "Neema");
        assertThat(ended.status()).isEqualTo("ENDED");
        assertThat(ended.endReason()).isEqualTo("DECEASED");
        assertThat(ended.endedOn()).isEqualTo(TODAY);
        var policy = asTenant(TENANT, () -> policyApi.getPolicy(policyNumber));
        assertThat(policy.status().name()).isEqualTo("ACTIVE");
        // 138,000 - 6,000 = 132,000 a year; x 1.05 / 12
        assertThat(policy.premiumAmount()).isEqualByComparingTo("11550.00");
    }

    @Test
    void aNaturalDeathInsideTheWaitingPeriodCannotBeApprovedAndIsDeclinedForIt() {
        String policyNumber = familyInForce();
        UUID neema = life(policyNumber, "Neema").coveredLifeId();
        ClaimView claim = register(policyNumber, neema, juma, false);
        assess(claim.claimId());

        assertThatThrownBy(() -> approve(claim.claimId(), "1000000.00"))
            .isInstanceOf(InvalidClaimStateException.class)
            .hasMessageContaining("was inside the waiting period, which runs to " + TODAY.plusMonths(6));

        asTenant(TENANT, () -> {
            claimsApi.decideSettlement(claim.claimId(), false, null, null, "Died inside the waiting period",
                ClaimDeclineReason.WITHIN_WAITING_PERIOD, null, null, "claims-manager");
            return null;
        });
        assertThat(status(claim.claimId())).isEqualTo(ClaimStatus.REJECTED);
        assertThat(life(policyNumber, "Neema").status()).isEqualTo("ACTIVE");
    }

    /**
     * Reopening a claim declined with a coded reason was a 500 (2026-10-08): the claim went back to REOPENED with its
     * WITHIN_WAITING_PERIOD still set, and chk_claim_decline_reason_only_when_rejected refused the save. Found by the
     * user: a death declined for the waiting period, reopened on new evidence that it was an accident.
     */
    @Test
    void aClaimDeclinedForTheWaitingPeriodReopensAndIsPaidOnceTheDeathIsRecordedAsAnAccident() {
        String policyNumber = familyInForce();
        UUID neema = life(policyNumber, "Neema").coveredLifeId();
        ClaimView claim = register(policyNumber, neema, juma, false);
        assess(claim.claimId());
        asTenant(TENANT, () -> {
            claimsApi.decideSettlement(claim.claimId(), false, null, null, "Died inside the waiting period",
                ClaimDeclineReason.WITHIN_WAITING_PERIOD, null, null, "claims-manager");
            return null;
        });

        asTenant(TENANT, () -> {
            claimsApi.reopenClaim(claim.claimId(), "New evidence: a road accident", "claims-manager");
            return null;
        });
        // The reopen saving at all is the fix: before it, the check constraint refused this row.
        assertThat(status(claim.claimId())).isEqualTo(ClaimStatus.REOPENED);

        asTenant(TENANT, () -> claimsApi.recordAccidentalDeath(claim.claimId(), true, "claims-assessor"));
        assess(claim.claimId());
        approve(claim.claimId(), "1000000.00");
        assertThat(status(claim.claimId())).isEqualTo(ClaimStatus.SETTLED);
    }

    @Test
    void theWaitingPeriodCannotBeCitedOnceItHasRun() {
        String policyNumber = familyInForce();
        coverStartedMonthsAgo(policyNumber, 7);
        ClaimView claim = register(policyNumber, life(policyNumber, "Neema").coveredLifeId(), juma, false);
        assess(claim.claimId());

        assertThatThrownBy(() -> asTenant(TENANT, () -> {
            claimsApi.decideSettlement(claim.claimId(), false, null, null, "No", ClaimDeclineReason.WITHIN_WAITING_PERIOD,
                null, null, "claims-manager");
            return null;
        })).isInstanceOf(ClaimValidationException.class).hasMessageContaining("that window was not open");
    }

    @Test
    void anAccidentalDeathInsideTheWaitingPeriodIsPaidWhenTheProductWaivesIt() {
        String policyNumber = familyInForce();
        UUID baraka = life(policyNumber, "Baraka").coveredLifeId();
        ClaimView claim = register(policyNumber, baraka, juma, false);
        assertThat(claim.accidental()).isFalse();
        // The assessor learns it was a road accident.
        assertThat(asTenant(TENANT, () -> claimsApi.recordAccidentalDeath(claim.claimId(), true, "claims-assessor")).accidental())
            .isTrue();
        assess(claim.claimId());
        approve(claim.claimId(), "1000000.00");

        assertThat(status(claim.claimId())).isEqualTo(ClaimStatus.SETTLED);
        assertThat(life(policyNumber, "Baraka").endReason()).isEqualTo("DECEASED");
    }

    @Test
    void aClaimOnAFuneralPlanMustNameTheLife() {
        String policyNumber = familyInForce();
        assertThatThrownBy(() -> register(policyNumber, null, juma, false))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessage("A claim on a funeral plan names the covered life who died");
    }

    @Test
    void aSecondClaimOnTheSameChildIsRefusedButOnASiblingIsNot() {
        String policyNumber = familyInForce();
        UUID neema = life(policyNumber, "Neema").coveredLifeId();
        register(policyNumber, neema, juma, false);

        assertThatThrownBy(() -> register(policyNumber, neema, juma, false))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining("A death claim already exists for this life");
        assertThat(register(policyNumber, life(policyNumber, "Baraka").coveredLifeId(), juma, false).status())
            .isEqualTo(ClaimStatus.REGISTERED);
    }

    @Test
    void aDependantsClaimFiledBySomeoneElseIsRefused() {
        String policyNumber = familyInForce();
        UUID stranger = fixtures.person(TENANT, 30, Sex.FEMALE);

        assertThatThrownBy(() -> register(policyNumber, life(policyNumber, "Neema").coveredLifeId(), stranger, false))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining("is claimed by the main member");
    }

    @Test
    void aDeclinedClaimLeavesTheLifeActive() {
        String policyNumber = familyInForce();
        coverStartedMonthsAgo(policyNumber, 7);
        ClaimView claim = register(policyNumber, life(policyNumber, "Zawadi").coveredLifeId(), juma, false);
        assess(claim.claimId());
        asTenant(TENANT, () -> {
            claimsApi.decideSettlement(claim.claimId(), false, null, null, "Not covered: fraud", null, null, null, "claims-manager");
            return null;
        });

        assertThat(life(policyNumber, "Zawadi").status()).isEqualTo("ACTIVE");
    }

    @Test
    void promotingADependantRegistersThemOnce() {
        String policyNumber = familyInForce();
        UUID neema = life(policyNumber, "Neema").coveredLifeId();
        var identity = new PromoteMemberRequest(new IdentityDocument(IdType.NATIONAL_ID, "19990101-11111-00001-11"),
            "+255711000111", Sex.FEMALE);

        UUID first = asTenant(TENANT, () -> policyApi.promoteCoveredLife(policyNumber, neema, identity, "claims-assessor")).partyId();
        UUID second = asTenant(TENANT, () -> policyApi.promoteCoveredLife(policyNumber, neema, identity, "claims-assessor")).partyId();

        assertThat(first).isNotNull().isEqualTo(second);
    }

    @Test
    void aClaimOnAnOrdinaryPolicyCannotNameACoveredLife() {
        var ordinary = annuityFixtures.publishOrdinary(TENANT);
        UUID someone = fixtures.person(TENANT, 40, Sex.MALE);
        String policyNumber = asTenant(TENANT, () -> policyApi.issuePolicy(UUID.randomUUID(), new PolicyApi.IssueRequest(someone,
            ordinary.productId(), ordinary.versionId(), new BigDecimal("1000000"), "TZS", new BigDecimal("1000"), "TZS",
            "MONTHLY", null, java.util.List.of(), "ordinary", null, null, null, null,
            tz.co.nlolo.lifeplatform.policy.api.IssuanceBasis.MIGRATION), "test-staff").policyNumber());

        assertThatThrownBy(() -> register(policyNumber, UUID.randomUUID(), someone, false))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessage("Policy " + policyNumber + " is not a funeral plan, so a claim on it cannot name a covered life");
    }
}
