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
import tz.co.nlolo.lifeplatform.policy.api.GroupFuneralFamilyView;
import tz.co.nlolo.lifeplatform.policy.api.MemberStatus;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PromoteMemberRequest;
import tz.co.nlolo.lifeplatform.policy.domain.InstalmentDates;
import tz.co.nlolo.lifeplatform.product.api.DependantClaimPayee;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.product.api.MainMemberDeathRule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.groupProposal;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.life;

/**
 * Death claims on a group funeral scheme's lives (2026-10-07), through the real chain -- registered, assessed,
 * approved, paid by the (stubbed) rail, settled -- and what each does to the scheme: a dependant's death ends that life
 * only; a main member's death takes the family off at month end, or hands it to the spouse, as the version says.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({FuneralTestFixtures.class, AnnuityTestFixtures.class})
class GroupFuneralClaimIntegrationTest {

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
    @MockBean private PaymentGatewayPort gateway;

    @BeforeEach
    void railAccepts() {
        when(gateway.submitDisbursement(any())).thenReturn(new PaymentGatewayPort.GatewayResult(true, "MM-GROUP-FUNERAL", null));
    }

    /** Juma, his wife Asha and their child Neema; Rehema alone -- plan A, paid, every life's waiting period run. */
    private String scheme(DependantClaimPayee payee, MainMemberDeathRule onDeath) {
        var product = fixtures.publishGroupFamilia(TENANT, payee, onDeath);
        UUID association = fixtures.association(TENANT);
        String policyNumber = fixtures.issueGroupScheme(TENANT, product, association, groupProposal("A", List.of(
            life("M001", FuneralRole.MAIN_MEMBER, "Juma Ali", 40), life("M001", FuneralRole.SPOUSE, "Asha Juma", 38),
            life("M001", FuneralRole.CHILD, "Neema Juma", 10), life("M002", FuneralRole.MAIN_MEMBER, "Rehema Said", 50))));
        annuityFixtures.collect(TENANT, policyNumber, "6000.00", TODAY);
        jdbc.update("UPDATE policy.covered_life SET cover_start = cover_start - make_interval(months => 7)"
            + " WHERE tenant_id = ? AND policy_number = ?", TENANT, policyNumber);
        return policyNumber;
    }

    private GroupFuneralFamilyView family(String policyNumber, String reference) {
        return asTenant(TENANT, () -> policyApi.groupFuneralFamilies(policyNumber)).stream()
            .filter(f -> reference.equals(f.memberReference())).findFirst().orElseThrow();
    }

    private CoveredLifeView lifeNamed(String policyNumber, String name) {
        return asTenant(TENANT, () -> policyApi.groupFuneralFamilies(policyNumber)).stream()
            .flatMap(f -> f.lives().stream()).filter(l -> l.fullName().equals(name)).findFirst().orElseThrow();
    }

    /** The main member registered as a party from their identity document, as a claim needs. */
    private UUID promote(String policyNumber, String name, String idNumber) {
        return asTenant(TENANT, () -> policyApi.promoteCoveredLife(policyNumber, lifeNamed(policyNumber, name).coveredLifeId(),
            new PromoteMemberRequest(new IdentityDocument(IdType.NATIONAL_ID, idNumber), "+255711000222", Sex.MALE),
            "claims-assessor")).partyId();
    }

    private ClaimView register(String policyNumber, UUID coveredLifeId, UUID claimant) {
        return asTenant(TENANT, () -> claimsApi.registerClaim(new ClaimsApi.RegisterClaimRequest(policyNumber, null, claimant,
            ClaimType.DEATH, TODAY, new DeathClaimDetails("Malaria", "Dar es Salaam", TODAY, "Dr. Test", null),
            coveredLifeId, false), UUID.randomUUID().toString(), "claims-clerk"));
    }

    private void settle(ClaimView claim, String amount) {
        asTenant(TENANT, () -> claimsApi.submitAssessment(claim.claimId(), "Death certificate seen", null, null, false,
            "claims-assessor", null));
        asTenant(TENANT, () -> {
            claimsApi.decideSettlement(claim.claimId(), true, new BigDecimal(amount), "TZS", null, "+255700000777",
                UUID.randomUUID().toString(), "claims-manager");
            return null;
        });
        assertThat(asTenant(TENANT, () -> claimsApi.getClaim(claim.claimId())).status()).isEqualTo(ClaimStatus.SETTLED);
    }

    @Test
    void aChildsDeathPaysTheChildsBenefitToTheMainMemberAndTheSchemeCarriesOn() {
        String policyNumber = scheme(DependantClaimPayee.MAIN_MEMBER, MainMemberDeathRule.POLICY_ENDS);
        UUID neema = lifeNamed(policyNumber, "Neema Juma").coveredLifeId();
        UUID stranger = fixtures.person(TENANT, 30, Sex.FEMALE);

        assertThatThrownBy(() -> register(policyNumber, neema, stranger))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining("paid to the main member, Juma Ali, who is not yet a registered party");
        UUID juma = promote(policyNumber, "Juma Ali", "19860101-11111-00001-11");
        assertThatThrownBy(() -> register(policyNumber, neema, stranger))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining("is claimed by the main member, Juma Ali");

        ClaimView claim = register(policyNumber, neema, juma);
        assertThat(asTenant(TENANT, () -> claimsApi.claimableCover(claim.claimId())).amount())
            .as("plan A's child benefit").isEqualByComparingTo("500000");
        assertThatThrownBy(() -> register(policyNumber, neema, juma))
            .isInstanceOf(ClaimValidationException.class)
            .hasMessageContaining("A death claim already exists for this life");
        settle(claim, "500000.00");

        assertThat(lifeNamed(policyNumber, "Neema Juma").endReason()).isEqualTo("DECEASED");
        assertThat(family(policyNumber, "M001").familyCover()).isEqualByComparingTo("2000000");
        var policy = asTenant(TENANT, () -> policyApi.getPolicy(policyNumber));
        assertThat(policy.status().name()).isEqualTo("ACTIVE");
        assertThat(policy.premiumAmount()).as("the bill counts members, not lives").isEqualByComparingTo("6000");
        assertThat(policy.sumAssuredAmount()).isEqualByComparingTo("3000000");
    }

    @Test
    void aBeneficiaryPayeeVersionTakesTheClaimFromWhoeverTheAssessorAccepts() {
        String policyNumber = scheme(DependantClaimPayee.MAIN_MEMBER_BENEFICIARY, MainMemberDeathRule.POLICY_ENDS);
        UUID beneficiary = fixtures.person(TENANT, 35, Sex.FEMALE);
        ClaimView claim = register(policyNumber, lifeNamed(policyNumber, "Neema Juma").coveredLifeId(), beneficiary);
        assertThat(claim.claimantPartyId()).isEqualTo(beneficiary);
    }

    @Test
    void aMainMembersDeathTakesTheFamilyOffAtMonthEndAndTheBillFallsByOne() {
        String policyNumber = scheme(DependantClaimPayee.MAIN_MEMBER, MainMemberDeathRule.POLICY_ENDS);
        UUID beneficiary = fixtures.person(TENANT, 38, Sex.FEMALE);
        settle(register(policyNumber, lifeNamed(policyNumber, "Juma Ali").coveredLifeId(), beneficiary), "1000000.00");

        LocalDate monthEnd = TODAY.with(TemporalAdjusters.lastDayOfMonth());
        GroupFuneralFamilyView juma = family(policyNumber, "M001");
        assertThat(juma.status()).isEqualTo(MemberStatus.EXITED);
        assertThat(juma.leftOn()).isEqualTo(monthEnd);
        assertThat(juma.lives()).filteredOn(l -> !l.fullName().equals("Juma Ali"))
            .allSatisfy(l -> assertThat(l.coverEnd()).isEqualTo(monthEnd.plusDays(1)));
        assertThat(family(policyNumber, "M002").status()).isEqualTo(MemberStatus.ACTIVE);
        var policy = asTenant(TENANT, () -> policyApi.getPolicy(policyNumber));
        assertThat(policy.status().name()).isEqualTo("ACTIVE");
        assertThat(policy.premiumAmount()).isEqualByComparingTo("3000");
        assertThat(InstalmentDates.nextAfter(TODAY, "MONTHLY", monthEnd)).isAfter(monthEnd);
    }

    @Test
    void underSpouseTakesOverTheSpouseHeadsTheFamilyAndTheBillIsUnchanged() {
        String policyNumber = scheme(DependantClaimPayee.MAIN_MEMBER, MainMemberDeathRule.SPOUSE_TAKES_OVER);
        UUID beneficiary = fixtures.person(TENANT, 38, Sex.FEMALE);
        settle(register(policyNumber, lifeNamed(policyNumber, "Juma Ali").coveredLifeId(), beneficiary), "1000000.00");

        GroupFuneralFamilyView family = family(policyNumber, "M001");
        assertThat(family.status()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(family.mainMemberName()).isEqualTo("Asha Juma");
        assertThat(family.beneficiaryName()).as("Juma's nomination was his to make").isNull();
        assertThat(lifeNamed(policyNumber, "Asha Juma").role()).isEqualTo(FuneralRole.MAIN_MEMBER);
        assertThat(lifeNamed(policyNumber, "Neema Juma").status()).isEqualTo("ACTIVE");
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).premiumAmount()).isEqualByComparingTo("6000");
    }

    @Test
    void aNaturalDeathInsideTheLifesOwnWaitingPeriodCannotBeApproved() {
        String policyNumber = scheme(DependantClaimPayee.MAIN_MEMBER, MainMemberDeathRule.POLICY_ENDS);
        UUID rehema = family(policyNumber, "M002").policyMemberId();
        // A baby joins today: its own six months start now, whatever the family's.
        asTenant(TENANT, () -> policyApi.addGroupFuneralLife(policyNumber, rehema, new PolicyApi.GroupFuneralLifeInput(null,
            FuneralRole.CHILD, "Mtoto Said", TODAY.minusMonths(1), null, null, false, null, null, null), "staff"));
        UUID mother = promote(policyNumber, "Rehema Said", "19760101-11111-00002-11");
        ClaimView claim = register(policyNumber, lifeNamed(policyNumber, "Mtoto Said").coveredLifeId(), mother);
        asTenant(TENANT, () -> claimsApi.submitAssessment(claim.claimId(), "Seen", null, null, false, "claims-assessor", null));
        assertThatThrownBy(() -> asTenant(TENANT, () -> {
            claimsApi.decideSettlement(claim.claimId(), true, new BigDecimal("500000.00"), "TZS", null, "+255700000777",
                UUID.randomUUID().toString(), "claims-manager");
            return null;
        })).isInstanceOf(InvalidClaimStateException.class).hasMessageContaining("inside the waiting period");
    }
}
