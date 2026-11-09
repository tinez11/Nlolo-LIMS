package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountPostingsView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportNotFoundException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParts;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.PERCENT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;

/**
 * What the income statement and the balance sheet share (2026-11-09, the reports redesign): their rows with this
 * period, the compared period and the change; their headline figures; and the computation tree -- a line opens to its
 * accounts, an account to its sub-accounts, and a leaf account to its postings, each level stating the sum it makes.
 */
final class StatementReports {

    private StatementReports() {}

    /** Postings shown at a time in the tree; "show all" asks for the following pages. */
    static final int POSTINGS_PAGE = 20;

    /** The two ledgers a statement is read from: this period, and the compared one (null when it compares with none). */
    record Ledgers(List<AccountBalanceView> current, List<AccountBalanceView> prior, String currentLabel,
                   String priorLabel) {}

    static List<ReportColumn> columns(boolean compares, String currentLabel, String priorLabel) {
        List<ReportColumn> columns = new ArrayList<>();
        columns.add(new ReportColumn("line", "Line", TEXT));
        columns.add(new ReportColumn("current", currentLabel, MONEY));
        if (compares) {
            columns.add(new ReportColumn("prior", priorLabel, MONEY));
            columns.add(new ReportColumn("change", "Change", MONEY));
            columns.add(new ReportColumn("changePercent", "Change %", PERCENT));
        }
        return columns;
    }

    /** One row per statement line, tagged for the tree. */
    static List<Map<String, String>> rows(List<Statement.Line> lines, Map<String, Statement.Figures> figures,
                                          boolean compares) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (Statement.Line line : lines) {
            Statement.Figures f = figures.get(line.id());
            Map<String, String> row = row(line.label(), f, compares);
            row.put("_lineId", line.id());
            row.put("_hasParts", "true");
            row.put("_level", "0");
            row.put("_kind", line.kind().name());
            if (line.goodWhen() != null) row.put("_goodWhen", line.goodWhen());
            rows.add(row);
        }
        return rows;
    }

    static Map<String, String> row(String label, Statement.Figures f, boolean compares) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("line", label);
        row.put("current", Figures.money(f.current()));
        if (compares) {
            row.put("prior", f.prior() == null ? "" : Figures.money(f.prior()));
            row.put("change", f.change() == null ? "" : Figures.money(f.change()));
            row.put("changePercent", f.percent() == null ? "" : f.percent());
        }
        return row;
    }

    static ReportFigure figure(Statement.Line line, Statement.Figures f, boolean lead) {
        return new ReportFigure(line.id(), line.label(), Figures.money(f.current()),
            f.prior() == null ? null : Figures.money(f.prior()),
            f.change() == null ? null : Figures.money(f.change()), f.percent(), line.goodWhen(), lead);
    }

    /** "compared with September 2026", or "compared with July to September 2026". */
    static String comparisonLabel(YearMonth[] months) {
        if (months == null) return null;
        return "compared with " + span(months[0], months[1]);
    }

    static String span(YearMonth from, YearMonth to) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
        if (from.equals(to)) return f.format(from);
        return from.getYear() == to.getYear()
            ? DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH).format(from) + " to " + f.format(to)
            : f.format(from) + " to " + f.format(to);
    }

    /**
     * The parts of a line: a statement line's parts (accounts or lines), or -- for {@code acct:<code>} -- an account's
     * sub-accounts, or a leaf account's postings in {@code fromPeriod..toPeriod}.
     */
    static ReportParts parts(String lineId, List<Statement.Line> lines, Ledgers ledgers, FinaccountingApi ledger,
                             String fromPeriod, String toPeriod, int page) {
        boolean compares = ledgers.prior() != null;
        List<ReportColumn> columns = columns(compares, ledgers.currentLabel(), ledgers.priorLabel());
        Map<String, Statement.Figures> figures = Statement.evaluate(lines, ledgers.current(), ledgers.prior());

        Statement.Line line = lines.stream().filter(l -> l.id().equals(lineId)).findFirst().orElse(null);
        if (line != null) {
            // A line made of one class root opens straight to that root's sub-accounts: "4000" alone explains nothing.
            if (line.parts().size() == 1 && line.parts().get(0).isAccount() && line.parts().get(0).sign() > 0) {
                return accountParts(lineId, line.label(), line.parts().get(0).code(), ledgers, ledger, fromPeriod,
                    toPeriod, page, columns, figures.get(line.id()).current());
            }
            List<Map<String, String>> rows = new ArrayList<>();
            List<BigDecimal> values = new ArrayList<>();
            List<Integer> signs = new ArrayList<>();
            Map<String, BigDecimal> evaluatedNow = new LinkedHashMap<>();
            Map<String, BigDecimal> evaluatedBefore = new LinkedHashMap<>();
            figures.forEach((k, v) -> {
                evaluatedNow.put(k, v.current());
                if (v.prior() != null) evaluatedBefore.put(k, v.prior());
            });
            for (Statement.Part part : line.parts()) {
                BigDecimal now = Statement.partValue(part, ledgers.current(), evaluatedNow);
                BigDecimal before = compares ? Statement.partValue(part, ledgers.prior(), evaluatedBefore) : null;
                String label = part.isAccount() ? accountLabel(ledgers.current(), part.code())
                    : lines.stream().filter(l -> l.id().equals(part.ref().substring(5))).findFirst()
                        .map(Statement.Line::label).orElse(part.ref());
                Map<String, String> row = row((part.sign() < 0 ? "less " : "") + label,
                    new Statement.Figures(now, before), compares);
                row.put("_lineId", part.isAccount() ? part.ref() : part.ref().substring(5));
                row.put("_hasParts", "true");
                row.put("_level", "1");
                row.put("_kind", "LINE");
                rows.add(row);
                values.add(now);
                signs.add(part.sign());
            }
            BigDecimal total = figures.get(line.id()).current();
            return new ReportParts(lineId, line.label(), columns, rows, rows.size(), 0, rows.size(),
                Statement.check(values, signs, total), Statement.adds(values, signs, total));
        }
        if (lineId.startsWith("acct:")) {
            String code = lineId.substring(5);
            BigDecimal total = Statement.account(ledgers.current(), code);
            return accountParts(lineId, accountLabel(ledgers.current(), code), code, ledgers, ledger, fromPeriod,
                toPeriod, page, columns, total);
        }
        throw new ReportNotFoundException("line " + lineId);
    }

    private static ReportParts accountParts(String lineId, String label, String code, Ledgers ledgers,
                                            FinaccountingApi ledger, String fromPeriod, String toPeriod, int page,
                                            List<ReportColumn> columns, BigDecimal total) {
        boolean compares = ledgers.prior() != null;
        AccountBalanceView parent = ledgers.current().stream().filter(a -> a.accountCode().equals(code)).findFirst()
            .orElse(null);
        List<AccountBalanceView> children = ledgers.current().stream()
            .filter(a -> code.equals(a.parentCode()))
            .filter(a -> LedgerAccounts.posted(a) || (compares && posted(ledgers.prior(), a.accountCode())))
            .toList();
        if (!children.isEmpty()) {
            List<Map<String, String>> rows = new ArrayList<>();
            List<BigDecimal> values = new ArrayList<>();
            List<Integer> signs = new ArrayList<>();
            for (AccountBalanceView child : children) {
                // A contra account nets against its parent: it carries the opposite normal balance.
                int sign = parent == null || child.normalBalance() == parent.normalBalance() ? 1 : -1;
                BigDecimal now = child.balance();
                BigDecimal before = compares ? Statement.account(ledgers.prior(), child.accountCode()) : null;
                Map<String, String> row = row((sign < 0 ? "less " : "") + child.accountCode() + " " + child.name(),
                    new Statement.Figures(now, before), compares);
                row.put("_lineId", "acct:" + child.accountCode());
                row.put("_hasParts", "true");
                row.put("_level", "1");
                row.put("_kind", "LINE");
                rows.add(row);
                values.add(now);
                signs.add(sign);
            }
            return new ReportParts(lineId, label, columns, rows, rows.size(), 0, rows.size(),
                Statement.check(values, signs, total), Statement.adds(values, signs, total));
        }
        if (parent == null) {
            // Not in this ledger's chart at all (a tenant with no chart yet): nothing makes it up, and it says so.
            return new ReportParts(lineId, label, columns, List.of(), 0, 0, 0,
                Statement.check(List.of(), List.of(), total), total.signum() == 0);
        }
        // A leaf: its postings in the span, signed in the account's own normal direction.
        AccountPostingsView postings = ledger.accountPostings(code, fromPeriod, toPeriod, page, POSTINGS_PAGE);
        PostingDirection normal = parent.normalBalance();
        List<ReportColumn> postingColumns = List.of(new ReportColumn("line", "Posting", TEXT),
            new ReportColumn("current", "Amount", MONEY));
        List<Map<String, String>> rows = new ArrayList<>();
        for (AccountPostingsView.Posting p : postings.postings()) {
            BigDecimal amount = p.direction() == normal ? p.amount() : p.amount().negate();
            Map<String, String> row = new LinkedHashMap<>();
            row.put("line", p.period() + " · " + readable(p.sourceEvent()) + " · " + p.sourceRef()
                + (p.policyNumber() == null ? "" : " · " + p.policyNumber()));
            row.put("current", Figures.money(amount));
            row.put("_lineId", "posting:" + p.postingId());
            row.put("_level", "2");
            row.put("_kind", "LINE");
            row.put("_source", p.sourceEvent() + ":" + p.sourceRef());
            rows.add(row);
        }
        BigDecimal net = normal == PostingDirection.DR ? postings.debitTotal().subtract(postings.creditTotal())
            : postings.creditTotal().subtract(postings.debitTotal());
        String check = postings.count() + (postings.count() == 1 ? " posting" : " postings") + " in "
            + periods(fromPeriod, toPeriod) + " = " + Statement.money(net);
        return new ReportParts(lineId, label, postingColumns, rows, postings.count(), postings.page(),
            postings.size(), check, net.compareTo(total) == 0);
    }

    private static boolean posted(List<AccountBalanceView> ledger, String code) {
        return ledger.stream().anyMatch(a -> a.accountCode().equals(code) && LedgerAccounts.posted(a));
    }

    private static String accountLabel(List<AccountBalanceView> ledger, String code) {
        return ledger.stream().filter(a -> a.accountCode().equals(code)).findFirst()
            .map(a -> a.accountCode() + " " + a.name()).orElse(code);
    }

    private static String periods(String from, String to) {
        return from.equals(to) ? from : from + " to " + to;
    }

    /** "billing.PremiumCollected" reads as "Premium collected". */
    static String readable(String sourceEvent) {
        if (sourceEvent == null) return "";
        String name = sourceEvent.contains(".") ? sourceEvent.substring(sourceEvent.lastIndexOf('.') + 1) : sourceEvent;
        // "PAARevenueEarned" -> "PAA revenue earned": an acronym keeps its capitals, every other word is lower case.
        String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
            .replace('_', ' ').trim();
        StringBuilder out = new StringBuilder();
        for (String word : spaced.split("\\s+")) {
            if (word.isEmpty()) continue;
            boolean acronym = word.length() > 1 && word.equals(word.toUpperCase(Locale.ROOT));
            if (out.length() > 0) out.append(' ');
            out.append(acronym ? word : word.toLowerCase(Locale.ROOT));
        }
        String words = out.toString();
        return words.isEmpty() ? "" : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** "Jan to Nov 2026", "Oct 2026": a period short enough to label a chart bar. */
    static String shortSpan(java.time.YearMonth from, java.time.YearMonth to) {
        DateTimeFormatter month = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);
        DateTimeFormatter monthYear = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
        if (from.equals(to)) return monthYear.format(from);
        return from.getYear() == to.getYear() ? month.format(from) + " to " + monthYear.format(to)
            : monthYear.format(from) + " to " + monthYear.format(to);
    }
}
