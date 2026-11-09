package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.organisation.api.OrganisationApi;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.domain.CivilDates;

/**
 * Renders the same ReportResult the screen shows -- never a separate query -- so an export cannot disagree with what
 * was on screen. Since 2026-11-09 the PDF and the workbook are in the company's letterhead and name who prepared them;
 * the CSV is data only, a zip when the report has several sections.
 */
@Component
public class ReportExporter {

    private final OrganisationApi organisation;

    public ReportExporter(OrganisationApi organisation) {
        this.organisation = organisation;
    }

    public record Export(byte[] bytes, String contentType, String fileName) {}

    public Export export(ReportResult result, String format) {
        return export(result, format, null);
    }

    public Export export(ReportResult result, String format, String preparedBy) {
        String base = result.key() + "-" + CivilDates.of(result.generatedAt());
        return switch (format == null ? "" : format) {
            case "xlsx" -> new Export(ReportXlsx.render(result, organisation.letterhead(), preparedBy),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", base + ".xlsx");
            case "csv" -> {
                ReportCsv.File file = ReportCsv.render(result);
                yield file.zip() ? new Export(file.bytes(), "application/zip", base + ".zip")
                    : new Export(file.bytes(), "text/csv", base + ".csv");
            }
            case "pdf" -> new Export(ReportPdf.render(result, organisation.letterhead(), preparedBy), "application/pdf",
                base + ".pdf");
            default -> throw new ReportParameterException("format must be xlsx, csv or pdf; got '" + format + "'");
        };
    }
}
