package tz.co.nlolo.lifeplatform.omnichannel;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument.Column;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument.Field;
import tz.co.nlolo.lifeplatform.omnichannel.domain.CustomerDocument.Kind;
import tz.co.nlolo.lifeplatform.omnichannel.domain.DocumentPdf;
import tz.co.nlolo.lifeplatform.omnichannel.domain.DocumentXlsx;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The customer documents (2026-10-07) render as a paged PDF table and a numeric Excel sheet. */
class CustomerDocumentRenderTest {

    /** A 40-row schedule: one month a row, the first three paid. Written to target/ to be looked at. */
    private static CustomerDocument schedule() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            boolean paid = i < 3;
            rows.add(Arrays.asList(String.valueOf(i + 1), LocalDate.of(2026, 11, 7).plusMonths(i), new BigDecimal("3500.00"),
                paid ? new BigDecimal("3500.00") : BigDecimal.ZERO, paid ? LocalDate.of(2026, 11, 2).plusMonths(i) : "",
                paid ? "MM-rtaohmxpdhfx" : "", paid ? "+255712345678" : "", paid ? "Paid" : "Upcoming",
                paid ? BigDecimal.ZERO : new BigDecimal("3500.00")));
        }
        return new CustomerDocument("Nlolo Life", "Premium payment schedule",
            List.of(new Field("Policyholder", "nlolo-group2"), new Field("Phone", "+255712345678"),
                new Field("Address", "Plot 12, Sinza, Ubungo, Dar es Salaam"), new Field("Policy number", "GRP-9E1BFD2A"),
                new Field("Product", "Nlolo Familia Group"), new Field("Premium", "TZS 3,500.00 a month"),
                new Field("Cover from", "07/10/2026"), new Field("Status", "ACTIVE"), new Field("Printed on", "07/10/2026")),
            List.of(new Column("No.", Kind.TEXT, 0.5f), new Column("Due date", Kind.DATE, 1.1f),
                new Column("Amount due (TZS)", Kind.MONEY, 1.3f), new Column("Paid (TZS)", Kind.MONEY, 1.3f),
                new Column("Paid on", Kind.DATE, 1.1f), new Column("Receipt ref", Kind.TEXT, 1.6f),
                new Column("Paid by", Kind.TEXT, 1.4f), new Column("Status", Kind.TEXT, 1.0f),
                new Column("Balance (TZS)", Kind.MONEY, 1.3f)),
            rows,
            List.of(new Field("Total charged", "TZS 140,000.00"), new Field("Total paid", "TZS 10,500.00"),
                new Field("Outstanding now", "TZS 0.00"), new Field("Still to come", "TZS 129,500.00"),
                new Field("Next payment", "TZS 3,500.00 due 07/02/2027")),
            List.of("A premium is paid when its balance is nil."), true);
    }

    @Test
    void aLongScheduleContinuesOverPagesWithItsHeaderAndPageNumbers() throws Exception {
        byte[] pdf = DocumentPdf.render(schedule());
        Files.write(Path.of("target", "sample-payment-schedule.pdf"), pdf);
        try (var doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Premium payment schedule", "nlolo-group2", "GRP-9E1BFD2A", "MM-rtaohmxpdhfx",
                "3,500.00", "Next payment", "Page 1 of 2", "Page 2 of 2");
            // The column labels repeat at the top of the second page.
            assertThat(text.split("Receipt ref", -1)).hasSize(3);
        }
    }

    @Test
    void theExcelCopyKeepsMoneyAsNumbersAndDatesAsDates() throws Exception {
        byte[] xlsx = DocumentXlsx.render(schedule());
        Files.write(Path.of("target", "sample-payment-schedule.xlsx"), xlsx);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Premium payment schedule");
            int header = -1;
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                var row = sheet.getRow(r);
                if (row != null && row.getCell(0) != null && "No.".equals(row.getCell(0).getStringCellValue())) header = r;
            }
            var first = sheet.getRow(header + 1);
            assertThat(first.getCell(2).getNumericCellValue()).isEqualTo(3500.0);
            assertThat(first.getCell(1).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 7));
            assertThat(first.getCell(5).getStringCellValue()).isEqualTo("MM-rtaohmxpdhfx");
        }
    }
}
