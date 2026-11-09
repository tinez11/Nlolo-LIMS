package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactorSetView;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactors;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactors.Charge;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactors.Shock;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactors.Threshold;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The factors every company starts on: STAND-INS, not TIRA's (2026-11-09, decision D5 -- the user will supply the
 * Risk-Based Capital guideline). Each account list is the IFRS 17 chart's own (ifrs17-chart.csv), chosen so that no
 * balance is charged twice. The rates are of the order a risk-based regime uses, and are marked as placeholders on
 * every report that uses them.
 */
final class PlaceholderFactors {

    private PlaceholderFactors() {}

    static final SolvencyFactorSetView SET = new SolvencyFactorSetView(0, "Placeholder risk charges (built in)",
        LocalDate.of(1900, 1, 1), true, null, null, factors());

    private static SolvencyFactors factors() {
        return new SolvencyFactors(
            List.of(
                charge("life", "Life contracts (GMM and VFA)", "4", "2110", "2111", "2112", "2113", "2120", "2130"),
                charge("paa", "Group, credit life and short-term (PAA)", "6", "2140"),
                charge("lic", "Claims incurred, not yet paid (LIC)", "5", "2200"),
                charge("investment", "Investment contracts", "1", "2300")),
            List.of(
                charge("bills", "Treasury bills", "0.5", "1210"),
                charge("bonds", "Government and corporate bonds", "2", "1215", "1216", "1240"),
                charge("deposits", "Fixed and term deposits", "1", "1220"),
                charge("equities", "Listed equities", "25", "1230", "1231"),
                charge("schemes", "Collective investment schemes", "10", "1250"),
                charge("property", "Investment property", "15", "1260")),
            List.of(
                charge("receivables", "Receivables outside IFRS 17", "5", "1300"),
                charge("reinsurance", "Reinsurance contract assets", "3", "1400")),
            new BigDecimal("3"),
            List.of("1375", "1385", "1610", "1620", "1650", "1750"),
            SolvencyFactors.ROOT_SUM_OF_SQUARES,
            List.of(
                new Threshold("MCR", "Minimum capital requirement (MCR)", new BigDecimal("100")),
                new Threshold("SCR", "Solvency capital requirement (SCR)", new BigDecimal("125")),
                new Threshold("PCR", "Prescribed capital requirement (PCR)", new BigDecimal("150"))),
            List.of(
                new Shock("equities", "Equities fall 20%", Shock.ASSETS, List.of("1230", "1231", "1250"),
                    new BigDecimal("-20"), null),
                new Shock("rates-up", "Interest rates rise 1%", Shock.ASSETS, List.of("1215", "1216", "1240"),
                    new BigDecimal("-5"), null),
                new Shock("rates-down", "Interest rates fall 1%", Shock.ASSETS, List.of("1215", "1216", "1240"),
                    new BigDecimal("5"), null),
                new Shock("deaths", "Deaths rise 15%", Shock.INSURANCE, List.of(), new BigDecimal("15"), null),
                new Shock("lapses", "Lapses rise 30%", Shock.INSURANCE, List.of(), new BigDecimal("30"), null),
                new Shock("reinsurer", "The largest reinsurer fails", Shock.ASSETS, List.of("1400"),
                    new BigDecimal("-50"), null),
                new Shock("catastrophe", "A catastrophe claim", Shock.LOSS, List.of(), null,
                    new BigDecimal("100000000"))));
    }

    private static Charge charge(String key, String label, String percent, String... accounts) {
        return new Charge(key, label, List.of(accounts), new BigDecimal(percent));
    }
}
