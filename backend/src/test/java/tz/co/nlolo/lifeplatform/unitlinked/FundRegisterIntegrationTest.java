package tz.co.nlolo.lifeplatform.unitlinked;

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
import tz.co.nlolo.lifeplatform.unitlinked.api.FundPriceView;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedApi;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedStateException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.ADMIN;
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.FINANCE;

/**
 * The register's rules on the real schema (spec §3): two people, never before the cut-off, never out of date
 * order, never edited or deleted -- not even by the owner the tests connect as -- and a large move needs a reason.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.class})
class FundRegisterIntegrationTest {

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
            UnitLinkedTestMigrations.ALL);
    }

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Autowired private UnitLinkedTestFixtures fixtures;
    @Autowired private UnitLinkedApi api;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aPriceProposedByOnePersonIsApprovedOnlyBySomeoneElse() {
        UUID tenant = UUID.randomUUID();
        fixtures.fund(tenant, "EQ1");
        FundPriceView proposed = asTenant(tenant, () ->
            api.proposePrice("EQ1", TODAY.minusDays(1), new BigDecimal("1.000000"), null, FINANCE));

        assertThatThrownBy(() -> asTenant(tenant, () -> api.approvePrice(proposed.priceId(), FINANCE)))
            .isInstanceOf(UnitLinkedStateException.class)
            .hasMessageContaining("a second person approves it");

        FundPriceView approved = asTenant(tenant, () -> api.approvePrice(proposed.priceId(), ADMIN));
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.approvedBy()).isEqualTo(ADMIN);
        assertThat(approved.price()).isEqualByComparingTo("1.000000");
    }

    @Test
    void aPriceCannotBeApprovedBeforeItsCutOff() {
        UUID tenant = UUID.randomUUID();
        fixtures.fund(tenant, "EQ1");
        // Tomorrow's cut-off has not passed whatever the time now: money for tomorrow may still arrive.
        FundPriceView proposed = asTenant(tenant, () ->
            api.proposePrice("EQ1", TODAY.plusDays(1), new BigDecimal("1.000000"), null, FINANCE));

        assertThatThrownBy(() -> asTenant(tenant, () -> api.approvePrice(proposed.priceId(), ADMIN)))
            .isInstanceOf(UnitLinkedStateException.class)
            .hasMessageContaining("cannot be approved before its 14:00 cut-off");
        assertThat(asTenant(tenant, () -> api.listPrices("EQ1", null, null)))
            .singleElement().extracting(FundPriceView::status).isEqualTo("PROPOSED");
    }

    @Test
    void onlyOneApprovedPricePerFundAndDate() {
        UUID tenant = UUID.randomUUID();
        fixtures.fund(tenant, "EQ1");
        fixtures.approvedPrice(tenant, "EQ1", TODAY.minusDays(1), "1.000000");

        assertThatThrownBy(() -> asTenant(tenant, () ->
                api.proposePrice("EQ1", TODAY.minusDays(1), new BigDecimal("1.010000"), null, FINANCE)))
            .isInstanceOf(UnitLinkedStateException.class)
            .hasMessageContaining("already has an approved price");
    }

    @Test
    void pricesAreApprovedInDateOrder() {
        UUID tenant = UUID.randomUUID();
        fixtures.fund(tenant, "EQ1");
        fixtures.approvedPrice(tenant, "EQ1", TODAY.minusDays(1), "1.000000");
        FundPriceView earlier = asTenant(tenant, () ->
            api.proposePrice("EQ1", TODAY.minusDays(2), new BigDecimal("1.000000"), null, FINANCE));

        assertThatThrownBy(() -> asTenant(tenant, () -> api.approvePrice(earlier.priceId(), ADMIN)))
            .isInstanceOf(UnitLinkedStateException.class)
            .hasMessageContaining("cannot be approved after the one for " + TODAY.minusDays(1));
    }

    @Test
    void anApprovedPriceCannotBeUpdatedOrDeletedEvenByTheOwner() {
        UUID tenant = UUID.randomUUID();
        fixtures.fund(tenant, "EQ1");
        FundPriceView approved = fixtures.approvedPrice(tenant, "EQ1", TODAY.minusDays(1), "1.000000");

        assertThatThrownBy(() -> jdbc.update("UPDATE unitlinked.fund_price SET price = 2 WHERE price_id = ?", approved.priceId()))
            .hasMessageContaining("An approved fund price never changes");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM unitlinked.fund_price WHERE price_id = ?", approved.priceId()))
            .hasMessageContaining("Fund prices are never deleted");
        assertThat(jdbc.queryForObject("SELECT price FROM unitlinked.fund_price WHERE price_id = ?", BigDecimal.class,
            approved.priceId())).isEqualByComparingTo("1.000000");
    }

    @Test
    void theApproverCanNeverBeTheProposerEvenWrittenByHand() {
        UUID tenant = UUID.randomUUID();
        fixtures.fund(tenant, "EQ1");
        FundPriceView proposed = asTenant(tenant, () ->
            api.proposePrice("EQ1", TODAY.minusDays(1), new BigDecimal("1.000000"), null, FINANCE));

        assertThatThrownBy(() -> jdbc.update("UPDATE unitlinked.fund_price SET status = 'APPROVED', approved_by = proposed_by,"
            + " approved_at = now() WHERE price_id = ?", proposed.priceId()))
            .hasMessageContaining("fund_price_check");
    }

    @Test
    void aBigMoveNeedsAReason() {
        UUID tenant = UUID.randomUUID();
        fixtures.fund(tenant, "EQ1");
        fixtures.approvedPrice(tenant, "EQ1", TODAY.minusDays(2), "1.000000");

        assertThatThrownBy(() -> asTenant(tenant, () ->
                api.proposePrice("EQ1", TODAY.minusDays(1), new BigDecimal("1.200000"), null, FINANCE)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("A move of 20.00% from 1 on " + TODAY.minusDays(2) + " needs a reason");

        FundPriceView withReason = asTenant(tenant, () ->
            api.proposePrice("EQ1", TODAY.minusDays(1), new BigDecimal("1.200000"), "Rally after the rate cut", FINANCE));
        assertThat(withReason.moveReason()).isEqualTo("Rally after the rate cut");
        // A move inside the alert needs none.
        FundPriceView small = asTenant(tenant, () -> {
            api.approvePrice(withReason.priceId(), ADMIN);
            return api.proposePrice("EQ1", TODAY, new BigDecimal("1.250000"), null, FINANCE);
        });
        assertThat(small.status()).isEqualTo("PROPOSED");
    }

    @Test
    void aCsvWithOneBadRowProposesNothing() {
        UUID tenant = UUID.randomUUID();
        fixtures.fund(tenant, "EQ1");
        fixtures.fund(tenant, "BD1");
        String csv = "fund_code,valuation_date,price\n"
            + "EQ1," + TODAY.minusDays(1) + ",1.000000\n"
            + "BD1," + TODAY.minusDays(1) + ",0\n";

        assertThatThrownBy(() -> asTenant(tenant, () -> api.proposePrices(csv, FINANCE)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Row 2");
        assertThat(asTenant(tenant, () -> api.listPrices("EQ1", null, null))).isEmpty();

        String good = "fund_code,valuation_date,price\n"
            + "EQ1," + TODAY.minusDays(1) + ",1.000000\n"
            + "BD1," + TODAY.minusDays(1) + ",10.500000\n";
        assertThat(asTenant(tenant, () -> api.proposePrices(good, FINANCE))).hasSize(2)
            .allSatisfy(p -> assertThat(p.status()).isEqualTo("PROPOSED"));
    }

    @Test
    void aClosedFundStillPricesItsUnits() {
        UUID tenant = UUID.randomUUID();
        fixtures.fund(tenant, "EQ1");
        asTenant(tenant, () -> api.closeFund("EQ1", ADMIN));

        FundPriceView approved = fixtures.approvedPrice(tenant, "EQ1", TODAY.minusDays(1), "1.000000");
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(asTenant(tenant, () -> api.getFund("EQ1")).status()).isEqualTo("CLOSED");
        assertThatThrownBy(() -> asTenant(tenant, () -> api.closeFund("EQ1", ADMIN)))
            .isInstanceOf(UnitLinkedStateException.class);
    }

    @Test
    void aFundCodeIsNeverReusedAndTenantsDoNotSeeEachOthersFunds() {
        UUID tenant = UUID.randomUUID();
        fixtures.fund(tenant, "EQ1");
        assertThatThrownBy(() -> fixtures.fund(tenant, "EQ1"))
            .isInstanceOf(UnitLinkedStateException.class)
            .hasMessageContaining("never reused");

        UUID other = UUID.randomUUID();
        assertThat(asTenant(other, () -> api.listFunds())).isEmpty();
        fixtures.fund(other, "EQ1"); // a different insurer's EQ1 is a different fund
        assertThat(asTenant(other, () -> api.listFunds())).hasSize(1);
    }
}
