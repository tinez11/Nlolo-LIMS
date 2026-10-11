package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.reporting.api.ColumnType;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportExportTest {

    private static ReportResult sample() {
        List<ReportColumn> cols = List.of(new ReportColumn("dimension", "Product", ColumnType.TEXT),
            new ReportColumn("policies", "Policies", ColumnType.COUNT),
            new ReportColumn("sumAssured", "Sum assured", ColumnType.MONEY));
        Map<String, String> row = new LinkedHashMap<>();
        row.put("dimension", "Family Funeral, Gold");
        row.put("policies", "12");
        row.put("sumAssured", "36000000.00");
        Map<String, String> totals = new LinkedHashMap<>(row);
        totals.put("dimension", "Total");
        return new ReportResult("policies-in-force", "Policies in force", "As of 2026-10-11 · Grouped by Product",
            Instant.parse("2026-10-11T08:00:00Z"), List.of(
                new ReportSection("Policies in force on 2026-10-11", cols, List.of(row), totals),
                new ReportSection("Second section", cols, List.of(), Map.of())));
    }

    private final ReportExporter exporter = new ReportExporter();

    @Test
    void xlsxHasAnAboutSheetAndOneSheetPerSectionWithNumericFigures() throws Exception {
        ReportExporter.Export export = exporter.export(sample(), "xlsx");
        assertThat(export.contentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(export.fileName()).isEqualTo("policies-in-force-2026-10-11.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(export.bytes()))) {
            assertThat(wb.getSheetName(0)).isEqualTo("About");
            assertThat(wb.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).contains("As of 2026-10-11");
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            Sheet sheet = wb.getSheetAt(1);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Product");
            assertThat(sheet.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(36000000.00);
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Total");
        }
    }

    @Test
    void csvQuotesValuesAndSeparatesSections() {
        String csv = new String(exporter.export(sample(), "csv").bytes(), StandardCharsets.UTF_8);
        assertThat(csv).contains("Policies in force on 2026-10-11").contains("Product,Policies,Sum assured")
            .contains("\"Family Funeral, Gold\",12,36000000.00").contains("Total,12,36000000.00").contains("Second section");
    }

    @Test
    void pdfCarriesTheTitleSummaryAndFigures() throws Exception {
        ReportExporter.Export export = exporter.export(sample(), "pdf");
        assertThat(export.contentType()).isEqualTo("application/pdf");
        try (PDDocument doc = Loader.loadPDF(export.bytes())) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Policies in force").contains("As of 2026-10-11").contains("36000000.00");
        }
    }

    @Test
    void anUnknownFormatIsRefused() {
        assertThatThrownBy(() -> exporter.export(sample(), "docx")).isInstanceOf(ReportParameterException.class);
    }
}
