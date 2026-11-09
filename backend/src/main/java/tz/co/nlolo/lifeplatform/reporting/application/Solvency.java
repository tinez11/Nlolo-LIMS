package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactors;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactors.Charge;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactors.Shock;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Capital adequacy, worked out from the ledger and a factor set (2026-11-09, the reports redesign batch 3). Pure: the
 * same balances and factors always give the same figures, so the stress test re-runs it under a shock and the forecast
 * under a projected year.
 *
 * <p>The answer is a tree. Every figure is the sum of the nodes beneath it, to the cent -- a charge is the sum of its
 * accounts' charges, each rounded to the cent first -- so the report's tree can open any figure and show it adding up.
 *
 * <ul>
 *   <li><b>Available capital</b> = equity + the year's result not yet closed into it − inadmissible assets.</li>
 *   <li><b>Required capital</b> = the four risk charges, combined as the factor set says.</li>
 *   <li><b>Capital adequacy ratio</b> = available ÷ required, in per cent.</li>
 * </ul>
 */
final class Solvency {

    private Solvency() {}

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final List<String> RESULT_CLASSES = List.of("4000", "5000", "6000", "7000", "8000");

    /** One figure and what it is made of. Leaves have no children. */
    record Node(String id, String label, BigDecimal value, List<Node> children) {
        Optional<Node> find(String wanted) {
            if (id.equals(wanted)) return Optional.of(this);
            for (Node c : children) {
                Optional<Node> hit = c.find(wanted);
                if (hit.isPresent()) return hit;
            }
            return Optional.empty();
        }
    }

    /**
     * What the calculation reads: the ledger through the month (positions) and insurance revenue over the twelve months
     * to it (the operational charge's base).
     */
    record Book(List<AccountBalanceView> ledger, BigDecimal revenue12, String revenueSpan) {}

    /** The answer. {@code ratio} is null when nothing is required: a ratio over zero is not a number. */
    record Capital(Node available, Node required, BigDecimal ratio, BigDecimal insurance, BigDecimal market,
                   BigDecimal credit, BigDecimal diversification, BigDecimal operational) {

        Node tree() {
            BigDecimal over = available.value().subtract(required.value());
            Node minusRequired = new Node("less-required", "less required capital", required.value().negate(), List.of());
            return new Node("root", "Capital adequacy", over, List.of(available, required,
                new Node("over", "Capital over required capital", over, List.of(
                    new Node("over-available", "Available capital", available.value(), List.of()), minusRequired))));
        }
    }

    static Capital compute(Book book, SolvencyFactors f) {
        return compute(book, f, null);
    }

    /** Under a stress {@code shock}, or none. */
    static Capital compute(Book book, SolvencyFactors f, Shock shock) {
        Map<String, AccountBalanceView> byCode = new HashMap<>();
        for (AccountBalanceView a : book.ledger()) byCode.put(a.accountCode(), a);
        Map<String, BigDecimal> balances = new HashMap<>();
        for (AccountBalanceView a : book.ledger()) balances.put(a.accountCode(), a.balance());

        // An asset shock moves balances; what it moves comes straight off capital, and the charges are re-read on the
        // moved balances.
        BigDecimal shockMove = BigDecimal.ZERO;
        if (shock != null && Shock.ASSETS.equals(shock.kind())) {
            for (String code : shock.accounts()) {
                BigDecimal before = balances.getOrDefault(code, BigDecimal.ZERO);
                BigDecimal move = cents(before.multiply(shock.percent()).divide(HUNDRED, MathContext.DECIMAL64));
                balances.put(code, before.add(move));
                shockMove = shockMove.add(move);
            }
        }

        // ---- available capital
        List<Node> sources = new ArrayList<>();
        sources.add(new Node("equity", "3000 Equity", balance(balances, "3000"), List.of()));
        sources.add(new Node("result", "Result for the year, not yet closed into equity", result(byCode), List.of()));
        if (shock != null && Shock.ASSETS.equals(shock.kind())) {
            sources.add(new Node("shock-assets", "Shock: " + shock.label(), shockMove, List.of()));
        }
        if (shock != null && Shock.LOSS.equals(shock.kind()) && shock.amount() != null) {
            sources.add(new Node("shock-loss", "Shock: " + shock.label(), shock.amount().negate(), List.of()));
        }
        for (String code : f.inadmissible()) {
            BigDecimal held = balance(balances, code);
            if (held.signum() > 0) {
                sources.add(new Node("inadmissible:" + code, "less " + name(byCode, code) + " (not capital)",
                    held.negate(), List.of()));
            }
        }
        Node available = new Node("available", "Available capital", sum(sources), sources);

        // ---- required capital
        Node insurance = risk("insurance", "Insurance risk", f.insurance(), balances, byCode);
        if (shock != null && Shock.INSURANCE.equals(shock.kind())) {
            BigDecimal more = cents(insurance.value().multiply(shock.percent()).divide(HUNDRED, MathContext.DECIMAL64));
            List<Node> parts = new ArrayList<>(insurance.children());
            parts.add(new Node("shock-insurance", "Shock: " + shock.label(), more, List.of()));
            insurance = new Node(insurance.id(), insurance.label(), sum(parts), parts);
        }
        Node market = risk("market", "Market risk", f.market(), balances, byCode);
        Node credit = risk("credit", "Credit risk", f.credit(), balances, byCode);
        BigDecimal base = book.revenue12().max(BigDecimal.ZERO);
        BigDecimal opCharge = cents(base.multiply(f.operationalPercent()).divide(HUNDRED, MathContext.DECIMAL64));
        Node operational = new Node("risk:operational", "Operational risk", opCharge, List.of(
            new Node("operational-base", "Insurance revenue, " + book.revenueSpan() + ": " + Statement.money(base)
                + " at " + rate(f.operationalPercent()), opCharge, List.of())));

        BigDecimal i = insurance.value();
        BigDecimal m = market.value();
        BigDecimal c = credit.value();
        List<Node> charges = new ArrayList<>(List.of(insurance, market, credit));
        BigDecimal diversification = BigDecimal.ZERO;
        if (SolvencyFactors.ROOT_SUM_OF_SQUARES.equals(f.combination())) {
            BigDecimal root = cents(i.pow(2).add(m.pow(2)).add(c.pow(2)).sqrt(MathContext.DECIMAL64));
            diversification = root.subtract(i).subtract(m).subtract(c);
            charges.add(new Node("diversification", "less diversification: the three risks do not all strike at once"
                + " (√(I² + M² + C²) = " + Statement.money(root) + ")", diversification, List.of()));
        }
        charges.add(operational);
        Node required = new Node("required", "Required capital", sum(charges), charges);

        BigDecimal ratio = required.value().signum() <= 0 ? null
            : available.value().multiply(HUNDRED).divide(required.value(), 1, RoundingMode.HALF_UP);
        return new Capital(available, required, ratio, i, m, c, diversification, opCharge);
    }

    /** A risk: its charges, each its accounts' balances at the charge's rate. Never below zero. */
    private static Node risk(String key, String label, List<Charge> charges, Map<String, BigDecimal> balances,
                             Map<String, AccountBalanceView> byCode) {
        List<Node> parts = new ArrayList<>();
        for (Charge charge : charges) {
            List<Node> accounts = new ArrayList<>();
            for (String code : charge.accounts()) {
                BigDecimal held = balance(balances, code);
                if (held.signum() == 0) continue;
                BigDecimal amount = cents(held.multiply(charge.percent()).divide(HUNDRED, MathContext.DECIMAL64));
                accounts.add(new Node("account:" + key + ":" + charge.key() + ":" + code,
                    name(byCode, code) + ": " + Statement.money(held) + " at " + rate(charge.percent()), amount, List.of()));
            }
            parts.add(new Node("charge:" + key + ":" + charge.key(), charge.label() + " at " + rate(charge.percent()),
                sum(accounts), accounts));
        }
        BigDecimal total = sum(parts);
        if (total.signum() < 0) {
            // A book that is a net asset where a liability is charged (or the reverse) owes no negative capital.
            parts.add(new Node("floor:" + key, "Raised to zero: a charge is never negative", total.negate(), List.of()));
            total = BigDecimal.ZERO;
        }
        return new Node("risk:" + key, label, total, parts);
    }

    /** The year's income less its expenses through the month, not yet closed into retained earnings. */
    private static BigDecimal result(Map<String, AccountBalanceView> byCode) {
        BigDecimal total = BigDecimal.ZERO;
        for (String root : RESULT_CLASSES) {
            AccountBalanceView a = byCode.get(root);
            if (a != null) total = total.add(a.credit().subtract(a.debit()));
        }
        return total;
    }

    private static BigDecimal balance(Map<String, BigDecimal> balances, String code) {
        return balances.getOrDefault(code, BigDecimal.ZERO);
    }

    private static String name(Map<String, AccountBalanceView> byCode, String code) {
        AccountBalanceView a = byCode.get(code);
        return a == null ? code : code + " " + a.name();
    }

    static BigDecimal sum(List<Node> nodes) {
        return nodes.stream().map(Node::value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static BigDecimal cents(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** "4%", "0.5%". */
    static String rate(BigDecimal percent) {
        return percent.stripTrailingZeros().toPlainString() + "%";
    }
}
