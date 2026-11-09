package tz.co.nlolo.lifeplatform.organisation.api;

import java.util.ArrayList;
import java.util.List;

/**
 * A document in the company's own clothes (2026-11-09, organisation settings): what every PDF the platform prints is
 * built as -- a customer's schedule or receipt, a statement, a report -- and {@link BrandedPdf} draws, under the
 * company's letterhead, in its colours and layout, signed where a signatory is set.
 *
 * <p>Cells arrive formatted: the document's maker knows a figure's unit; the renderer only places it.
 *
 * @param subtitle  a line under the title: the period and what it is compared with
 * @param figures   the headline figures panel (a report's answer)
 * @param notices   what a reader must know first, in a tinted box ("Placeholder risk charges, not TIRA's")
 * @param chart     one chart under the figures, or null
 * @param totals    labelled totals under the last table, right-aligned
 * @param landscape the page turned for a wide table
 * @param report    an internal report: the footer carries the company's confidentiality line
 * @param signature who signs it, or null
 */
public record BrandedDocument(String title, String subtitle, List<Field> facts, List<Figure> figures,
                              List<String> notices, Chart chart, List<Table> tables, List<Field> totals,
                              List<String> notes, boolean landscape, boolean report, SignatureStamp signature) {

    public BrandedDocument {
        facts = facts == null ? List.of() : List.copyOf(facts);
        figures = figures == null ? List.of() : List.copyOf(figures);
        notices = notices == null ? List.of() : List.copyOf(notices);
        tables = tables == null ? List.of() : List.copyOf(tables);
        totals = totals == null ? List.of() : List.copyOf(totals);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** A labelled fact: "Policy number  POL-0F16C3B8". */
    public record Field(String label, String value) {}

    /** A headline figure: its label, its value written in its unit, and a caption ("up 3.2% from 1,204.00"). */
    public record Figure(String label, String value, String caption) {}

    public record Table(String title, List<Column> columns, List<Row> rows) {
        public Table {
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
        }
    }

    /** {@code right}: a figure, right-aligned so it compares down the column. {@code weight} shares the width. */
    public record Column(String label, boolean right, float weight) {}

    public enum Style { LINE, SUBTOTAL, TOTAL }

    /** {@code level} indents the first cell: a line opened beneath another. */
    public record Row(List<String> cells, Style style, int level) {
        public Row {
            cells = new ArrayList<>(cells);
        }

        public static Row of(List<String> cells) {
            return new Row(cells, Style.LINE, 0);
        }
    }

    /**
     * The one chart: a BRIDGE (floating bars from a start to an end figure) or a TREND (lines over periods).
     *
     * @param unit MONEY, COUNT or PERCENT -- how the values on it are written
     */
    public record Chart(String kind, String description, List<Bar> bars, List<String> periods, List<Series> series,
                        String unit) {
        public Chart {
            bars = bars == null ? List.of() : List.copyOf(bars);
            periods = periods == null ? List.of() : List.copyOf(periods);
            series = series == null ? List.of() : List.copyOf(series);
        }
    }

    /** @param kind START, UP, DOWN or END */
    public record Bar(String label, double value, String kind) {}

    /** One line; a null value is a gap. */
    public record Series(String label, boolean dashed, List<Double> values) {
        public Series {
            values = new ArrayList<>(values);
        }
    }
}
