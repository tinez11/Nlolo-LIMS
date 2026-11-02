package tz.co.nlolo.lifeplatform.policy;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.XlsxToCsv;
import tz.co.nlolo.lifeplatform.policy.api.CreditLifePremiumBasis;
import tz.co.nlolo.lifeplatform.policy.domain.CreditLifePremium;
import tz.co.nlolo.lifeplatform.policy.domain.CreditLifeTemplateXlsx;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentCsvParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The credit-life file a lender fills in, built from the scheme's agreement (2026-10-30).
 *
 * <p>The assertion this class exists for is the first: the premium the lender sees in Excel, as they type, is the
 * premium the platform charges -- for every pricing rule, at the minimum and above it. A file whose own formula
 * disagreed with the insurer would turn every month's reconciliation into an argument.
 */
class CreditLifeTemplateXlsxTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    private static CreditLifeTemplateXlsx.Terms terms(CreditLifePremiumBasis basis) {
        return new CreditLifeTemplateXlsx.Terms("Mkombozi Bank", "CL-1234ABCD", "TZS", FROM, TO,
            new BigDecimal("0.6000"), basis, new BigDecimal("2000.00"), 18, 65, 12, 120, new BigDecimal("10"));
    }

    private static XSSFWorkbook open(byte[] bytes) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    private static byte[] bytes(Workbook workbook) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        return out.toByteArray();
    }

    /** Types one loan into Excel row {@code excelRow}, as a lender would: dates as dates, numbers as numbers. */
    private static void type(Sheet sheet, int excelRow, String name, LocalDate born, String amount, int term,
                             LocalDate start) {
        Row row = sheet.getRow(excelRow - 1);
        row.getCell(0).setCellValue(name);
        row.getCell(1).setCellValue(born);
        row.getCell(3).setCellValue(Double.parseDouble(amount));
        row.getCell(4).setCellValue(term);
        row.getCell(5).setCellValue(start);
    }

    private static String text(Workbook workbook, Cell cell) {
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        var value = evaluator.evaluate(cell);
        return value.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING ? value.getStringValue()
            : String.valueOf(value.getNumberValue());
    }

    // ---- the price ------------------------------------------------------------

    @Test
    void theWorkbooksPremiumIsThePlatformsPremiumForEveryRule() throws Exception {
        List<String> amounts = List.of("100000", "1500000", "10000000", "24999999.99");
        List<Integer> terms = List.of(12, 13, 18, 24, 36, 60, 120);
        for (CreditLifePremiumBasis basis : CreditLifePremiumBasis.values()) {
            try (XSSFWorkbook workbook = open(CreditLifeTemplateXlsx.build(terms(basis)))) {
                Sheet sheet = workbook.getSheet(CreditLifeTemplateXlsx.SHEET);
                FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
                int excelRow = 8;
                for (String amount : amounts) {
                    for (int term : terms) {
                        type(sheet, excelRow, "Borrower " + excelRow, LocalDate.of(1980, 5, 5), amount, term,
                            LocalDate.of(2026, 3, 1));
                        double inExcel = evaluator.evaluate(sheet.getRow(excelRow - 1).getCell(10)).getNumberValue();
                        BigDecimal charged = CreditLifePremium.forLoan(new BigDecimal(amount), term,
                            new BigDecimal("0.6000"), basis, new BigDecimal("2000.00"));
                        assertThat(BigDecimal.valueOf(inExcel))
                            .as("%s on %s over %d months", basis, amount, term)
                            .isEqualByComparingTo(charged);
                        excelRow++;
                    }
                }
            }
        }
    }

    @Test
    void theTopSaysThePriceInWordsAndAddsUpTheFile() throws Exception {
        try (XSSFWorkbook workbook = open(CreditLifeTemplateXlsx.build(
                terms(CreditLifePremiumBasis.WHOLE_YEARS_ROUNDED_UP)))) {
            Sheet sheet = workbook.getSheet(CreditLifeTemplateXlsx.SHEET);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue())
                .contains("Mkombozi Bank").contains("CL-1234ABCD").contains("1 Jan 2026 to 31 Dec 2026");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue())
                .contains("× 0.6% for each year").contains("at least TZS 2,000").contains("12 to 120 months")
                .contains("aged 18 to 65");

            type(sheet, 8, "Amina Hassan", LocalDate.of(1988, 3, 14), "10000000", 18, LocalDate.of(2026, 3, 1));
            type(sheet, 9, "Juma Rajabu", LocalDate.of(1990, 7, 2), "100000", 12, LocalDate.of(2026, 3, 2));
            // Refused (six months), so it is in none of the totals: they quote what will actually be charged.
            type(sheet, 10, "Neema Paulo", LocalDate.of(1995, 11, 9), "2000000", 6, LocalDate.of(2026, 3, 3));
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            evaluator.evaluateAll();
            Row values = sheet.getRow(4);
            assertThat(values.getCell(0).getNumericCellValue()).isEqualTo(2);           // borrowers
            assertThat(values.getCell(2).getNumericCellValue()).isEqualTo(10_100_000);  // loan amount
            assertThat(values.getCell(4).getNumericCellValue()).isEqualTo(122_000);     // 120,000 + the 2,000 minimum
            assertThat(values.getCell(6).getNumericCellValue()).isEqualTo(12_200);      // 10% commission
            assertThat(values.getCell(8).getNumericCellValue()).isEqualTo(122_000);     // the full premium is paid
        }
    }

    // ---- each row checks itself -------------------------------------------------

    @Test
    void theCheckColumnSaysWhatIsWrongBeforeTheFileIsSent() throws Exception {
        try (XSSFWorkbook workbook = open(CreditLifeTemplateXlsx.build(
                terms(CreditLifePremiumBasis.WHOLE_YEARS_ROUNDED_UP)))) {
            Sheet sheet = workbook.getSheet(CreditLifeTemplateXlsx.SHEET);
            type(sheet, 8, "Fine", LocalDate.of(1980, 1, 1), "1000000", 12, LocalDate.of(2026, 3, 1));
            type(sheet, 9, "Too old", LocalDate.of(1960, 1, 1), "1000000", 12, LocalDate.of(2026, 3, 1));
            type(sheet, 10, "Six months", LocalDate.of(1980, 1, 1), "1000000", 6, LocalDate.of(2026, 3, 1));
            type(sheet, 11, "Last year", LocalDate.of(1980, 1, 1), "1000000", 12, LocalDate.of(2025, 12, 1));
            sheet.getRow(11).getCell(0).setCellValue("No dates");
            type(sheet, 14, "Too young", LocalDate.of(2010, 1, 1), "1000000", 12, LocalDate.of(2026, 3, 1));

            assertThat(text(workbook, sheet.getRow(7).getCell(11))).isEqualTo("OK");
            assertThat(text(workbook, sheet.getRow(8).getCell(11))).isEqualTo("Older than 65 when the loan started");
            assertThat(text(workbook, sheet.getRow(13).getCell(11))).isEqualTo("Younger than 18 when the loan started");
            assertThat(text(workbook, sheet.getRow(9).getCell(11))).isEqualTo("Shorter than 12 months");
            assertThat(text(workbook, sheet.getRow(10).getCell(11))).startsWith("Started before the agreement");
            assertThat(text(workbook, sheet.getRow(11).getCell(11))).startsWith("Fill in");
            // An untouched row says nothing at all, rather than a column of complaints about empty rows.
            assertThat(text(workbook, sheet.getRow(20).getCell(11))).isEmpty();
        }
    }

    // ---- what the upload makes of it --------------------------------------------

    @Test
    void aFilledTemplateIsReadBackWithItsOwnRowNumbersAndPremiums() throws Exception {
        byte[] filled;
        try (XSSFWorkbook workbook = open(CreditLifeTemplateXlsx.build(
                terms(CreditLifePremiumBasis.WHOLE_YEARS_ROUNDED_UP)))) {
            Sheet sheet = workbook.getSheet(CreditLifeTemplateXlsx.SHEET);
            type(sheet, 8, "Amina Hassan", LocalDate.of(1988, 3, 14), "10000000", 18, LocalDate.of(2026, 3, 1));
            type(sheet, 10, "Juma Rajabu", LocalDate.of(1990, 7, 2), "100000", 12, LocalDate.of(2026, 3, 2));
            filled = bytes(workbook);
        }

        String csv = XlsxToCsv.convertSchedule(new ByteArrayInputStream(filled), EnrolmentCsvParser::looksLikeHeader);
        var parsed = EnrolmentCsvParser.parse(new StringReader(csv));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(2);
        // The rows the lender sees in Excel, so a refusal points at the right one.
        assertThat(parsed.rows()).extracting(r -> r.lineNumber()).containsExactly(8, 10);
        var amina = parsed.rows().get(0);
        assertThat(amina.borrowerDateOfBirth()).isEqualTo(LocalDate.of(1988, 3, 14));
        assertThat(amina.loanPrincipalAmount()).isEqualByComparingTo("10000000");
        assertThat(amina.loanTermMonths()).isEqualTo(18);
        assertThat(amina.disbursementDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        // The workbook's own premium, worked out on upload even though nobody opened the file in Excel.
        assertThat(amina.statedPremiumAmount()).isEqualByComparingTo("120000");
        assertThat(parsed.rows().get(1).statedPremiumAmount()).isEqualByComparingTo("2000");
    }

    @Test
    void anUntouchedTemplateHoldsNobody() throws Exception {
        String csv = XlsxToCsv.convertSchedule(new ByteArrayInputStream(CreditLifeTemplateXlsx.build(
            terms(CreditLifePremiumBasis.PER_ANNUM_ON_PRINCIPAL))), EnrolmentCsvParser::looksLikeHeader);
        var parsed = EnrolmentCsvParser.parse(new StringReader(csv));
        assertThat(parsed.rows()).isEmpty();
        assertThat(parsed.errors()).isEmpty();
    }

    @Test
    void aLendersOwnWorkbookIsReadBehindItsSummarySheetAndAboveItsTotal() throws Exception {
        // The shape of the lenders' real file: a Summary sheet first, a title block, the header on row 7 in their
        // own words, and a TOTAL row under the borrowers.
        byte[] theirs;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Summary").createRow(0).createCell(0).setCellValue("TOTAL CLIENTS");
            XSSFSheet sheet = workbook.createSheet("Policy Declaration");
            sheet.createRow(0).createCell(0).setCellValue("CREDIT LIFE POLICY DECLARATION");
            sheet.createRow(5).createCell(0).setCellValue("REPORTING DATE");
            String[] header = { "COMPANY NAME", "CLIENT NAME", "DATE OF BIRTH", "GENDER", "POLICY TERM (MONTHS)",
                "SUM ASSURED (TZS)", "PREMIUM (TZS)", "START DATE", "END DATE" };
            Row h = sheet.createRow(6);
            for (int c = 0; c < header.length; c++) h.createCell(c).setCellValue(header[c]);
            var dates = workbook.createCellStyle();
            dates.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
            Row r = sheet.createRow(7);
            r.createCell(0).setCellValue("Bumaco");
            r.createCell(1).setCellValue("GRACE SHIRIMA");
            r.createCell(2).setCellValue(LocalDate.of(1992, 6, 21));
            r.getCell(2).setCellStyle(dates);
            r.createCell(3).setCellValue("F");
            r.createCell(4).setCellValue(12);
            r.createCell(5).setCellValue(3200000);
            r.createCell(6).setCellFormula("MAX(2000,F8*0.006*ROUNDUP(E8/12,0))");
            r.createCell(7).setCellValue(LocalDate.of(2026, 8, 3));
            r.getCell(7).setCellStyle(dates);
            Row total = sheet.createRow(8);
            total.createCell(1).setCellValue("TOTAL");
            total.createCell(5).setCellFormula("SUM(F8:F8)");
            total.createCell(6).setCellFormula("SUM(G8:G8)");
            theirs = bytes(workbook);
        }

        String csv = XlsxToCsv.convertSchedule(new ByteArrayInputStream(theirs), EnrolmentCsvParser::looksLikeHeader);
        var parsed = EnrolmentCsvParser.parse(new StringReader(csv));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).singleElement().satisfies(row -> {
            assertThat(row.borrowerFullName()).isEqualTo("GRACE SHIRIMA");
            assertThat(row.borrowerDateOfBirth()).isEqualTo(LocalDate.of(1992, 6, 21));
            assertThat(row.loanPrincipalAmount()).isEqualByComparingTo("3200000");
            assertThat(row.disbursementDate()).isEqualTo(LocalDate.of(2026, 8, 3));
            assertThat(row.statedPremiumAmount()).isEqualByComparingTo("19200");
            assertThat(row.lineNumber()).isEqualTo(8);
        });
    }

    @Test
    void aWorkbookWithNoBorrowersTableAnywhereIsSaidSo() throws Exception {
        byte[] wrong;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Notes").createRow(0).createCell(0).setCellValue("hello");
            wrong = bytes(workbook);
        }
        assertThat(XlsxToCsv.convertSchedule(new ByteArrayInputStream(wrong), EnrolmentCsvParser::looksLikeHeader))
            .isNull();
    }

    // ---- what the lender sees -------------------------------------------------------

    @Test
    void theBorrowersComeFirstWithTheGuideBesideThem() throws Exception {
        try (XSSFWorkbook workbook = open(CreditLifeTemplateXlsx.build(
                terms(CreditLifePremiumBasis.WHOLE_YEARS_ROUNDED_UP)))) {
            assertThat(workbook.getSheetName(0)).isEqualTo("Borrowers");
            assertThat(workbook.getSheetName(1)).isEqualTo("How to fill this in");
            String guide = StreamSupport.stream(workbook.getSheet("How to fill this in").spliterator(), false)
                .flatMap(row -> StreamSupport.stream(row.spliterator(), false))
                .map(Cell::getStringCellValue).reduce("", (a, b) -> a + " " + b);
            // The worked example is the platform's own price: 10,000,000 over 18 months at 0.6% is two years.
            assertThat(guide).contains("TZS 10,000,000 for 18 months pays TZS 120,000");
            assertThat(guide).contains("THAT BORROWER IS NOT COVERED");
            assertThat(guide).contains("Do not save it as CSV");
        }
    }

    /**
     * The BUMACO May sheet pasted in as the bank holds it (2026-11-02): every date is TEXT, day first, which no date
     * formula reads -- every row said "has not started yet" and every end date was #VALUE!. Read day first, they check
     * OK, price as the bank's own sheet does, and come back through the upload unchanged.
     */
    @Test
    void rowsPastedFromALendersSheetWithTextDatesCheckPriceAndUploadAsTheyShould() throws Exception {
        var bumaco = new CreditLifeTemplateXlsx.Terms("BUMACO", "GRP-BUMACO", "TZS", LocalDate.of(2026, 5, 1),
            LocalDate.of(2027, 4, 30), new BigDecimal("0.6000"), CreditLifePremiumBasis.FLAT_ON_PRINCIPAL, null, 18, 70,
            1, 120, new BigDecimal("15"));
        byte[] filled;
        try (XSSFWorkbook workbook = open(CreditLifeTemplateXlsx.build(bumaco))) {
            Sheet sheet = workbook.getSheet(CreditLifeTemplateXlsx.SHEET);
            String[][] may = {
                { "JULIUS MBASHANGO MALUNDE", "12/05/1960", "1500000", "4", "18/05/2026" },
                { "GAUDENCE PETTER CHAMI", "23/04/1966", "6000000", "12", "18-05-2026" },
                { "CONSTANTINE LEONARD MALIPESA", "20.09.1962", "1000000", "2", "18/05/2026" },
            };
            for (int i = 0; i < may.length; i++) {
                Row row = sheet.getRow(CreditLifeTemplateXlsx.HEADER_ROW + 1 + i);
                row.getCell(0).setCellValue(may[i][0]);
                row.getCell(1).setCellValue(may[i][1]);
                row.getCell(3).setCellValue(Double.parseDouble(may[i][2]));
                row.getCell(4).setCellValue(Integer.parseInt(may[i][3]));
                row.getCell(5).setCellValue(may[i][4]);
            }
            Row julius = sheet.getRow(CreditLifeTemplateXlsx.HEADER_ROW + 1);
            assertThat(text(workbook, julius.getCell(11))).isEqualTo("OK");
            assertThat(text(workbook, julius.getCell(10))).isEqualTo("9000.0");
            // 18 May plus four months, less a day.
            assertThat(text(workbook, julius.getCell(9))).isEqualTo(String.valueOf(
                (double) org.apache.poi.ss.usermodel.DateUtil.getExcelDate(LocalDate.of(2026, 9, 17))));
            assertThat(text(workbook, sheet.getRow(CreditLifeTemplateXlsx.HEADER_ROW + 2).getCell(11))).isEqualTo("OK");
            assertThat(text(workbook, sheet.getRow(CreditLifeTemplateXlsx.HEADER_ROW + 3).getCell(11))).isEqualTo("OK");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            filled = out.toByteArray();
        }

        var parsed = EnrolmentCsvParser.parse(new StringReader(
            XlsxToCsv.convertSchedule(new ByteArrayInputStream(filled), EnrolmentCsvParser::looksLikeHeader)));
        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(3);
        assertThat(parsed.rows().get(0).borrowerDateOfBirth()).isEqualTo(LocalDate.of(1960, 5, 12));
        assertThat(parsed.rows().get(1).disbursementDate()).isEqualTo(LocalDate.of(2026, 5, 18));
        assertThat(parsed.rows().get(2).borrowerDateOfBirth()).isEqualTo(LocalDate.of(1962, 9, 20));
    }

    @Test
    void theSheetIsNeverProtectedSoAPastedBlockCanBeCorrectedAndTheHeaderStaysInView() throws Exception {
        try (XSSFWorkbook workbook = open(CreditLifeTemplateXlsx.build(
                terms(CreditLifePremiumBasis.WHOLE_YEARS_ROUNDED_UP)))) {
            XSSFSheet sheet = workbook.getSheet(CreditLifeTemplateXlsx.SHEET);
            // A block pasted from a bank's sheet carries locked formatting: under protection it could not be edited.
            assertThat(sheet.getProtect()).isFalse();
            Row first = sheet.getRow(CreditLifeTemplateXlsx.HEADER_ROW + 1);
            for (int typed = 0; typed <= 8; typed++) {
                assertThat(first.getCell(typed).getCellStyle().getLocked()).as("column %d", typed).isFalse();
            }
            for (int filled = 9; filled <= 11; filled++) {
                assertThat(first.getCell(filled).getCellStyle().getLocked()).as("column %d", filled).isTrue();
            }
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet.getDataValidations()).hasSizeGreaterThanOrEqualTo(5);
        }
    }

}
