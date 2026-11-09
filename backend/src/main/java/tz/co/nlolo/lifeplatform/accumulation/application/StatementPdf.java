package tz.co.nlolo.lifeplatform.accumulation.application;

import tz.co.nlolo.lifeplatform.accumulation.api.LedgerEntryView;
import tz.co.nlolo.lifeplatform.accumulation.api.StatementView;
import tz.co.nlolo.lifeplatform.organisation.api.BrandedDocument;
import tz.co.nlolo.lifeplatform.organisation.api.BrandedPdf;
import tz.co.nlolo.lifeplatform.organisation.api.Letterhead;
import tz.co.nlolo.lifeplatform.organisation.api.SignatureStamp;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The statement as a PDF, laid out from the SAME {@link StatementView} the console shows (rule 5) -- since 2026-11-09 in
 * the company's own letterhead and signed by its statements signatory (organisation settings): the balances as facts,
 * each kind of movement as its own table with its total, the closing balance beneath.
 */
final class StatementPdf {
    private StatementPdf() {}

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    static byte[] render(StatementView s, Letterhead letterhead, SignatureStamp signature) {
        List<BrandedDocument.Table> tables = new ArrayList<>();
        for (StatementView.Group g : s.groups()) {
            List<BrandedDocument.Row> rows = new ArrayList<>();
            for (LedgerEntryView e : g.entries()) {
                rows.add(BrandedDocument.Row.of(List.of(DMY.format(e.effectiveDate()), money(e.amount()),
                    e.reason() == null ? "" : e.reason())));
            }
            rows.add(new BrandedDocument.Row(List.of("Total", money(g.total()), ""), BrandedDocument.Style.TOTAL, 0));
            tables.add(new BrandedDocument.Table(StatementLabels.of(g.type()),
                List.of(new BrandedDocument.Column("Date", false, 1.2f),
                    new BrandedDocument.Column("Amount (" + s.currency() + ")", true, 1.4f),
                    new BrandedDocument.Column("Detail", false, 4f)), rows));
        }
        return BrandedPdf.render(new BrandedDocument("Savings account statement",
            "From " + DMY.format(s.periodFrom()) + " to " + DMY.format(s.periodTo()),
            List.of(new BrandedDocument.Field("Policy number", s.policyNumber()),
                new BrandedDocument.Field("Period", DMY.format(s.periodFrom()) + " to " + DMY.format(s.periodTo())),
                new BrandedDocument.Field("Opening balance", s.currency() + " " + money(s.openingBalance())),
                new BrandedDocument.Field("Closing balance", s.currency() + " " + money(s.closingBalance()))),
            List.of(), List.of(), null, tables,
            List.of(new BrandedDocument.Field("Closing balance " + DMY.format(s.periodTo()),
                s.currency() + " " + money(s.closingBalance()))),
            List.of("Includes every entry up to number " + s.lastSeq() + ". A later correction appears on the next statement."),
            false, false, signature), letterhead);
    }

    private static String money(BigDecimal amount) {
        return new DecimalFormat("#,##0.00;-#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(amount);
    }
}
