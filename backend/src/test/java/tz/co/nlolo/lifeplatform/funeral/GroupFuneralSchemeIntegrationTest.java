package tz.co.nlolo.lifeplatform.funeral;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;
import tz.co.nlolo.lifeplatform.policy.api.BenefitBasis;
import tz.co.nlolo.lifeplatform.policy.api.CoveredLifeView;
import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.underwriting.api.GroupProposal;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.groupProposal;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.life;

/** Group funeral schemes (2026-10-07): an accepted proposal issues the scheme with its families on cover. */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({FuneralTestFixtures.class, AnnuityTestFixtures.class})
class GroupFuneralSchemeIntegrationTest {

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
    @Autowired private PolicyApi policyApi;
    @Autowired private BillingApi billingApi;
    @Autowired private JdbcTemplate jdbc;

    /** Juma (beneficiary Asha) with his wife and two children; Rehema alone. */
    private static List<GroupProposal.LifeLine> twoFamilies() {
        GroupProposal.LifeLine juma = life("M001", FuneralRole.MAIN_MEMBER, "Juma Ali", 40);
        juma = new GroupProposal.LifeLine(juma.memberReference(), juma.role(), juma.fullName(), juma.dateOfBirth(), "MALE",
            "19840101-00001-00001-11", false, "Asha Juma", "Spouse", "+255712000001");
        return List.of(juma, life("M001", FuneralRole.SPOUSE, "Asha Juma", 38),
            life("M001", FuneralRole.CHILD, "Neema Juma", 10), life("M001", FuneralRole.CHILD, "Baraka Juma", 7),
            life("M002", FuneralRole.MAIN_MEMBER, "Rehema Said", 50));
    }

    @Test
    void anAcceptedProposalIssuesTheSchemeWithItsFamilies() {
        var product = fixtures.publishGroupFamilia(TENANT);
        UUID association = fixtures.association(TENANT);
        String policyNumber = fixtures.issueGroupScheme(TENANT, product, association, groupProposal("A", twoFamilies()));

        var policy = asTenant(TENANT, () -> policyApi.getPolicy(policyNumber));
        assertThat(policy.status()).as("an offer until the association pays").isEqualTo(PolicyStatus.PROPOSED);
        assertThat(policy.premiumFrequency()).isEqualTo("MONTHLY");
        assertThat(policy.premiumAmount()).as("2 members x 3,000").isEqualByComparingTo("6000");
        // Plan A: main member and spouse 1,000,000 each, a child 500,000.
        assertThat(policy.sumAssuredAmount()).isEqualByComparingTo("4000000");

        var scheme = asTenant(TENANT, () -> policyApi.getGroupScheme(policyNumber));
        assertThat(scheme.benefitBasis()).isEqualTo(BenefitBasis.FUNERAL_PLAN);
        assertThat(scheme.activeMemberCount()).as("main members, what the bill counts").isEqualTo(2);
        assertThat(scheme.totalCoveredAmount()).isEqualByComparingTo("4000000");

        List<CoveredLifeView> lives = asTenant(TENANT, () -> policyApi.coveredLives(policyNumber));
        assertThat(lives).extracting(CoveredLifeView::fullName)
            .containsExactlyInAnyOrder("Juma Ali", "Asha Juma", "Neema Juma", "Baraka Juma", "Rehema Said");
        assertThat(lives).allSatisfy(l -> {
            assertThat(l.yearlyPremium()).isEqualByComparingTo("0");
            assertThat(l.coverStart()).isEqualTo(TODAY);
        });
        assertThat(lives).filteredOn(l -> l.role() == FuneralRole.CHILD).extracting(CoveredLifeView::benefit)
            .allSatisfy(b -> assertThat(b).isEqualByComparingTo("500000"));

        // Every life linked to its family's main member; the association's numbers and Juma's beneficiary kept.
        List<String> families = jdbc.queryForList("""
            SELECT g.association_reference || ':' || count(l.*) FROM policy.group_funeral_member g
            JOIN policy.covered_life l ON l.policy_member_id = g.policy_member_id
            WHERE g.policy_number = ? GROUP BY g.association_reference ORDER BY 1""", String.class, policyNumber);
        assertThat(families).containsExactly("M001:4", "M002:1");
        assertThat(jdbc.queryForObject("SELECT beneficiary_name FROM policy.group_funeral_member"
            + " WHERE policy_number = ? AND association_reference = 'M001'", String.class, policyNumber)).isEqualTo("Asha Juma");
        assertThat(jdbc.queryForObject("SELECT plan_code FROM policy.funeral_policy WHERE policy_number = ?",
            String.class, policyNumber)).isEqualTo("A");

        // Billed monthly at the scheme's premium, like any agreed scheme -- never per enrolment.
        List<InvoiceView> invoices = asTenant(TENANT, () -> billingApi.listInvoices(policyNumber, null));
        assertThat(invoices).isNotEmpty().allSatisfy(i -> assertThat(i.amount()).isEqualByComparingTo("6000"));
    }

    @Autowired private AnnuityTestFixtures annuityFixtures;
    @Autowired private tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi underwritingApi;
    @Autowired private tz.co.nlolo.lifeplatform.benefitpayout.api.BenefitPayoutApi benefitPayoutApi;

    @Test
    void aSchemeIsNeverCancelledInAnIndividualBuyersFreeLookWindow() {
        // Audit 2026-10-07: category FUNERAL passed the free-look category gate, so one click cancelled an
        // association's whole scheme from inception.
        var product = fixtures.publishGroupFamilia(TENANT);
        UUID association = fixtures.association(TENANT);
        String policyNumber = fixtures.issueGroupScheme(TENANT, product, association, groupProposal("A", twoFamilies()));
        annuityFixtures.collect(TENANT, policyNumber, "6000.00", TODAY);

        assertThatThrownBy(() -> asTenant(TENANT, () -> benefitPayoutApi.requestFreeLook(policyNumber, "+255700000009",
            List.of(), "csr-1")))
            .hasMessageContaining("scheme " + policyNumber + " is cancelled under the terms its policyholder agreed");
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    void anIssuedPolicyAlwaysRecordsWhenCoverStarts() {
        // Audit 2026-10-07: a case that stated no start date issued a policy with none, and a missing start
        // read as "covered on every earlier day". The scheme commences today; so does any policy without one.
        var product = fixtures.publishGroupFamilia(TENANT);
        UUID association = fixtures.association(TENANT);
        String policyNumber = fixtures.issueGroupScheme(TENANT, product, association, groupProposal("A", twoFamilies()));
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).commencementDate()).isEqualTo(TODAY);
        // An individual case that states NO start date -- the shape the console sends when the field is blank.
        var familia = fixtures.publishFamilia(TENANT);
        UUID mainMember = fixtures.person(TENANT, 40, tz.co.nlolo.lifeplatform.party.api.Sex.MALE);
        UUID caseId = asTenant(TENANT, () -> underwritingApi.openCase(mainMember, familia.productId(), familia.versionId(),
            new BigDecimal("2000000.00"), "TZS", null,
            new tz.co.nlolo.lifeplatform.underwriting.api.ProposalDetails(null, null, null, null, null, null, "MONTHLY",
                List.of()), "staff-opener").caseId());
        asTenant(TENANT, () -> underwritingApi.recordFuneralApplication(caseId, "B", List.of(), "staff-opener"));
        fixtures.decide(TENANT, caseId, tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome.ACCEPT, null);
        String individual = asTenant(TENANT, () -> policyApi.searchPolicies(mainMember, null, null, null, null,
            org.springframework.data.domain.PageRequest.of(0, 5)).getContent().get(0).policyNumber());
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(individual)).commencementDate()).isEqualTo(TODAY);
    }

    @Test
    void aSchemeIsIssuedOnlyOnAFuneralProductSoldToGroups() {
        var product = fixtures.publishFamilia(TENANT); // individual only
        UUID association = fixtures.association(TENANT);
        assertThatThrownBy(() -> asTenant(TENANT, () -> policyApi.issueGroupFuneralScheme(
            new PolicyApi.IssueGroupFuneralSchemeRequest(association, product.productId(), product.versionId(), null, "A",
                "TZS", TODAY, null, List.of(new PolicyApi.GroupFuneralLifeInput("M001", FuneralRole.MAIN_MEMBER,
                    "Juma Ali", TODAY.minusYears(40), null, null, false, null, null, null)), "test"),
            "staff", null, null)))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessage("This product is not sold to group schemes");
    }

    @Test
    void aBadFamilyRefusesTheWholeSchemeNamingTheMember() {
        var product = fixtures.publishGroupFamilia(TENANT);
        UUID association = fixtures.association(TENANT);
        assertThatThrownBy(() -> asTenant(TENANT, () -> policyApi.issueGroupFuneralScheme(
            new PolicyApi.IssueGroupFuneralSchemeRequest(association, product.productId(), product.versionId(), null, "A",
                "TZS", TODAY, null, List.of(
                    new PolicyApi.GroupFuneralLifeInput("M001", FuneralRole.MAIN_MEMBER, "Juma Ali", TODAY.minusYears(40),
                        null, null, false, null, null, null),
                    new PolicyApi.GroupFuneralLifeInput("M002", FuneralRole.CHILD, "Orphan", TODAY.minusYears(5),
                        null, null, false, null, null, null)), "test"),
            "staff", null, null)))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("Member M002: A family has exactly one main member, not 0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policy.policy WHERE policyholder_party_id = ?",
            Integer.class, association)).isZero();
    }

    @Test
    void anEmployerSchemeOnAFuneralProductIsStillRefused() {
        var product = fixtures.publishGroupFamilia(TENANT);
        UUID association = fixtures.association(TENANT);
        assertThatThrownBy(() -> asTenant(TENANT, () -> policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            association, product.productId(), product.versionId(), null, BenefitBasis.FLAT, new BigDecimal("1000000"),
            null, null, "TZS", List.of(), List.of(new PolicyApi.MemberInput(fixtures.person(TENANT, 40,
                tz.co.nlolo.lifeplatform.party.api.Sex.MALE), null, null, null)),
            new BigDecimal("3000"), "TZS", "MONTHLY", TODAY, null, "test", null), "staff")))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("GROUP_LIFE or CREDIT_LIFE");
    }
}
