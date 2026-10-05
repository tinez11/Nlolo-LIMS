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
import tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures;
import tz.co.nlolo.lifeplatform.unitlinked.api.FundPriceView;
import tz.co.nlolo.lifeplatform.unitlinked.api.PolicyUnitsView;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedApi;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedStateException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.ADMIN;
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.FINANCE;
import static tz.co.nlolo.lifeplatform.unitlinked.UnitLinkedTestFixtures.eat;

/**
 * Two-person price corrections (spec §3): the wrong price is superseded, every movement priced at it is reversed and
 * entered again at the right one -- new entries, nothing overwritten -- and the carried liability is trued up to the
 * fund's latest price. A correction that would leave a policy short of units is refused whole.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, FuneralTestFixtures.class})
class PriceCorrectionIntegrationTest {

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

    private static final LocalDate D = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam")).minusDays(10);

    @Autowired private UnitLinkedTestFixtures fixtures;
    @Autowired private UnitLinkedApi api;
    @Autowired private JdbcTemplate jdbc;

    private record Bought(UUID tenant, String policyNumber, FundPriceView eqPrice) {}

    /** A 60/40 policy whose 100,000 premium was bought on D, EQ1 at a wrong 1.500000 (BD1 at 1.000000). */
    private Bought boughtAtAWrongPrice() {
        UUID tenant = UUID.randomUUID();
        var product = fixtures.publishStandard(tenant);
        String policy = fixtures.sell(tenant, product, fixtures.person(tenant, 35), UnitLinkedTestFixtures.standardChoice());
        fixtures.collectAt(tenant, policy, "100000.00", eat(D, 9, 0), UUID.randomUUID());
        FundPriceView eq = fixtures.approvedPrice(tenant, "EQ1", D, "1.500000");
        fixtures.approvedPrice(tenant, "BD1", D, "1.000000");
        return new Bought(tenant, policy, eq);
    }

    private BigDecimal eqUnits(Bought b) {
        return asTenant(b.tenant(), () -> api.units(b.policyNumber())).holdings().stream()
            .filter(h -> h.fundCode().equals("EQ1")).findFirst().orElseThrow().units();
    }

    private FundPriceView correct(Bought b, String price, String approver) {
        return asTenant(b.tenant(), () -> {
            FundPriceView proposed = api.proposeCorrection(b.eqPrice().priceId(), new BigDecimal(price), "Typo in the feed", FINANCE);
            return api.approvePrice(proposed.priceId(), approver);
        });
    }

    @Test
    void aCorrectionReversesAndReEntersEveryMovementAtTheRightPrice() {
        Bought b = boughtAtAWrongPrice();
        assertThat(eqUnits(b)).isEqualByComparingTo("36000.000000"); // 54,000 at the wrong 1.50

        correct(b, "1.000000", ADMIN);

        assertThat(eqUnits(b)).isEqualByComparingTo("54000.000000"); // 54,000 at the right 1.00
        PolicyUnitsView units = asTenant(b.tenant(), () -> api.units(b.policyNumber()));
        // The original stays, reversed by its own entry; the movement is entered again -- nothing overwritten.
        assertThat(units.entries()).filteredOn(e -> e.fundCode() != null && e.fundCode().equals("EQ1"))
            .extracting(PolicyUnitsView.Entry::type)
            .containsExactlyInAnyOrder("ALLOCATION", "PRICE_CORRECTION", "ALLOCATION");
        assertThat(jdbc.queryForObject("SELECT status FROM unitlinked.fund_price WHERE price_id = ?", String.class,
            b.eqPrice().priceId())).isEqualTo("SUPERSEDED");
        assertThat(asTenant(b.tenant(), () -> api.listPrices("EQ1", D, D))).extracting(FundPriceView::status)
            .containsExactlyInAnyOrder("SUPERSEDED", "APPROVED");
    }

    @Test
    void afterACorrectionTheCarriedLiabilityIsUnitsTimesTheLatestPrice() {
        Bought b = boughtAtAWrongPrice();
        fixtures.approvedPrice(b.tenant(), "EQ1", D.plusDays(1), "1.100000"); // a later price already in force

        correct(b, "1.000000", ADMIN);

        BigDecimal carried = jdbc.queryForObject("SELECT l.carried FROM unitlinked.fund_liability l JOIN unitlinked.fund f"
            + " ON f.fund_id = l.fund_id WHERE f.tenant_id = ? AND f.code = 'EQ1'", BigDecimal.class, b.tenant());
        assertThat(carried).isEqualByComparingTo(new BigDecimal("54000.000000").multiply(new BigDecimal("1.100000"))
            .setScale(2, RoundingMode.HALF_EVEN));
    }

    @Test
    void theCorrectionsProposerCannotApproveIt() {
        Bought b = boughtAtAWrongPrice();
        assertThatThrownBy(() -> correct(b, "1.000000", FINANCE))
            .isInstanceOf(UnitLinkedStateException.class)
            .hasMessageContaining("a second person approves it");
        assertThat(eqUnits(b)).isEqualByComparingTo("36000.000000");
    }

    @Test
    void aCorrectionNeedsAReasonAndADifferentPrice() {
        Bought b = boughtAtAWrongPrice();
        assertThatThrownBy(() -> asTenant(b.tenant(), () ->
                api.proposeCorrection(b.eqPrice().priceId(), new BigDecimal("1.000000"), " ", FINANCE)))
            .hasMessage("A price correction needs a reason");
        assertThatThrownBy(() -> asTenant(b.tenant(), () ->
                api.proposeCorrection(b.eqPrice().priceId(), new BigDecimal("1.500000"), "Same", FINANCE)))
            .hasMessage("The corrected price is the same as the approved one");
    }

    @Test
    void aCorrectionThatWouldLeaveAPolicyShortIsRefusedWhole() {
        Bought b = boughtAtAWrongPrice();
        // A later sale of 30,000 of the 36,000 units, written as the ledger would.
        FundPriceView later = fixtures.approvedPrice(b.tenant(), "EQ1", D.plusDays(1), "1.500000");
        jdbc.update("INSERT INTO unitlinked.unit_entry (tenant_id, policy_number, fund_id, entry_type, units, price, price_id,"
            + " amount, valuation_date, source_type, source_ref, created_by) VALUES (?, ?, ?, 'POLICY_FEE', -30000, 1.5, ?,"
            + " -45000, ?, 'charge', 'test-sale', 'test')", b.tenant(), b.policyNumber(), later.fundId(), later.priceId(), D.plusDays(1));

        // Corrected UP to 3.00, the premium would have bought only 18,000 units -- fewer than were later sold.
        assertThatThrownBy(() -> correct(b, "3.000000", ADMIN))
            .isInstanceOf(UnitLinkedStateException.class)
            .hasMessageContaining("short of")
            .hasMessageContaining("nothing was changed");
        assertThat(jdbc.queryForObject("SELECT status FROM unitlinked.fund_price WHERE price_id = ?", String.class,
            b.eqPrice().priceId())).isEqualTo("APPROVED");
    }

    // A corrected sale whose payout was already PAID: UnitLinkedExitsIntegrationTest, which has the payment rail and
    // the clock a surrender needs.
}
