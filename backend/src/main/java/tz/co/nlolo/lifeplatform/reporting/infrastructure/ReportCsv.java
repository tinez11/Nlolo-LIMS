package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** One file: the title and filters, then each section as its title, a header row, the rows, the total row
 * and a blank line. */
final class ReportCsv {
    private ReportCsv() {}

    static byte[] render(ReportResult result) {
        StringWriter out = new StringWriter();
        try (CSVPrinter csv = new CSVPrinter(out, CSVFormat.DEFAULT)) {
            csv.printRecord(result.title());
            csv.printRecord(result.filterSummary());
            csv.println();
            for (ReportSection section : result.sections()) {
                csv.printRecord(section.title());
                csv.printRecord(section.columns().stream().map(ReportColumn::label).toList());
                for (Map<String, String> row : section.rows()) {
                    csv.printRecord(section.columns().stream().map(c -> row.getOrDefault(c.key(), "")).toList());
                }
                if (!section.totals().isEmpty()) {
                    csv.printRecord(section.columns().stream().map(c -> section.totals().getOrDefault(c.key(), "")).toList());
                }
                csv.println();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
}
