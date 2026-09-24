package tz.co.nlolo.lifeplatform.policy.domain;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The enrolment template as a spreadsheet, because the CSV one cannot survive Excel.
 *
 * <p><b>This exists because of two real files that were refused entire.</b> A lender filled in the
 * CSV template and sent it back with {@code 1-Sep-00} in every date; told to use YYYY-MM-DD, they
 * filled it in again and sent back {@code 9/1/2000}. Neither was carelessness. Excel rewrites a
 * date the moment it opens a CSV and again when it saves one, and it rewrites the worked example
 * we put there along with everything else — so the example row, whose whole job was to show the
 * format, arrived back in the wrong format too.
 *
 * <p><b>A spreadsheet has no such problem, because a date in it is not text.</b> It is a typed
 * cell, Excel round-trips it whatever it chooses to display, and {@link XlsxToCsv} already reads
 * one back as ISO through {@code DateUtil.isCellDateFormatted}. The enrolment endpoint has
 * accepted XLSX since it was written; nothing was handing one out.
 *
 * <p>The CSV template stays, for a lender whose loan system exports one and who never opens it in
 * a spreadsheet at all. It is the second option now rather than the only one.
 */
public final class EnrolmentTemplateXlsx {

    /** What a borrower looks like, as the sheet needs them: typed, not text. */
    public record ExampleRow(String memberReference, String borrowerFullName,
                              LocalDate borrowerDateOfBirth, BigDecimal loanPrincipalAmount,
                              int loanTermMonths, LocalDate disbursementDate,
                              String loanAccountNumber) {}

    private EnrolmentTemplateXlsx() {}

    public static byte[] build(ExampleRow example) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("Borrowers");

            CreationHelper helper = workbook.getCreationHelper();
            CellStyle dateStyle = workbook.createCellStyle();
            // ISO in the DISPLAY too, so a person reading the sheet sees the same shape the
            // platform stores. The cell is a date either way -- this only decides what Excel
            // shows -- but a lender who then copies the file into a CSV by hand gets it right.
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));

            CellStyle headerStyle = workbook.createCellStyle();
            var bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            String[] headers = EnrolmentCsvParser.templateCsv().strip().split(",");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            if (example != null) {
                Row row = sheet.createRow(1);
                text(row, 0, example.memberReference());
                text(row, 1, example.borrowerFullName());
                date(row, 2, example.borrowerDateOfBirth(), dateStyle);
                // sex, national id, phone: left empty, all three optional.
                if (example.loanPrincipalAmount() != null) {
                    row.createCell(6).setCellValue(example.loanPrincipalAmount().doubleValue());
                }
                row.createCell(7).setCellValue(example.loanTermMonths());
                date(row, 8, example.disbursementDate(), dateStyle);
                text(row, 9, example.loanAccountNumber());
            }

            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the enrolment template", e);
        }
    }

    private static void text(Row row, int column, String value) {
        if (value != null && !value.isBlank()) {
            row.createCell(column).setCellValue(value);
        }
    }

    private static void date(Row row, int column, LocalDate value, CellStyle style) {
        if (value == null) {
            return;
        }
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }
}
