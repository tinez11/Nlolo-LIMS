package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.domain.CivilDates;

/** Renders the same ReportResult the screen shows -- never a separate query -- so an export cannot disagree
 * with what was on screen. */
@Component
public class ReportExporter {

    public record Export(byte[] bytes, String contentType, String fileName) {}

    public Export export(ReportResult result, String format) {
        String base = result.key() + "-" + CivilDates.of(result.generatedAt());
        return switch (format == null ? "" : format) {
            case "xlsx" -> new Export(ReportXlsx.render(result),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", base + ".xlsx");
            case "csv" -> new Export(ReportCsv.render(result), "text/csv", base + ".csv");
            case "pdf" -> new Export(ReportPdf.render(result), "application/pdf", base + ".pdf");
            default -> throw new ReportParameterException("format must be xlsx, csv or pdf; got '" + format + "'");
        };
    }
}
