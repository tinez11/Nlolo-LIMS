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
import tz.co.nlolo.lifeplatform.policy.api.CoveredLifeView;
import tz.co.nlolo.lifeplatform.policy.api.ExitReason;
import tz.co.nlolo.lifeplatform.policy.api.GroupFuneralFamilyView;
import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;
import tz.co.nlolo.lifeplatform.policy.api.MemberStatus;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.domain.InstalmentDates;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.underwriting.api.GroupProposal;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.TODAY;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.groupProposal;
import static tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.life;

/** Group funeral schemes (2026-10-07): families join, grow, shrink and leave; the monthly bill follows the count. */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({FuneralTestFixtures.class, AnnuityTestFixtures.class})
class GroupFuneralMembersIntegrationTest {

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
    /** The first billing date after today, on a scheme issued today. */
    private static final LocalDate NEXT_BILL = InstalmentDates.nextAfter(TODAY, "MONTHLY", TODAY);

    @Autowired private FuneralTestFixtures fixtures;
    @Autowired private AnnuityTestFixtures annuityFixtures;
    @Autowired private PolicyApi policyApi;
    @Autowired private BillingApi billingApi;

    /** Juma with his wife and a child; Rehema alone -- on plan A at 3,000 a member, paid, so in force. */
    private String inForceScheme() {
        var product = fixtures.publishGroupFamilia(TENANT);
        UUID association = fixtures.association(TENANT);
        String policyNumber = fixtures.issueGroupScheme(TENANT, product, association, groupProposal("A", List.of(
            life("M001", FuneralRole.MAIN_MEMBER, "Juma Ali", 40), life("M001", FuneralRole.SPOUSE, "Asha Juma", 38),
            life("M001", FuneralRole.CHILD, "Neema Juma", 10), life("M002", FuneralRole.MAIN_MEMBER, "Rehema Said", 50))));
        annuityFixtures.collect(TENANT, policyNumber, "6000.00", TODAY);
        return policyNumber;
    }

    private static PolicyApi.GroupFuneralLifeInput joiner(String reference, FuneralRole role, String name, LocalDate born) {
        return new PolicyApi.GroupFuneralLifeInput(reference, role, name, born, null, null, false, null, null, null);
    }

    private List<InvoiceView> billsFrom(String policyNumber, LocalDate from) {
        return asTenant(TENANT, () -> billingApi.listInvoices(policyNumber, null)).stream()
            .filter(i -> !i.dueDate().isBefore(from)).toList();
    }

    private GroupFuneralFamilyView family(String policyNumber, String reference) {
        return asTenant(TENANT, () -> policyApi.groupFuneralFamilies(policyNumber)).stream()
            .filter(f -> reference.equals(f.memberReference())).findFirst().orElseThrow();
    }

    @Test
    void aJoiningFamilyRaisesTheNextBillByOneRate() {
        String policyNumber = inForceScheme();
        assertThat(snapshotLives(policyNumber)).as("the expense allocation counts the scheme's lives").isEqualTo(4);
        asTenant(TENANT, () -> policyApi.addGroupFuneralFamily(policyNumber, List.of(
            joiner("M003", FuneralRole.MAIN_MEMBER, "Hamisi Juma", TODAY.minusYears(35)),
            joiner("M003", FuneralRole.CHILD, "Mtoto Hamisi", TODAY.minusYears(2))), null, "staff"));

        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).premiumAmount()).isEqualByComparingTo("9000");
        assertThat(billsFrom(policyNumber, NEXT_BILL)).isNotEmpty()
            .allSatisfy(i -> assertThat(i.amount()).as("3 members x 3,000").isEqualByComparingTo("9000"));
        assertThat(asTenant(TENANT, () -> policyApi.getGroupScheme(policyNumber)).totalCoveredAmount())
            .as("Juma's family 2,500,000 + Rehema 1,000,000 + Hamisi 1,000,000 and his child 500,000")
            .isEqualByComparingTo("5000000");
        assertThat(family(policyNumber, "M003").lives()).extracting(CoveredLifeView::fullName)
            .containsExactly("Hamisi Juma", "Mtoto Hamisi");
        assertThat(snapshotLives(policyNumber)).isEqualTo(6);
    }

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private int snapshotLives(String policyNumber) {
        return jdbc.queryForObject("SELECT lives FROM finaccounting.policy_snapshot WHERE tenant_id = ? AND policy_number = ?",
            Integer.class, TENANT, policyNumber);
    }

    @Test
    void aMemberWhoLeavesIsCoveredToMonthEndAndTheBillFallsAfter() {
        String policyNumber = inForceScheme();
        GroupFuneralFamilyView juma = family(policyNumber, "M001");
        asTenant(TENANT, () -> policyApi.exitMember(policyNumber, juma.policyMemberId(), TODAY, ExitReason.CANCELLED,
            null, "staff"));

        LocalDate monthEnd = TODAY.with(TemporalAdjusters.lastDayOfMonth());
        GroupFuneralFamilyView left = family(policyNumber, "M001");
        assertThat(left.status()).isEqualTo(MemberStatus.EXITED);
        assertThat(left.leftOn()).isEqualTo(monthEnd);
        assertThat(left.lives()).allSatisfy(l -> assertThat(l.coverEnd()).isEqualTo(monthEnd.plusDays(1)));

        LocalDate firstBillAfter = InstalmentDates.nextAfter(TODAY, "MONTHLY", monthEnd);
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).premiumAmount()).isEqualByComparingTo("3000");
        assertThat(billsFrom(policyNumber, firstBillAfter)).isNotEmpty()
            .allSatisfy(i -> assertThat(i.amount()).isEqualByComparingTo("3000"));
    }

    @Test
    void aLifeJoinsAFamilyByThePlansRulesAndTheBillIsUnchanged() {
        String policyNumber = inForceScheme();
        UUID juma = family(policyNumber, "M001").policyMemberId();
        assertThatThrownBy(() -> asTenant(TENANT, () -> policyApi.addGroupFuneralLife(policyNumber, juma,
            joiner(null, FuneralRole.SPOUSE, "Mwanaisha", TODAY.minusYears(30)), "staff")))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("At most 1 spouse may be covered, not 2");

        UUID rehema = family(policyNumber, "M002").policyMemberId();
        asTenant(TENANT, () -> policyApi.addGroupFuneralLife(policyNumber, rehema,
            joiner(null, FuneralRole.CHILD, "Zuri Said", TODAY.minusYears(12)), "staff"));
        assertThat(family(policyNumber, "M002").familyCover()).isEqualByComparingTo("1500000");
        assertThat(asTenant(TENANT, () -> policyApi.getGroupScheme(policyNumber)).totalCoveredAmount())
            .as("3,500,000 and Zuri's 500,000").isEqualByComparingTo("4000000");
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).premiumAmount())
            .as("a family's lives are free; the bill counts members").isEqualByComparingTo("6000");

        CoveredLifeView zuri = family(policyNumber, "M002").lives().stream()
            .filter(l -> l.fullName().equals("Zuri Said")).findFirst().orElseThrow();
        CoveredLifeView removed = asTenant(TENANT, () -> policyApi.removeGroupFuneralLife(policyNumber,
            zuri.coveredLifeId(), "moved away", "staff"));
        assertThat(removed.coverEnd()).isEqualTo(TODAY.with(TemporalAdjusters.firstDayOfNextMonth()));
        assertThatThrownBy(() -> asTenant(TENANT, () -> policyApi.removeGroupFuneralLife(policyNumber,
            family(policyNumber, "M002").lives().get(0).coveredLifeId(), null, "staff")))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("The main member cannot be removed");
    }

    @Test
    void aJoiningFileTakesEveryGoodFamilyWholeAndNamesTheRest() {
        String policyNumber = inForceScheme();
        String file = String.join(",", tz.co.nlolo.lifeplatform.underwriting.api.FuneralScheduleFile.HEADER) + "\n"
            + "M010,MAIN_MEMBER,Hamisi Ali," + TODAY.minusYears(35) + ",,,,,,\n"
            + "M010,CHILD,Mtoto Ali," + TODAY.minusYears(3) + ",,,,,,\n"
            + "M011,MAIN_MEMBER,Zawadi Omar," + TODAY.minusYears(29) + ",,,,,,\n"
            + "M012,MAIN_MEMBER,Bakari Musa," + TODAY.minusYears(44) + ",,,,,,\n"
            + "M012,CHILD,Old Child," + TODAY.minusYears(30) + ",,,,,,\n"
            + "M013,MAIN_MEMBER,Saida Juma," + TODAY.minusYears(50) + ",,,,,,\n"
            + "M013,CHILD,Tatu Juma,not-a-date,,,,,,\n"
            + "M001,MAIN_MEMBER,Juma Again," + TODAY.minusYears(40) + ",,,,,,\n";

        var report = asTenant(TENANT, () -> policyApi.joinGroupFuneralFamilies(policyNumber,
            file.getBytes(java.nio.charset.StandardCharsets.UTF_8), null, "staff"));

        assertThat(report.joined()).extracting(j -> j.memberReference()).containsExactly("M010", "M011");
        assertThat(report.refused()).extracting(r -> r.memberReference()).containsExactlyInAnyOrder("M012", "M013", "M001");
        assertThat(report.refused()).filteredOn(r -> r.memberReference().equals("M012")).singleElement()
            .satisfies(r -> assertThat(r.problems()).anyMatch(p -> p.startsWith("Old Child: a child must be 0 to")));
        assertThat(report.refused()).filteredOn(r -> r.memberReference().equals("M013")).singleElement()
            .satisfies(r -> assertThat(r.problems()).anyMatch(p -> p.startsWith("Row 8: date_of_birth")));
        assertThat(report.refused()).filteredOn(r -> r.memberReference().equals("M001")).singleElement()
            .satisfies(r -> assertThat(r.problems()).contains("Member M001 is already on scheme " + policyNumber));
        assertThat(asTenant(TENANT, () -> policyApi.groupFuneralFamilies(policyNumber)))
            .extracting(GroupFuneralFamilyView::memberReference).containsExactly("M001", "M002", "M010", "M011");
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).premiumAmount())
            .as("4 members x 3,000, restated once for the file").isEqualByComparingTo("12000");
    }

    @Test
    void aChildAgeingOutEndsThatLifeAndTheFamilysCoverFalls() {
        String policyNumber = inForceScheme();
        UUID rehema = family(policyNumber, "M002").policyMemberId();
        // 20 today, 21 -- the plan's stop age for a child -- tomorrow.
        asTenant(TENANT, () -> policyApi.addGroupFuneralLife(policyNumber, rehema,
            joiner(null, FuneralRole.CHILD, "Baraka Said", TODAY.minusYears(21).plusDays(1)), "staff"));
        assertThat(asTenant(TENANT, () -> policyApi.getGroupScheme(policyNumber)).totalCoveredAmount())
            .isEqualByComparingTo("4000000");

        asTenant(TENANT, () -> {
            policyApi.sweepFuneralPolicy(policyNumber, TODAY.plusDays(1));
            return null;
        });
        CoveredLifeView baraka = family(policyNumber, "M002").lives().stream()
            .filter(l -> l.fullName().equals("Baraka Said")).findFirst().orElseThrow();
        assertThat(baraka.status()).isEqualTo("ENDED");
        assertThat(baraka.endReason()).isEqualTo("AGED_OUT");
        assertThat(family(policyNumber, "M001").lives()).allSatisfy(l -> assertThat(l.status()).isEqualTo("ACTIVE"));
        // Restated as at the sweep's day (tomorrow), so read off the policy's stored sum assured.
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).sumAssuredAmount())
            .as("Baraka's 500,000 off Rehema's family").isEqualByComparingTo("3500000");
        assertThat(asTenant(TENANT, () -> policyApi.getPolicy(policyNumber)).premiumAmount()).isEqualByComparingTo("6000");
    }
}
