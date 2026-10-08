package tz.co.nlolo.lifeplatform.omnichannel.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A document handed to a customer (2026-10-07): a title, the facts that identify whose it is, one table and
 * its totals. Rendered as PDF ({@link DocumentPdf}) or Excel ({@link DocumentXlsx}) from this one shape, so
 * the two downloads can never say different things.
 *
 * @param landscape a wide table (the payment schedule's nine columns) prints across the page
 */
public record CustomerDocument(String issuer, String title, List<Field> header, List<Column> columns,
                               List<List<Object>> rows, List<Field> totals, List<String> notes, boolean landscape) {

    public CustomerDocument {
        header = List.copyOf(header);
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
        totals = List.copyOf(totals);
        notes = List.copyOf(notes);
    }

    /** A labelled fact: "Policy number  POL-0F16C3B8". */
    public record Field(String label, String value) {}

    /**
     * One column. {@code weight} shares the page width; a {@link Kind#MONEY} column is right-aligned with two
     * decimals and its cells are {@link BigDecimal} (null shows blank); a {@link Kind#DATE} column's cells are
     * {@link LocalDate}; anything else is text.
     */
    public record Column(String label, Kind kind, float weight) {}

    public enum Kind { TEXT, DATE, MONEY }
}
