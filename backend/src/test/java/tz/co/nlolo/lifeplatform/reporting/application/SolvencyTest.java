package tz.co.nlolo.lifeplatform.reporting.application;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.reporting.api.NewSolvencyFactorSet;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactorSetView;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactors;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactors.Charge;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactors.Shock;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactors.Threshold;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.SolvencyFactorStore;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capital adequacy from a small hand-made book whose every figure can be worked out on paper (2026-11-09, the reports
 * redesign batch 3, spec §7: "every tree's parts sum to their line"; "placeholder factors produce the strip").
 */
class SolvencyTest {

    private static AccountBalanceView acct(String code, String name, String balance) {
        return acct(code, name, balance, "0", "0");
    }

    private static AccountBalanceView acct(String code, String name, String balance, String debit, String credit) {
        AccountType type = switch (code.charAt(0)) {
            case '1' -> AccountType.ASSET;
            case '2' -> AccountType.LIABILITY;
            case '3' -> AccountType.EQUITY;
            case '4', '7' -> AccountType.INCOME;
            default -> AccountType.EXPENSE;
        };
        return new AccountBalanceView(code, name, type, PostingDirection.DR, null, (short) 1, true, AccountStatus.ACTIVE,
            "TZS", BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(debit), new BigDecimal(credit), new BigDecimal(balance));
    }

    /**
     * Equity 1,000,000; this year's income 300,000 against expenses 100,000 (a result of 200,000); 50,000 of
     * prepayments, which are not capital. Liabilities of 2,000,000 at 4%; equities of 400,000 at 25%; receivables of
     * 100,000 at 5%; revenue of 1,000,000 a year at 3%.
     */
    private static Solvency.Book book() {
        return new Solvency.Book(List.of(
            acct("3000", "Equity", "1000000.00"),
            acct("4000", "Insurance revenue", "300000.00", "0", "300000.00"),
            acct("5000", "Insurance service expenses", "100000.00", "100000.00", "0"),
            acct("1610", "Prepayments", "50000.00"),
            acct("2110", "LRC", "2000000.00"),
            acct("1230", "Listed equities", "400000.00"),
            acct("1300", "Receivables", "100000.00")),
            new BigDecimal("1000000.00"), "Dec 25 – Nov 26");
    }

    private static SolvencyFactors factors(String combination) {
        return new SolvencyFactors(
            List.of(new Charge("life", "Life", List.of("2110"), new BigDecimal("4"))),
            List.of(new Charge("equities", "Equities", List.of("1230", "1231"), new BigDecimal("25"))),
            List.of(new Charge("receivables", "Receivables", List.of("1300"), new BigDecimal("5"))),
            new BigDecimal("3"), List.of("1610"), combination,
            List.of(new Threshold("MCR", "Minimum", new BigDecimal("100"))),
            List.of(new Shock("equities", "Equities fall 20%", Shock.ASSETS, List.of("1230"), new BigDecimal("-20"), null),
                new Shock("deaths", "Deaths rise 15%", Shock.INSURANCE, List.of(), new BigDecimal("15"), null),
                new Shock("cat", "Catastrophe", Shock.LOSS, List.of(), null, new BigDecimal("250000.00"))));
    }

    @Test
    void availableCapitalIsEquityPlusTheYearsResultLessWhatIsNotCapital() {
        Solvency.Capital c = Solvency.compute(book(), factors(SolvencyFactors.SIMPLE_SUM));
        // 1,000,000 + (300,000 - 100,000) - 50,000
        assertEquals(new BigDecimal("1150000.00"), c.available().value());
    }

    @Test
    void requiredCapitalAddsTheChargesWhenTheSetSaysSo() {
        Solvency.Capital c = Solvency.compute(book(), factors(SolvencyFactors.SIMPLE_SUM));
        assertEquals(new BigDecimal("80000.00"), c.insurance());   // 2,000,000 x 4%
        assertEquals(new BigDecimal("100000.00"), c.market());     // 400,000 x 25%
        assertEquals(new BigDecimal("5000.00"), c.credit());       // 100,000 x 5%
        assertEquals(new BigDecimal("30000.00"), c.operational()); // 1,000,000 x 3%
        assertEquals(new BigDecimal("215000.00"), c.required().value());
        assertEquals(new BigDecimal("534.9"), c.ratio());          // 1,150,000 / 215,000
    }

    @Test
    void theRootSumOfSquaresAllowsForRisksNotStrikingTogether() {
        Solvency.Capital c = Solvency.compute(book(), factors(SolvencyFactors.ROOT_SUM_OF_SQUARES));
        // sqrt(80,000^2 + 100,000^2 + 5,000^2) = 128,160.06 (to the cent), then + 30,000
        assertEquals(new BigDecimal("158160.06"), c.required().value());
        assertEquals(new BigDecimal("-56839.94"), c.diversification());
    }

    @Test
    void everyFigureInTheTreeIsTheSumOfWhatItIsMadeOf() {
        for (String combination : List.of(SolvencyFactors.SIMPLE_SUM, SolvencyFactors.ROOT_SUM_OF_SQUARES)) {
            SolvencyFactors f = factors(combination);
            List<Solvency.Capital> runs = new ArrayList<>(List.of(Solvency.compute(book(), f)));
            for (Shock s : f.shocks()) runs.add(Solvency.compute(book(), f, s));
            // The root only holds the three lines the report shows; each of them is a sum.
            for (Solvency.Capital c : runs) c.tree().children().forEach(SolvencyTest::assertAddsUp);
        }
    }

    private static void assertAddsUp(Solvency.Node node) {
        if (node.children().isEmpty()) return;
        assertEquals(0, Solvency.sum(node.children()).compareTo(node.value()), node.id() + " does not add up");
        node.children().forEach(SolvencyTest::assertAddsUp);
    }

    @Test
    void anAssetShockMovesTheBalancesItNamesAndComesOffCapital() {
        SolvencyFactors f = factors(SolvencyFactors.SIMPLE_SUM);
        Solvency.Capital c = Solvency.compute(book(), f, f.shocks().get(0));
        // Equities lose 80,000: capital falls by it, and the market charge is 25% of 320,000.
        assertEquals(new BigDecimal("1070000.00"), c.available().value());
        assertEquals(new BigDecimal("80000.00"), c.market());
    }

    @Test
    void aMortalityShockScalesTheInsuranceChargeAndALossComesOffCapital() {
        SolvencyFactors f = factors(SolvencyFactors.SIMPLE_SUM);
        assertEquals(new BigDecimal("92000.00"), Solvency.compute(book(), f, f.shocks().get(1)).insurance());
        assertEquals(new BigDecimal("900000.00"), Solvency.compute(book(), f, f.shocks().get(2)).available().value());
    }

    /**
     * With capital already negative, more required capital makes the ratio LESS negative (-250% to -217.4%): a ranking by
     * ratio would call more deaths good news. The stress test ranks by what is left over required capital instead.
     */
    @Test
    void aShockOnANegativeCapitalStillRanksAsWorse() {
        Solvency.Book negative = new Solvency.Book(List.of(acct("3000", "Equity", "-100.00"), acct("2110", "LRC", "1000.00")),
            BigDecimal.ZERO, "x");
        SolvencyFactors f = factors(SolvencyFactors.SIMPLE_SUM);
        Solvency.Capital base = Solvency.compute(negative, f);
        Solvency.Capital deaths = Solvency.compute(negative, f, f.shocks().get(1));
        assertEquals(new BigDecimal("-250.0"), base.ratio());
        assertEquals(new BigDecimal("-217.4"), deaths.ratio());
        assertTrue(StressTestReport.over(deaths).compareTo(StressTestReport.over(base)) < 0);
    }

    @Test
    void aChargeIsNeverNegativeAndNothingRequiredHasNoRatio() {
        Solvency.Book upsideDown = new Solvency.Book(List.of(acct("3000", "Equity", "10.00"),
            acct("2110", "LRC", "-500.00")), BigDecimal.ZERO, "x");
        Solvency.Capital c = Solvency.compute(upsideDown, factors(SolvencyFactors.SIMPLE_SUM));
        assertEquals(0, c.insurance().signum());
        c.tree().children().forEach(SolvencyTest::assertAddsUp);
        assertNull(c.ratio());
    }

    @Test
    void placeholderFactorsCarryTheStripAndApprovedOnesDoNot() {
        assertTrue(SolvencyReports.notices(PlaceholderFactors.SET).contains(SolvencyReports.PLACEHOLDER_NOTICE));
        SolvencyFactorSetView approved = new SolvencyFactorSetView(1, "TIRA 2025", LocalDate.of(2026, 1, 1), false,
            "Asha", Instant.now(), factors(SolvencyFactors.SIMPLE_SUM));
        assertFalse(SolvencyReports.notices(approved).contains(SolvencyReports.PLACEHOLDER_NOTICE));
    }

    @Test
    void aMonthIsWorkedOutOnTheVersionInForceAtItsEnd() {
        SolvencyFactorStore store = Mockito.mock(SolvencyFactorStore.class);
        UUID tenant = UUID.randomUUID();
        SolvencyFactorSetView v2 = new SolvencyFactorSetView(2, "From 2027", LocalDate.of(2027, 1, 1), false, "Asha",
            Instant.now(), factors(SolvencyFactors.SIMPLE_SUM));
        SolvencyFactorSetView v1 = new SolvencyFactorSetView(1, "From October", LocalDate.of(2026, 10, 15), false,
            "Asha", Instant.now(), factors(SolvencyFactors.SIMPLE_SUM));
        Mockito.when(store.all(tenant)).thenReturn(List.of(v2, v1));
        SolvencyFactorSets sets = new SolvencyFactorSets(store);

        assertEquals(1, sets.forMonth(tenant, YearMonth.of(2026, 10)).version(), "in force by the month's end");
        assertEquals(0, sets.forMonth(tenant, YearMonth.of(2026, 9)).version(), "before any: the placeholder set");
        assertEquals(2, sets.forMonth(tenant, YearMonth.of(2027, 3)).version());
        assertEquals(0, sets.all(tenant).get(2).version(), "the built-in set closes the list");
    }

    @Test
    void aFactorSetItCannotUseIsRefusedInWords() {
        SolvencyFactors good = factors(SolvencyFactors.SIMPLE_SUM);
        SolvencyFactorSets.validate(new NewSolvencyFactorSet("TIRA", LocalDate.of(2027, 1, 1), false, good));
        SolvencyFactors badRate = new SolvencyFactors(List.of(new Charge("life", "Life", List.of("2110"),
            new BigDecimal("140"))), good.market(), good.credit(), good.operationalPercent(), good.inadmissible(),
            good.combination(), good.thresholds(), good.shocks());
        ReportParameterException e = assertThrows(ReportParameterException.class, () -> SolvencyFactorSets.validate(
            new NewSolvencyFactorSet("TIRA", LocalDate.of(2027, 1, 1), false, badRate)));
        assertEquals("Life: the rate must be between 0% and 100%.", e.getMessage());
        assertThrows(ReportParameterException.class, () -> SolvencyFactorSets.validate(
            new NewSolvencyFactorSet(" ", LocalDate.of(2027, 1, 1), false, good)));
    }

    @Test
    void theBuiltInSetIsValidAndAPlaceholder() {
        SolvencyFactorSets.validate(new NewSolvencyFactorSet("copy", LocalDate.of(2026, 1, 1), true,
            PlaceholderFactors.SET.factors()));
        assertTrue(PlaceholderFactors.SET.placeholder());
    }

    @Test
    void aTrendThroughAStraightLineProjectsItWithNoSpread() {
        Trend t = Trend.fit(List.of(new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("120"),
            new BigDecimal("130")));
        assertEquals(new BigDecimal("140.00"), t.ahead(1));
        assertEquals(new BigDecimal("250.00"), t.ahead(12));
        assertEquals(new BigDecimal("0.00"), t.spreadCents());
        assertEquals(new BigDecimal("7.00"), Trend.fit(List.of(new BigDecimal("7"))).ahead(3), "one month: flat");
    }
}
