package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import tz.co.nlolo.lifeplatform.reporting.api.ColumnType;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * How a report's figures are written in an export (2026-11-09, report formatting): money with separators and two
 * decimals, a loss in brackets as an accountant writes it, counts whole, percentages with their sign, days as dd/MM/yyyy
 * -- the same in the PDF and on the Excel cover, as on the screen.
 */
final class ReportFormat {

    private ReportFormat() {}

    static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    static boolean numeric(ColumnType type) {
        return type == ColumnType.MONEY || type == ColumnType.COUNT || type == ColumnType.PERCENT;
    }

    /** A cell as printed; a value that is not a number in a number column (a dash, a word) as it is. */
    static String cell(ColumnType type, String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            return switch (type) {
                case MONEY -> money(new BigDecimal(raw));
                case COUNT -> new DecimalFormat("#,##0", symbols()).format(new BigDecimal(raw));
                case PERCENT -> new DecimalFormat("#,##0.0#", symbols()).format(new BigDecimal(raw)) + "%";
                case DATE -> DMY.format(LocalDate.parse(raw));
                case TEXT -> raw;
            };
        } catch (RuntimeException e) {
            return raw;
        }
    }

    /** 1,204.00, or (1,204.00) for a negative: a loss reads as one without a minus sign to miss. */
    static String money(BigDecimal v) {
        return new DecimalFormat("#,##0.00;(#,##0.00)", symbols()).format(v);
    }

    /** A headline figure's value in its unit. */
    static String figure(ReportFigure f) {
        if (f.value() == null || f.value().isBlank()) return "-";
        return switch (f.unit()) {
            case "COUNT" -> cell(ColumnType.COUNT, f.value());
            case "PERCENT" -> cell(ColumnType.PERCENT, f.value());
            case "DAYS" -> cell(ColumnType.COUNT, f.value()) + " days";
            default -> cell(ColumnType.MONEY, f.value());
        };
    }

    /** "up 3.2% from 1,204.00", "down 2.5 pts from 12.0%", "no change from 40" -- or null with no comparison. */
    static String caption(ReportFigure f) {
        if (f.prior() == null || f.change() == null) return null;
        BigDecimal change;
        try {
            change = new BigDecimal(f.change());
        } catch (RuntimeException e) {
            return null;
        }
        String from = figure(new ReportFigure(f.key(), f.label(), f.prior(), null, null, null, null, false, f.unit()));
        if (change.signum() == 0) return "no change from " + from;
        String direction = change.signum() > 0 ? "up " : "down ";
        String size = "PERCENT".equals(f.unit()) ? change.abs().stripTrailingZeros().toPlainString() + " pts"
            : f.changePercent() != null ? f.changePercent().replace("-", "") + "%"
            : figure(new ReportFigure(f.key(), f.label(), change.abs().toPlainString(), null, null, null, null, false, f.unit()));
        return direction + size + " from " + from;
    }

    /** "9 Nov 2026, 18:32" in Dar es Salaam: when the report was run, as a person reads a time. */
    static String stamp(Instant at) {
        return STAMP.format(at.atZone(CIVIL));
    }

    private static DecimalFormatSymbols symbols() {
        return DecimalFormatSymbols.getInstance(Locale.ROOT);
    }
}
