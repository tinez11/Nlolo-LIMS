package tz.co.nlolo.lifeplatform.reporting.api;

import java.util.List;

/**
 * The one chart a report draws (2026-11-09). A BRIDGE walks from a start figure to an end figure, one bar per thing
 * that moved it. Every bar's value is the server's; the browser only places them.
 *
 * @param kind        BRIDGE
 * @param description what the chart shows, in words, for a screen reader
 */
public record ReportChart(String kind, String description, List<Bar> bars) {

    /** @param kind START, UP, DOWN or END -- a start or end figure, or a step that raised or lowered it */
    public record Bar(String label, String value, String kind) {}
}
