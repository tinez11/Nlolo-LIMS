package tz.co.nlolo.lifeplatform.reinsurance.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The quarterly reinsurance statement's arithmetic (IFRS 17 I3d, guide R-01, R-03, R-04). Pure.
 *
 * <p>With P premium payable, C commission receivable, R recoveries, W funds withheld and PC profit commission:
 * R-04 Dr 1430 / Cr 2550 W; R-01 Dr 1430 (P - W), Cr 1431 C, Cr 1420 R, and 1434 takes the balance -- Dr when the
 * reinsurer owes us (R + C > P - W), Cr when we owe it; R-03 Dr 1433 / Cr 6120 PC. Zero lines are left out, so the
 * journal clears exactly what the quarter's bordereaux (K-01, K-02) and recoveries (B-05) posted.
 */
public final class StatementCalculator {

    private StatementCalculator() {}

    /** A calendar quarter, {@code YYYY-Qn}. It ends when the next one begins, by the civil date the caller passes. */
    public record Quarter(int year, int q) {

        private static final Pattern FORMAT = Pattern.compile("^(\\d{4})-Q([1-4])$");

        public static Quarter parse(String s) {
            Matcher m = s == null ? null : FORMAT.matcher(s);
            if (m == null || !m.matches()) {
                throw new IllegalArgumentException("A quarter is YYYY-Qn, for example 2026-Q3; got '" + s + "'");
            }
            return new Quarter(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }

        public static Quarter of(LocalDate date) {
            return new Quarter(date.getYear(), (date.getMonthValue() - 1) / 3 + 1);
        }

        public YearMonth first() { return YearMonth.of(year, (q - 1) * 3 + 1); }

        public YearMonth last() { return first().plusMonths(2); }

        public List<YearMonth> months() { return List.of(first(), first().plusMonths(1), last()); }

        public LocalDate start() { return first().atDay(1); }

        public LocalDate endExclusive() { return last().plusMonths(1).atDay(1); }

        public boolean hasEnded(LocalDate today) { return !today.isBefore(endExclusive()); }

        @Override
        public String toString() { return year + "-Q" + q; }
    }

    /** The quarter's months that fall inside the treaty's dates: each needs its bordereau before the quarter settles. */
    public static List<YearMonth> monthsRequired(Quarter quarter, LocalDate effectiveFrom, LocalDate effectiveTo) {
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth m : quarter.months()) {
            boolean afterStart = !m.atEndOfMonth().isBefore(effectiveFrom);
            boolean beforeEnd = effectiveTo == null || !m.atDay(1).isAfter(effectiveTo);
            if (afterStart && beforeEnd) {
                months.add(m);
            }
        }
        return months;
    }

    /** One line of the statement's journal: the guide entry it belongs to, the account, DR or CR, the amount. */
    public record JournalLine(String entry, String account, String side, BigDecimal amount) {}

    /** The platform's P, C and R for the quarter, and what finance entered from the reinsurer's statement (W, PC). */
    public record Figures(BigDecimal premium, BigDecimal commission, BigDecimal recoveries, BigDecimal withheld,
                          BigDecimal profitCommission) {

        /** Every problem with the entered figures; the platform's own are sums of posted amounts and are not judged. */
        public List<String> problems() {
            List<String> problems = new ArrayList<>();
            if (withheld.signum() < 0) {
                problems.add("Funds withheld cannot be negative");
            } else if (tooPrecise(withheld)) {
                problems.add("Funds withheld has at most two decimal places");
            } else if (withheld.compareTo(premium) > 0) {
                problems.add("Funds withheld (" + withheld.toPlainString() + ") cannot exceed the quarter's premium payable ("
                    + premium.toPlainString() + ")");
            }
            if (profitCommission.signum() < 0) {
                problems.add("Profit commission cannot be negative");
            } else if (tooPrecise(profitCommission)) {
                problems.add("Profit commission has at most two decimal places");
            }
            return problems;
        }

        private static boolean tooPrecise(BigDecimal amount) {
            return amount.stripTrailingZeros().scale() > 2;
        }

        /** Recoveries and commission, less the premium still payable after what is withheld: positive, they owe us. */
        private BigDecimal net() {
            return recoveries.add(commission).subtract(premium.subtract(withheld));
        }

        public BigDecimal owedToUs() { return net().max(BigDecimal.ZERO); }

        public BigDecimal owedByUs() { return net().negate().max(BigDecimal.ZERO); }

        public List<JournalLine> journal() {
            List<JournalLine> lines = new ArrayList<>();
            add(lines, "R-04", "1430", "DR", withheld);
            add(lines, "R-04", "2550", "CR", withheld);
            add(lines, "R-01", "1430", "DR", premium.subtract(withheld));
            add(lines, "R-01", "1431", "CR", commission);
            add(lines, "R-01", "1420", "CR", recoveries);
            add(lines, "R-01", "1434", "DR", owedToUs());
            add(lines, "R-01", "1434", "CR", owedByUs());
            add(lines, "R-03", "1433", "DR", profitCommission);
            add(lines, "R-03", "6120", "CR", profitCommission);
            return lines;
        }

        private static void add(List<JournalLine> lines, String entry, String account, String side, BigDecimal amount) {
            if (amount.signum() != 0) {
                lines.add(new JournalLine(entry, account, side, amount));
            }
        }
    }
}
