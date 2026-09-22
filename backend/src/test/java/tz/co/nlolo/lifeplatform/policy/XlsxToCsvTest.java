package tz.co.nlolo.lifeplatform.policy;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentCsvParser;
import tz.co.nlolo.lifeplatform.policy.domain.XlsxToCsv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conversion carries the whole hazard of accepting XLSX, so each hazard is a test.
 *
 * <p>The workbook is built here with POI rather than committed as a fixture: a binary
 * fixture nobody can read in a diff is a fixture nobody maintains, and the cell TYPES
 * are the point of every case below.
 */
class XlsxToCsvTest {

    private static final String[] HEADER = {
        "loan_account_number", "borrower_full_name", "borrower_date_of_birth", "borrower_sex",
        "borrower_national_id", "borrower_phone", "loan_principal_amount",
        "loan_term_months", "disbursement_date"
    };

    /** A workbook whose amounts are NUMERIC and whose dates are DATE-FORMATTED. */
    private static byte[] realisticWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper()
                .createDataFormat().getFormat("dd/mm/yyyy"));

            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADER.length; i++) header.createCell(i).setCellValue(HEADER[i]);

            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("LN-2026-00417");
            data.createCell(1).setCellValue("Amina Hassan Mwinyi");
            var dob = data.createCell(2);
            dob.setCellValue(java.sql.Date.valueOf(LocalDate.of(1988, 3, 14)));
            dob.setCellStyle(dateStyle);
            data.createCell(3).setCellValue("F");
            data.createCell(4).setCellValue("");
            data.createCell(5).setCellValue("");
            data.createCell(6).setCellValue(8500000.00);   // NUMERIC, not text
            data.createCell(7).setCellValue(48);
            var disbursed = data.createCell(8);
            disbursed.setCellValue(java.sql.Date.valueOf(LocalDate.of(2026, 6, 30)));
            disbursed.setCellStyle(dateStyle);

            // Formula residue below the data, as the real LOLC sheet carries.
            sheet.createRow(2);
            sheet.createRow(3).createCell(0).setCellValue("");

            workbook.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void aWorkbookBecomesCsvTheParserCanRead() throws Exception {
        String csv = XlsxToCsv.convert(new ByteArrayInputStream(realisticWorkbook()));

        var parsed = EnrolmentCsvParser.parse(new StringReader(csv));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(1);
        var row = parsed.rows().get(0);
        assertThat(row.loanAccountNumber()).isEqualTo("LN-2026-00417");
        assertThat(row.borrowerDateOfBirth()).isEqualTo(LocalDate.of(1988, 3, 14));
        assertThat(row.disbursementDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(row.loanTermMonths()).isEqualTo(48);
    }

    @Test
    void anAmountKeepsEveryCentAndNeverBecomesScientificNotation() throws Exception {
        // XLSX stores numbers as doubles. Read through getNumericCellValue and formatted
        // carelessly, 8500000.00 becomes 8499999.999999999 or 8.5E+6 -- and the parser
        // refuses scientific notation outright, so the row would be rejected.
        String csv = XlsxToCsv.convert(new ByteArrayInputStream(realisticWorkbook()));

        assertThat(csv).contains("8500000");
        assertThat(csv).doesNotContain("E+").doesNotContain("8499999");

        var parsed = EnrolmentCsvParser.parse(new StringReader(csv));
        assertThat(parsed.rows().get(0).loanPrincipalAmount()).isEqualByComparingTo("8500000");
    }

    @Test
    void aDateBecomesIsoRatherThanAnExcelSerial() throws Exception {
        String csv = XlsxToCsv.convert(new ByteArrayInputStream(realisticWorkbook()));

        // 46203 is 2026-06-30 to Excel and nothing at all to the parser.
        assertThat(csv).contains("2026-06-30").doesNotContain("46203");
    }

    @Test
    void rowsBelowTheDataAreDropped() throws Exception {
        String csv = XlsxToCsv.convert(new ByteArrayInputStream(realisticWorkbook()));

        // Header plus one data row, and neither of the two residue rows.
        assertThat(csv.lines().count()).isEqualTo(2);
    }

    @Test
    void aNumericAccountNumberKeepsItsDigits() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("loan_account_number");
            sheet.createRow(1).createCell(0).setCellValue(417000000000L);
            workbook.write(out);

            String csv = XlsxToCsv.convert(new ByteArrayInputStream(out.toByteArray()));

            assertThat(csv).contains("417000000000").doesNotContain("4.17");
        }
    }
}
