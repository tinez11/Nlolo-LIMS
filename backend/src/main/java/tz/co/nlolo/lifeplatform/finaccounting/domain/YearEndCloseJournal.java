package tz.co.nlolo.lifeplatform.finaccounting.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The year-end close's journal (IFRS 17 I6, guide 5.7): every class 4-8 account with a balance for the year closed to
 * 3310 Current year profit or loss, then 3310 to 3210 Retained earnings (M-07), then dividends declared 3320 to 3210
 * (M-11). Balances are net Dr - Cr; a credit balance of classes 4-8 is a profit. Zero amounts write no line. Pure.
 */
public final class YearEndCloseJournal {

    public static final String CURRENT_YEAR = "3310";
    public static final String RETAINED = "3210";
    public static final String DIVIDENDS = "3320";

    /** An account's net Dr - Cr for the year. */
    public record Balance(String account, BigDecimal net) {}

    /** One journal line; side DR or CR. */
    public record Line(String account, String side, BigDecimal amount) {}

    /** Totals per class (net Dr - Cr), the profit (positive) or loss (negative), dividends declared, and the lines. */
    public record Result(Map<String, BigDecimal> classTotals, BigDecimal profit, BigDecimal dividends, List<Line> lines) {}

    private YearEndCloseJournal() {}

    public static Result build(List<Balance> classAccounts, BigDecimal dividendsNetDr) {
        List<Line> lines = new ArrayList<>();
        Map<String, BigDecimal> totals = new TreeMap<>();
        BigDecimal net = BigDecimal.ZERO;
        for (Balance b : classAccounts.stream().sorted(Comparator.comparing(Balance::account)).toList()) {
            if (b.net() == null || b.net().signum() == 0) {
                continue;
            }
            totals.merge(b.account().substring(0, 1), b.net(), BigDecimal::add);
            net = net.add(b.net());
            lines.add(new Line(b.account(), b.net().signum() > 0 ? "CR" : "DR", b.net().abs()));
        }
        BigDecimal profit = net.negate();
        if (net.signum() != 0) {
            // The accounts' closing sides net to -net; 3310 takes the year's result ...
            lines.add(new Line(CURRENT_YEAR, net.signum() > 0 ? "DR" : "CR", net.abs()));
            // ... and M-07 moves it on to retained earnings.
            if (profit.signum() > 0) {
                lines.add(new Line(CURRENT_YEAR, "DR", profit));
                lines.add(new Line(RETAINED, "CR", profit));
            } else {
                lines.add(new Line(RETAINED, "DR", profit.abs()));
                lines.add(new Line(CURRENT_YEAR, "CR", profit.abs()));
            }
        }
        BigDecimal dividends = dividendsNetDr == null || dividendsNetDr.signum() < 0 ? BigDecimal.ZERO : dividendsNetDr;
        if (dividends.signum() > 0) {
            lines.add(new Line(RETAINED, "DR", dividends));   // M-11
            lines.add(new Line(DIVIDENDS, "CR", dividends));
        }
        return new Result(totals, profit, dividends, lines);
    }
}
