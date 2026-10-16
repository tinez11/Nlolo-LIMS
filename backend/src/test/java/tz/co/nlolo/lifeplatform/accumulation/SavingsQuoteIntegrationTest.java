package tz.co.nlolo.lifeplatform.accumulation;

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
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationApi;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationStateException;
import tz.co.nlolo.lifeplatform.accumulation.api.SavingsQuoteView;
import tz.co.nlolo.lifeplatform.product.api.AccumulationPlan;
import tz.co.nlolo.lifeplatform.product.api.ValueBasis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Quoting a savings product BEFORE any policy exists (2026-10-16).
 *
 * <p>The console's quotation panel could only ask for a premium off a base rate table, so a deposit and a savings
 * account -- which have neither a sum assured nor a premium rate -- answered every quote with a red 422 saying the
 * version "carries no base rate table, so it cannot be priced", on products working exactly as designed. These tests
 * hold the quote to the SAME figures the running policy earns: the rate from the version's own grid, read for the
 * term and not for a year.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AccumulationTestFixtures.class)
class SavingsQuoteIntegrationTest {

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
            DepositTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private AccumulationApi api;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    /**
     * The user's grid pays 5% over twelve months on the 500,000 band, and that 5% is the WHOLE term's -- so five
     * million earns 250,000, not 250,000 a year. A quote that annualised it would promise the wrong number.
     */
    @Test
    void pricesADepositFromTheVersionsOwnGrid() {
        var on = fixtures.issueDeposit(TENANT, new BigDecimal("5000000.00"), 12, TODAY);

        SavingsQuoteView quote = asTenant(() -> api.quoteProductSavings(on.productId(), on.productVersionId(),
            new BigDecimal("5000000.00"), 12, TODAY));

        assertThat(quote.kind()).isEqualTo(SavingsQuoteView.Kind.DEPOSIT);
        assertThat(quote.ratePercent()).isEqualByComparingTo("5");
        assertThat(quote.interest()).isEqualByComparingTo("250000.00");
        assertThat(quote.maturityValue()).isEqualByComparingTo("5250000.00");
        assertThat(quote.maturityDate()).isEqualTo(TODAY.plusMonths(12));
        assertThat(quote.offeredTermMonths()).containsExactly(3, 6, 12);
        assertThat(quote.smallestDeposit()).isEqualByComparingTo("500000");
        assertThat(quote.refusal()).isNull();
    }

    /** The band is read from the AMOUNT, so a larger deposit on the same product earns a different rate. */
    @Test
    void readsTheRateFromTheBandTheAmountFallsIn() {
        var on = fixtures.issueDeposit(TENANT, new BigDecimal("5000000.00"), 12, TODAY);

        SavingsQuoteView quote = asTenant(() -> api.quoteProductSavings(on.productId(), on.productVersionId(),
            new BigDecimal("12000000.00"), 12, TODAY));

        // The 10,000,000.01 band: 7% over twelve months, not the lower band's 6%.
        assertThat(quote.ratePercent()).isEqualByComparingTo("7");
        assertThat(quote.interest()).isEqualByComparingTo("840000.00");
    }

    /**
     * A term the grid does not sell is an ANSWER, not an error: the useful reply to "nine months" is "this one runs
     * 3, 6 or 12". Throwing here would put a red panel on a screen whose next click fixes it.
     */
    @Test
    void namesTheTermsOfferedRatherThanRefusingOutright() {
        var on = fixtures.issueDeposit(TENANT, new BigDecimal("5000000.00"), 12, TODAY);

        SavingsQuoteView quote = asTenant(() -> api.quoteProductSavings(on.productId(), on.productVersionId(),
            new BigDecimal("5000000.00"), 9, TODAY));

        assertThat(quote.ratePercent()).isNull();
        assertThat(quote.maturityValue()).isNull();
        assertThat(quote.offeredTermMonths()).containsExactly(3, 6, 12);
        assertThat(quote.refusal()).contains("9 months");
    }

    /** Below the smallest band there is no rate at all, and the quote says what the least this product takes is. */
    @Test
    void saysTheSmallestDepositItTakes() {
        var on = fixtures.issueDeposit(TENANT, new BigDecimal("5000000.00"), 12, TODAY);

        SavingsQuoteView quote = asTenant(() -> api.quoteProductSavings(on.productId(), on.productVersionId(),
            new BigDecimal("100000.00"), 12, TODAY));

        assertThat(quote.ratePercent()).isNull();
        assertThat(quote.smallestDeposit()).isEqualByComparingTo("500000");
        assertThat(quote.refusal()).contains("500000");
    }

    /**
     * An account has no maturity to quote: it runs until it is closed. What it CAN say is the rate it earns, which
     * with no declaration yet is the version's guarantee -- the same max(declared, guaranteed) the valuer credits on.
     */
    @Test
    void quotesAnAccountAsItsRateAndItsFloor() {
        var on = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, TODAY);

        SavingsQuoteView quote = asTenant(() -> api.quoteProductSavings(on.productId(), on.productVersionId(),
            new BigDecimal("200000.00"), null, TODAY));

        assertThat(quote.kind()).isEqualTo(SavingsQuoteView.Kind.ACCOUNT);
        assertThat(quote.ratePercent()).isEqualByComparingTo("3");
        assertThat(quote.minimumBalance()).isEqualByComparingTo("50000.00");
        assertThat(quote.maturityValue()).isNull();
        assertThat(quote.offeredTermMonths()).isEmpty();
    }

    /** A declared rate above the guarantee is what a customer would actually earn, so it is what the quote shows. */
    @Test
    void quotesTheDeclaredRateOnceItIsApproved() {
        var on = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, TODAY);
        asTenant(() -> {
            var declaration = api.proposeRate(on.productId(), new BigDecimal("6.5"), TODAY, "actuary");
            return api.approveRate(declaration.declarationId(), "finance-officer");
        });

        SavingsQuoteView quote = asTenant(() -> api.quoteProductSavings(on.productId(), on.productVersionId(),
            new BigDecimal("200000.00"), null, TODAY));

        assertThat(quote.ratePercent()).isEqualByComparingTo("6.5");
    }

    /**
     * The two ids arrive from a URL and have to go together.
     *
     * <p>Found by quoting with a product id that was never checked: the deposit priced anyway, because the deposit
     * grid hangs off the VERSION. On an account it would have been worse than untidy -- the rate comes from the
     * PRODUCT's declarations and the floor from the version, so a mismatched pair quotes one product's rate over
     * another's guarantee.
     */
    @Test
    void refusesAVersionThatBelongsToAnotherProduct() {
        var one = fixtures.issueDeposit(TENANT, new BigDecimal("5000000.00"), 12, TODAY);
        var other = fixtures.issueDeposit(TENANT, new BigDecimal("5000000.00"), 12, TODAY);

        assertThatThrownBy(() -> asTenant(() -> api.quoteProductSavings(other.productId(), one.productVersionId(),
            new BigDecimal("5000000.00"), 12, TODAY)))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessageContaining("belongs to product");
    }

    /** A premium-rated product has no account to describe, and saying so beats inventing a rate for it. */
    @Test
    void refusesAProductThatKeepsNoAccount() {
        var on = fixtures.issueSavingsPlan(TENANT,
            new AccumulationPlan(ValueBasis.SCALE, null, null, java.util.List.of()), TODAY);

        assertThatThrownBy(() -> asTenant(() -> api.quoteProductSavings(on.productId(), on.productVersionId(),
            new BigDecimal("200000.00"), null, TODAY)))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessageContaining("does not keep an account");
    }
}
