package tz.co.nlolo.lifeplatform.omnichannel.domain;

import tz.co.nlolo.lifeplatform.organisation.api.BrandedDocument;
import tz.co.nlolo.lifeplatform.organisation.api.BrandedPdf;
import tz.co.nlolo.lifeplatform.organisation.api.Letterhead;
import tz.co.nlolo.lifeplatform.organisation.api.SignatureStamp;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A {@link CustomerDocument} as a printable PDF in the company's own letterhead (2026-11-09, organisation settings): the
 * facts, the table, the totals, the notes and the signature, drawn by the one branded renderer every document shares.
 * This class keeps what is the customer document's own: how a cell is written -- money with two decimals, a day as
 * dd/MM/yyyy.
 */
public final class DocumentPdf {
    private DocumentPdf() {}

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** In the platform's own letterhead, unsigned: for a caller with no company to hand. */
    public static byte[] render(CustomerDocument d) {
        return render(d, Letterhead.fallback(), null);
    }

    public static byte[] render(CustomerDocument d, Letterhead letterhead, SignatureStamp signature) {
        return BrandedPdf.render(branded(d, signature), letterhead);
    }

    static BrandedDocument branded(CustomerDocument d, SignatureStamp signature) {
        List<BrandedDocument.Column> columns = new ArrayList<>();
        for (CustomerDocument.Column c : d.columns()) {
            columns.add(new BrandedDocument.Column(c.label(), c.kind() == CustomerDocument.Kind.MONEY, c.weight()));
        }
        List<BrandedDocument.Row> rows = new ArrayList<>();
        for (List<Object> values : d.rows()) {
            List<String> cells = new ArrayList<>();
            for (int i = 0; i < d.columns().size(); i++) {
                cells.add(cell(i < values.size() ? values.get(i) : null, d.columns().get(i).kind()));
            }
            rows.add(BrandedDocument.Row.of(cells));
        }
        return new BrandedDocument(d.title(), null,
            d.header().stream().map(f -> new BrandedDocument.Field(f.label(), f.value())).toList(),
            List.of(), List.of(), null, List.of(new BrandedDocument.Table(null, columns, rows)),
            d.totals().stream().map(f -> new BrandedDocument.Field(f.label(), f.value())).toList(),
            d.notes(), d.landscape(), false, signature);
    }

    static String cell(Object value, CustomerDocument.Kind kind) {
        if (value == null) return "";
        return switch (kind) {
            case MONEY -> value instanceof BigDecimal b ? money(b) : value.toString();
            case DATE -> value instanceof LocalDate date ? DMY.format(date) : value.toString();
            case TEXT -> value.toString();
        };
    }

    public static String money(BigDecimal amount) {
        return new DecimalFormat("#,##0.00;-#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(amount);
    }
}
