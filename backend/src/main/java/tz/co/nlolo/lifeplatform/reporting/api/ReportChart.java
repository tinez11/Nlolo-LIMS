package tz.co.nlolo.lifeplatform.reporting.api;

import java.util.List;

/**
 * The one chart a report draws (2026-11-09). Every value is the server's; the browser only places them.
 *
 * <ul>
 *   <li>A BRIDGE walks from a start figure to an end figure, one bar per thing that moved it ({@code bars}).</li>
 *   <li>A TREND draws lines over time ({@code series}), each a value per period; a forecast is a dashed line.</li>
 * </ul>
 *
 * @param kind        BRIDGE or TREND
 * @param description what the chart shows, in words, for a screen reader
 * @param unit        MONEY, COUNT or PERCENT -- how every value on it is written (2026-11-09, batch 4: a movement of
 *                    policies is not "12.00")
 */
public record ReportChart(String kind, String description, List<Bar> bars, List<Series> series, String unit) {

    public ReportChart {
        bars = bars == null ? List.of() : List.copyOf(bars);
        series = series == null ? List.of() : List.copyOf(series);
        unit = unit == null ? "MONEY" : unit;
    }

    public ReportChart(String kind, String description, List<Bar> bars) {
        this(kind, description, bars, List.of(), "MONEY");
    }

    public ReportChart(String kind, String description, List<Bar> bars, List<Series> series) {
        this(kind, description, bars, series, "MONEY");
    }

    /** @param kind START, UP, DOWN or END -- a start or end figure, or a step that raised or lowered it */
    public record Bar(String label, String value, String kind) {}

    /** One line. {@code dashed} for a projection; a point whose value is null is a gap. */
    public record Series(String label, boolean dashed, List<Point> points) {}

    /** @param period the period's label on the axis, the same across every series of the chart */
    public record Point(String period, String value) {}
}
