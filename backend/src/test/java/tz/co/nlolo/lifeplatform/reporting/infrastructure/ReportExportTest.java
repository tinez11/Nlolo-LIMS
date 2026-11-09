package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.organisation.api.BranchView;
import tz.co.nlolo.lifeplatform.organisation.api.DocumentKind;
import tz.co.nlolo.lifeplatform.organisation.api.Letterhead;
import tz.co.nlolo.lifeplatform.organisation.api.OrganisationApi;
import tz.co.nlolo.lifeplatform.organisation.api.SignatureStamp;
import tz.co.nlolo.lifeplatform.reporting.api.ColumnType;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Report exports (2026-11-09, report formatting): branded PDF and workbook, data-only CSV. */
class ReportExportTest {

    private static final Letterhead COMPANY = new Letterhead("Bima Bora Life Assurance Ltd", "Bima Bora", "TIRA/LI/021",
        "100-200-300", "Plot 12, Samora Avenue, Dar es Salaam", "+255 22 211 0000", "care@bimabora.co.tz", null, null,
        "#17324D", "#0E8C7F", "SANS", "BANDED", "A4", null, "Confidential: board pack.", null);

    private final ReportExporter exporter = new ReportExporter(new OrganisationApi() {
        @Override public Letterhead letterhead() { return COMPANY; }
        @Override public Optional<SignatureStamp> signatory(DocumentKind kind, LocalDate onDay) { return Optional.empty(); }
        @Override public List<BranchView> branches() { return List.of(); }
        @Override public boolean isActiveBranch(String code) { return true; }
        @Override public String headOfficeBranch() { return "DSM"; }
    });

    private static ReportResult sample(boolean twoSections) {
        List<ReportColumn> cols = List.of(new ReportColumn("dimension", "Product", ColumnType.TEXT),
            new ReportColumn("policies", "Policies", ColumnType.COUNT),
            new ReportColumn("sumAssured", "Sum assured", ColumnType.MONEY),
            new ReportColumn("change", "Change", ColumnType.MONEY),
            new ReportColumn("rate", "Take-up %", ColumnType.PERCENT));
        Map<String, String> row = new LinkedHashMap<>();
        row.put("dimension", "Family Funeral, Gold -- a product whose name runs long enough to need a second line");
        row.put("policies", "1200");
        row.put("sumAssured", "36000000.00");
        row.put("change", "-1500.50");
        row.put("rate", "12.5");
        Map<String, String> totals = new LinkedHashMap<>(row);
        totals.put("dimension", "Total");
        List<ReportSection> sections = new ArrayList<>(List.of(
            new ReportSection("Policies in force on 2026-10-11", cols, List.of(row), totals)));
        if (twoSections) sections.add(new ReportSection("Second section", cols, List.of(), Map.of()));
        return new ReportResult("policies-in-force", "Policies in force", "As of 2026-10-11 · Grouped by Product",
            Instant.parse("2026-10-11T08:00:00Z"), sections,
            List.of(new ReportFigure("policies", "Policies in force", "1200", "1000", "200", "20.0", "UP", true, "COUNT")),
            null, "compared with 2026-09-11", List.of("Placeholder risk charges, not TIRA's."));
    }

    @Test
    void theWorkbookOpensOnABrandedSummaryAndKeepsFiguresAsNumbers() throws Exception {
        ReportExporter.Export export = exporter.export(sample(true), "xlsx", "Asha Admin");
        assertThat(export.fileName()).isEqualTo("policies-in-force-2026-10-11.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(export.bytes()))) {
            assertThat(wb.getSheetName(0)).isEqualTo("Summary");
            Sheet summary = wb.getSheetAt(0);
            StringBuilder text = new StringBuilder();
            summary.forEach(r -> r.forEach(c -> text.append(c.toString()).append('\n')));
            assertThat(text).contains("Bima Bora", "Policies in force", "Prepared by Asha Admin on 11 Oct 2026, 11:00",
                "Placeholder risk charges, not TIRA's.", "1,200", "up 20.0% from 1,000");
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);

            Sheet sheet = wb.getSheetAt(1);
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Product");
            assertThat(sheet.getRow(3).getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(3).getCell(2).getNumericCellValue()).isEqualTo(36000000.00);
            assertThat(sheet.getRow(3).getCell(4).getNumericCellValue()).isEqualTo(0.125);
            assertThat(sheet.getRow(3).getCell(4).getCellStyle().getDataFormatString()).isEqualTo("0.0%");
            assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("Total");
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 3);
            assertThat(sheet.getPrintSetup().getLandscape()).isTrue();
        }
    }

    @Test
    void aOneSectionCsvIsDataOnlyAndASeveralSectionCsvIsAZip() throws Exception {
        ReportExporter.Export one = exporter.export(sample(false), "csv");
        assertThat(one.contentType()).isEqualTo("text/csv");
        String csv = new String(one.bytes(), StandardCharsets.UTF_8);
        // The heading row first, nothing above it: a pivot table imports it as it is.
        assertThat(csv).startsWith("Product,Policies,Sum assured,Change,Take-up %");
        assertThat(csv).doesNotContain("As of 2026-10-11").contains(",1200,36000000.00,-1500.50,12.5");

        ReportExporter.Export two = exporter.export(sample(true), "csv");
        assertThat(two.contentType()).isEqualTo("application/zip");
        assertThat(two.fileName()).endsWith(".zip");
        List<String> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(two.bytes()))) {
            for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) entries.add(e.getName());
        }
        assertThat(entries).containsExactly("policies-in-force-on-2026-10-11.csv", "second-section.csv");
    }

    @Test
    void thePdfIsInTheLetterheadWithFiguresWrittenInTheirUnit() throws Exception {
        ReportExporter.Export export = exporter.export(sample(true), "pdf", "Asha Admin");
        assertThat(export.contentType()).isEqualTo("application/pdf");
        try (PDDocument doc = Loader.loadPDF(export.bytes())) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Bima Bora", "TIRA reg. TIRA/LI/021", "Policies in force", "As of 2026-10-11",
                "Prepared by Asha Admin on 11 Oct 2026, 11:00", "Placeholder risk charges, not TIRA's.",
                "36,000,000.00", "(1,500.50)", "12.5%", "1,200", "up 20.0% from 1,000",
                "Confidential: board pack.", "Page 1 of");
            // Long text wraps rather than being cut off.
            assertThat(text).contains("second line").doesNotContain("~");
        }
    }

    @Test
    void anUnknownFormatIsRefused() {
        assertThatThrownBy(() -> exporter.export(sample(true), "docx")).isInstanceOf(ReportParameterException.class);
    }
}
