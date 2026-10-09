package tz.co.nlolo.lifeplatform.funeral;

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
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.policy.api.CoveredLifeView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.policy.domain.InstalmentDates;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.family;

/** An accepted funeral case issues ONE policy carrying every covered life, billed at the family's instalment. */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({FuneralTestFixtures.class, AnnuityTestFixtures.class})
class FuneralIssueIntegrationTest {

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
    @Autowired private BillingApi billingApi;

    @Test
    void acceptanceIssuesOnePolicyWithAPremiumOfTheWholeFamily() {
        var product = fixtures.publishFamilia(TENANT);
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);
        String policyNumber = fixtures.issueFamily(TENANT, product, juma, family());

        PolicyView policy = asTenant(TENANT, () -> policyApi.getPolicy(policyNumber));
        // 138,000 a year x 1.05 / 12
        assertThat(policy.premiumAmount()).isEqualByComparingTo("12075.00");
        assertThat(policy.premiumFrequency()).isEqualTo("MONTHLY");
        assertThat(policy.sumAssuredAmount()).isEqualByComparingTo("2000000");
        assertThat(asTenant(TENANT, () -> policyApi.searchPolicies(juma, null, null, null, null,
            org.springframework.data.domain.PageRequest.of(0, 5))).getTotalElements()).isEqualTo(1);
    }

    @Test
    void everyLifeIsRecordedWithItsBenefitPremiumAndCoverStart() {
        var product = fixtures.publishFamilia(TENANT);
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);
        String policyNumber = fixtures.issueFamily(TENANT, product, juma, family());

        List<CoveredLifeView> lives = asTenant(TENANT, () -> policyApi.coveredLives(policyNumber));
        assertThat(lives).hasSize(5);
        CoveredLifeView main = lives.get(0);
        assertThat(main.role()).isEqualTo(FuneralRole.MAIN_MEMBER);
        assertThat(main.partyId()).isEqualTo(juma);
        assertThat(main.benefit()).isEqualByComparingTo("2000000");
        assertThat(main.yearlyPremium()).isEqualByComparingTo("60000");
        assertThat(main.pricedAtAge()).isEqualTo(40);
        assertThat(lives).extracting(CoveredLifeView::fullName).containsExactly(main.fullName(), "Asha", "Neema", "Baraka", "Zawadi");
        CoveredLifeView neema = lives.get(2);
        assertThat(neema.role()).isEqualTo(FuneralRole.CHILD);
        assertThat(neema.partyId()).isNull();
        assertThat(neema.benefit()).isEqualByComparingTo("1000000");
        assertThat(neema.yearlyPremium()).isEqualByComparingTo("6000");
        assertThat(neema.coverStart()).isEqualTo(TODAY);
        assertThat(neema.waitingPeriodEnds()).isEqualTo(TODAY.plusMonths(6));
        assertThat(lives).allMatch(l -> l.status().equals("ACTIVE") && l.coverEnd() == null);
    }

    @Test
    void billingRaisesTheFamilyInstalmentOnTheDatesInstalmentDatesNames() {
        var product = fixtures.publishFamilia(TENANT);
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);
        String policyNumber = fixtures.issueFamily(TENANT, product, juma, family());
        PolicyView policy = asTenant(TENANT, () -> policyApi.getPolicy(policyNumber));

        List<InvoiceView> invoices = asTenant(TENANT, () -> billingApi.listInvoices(policyNumber, null)).stream()
            .sorted(Comparator.comparing(InvoiceView::dueDate)).toList();
        assertThat(invoices).isNotEmpty();
        assertThat(invoices.get(0).amount()).isEqualByComparingTo("12075.00");
        // Premiums in advance (billing V11): the first instalment falls due the day cover starts.
        assertThat(invoices.get(0).dueDate()).isEqualTo(policy.issueDate());
        // Every later invoice lands where InstalmentDates says the next one does.
        for (int i = 1; i < invoices.size(); i++) {
            assertThat(invoices.get(i).dueDate())
                .isEqualTo(InstalmentDates.nextAfter(policy.issueDate(), "MONTHLY", invoices.get(i - 1).dueDate()));
        }
    }

    @Test
    void anOrdinaryPolicyHasNoCoveredLives() {
        var ordinary = annuityFixtures.publishOrdinary(TENANT);
        UUID someone = fixtures.person(TENANT, 40, Sex.MALE);
        String policyNumber = asTenant(TENANT, () -> policyApi.issuePolicy(UUID.randomUUID(), new PolicyApi.IssueRequest(someone,
            ordinary.productId(), ordinary.versionId(), new java.math.BigDecimal("1000000"), "TZS",
            new java.math.BigDecimal("1000"), "TZS", "MONTHLY", null, List.of(), "ordinary", null, null, null, null,
            tz.co.nlolo.lifeplatform.policy.api.IssuanceBasis.MIGRATION), "test-staff").policyNumber());

        assertThat(asTenant(TENANT, () -> policyApi.coveredLives(policyNumber))).isEmpty();
    }
}
