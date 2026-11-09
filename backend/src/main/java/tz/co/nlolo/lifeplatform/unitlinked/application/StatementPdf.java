package tz.co.nlolo.lifeplatform.unitlinked.application;

import tz.co.nlolo.lifeplatform.organisation.api.BrandedDocument;
import tz.co.nlolo.lifeplatform.organisation.api.BrandedPdf;
import tz.co.nlolo.lifeplatform.organisation.api.Letterhead;
import tz.co.nlolo.lifeplatform.organisation.api.SignatureStamp;
import tz.co.nlolo.lifeplatform.unitlinked.api.StatementData;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A unit statement as a PDF, laid out from {@link StatementData} alone -- since 2026-11-09 in the company's own
 * letterhead and signed by its statements signatory (organisation settings): the units held at the start and the end
 * as tables, the movements between, and what the period cost and paid. A position with no price yet says "No price
 * yet", never 0.00.
 */
final class StatementPdf {
    private StatementPdf() {}

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    static byte[] render(StatementData s, Letterhead letterhead, SignatureStamp signature) {
        List<BrandedDocument.Table> tables = new ArrayList<>();
        tables.add(positions("Your units on " + DMY.format(s.from().minusDays(1)), s.opening(), s.currency()));

        List<BrandedDocument.Row> moves = new ArrayList<>();
        for (StatementData.Line l : s.lines()) {
            moves.add(BrandedDocument.Row.of(List.of(DMY.format(l.date()), label(l.type()),
                l.fundCode() == null ? "" : l.fundCode(), l.units() == null ? "" : units(l.units()),
                l.price() == null ? "" : l.price().toPlainString(), money(l.amount()))));
        }
        tables.add(new BrandedDocument.Table("Movements", List.of(
            new BrandedDocument.Column("Date", false, 1.1f), new BrandedDocument.Column("What", false, 2f),
            new BrandedDocument.Column("Fund", false, 1f), new BrandedDocument.Column("Units", true, 1.4f),
            new BrandedDocument.Column("Price", true, 1.1f),
            new BrandedDocument.Column("Amount (" + s.currency() + ")", true, 1.4f)), moves));

        tables.add(positions("Your units on " + DMY.format(s.to()), s.closing(), s.currency()));

        List<BrandedDocument.Row> period = new ArrayList<>();
        period.add(BrandedDocument.Row.of(List.of("Invested in units", money(s.paidIn()))));
        for (Map.Entry<String, BigDecimal> c : s.chargesByType().entrySet()) {
            period.add(BrandedDocument.Row.of(List.of(label(c.getKey()), money(c.getValue()))));
        }
        period.add(BrandedDocument.Row.of(List.of("Paid out", money(s.paidOut()))));
        tables.add(new BrandedDocument.Table("In this period", List.of(new BrandedDocument.Column("", false, 3f),
            new BrandedDocument.Column("Amount (" + s.currency() + ")", true, 1.4f)), period));

        LocalDate priced = StatementData.priceDateOf(s.closing());
        return BrandedPdf.render(new BrandedDocument("Unit-linked statement",
            "From " + DMY.format(s.from()) + " to " + DMY.format(s.to()),
            List.of(new BrandedDocument.Field("Policy number", s.policyNumber()),
                new BrandedDocument.Field("Period", DMY.format(s.from()) + " to " + DMY.format(s.to())),
                new BrandedDocument.Field("Value at the end",
                    priced == null ? "No price yet" : s.currency() + " " + money(StatementData.valueOf(s.closing())))),
            List.of(), List.of(), null, tables, List.of(),
            List.of("Unit prices go down as well as up. Each value is at the latest approved price on or before its day,"
                + " shown with that price's date."),
            true, false, signature), letterhead);
    }

    private static BrandedDocument.Table positions(String title, List<StatementData.Position> positions, String currency) {
        List<BrandedDocument.Row> rows = new ArrayList<>();
        for (StatementData.Position p : positions) {
            rows.add(BrandedDocument.Row.of(List.of(p.fundCode(), units(p.units()),
                p.price() == null ? "No price yet" : p.price().toPlainString() + " (" + DMY.format(p.priceDate()) + ")",
                p.price() == null ? "" : money(p.value()))));
        }
        LocalDate priced = StatementData.priceDateOf(positions);
        rows.add(new BrandedDocument.Row(List.of("Total value", "", "",
            priced == null ? "No price yet" : money(StatementData.valueOf(positions))), BrandedDocument.Style.TOTAL, 0));
        return new BrandedDocument.Table(title, List.of(new BrandedDocument.Column("Fund", false, 1.4f),
            new BrandedDocument.Column("Units", true, 1.4f), new BrandedDocument.Column("Price", true, 1.8f),
            new BrandedDocument.Column("Value (" + currency + ")", true, 1.4f)), rows);
    }

    private static String label(String type) {
        String words = type.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String units(BigDecimal units) {
        return new DecimalFormat("#,##0.000000;-#,##0.000000", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(units);
    }

    private static String money(BigDecimal amount) {
        return new DecimalFormat("#,##0.00;-#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(amount);
    }
}
