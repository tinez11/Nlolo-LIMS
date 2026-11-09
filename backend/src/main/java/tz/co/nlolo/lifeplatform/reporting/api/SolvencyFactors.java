package tz.co.nlolo.lifeplatform.reporting.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * The risk charges, thresholds and stress shocks the solvency reports are worked out with (2026-11-09, the reports
 * redesign, decision D5). Settings, not code: a company changes them by publishing a new version, and a past month is
 * re-run on the version that was in force then.
 *
 * <p>Every rate is a PERCENTAGE as a person writes it ("4" is four per cent), so what is stored, shown and typed is the
 * same number.
 *
 * @param insurance    charges on insurance contract liabilities, by portfolio
 * @param market       charges on investments, by asset class
 * @param credit       charges on what others owe: receivables, reinsurance assets
 * @param operationalPercent a charge on insurance revenue over the last twelve months
 * @param inadmissible accounts that are not capital: an asset nobody would pay for in a wind-up (prepayments, deferred
 *                     tax, software, advances to agents)
 * @param combination  how the charges become one figure: {@link #ROOT_SUM_OF_SQUARES} or {@link #SIMPLE_SUM}
 * @param thresholds   the ratios a company is measured against -- MCR, SCR, PCR -- as the regulator names them
 * @param shocks       the named scenarios the stress test re-runs the ratio under
 */
public record SolvencyFactors(List<Charge> insurance, List<Charge> market, List<Charge> credit,
                              BigDecimal operationalPercent, List<String> inadmissible, String combination,
                              List<Threshold> thresholds, List<Shock> shocks) {

    /** √(insurance² + market² + credit²) + operational: risks that do not all strike at once, then operations. */
    public static final String ROOT_SUM_OF_SQUARES = "ROOT_SUM_OF_SQUARES";
    /** Every charge added as it stands: no allowance for risks not striking together. */
    public static final String SIMPLE_SUM = "SIMPLE_SUM";

    /** One charge: a rate on the balances of these accounts (each code read as the chart rolls it up). */
    public record Charge(String key, String label, List<String> accounts, BigDecimal percent) {}

    /** A capital adequacy ratio the company must hold, in per cent of required capital. */
    public record Threshold(String key, String label, BigDecimal ratioPercent) {}

    /**
     * A stress scenario.
     *
     * @param kind    ASSETS (these accounts' balances move by {@code percent}), INSURANCE (the insurance-risk charge
     *                grows by {@code percent}) or LOSS (a loss of {@code amount} falls on capital)
     */
    public record Shock(String key, String label, String kind, List<String> accounts, BigDecimal percent,
                        BigDecimal amount) {
        public static final String ASSETS = "ASSETS";
        public static final String INSURANCE = "INSURANCE";
        public static final String LOSS = "LOSS";
    }
}
