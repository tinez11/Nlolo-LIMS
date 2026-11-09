package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A financial statement as a tree of lines (2026-11-09, the user: show the computation "until it reaches the figure
 * summed up"). A line is made of signed parts -- chart accounts (their rolled balances, the ledger's own aggregation)
 * or other lines -- so a subtotal is a line made of lines, and every figure can say what it is the sum of.
 *
 * <p>Evaluated on two ledgers at once, this period and the one it is compared with, so a change is always the
 * difference of two figures the same rule produced.
 */
final class Statement {

    private Statement() {}

    enum Kind { LINE, SUBTOTAL, TOTAL }

    /** {@code ref} is {@code acct:<code>} or {@code line:<id>}; {@code sign} is 1 or -1. */
    record Part(String ref, int sign) {
        boolean isAccount() { return ref.startsWith("acct:"); }
        String code() { return ref.substring(5); }
    }

    /** @param goodWhen UP when a rise is good news for the insurer, DOWN when it is bad */
    record Line(String id, String label, Kind kind, List<Part> parts, String goodWhen) {

        static Line accounts(String id, String label, String goodWhen, String... codes) {
            return new Line(id, label, Kind.LINE, java.util.Arrays.stream(codes).map(c -> new Part("acct:" + c, 1))
                .toList(), goodWhen);
        }

        static Line subtotal(String id, String label, String goodWhen, Part... parts) {
            return new Line(id, label, Kind.SUBTOTAL, List.of(parts), goodWhen);
        }

        static Line total(String id, String label, String goodWhen, Part... parts) {
            return new Line(id, label, Kind.TOTAL, List.of(parts), goodWhen);
        }
    }

    /** A line on both periods. {@code prior} is null when the run compares with nothing. */
    record Figures(BigDecimal current, BigDecimal prior) {
        BigDecimal change() {
            return prior == null ? null : current.subtract(prior);
        }

        /** The change as a percentage of the prior's size, one decimal; null when there is no prior or it is zero. */
        String percent() {
            if (prior == null || prior.signum() == 0) return null;
            return change().multiply(BigDecimal.valueOf(100)).divide(prior.abs(), 1, RoundingMode.HALF_UP)
                .toPlainString();
        }
    }

    /** Every line's figures. {@code prior} may be null: no comparison. Lines are evaluated in order. */
    static Map<String, Figures> evaluate(List<Line> lines, List<AccountBalanceView> current,
                                         List<AccountBalanceView> prior) {
        Map<String, BigDecimal> now = new LinkedHashMap<>();
        Map<String, BigDecimal> before = new LinkedHashMap<>();
        Map<String, Figures> out = new LinkedHashMap<>();
        for (Line line : lines) {
            BigDecimal c = sum(line, current, now);
            BigDecimal p = prior == null ? null : sum(line, prior, before);
            now.put(line.id(), c);
            if (p != null) before.put(line.id(), p);
            out.put(line.id(), new Figures(c, p));
        }
        return out;
    }

    /** A part's value on one ledger: an account's rolled balance, or an earlier line's figure. */
    static BigDecimal partValue(Part part, List<AccountBalanceView> ledger, Map<String, BigDecimal> lines) {
        if (part.isAccount()) {
            return account(ledger, part.code());
        }
        BigDecimal v = lines.get(part.ref().substring(5));
        if (v == null) {
            throw new IllegalStateException("Line " + part.ref() + " is used before it is defined");
        }
        return v;
    }

    /** An account's rolled balance; a class root falls back to its posted leaves, as the existing reports do. */
    static BigDecimal account(List<AccountBalanceView> ledger, String code) {
        if (code.endsWith("000")) {
            return LedgerAccounts.classTotal(ledger, code);
        }
        return ledger.stream().filter(a -> a.accountCode().equals(code)).map(AccountBalanceView::balance)
            .findFirst().orElse(BigDecimal.ZERO);
    }

    private static BigDecimal sum(Line line, List<AccountBalanceView> ledger, Map<String, BigDecimal> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (Part part : line.parts()) {
            BigDecimal v = partValue(part, ledger, lines);
            total = part.sign() < 0 ? total.subtract(v) : total.add(v);
        }
        return total;
    }

    /** "1,102.00 + 96.30 + 42.20 = 1,240.50" -- the sum a line's parts make, from the same figures. */
    static String check(List<BigDecimal> parts, List<Integer> signs, BigDecimal total) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            boolean minus = signs.get(i) < 0;
            if (i == 0) {
                s.append(minus ? "−" : "").append(money(parts.get(i)));
            } else {
                s.append(minus ? " − " : " + ").append(money(parts.get(i)));
            }
        }
        if (parts.isEmpty()) {
            s.append("0.00");
        }
        return s.append(" = ").append(money(total)).toString();
    }

    /** Whether the parts, signed, come to the total exactly. */
    static boolean adds(List<BigDecimal> parts, List<Integer> signs, BigDecimal total) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < parts.size(); i++) {
            sum = signs.get(i) < 0 ? sum.subtract(parts.get(i)) : sum.add(parts.get(i));
        }
        return sum.compareTo(total) == 0;
    }

    static String money(BigDecimal value) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return f.format(value.setScale(2, RoundingMode.HALF_UP));
    }

    static List<BigDecimal> values(List<Part> parts, List<AccountBalanceView> ledger, Map<String, BigDecimal> lines) {
        List<BigDecimal> out = new ArrayList<>();
        for (Part p : parts) out.add(partValue(p, ledger, lines));
        return out;
    }
}
