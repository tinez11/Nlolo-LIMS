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
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.policy.api.CoveredLifeView;
import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.policy.domain.InstalmentDates;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.underwriting.api.FuneralApplication;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.dependant;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.family;

/**
 * Adding and removing lives from the next premium date: the premium restated by the one arithmetic,
 * billing's untouched instalments restated in place, and the ledger's receivable moved by the difference.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({FuneralTestFixtures.class, AnnuityTestFixtures.class})
class FuneralEndorsementIntegrationTest {

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
    @Autowired private JdbcTemplate jdbc;

    private String familyInForce(List<FuneralApplication.Life> dependants) {
        var product = fixtures.publishFamilia(TENANT);
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);
        return fixtures.issueFamilyInForce(TENANT, product, juma, dependants, annuityFixtures);
    }

    private List<InvoiceView> invoices(String policyNumber) {
        return asTenant(TENANT, () -> billingApi.listInvoices(policyNumber, null)).stream()
            .sorted(java.util.Comparator.comparing(InvoiceView::dueDate)).toList();
    }

    /**
     * What issueFamilyInForce collected (DR cash, CR receivable). Billing's own invoices never see it -- the
     * fixture publishes the collection event without paying an invoice -- so the ledger's receivable is every
     * invoice billed less this one collection.
     */
    private static final BigDecimal FIRST_PREMIUM_COLLECTED = new BigDecimal("12075.00");

    /**
     * The ledger's premium receivable for one policy: debits less credits on 2142 LRC (PAA) premiums due -- funeral
     * cover is measured under PAA by the register's baseline, so since IFRS 17 I3a its premium posts to the PAA
     * accounts (2122 between I1 and I3a, 1210 before I1).
     */
    private BigDecimal receivable(String policyNumber) {
        BigDecimal balance = jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN direction = 'DR' THEN amount ELSE -amount END), 0)"
            + " FROM finaccounting.gl_posting WHERE tenant_id = ? AND policy_number = ? AND account_code = '2142'",
            BigDecimal.class, TENANT, policyNumber);
        return balance;
    }

    @Test
    void addingABabyCoversItFromTheNextPremiumDateAndRaisesThePremium() {
        String policyNumber = familyInForce(family());
        PolicyView before = asTenant(TENANT, () -> policyApi.getPolicy(policyNumber));
        java.time.LocalDate next = InstalmentDates.nextAfter(before.issueDate(), "MONTHLY", TODAY);

        CoveredLifeView baby = asTenant(TENANT, () -> policyApi.addCoveredLife(policyNumber,
            new FuneralApplication.Life(FuneralRole.CHILD, "Imani", TODAY.minusMonths(2), "FEMALE", null, false), "staff-one"));

        assertThat(baby.coverStart()).isEqualTo(next);
        assertThat(baby.waitingPeriodEnds()).isEqualTo(next.plusMonths(6));
        assertThat(baby.yearlyPremium()).isEqualByComparingTo("6000");
        assertThat(baby.benefit()).isEqualByComparingTo("1000000");
        // 138,000 + 6,000 = 144,000 a year; x 1.05 / 12
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).premiumAmount()).isEqualByComparingTo("12600.00");
        assertThat(asTenant(TENANT, () -> policyApi.coveredLives(policyNumber))).hasSize(6);

        List<InvoiceView> invoices = invoices(policyNumber);
        assertThat(invoices).allSatisfy(i -> assertThat(i.amount()).isEqualByComparingTo(
            i.dueDate().isBefore(next) ? "12075.00" : "12600.00"));
        // The ledger carries exactly what billing is owed: every invoice, at its restated amount.
        BigDecimal owed = invoices.stream().map(InvoiceView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(receivable(policyNumber)).isEqualByComparingTo(owed.subtract(FIRST_PREMIUM_COLLECTED));
    }

    @Test
    void aSecondRestatementOfTheSameInstalmentPostsAgain() {
        String policyNumber = familyInForce(family());
        asTenant(TENANT, () -> policyApi.addCoveredLife(policyNumber, dependant(FuneralRole.PARENT, "Bibi", 70), "staff-one"));
        asTenant(TENANT, () -> policyApi.addCoveredLife(policyNumber, dependant(FuneralRole.PARENT, "Babu", 72), "staff-one"));

        // 138,000 + 2 x 90,000 = 318,000; x 1.05 / 12
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).premiumAmount()).isEqualByComparingTo("27825.00");
        BigDecimal owed = invoices(policyNumber).stream().map(InvoiceView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(receivable(policyNumber)).isEqualByComparingTo(owed.subtract(FIRST_PREMIUM_COLLECTED));
    }

    @Test
    void aSeventhChildIsRefusedInTheProductsWords() {
        List<FuneralApplication.Life> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(dependant(FuneralRole.CHILD, "Mtoto " + i, 2 + i));
        }
        String policyNumber = familyInForce(six);

        assertThatThrownBy(() -> asTenant(TENANT, () -> policyApi.addCoveredLife(policyNumber,
                dependant(FuneralRole.CHILD, "Wa saba", 1), "staff-one")))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessage("At most 6 children may be covered");
    }

    @Test
    void removingTheSpouseKeepsCoverToThePaidPeriodAndLowersThePremium() {
        String policyNumber = familyInForce(family());
        PolicyView before = asTenant(TENANT, () -> policyApi.getPolicy(policyNumber));
        UUID asha = asTenant(TENANT, () -> policyApi.coveredLives(policyNumber)).stream()
            .filter(l -> l.role() == FuneralRole.SPOUSE).findFirst().orElseThrow().coveredLifeId();

        CoveredLifeView removed = asTenant(TENANT, () -> policyApi.removeCoveredLife(policyNumber, asha, "Divorce", "staff-one"));

        assertThat(removed.status()).isEqualTo("ACTIVE");
        assertThat(removed.coverEnd()).isEqualTo(InstalmentDates.nextAfter(before.issueDate(), "MONTHLY", TODAY));
        // 138,000 - 60,000 = 78,000; x 1.05 / 12
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).premiumAmount()).isEqualByComparingTo("6825.00");
        BigDecimal owed = invoices(policyNumber).stream().map(InvoiceView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(receivable(policyNumber)).isEqualByComparingTo(owed.subtract(FIRST_PREMIUM_COLLECTED));
        // A spouse already leaving cannot be removed twice.
        assertThatThrownBy(() -> asTenant(TENANT, () -> policyApi.removeCoveredLife(policyNumber, asha, null, "staff-one")))
            .isInstanceOf(InvalidPolicyStateException.class).hasMessage("Asha is already coming off cover");
    }

    @Test
    void theMainMemberCannotBeRemoved() {
        String policyNumber = familyInForce(family());
        UUID main = asTenant(TENANT, () -> policyApi.coveredLives(policyNumber)).get(0).coveredLifeId();

        assertThatThrownBy(() -> asTenant(TENANT, () -> policyApi.removeCoveredLife(policyNumber, main, null, "staff-one")))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessage("The main member cannot be removed; end the policy instead");
    }

    @Test
    void livesChangeOnlyWhileThePolicyIsInForce() {
        var product = fixtures.publishFamilia(TENANT);
        UUID juma = fixtures.person(TENANT, 40, Sex.MALE);
        String proposed = fixtures.issueFamily(TENANT, product, juma, family());

        assertThatThrownBy(() -> asTenant(TENANT, () -> policyApi.addCoveredLife(proposed,
                dependant(FuneralRole.CHILD, "Imani", 0), "staff-one")))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("lives are added and removed only while it is in force");
    }

    @Test
    void anOrdinaryPolicyRefusesCoveredLives() {
        var ordinary = annuityFixtures.publishOrdinary(TENANT);
        UUID someone = fixtures.person(TENANT, 40, Sex.MALE);
        String policyNumber = asTenant(TENANT, () -> policyApi.issuePolicy(UUID.randomUUID(), new PolicyApi.IssueRequest(someone,
            ordinary.productId(), ordinary.versionId(), new BigDecimal("1000000"), "TZS", new BigDecimal("1000"), "TZS",
            "MONTHLY", null, List.of(), "ordinary", null, null, null, null,
            tz.co.nlolo.lifeplatform.policy.api.IssuanceBasis.MIGRATION), "test-staff").policyNumber());

        assertThatThrownBy(() -> asTenant(TENANT, () -> policyApi.addCoveredLife(policyNumber,
                dependant(FuneralRole.CHILD, "Imani", 0), "staff-one")))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessage("Policy " + policyNumber + " is not a funeral plan, so it covers no family");
    }
}
