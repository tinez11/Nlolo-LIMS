package tz.co.nlolo.lifeplatform.reporting.api;

/**
 * One headline figure of a report, with its comparison -- every value already formatted by the server, which is the
 * only place a sum, a change or a percentage is worked out (DESIGN.md: never in the browser).
 *
 * @param prior         the compared figure, or null when the run compares with nothing
 * @param change        current minus prior, signed ("-2.40"), or null
 * @param changePercent the change as a percentage of the prior's size, one decimal ("6.2"), or null when the prior is
 *                      zero or there is no comparison
 * @param goodWhen      UP when a rise is good news for the insurer, DOWN when it is bad; null when neither
 * @param lead          on the figures band (the answer the report exists for), rather than a tile
 */
public record ReportFigure(String key, String label, String value, String prior, String change, String changePercent,
                           String goodWhen, boolean lead) {}
