package tz.co.nlolo.lifeplatform.reporting.api;

import java.util.List;
import java.util.Map;

/**
 * What a report line is made of (2026-11-09, the user: show the computation "until it reaches the figure summed up").
 * A statement line opens to its accounts, an account to its sub-accounts or its postings.
 *
 * @param rows     one page of parts, keyed by column; the reserved keys {@code _lineId}, {@code _hasParts},
 *                 {@code _level}, {@code _kind}, {@code _goodWhen} and {@code _source} are for the table, never columns
 * @param total    how many parts there are in all (a postings list is paged)
 * @param check    the server's sum statement over ALL the parts, e.g. "1,102.00 + 96.30 + 42.20 = 1,240.50"
 * @param balanced whether the parts add up to the line -- shown, never hidden, when they do not
 */
public record ReportParts(String lineId, String label, List<ReportColumn> columns, List<Map<String, String>> rows,
                          long total, int page, int size, String check, boolean balanced) {}
