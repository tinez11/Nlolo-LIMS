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
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.application.CoveredLifeSweep;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.underwriting.api.FuneralApplication;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.dependant;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.family;

/** The nightly sweep, run on chosen days: scheduled ends, ageing out, and the anniversary re-pricing. */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({FuneralTestFixtures.class, AnnuityTestFixtures.class})
class CoveredLifeSweepIntegrationTest {

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
    @Autowired private CoveredLifeSweep sweep;
    @Autowired private JdbcTemplate jdbc;

    /** A main member born {@code mainBorn}, with these dependants, in force from today. */
    private String inForce(LocalDate mainBorn, List<FuneralApplication.Life> dependants) {
        var product = fixtures.publishFamilia(TENANT);
        UUID main = fixtures.person(TENANT, 40, Sex.MALE);
        if (mainBorn != null) {
            annuityFixtures.amendDateOfBirth(TENANT, main, mainBorn);
        }
        return fixtures.issueFamilyInForce(TENANT, product, main, dependants, annuityFixtures);
    }

    private CoveredLifeView life(String policyNumber, String name) {
        return asTenant(TENANT, () -> policyApi.coveredLives(policyNumber)).stream()
            .filter(l -> l.fullName().equals(name)).findFirst().orElseThrow();
    }

    private java.math.BigDecimal premium(String policyNumber) {
        return asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).premiumAmount();
    }

    private int schedules(String policyNumber) {
        return jdbc.queryForObject("SELECT count(*) FROM billing.billing_schedule WHERE tenant_id = ? AND policy_number = ?",
            Integer.class, TENANT, policyNumber);
    }

    private static FuneralApplication.Life born(FuneralRole role, String name, LocalDate dateOfBirth, boolean student) {
        return new FuneralApplication.Life(role, name, dateOfBirth, null, null, student);
    }

    @Test
    void aChildEndsOnTheirTwentyFirstBirthdayAndThePremiumFalls() {
        LocalDate neemaBorn = TODAY.minusYears(20).plusDays(40);
        String policyNumber = inForce(null, List.of(dependant(FuneralRole.SPOUSE, "Asha", 38),
            born(FuneralRole.CHILD, "Neema", neemaBorn, false)));
        LocalDate birthday = neemaBorn.plusYears(21);

        sweep.sweepOne(policyNumber, TENANT, birthday.minusDays(1));
        assertThat(life(policyNumber, "Neema").status()).isEqualTo("ACTIVE");

        sweep.sweepOne(policyNumber, TENANT, birthday);
        CoveredLifeView neema = life(policyNumber, "Neema");
        assertThat(neema.status()).isEqualTo("ENDED");
        assertThat(neema.endReason()).isEqualTo("AGED_OUT");
        assertThat(neema.endedOn()).isEqualTo(birthday);
        // 60,000 + 60,000 a year; x 1.05 / 12
        assertThat(premium(policyNumber)).isEqualByComparingTo("10500.00");
    }

    @Test
    void aStudentChildIsCoveredToTwentyFive() {
        LocalDate neemaBorn = TODAY.minusYears(20).plusDays(40);
        String policyNumber = inForce(null, List.of(born(FuneralRole.CHILD, "Neema", neemaBorn, true)));

        sweep.sweepOne(policyNumber, TENANT, neemaBorn.plusYears(21));
        assertThat(life(policyNumber, "Neema").status()).isEqualTo("ACTIVE");

        sweep.sweepOne(policyNumber, TENANT, neemaBorn.plusYears(25));
        assertThat(life(policyNumber, "Neema").endReason()).isEqualTo("AGED_OUT");
        assertThat(life(policyNumber, "Neema").endedOn()).isEqualTo(neemaBorn.plusYears(25));
    }

    @Test
    void aRemovedSpouseEndsOnTheScheduledDateWithNoSecondRestatement() {
        String policyNumber = inForce(null, family());
        UUID asha = life(policyNumber, "Asha").coveredLifeId();
        CoveredLifeView removing = asTenant(TENANT, () -> policyApi.removeCoveredLife(policyNumber, asha, null, "staff-one"));
        int schedulesAfterRemoval = schedules(policyNumber);

        sweep.sweepOne(policyNumber, TENANT, removing.coverEnd().minusDays(1));
        assertThat(life(policyNumber, "Asha").status()).isEqualTo("ACTIVE");

        sweep.sweepOne(policyNumber, TENANT, removing.coverEnd());
        CoveredLifeView asha2 = life(policyNumber, "Asha");
        assertThat(asha2.status()).isEqualTo("ENDED");
        assertThat(asha2.endReason()).isEqualTo("REMOVED");
        assertThat(asha2.endedOn()).isEqualTo(removing.coverEnd());
        assertThat(asha2.coverEnd()).isNull();
        // The premium already fell when the removal was scheduled.
        assertThat(schedules(policyNumber)).isEqualTo(schedulesAfterRemoval);
    }

    @Test
    void theAnniversaryRepricesEveryLifeAtItsNewAgeBandButKeepsTheBenefits() {
        // The main member is 35 at issue and 36 at the first anniversary: band 18-35 -> 36-50.
        String policyNumber = inForce(TODAY.minusYears(36).plusDays(20), family());
        // 36,000 + 60,000 + 3 x 6,000 = 114,000; x 1.05 / 12
        assertThat(premium(policyNumber)).isEqualByComparingTo("9975.00");
        LocalDate anniversary = asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).issueDate().plusYears(1);

        sweep.sweepOne(policyNumber, TENANT, anniversary);

        CoveredLifeView main = asTenant(TENANT, () -> policyApi.coveredLives(policyNumber)).get(0);
        assertThat(main.pricedAtAge()).isEqualTo(36);
        assertThat(main.yearlyPremium()).isEqualByComparingTo("60000");
        assertThat(main.benefit()).isEqualByComparingTo("2000000");
        // 60,000 + 60,000 + 18,000 = 138,000
        assertThat(premium(policyNumber)).isEqualByComparingTo("12075.00");
        // The instalment due ON the anniversary is the first at the new rate; the one before is not.
        List<InvoiceView> invoices = asTenant(TENANT, () -> billingApi.listInvoices(policyNumber, null));
        // hasSize first: an allSatisfy over no invoices would pass while proving nothing.
        assertThat(invoices).filteredOn(i -> i.dueDate().equals(anniversary)).hasSize(1)
            .allSatisfy(i -> assertThat(i.amount()).isEqualByComparingTo("12075.00"));
        assertThat(invoices).filteredOn(i -> i.dueDate().isBefore(anniversary)).hasSize(11)
            .allSatisfy(i -> assertThat(i.amount()).isEqualByComparingTo("9975.00"));
    }

    @Test
    void aMidYearBirthdayDoesNotMoveABand() {
        LocalDate mainBorn = TODAY.minusYears(36).plusDays(90);
        String policyNumber = inForce(mainBorn, family());
        java.math.BigDecimal before = premium(policyNumber);

        sweep.sweepOne(policyNumber, TENANT, mainBorn.plusYears(36));

        assertThat(premium(policyNumber)).isEqualByComparingTo(before);
        assertThat(asTenant(TENANT, () -> policyApi.coveredLives(policyNumber)).get(0).pricedAtAge()).isEqualTo(35);
    }

    @Test
    void ageingOutAndTheAnniversaryOnOneDayRestateOnce() {
        String policyNumber = inForce(null, List.of(born(FuneralRole.CHILD, "Neema", TODAY.minusYears(20), false)));
        LocalDate anniversary = asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).issueDate().plusYears(1);
        int before = schedules(policyNumber);

        sweep.sweepOne(policyNumber, TENANT, anniversary);

        assertThat(life(policyNumber, "Neema").endReason()).isEqualTo("AGED_OUT");
        assertThat(schedules(policyNumber)).isEqualTo(before + 1);
    }
}
