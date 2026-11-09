package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A report as data for another system (2026-11-09, report formatting): one table per file, one heading row and nothing
 * above it, figures as plain numbers -- so it imports into a spreadsheet's pivot table or another tool without editing.
 * The title, filters and notices belong to the PDF and the workbook. A report of one section is one CSV; of several, a
 * zip of one CSV per section.
 */
final class ReportCsv {
    private ReportCsv() {}

    /** The file and what it is. */
    record File(byte[] bytes, boolean zip) {}

    static File render(ReportResult result) {
        if (result.sections().size() == 1) {
            return new File(csv(result.sections().get(0)).getBytes(StandardCharsets.UTF_8), false);
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
            Set<String> names = new HashSet<>();
            for (ReportSection section : result.sections()) {
                zip.putNextEntry(new ZipEntry(uniqueName(section.title(), names) + ".csv"));
                zip.write(csv(section).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return new File(out.toByteArray(), true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One section: a heading row, then its rows; the total row last, as a row like any other. */
    static String csv(ReportSection section) {
        List<ReportColumn> columns = section.columns().stream().filter(c -> !c.key().startsWith("_")).toList();
        StringWriter out = new StringWriter();
        try (CSVPrinter csv = new CSVPrinter(out, CSVFormat.DEFAULT)) {
            csv.printRecord(columns.stream().map(ReportColumn::label).toList());
            for (Map<String, String> row : section.rows()) {
                csv.printRecord(columns.stream().map(c -> row.getOrDefault(c.key(), "")).toList());
            }
            if (!section.totals().isEmpty()) {
                csv.printRecord(columns.stream().map(c -> section.totals().getOrDefault(c.key(), "")).toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    /** "Policies in force on 2026-11-09" as policies-in-force-on-2026-11-09, unique within the zip. */
    private static String uniqueName(String title, Set<String> taken) {
        String base = title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isEmpty()) base = "section";
        if (base.length() > 60) base = base.substring(0, 60);
        String name = base;
        for (int i = 2; !taken.add(name); i++) name = base + "-" + i;
        return name;
    }
}
