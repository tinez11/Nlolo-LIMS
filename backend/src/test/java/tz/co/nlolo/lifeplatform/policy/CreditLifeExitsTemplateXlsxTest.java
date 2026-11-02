package tz.co.nlolo.lifeplatform.policy;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.XlsxToCsv;
import tz.co.nlolo.lifeplatform.policy.api.ExitReason;
import tz.co.nlolo.lifeplatform.policy.domain.CreditLifeExitsTemplateXlsx;
import tz.co.nlolo.lifeplatform.policy.domain.ExitCsvParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The loans-ending file a lender fills in (2026-11-01). The joining file is {@link CreditLifeTemplateXlsxTest}'s.
 *
 * <p>Why any of this is in a workbook: it is the only artefact that reaches the lender. There is no portal, so
 * everything the console knows about the format reaches them only if it is in the file.
 */
class CreditLifeExitsTemplateXlsxTest {

    private static XSSFWorkbook open(byte[] bytes) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    private static byte[] template() {
        return CreditLifeExitsTemplateXlsx.build("Mkombozi Bank", "GRP-1234ABCD", "TZS");
    }

    private static void type(Sheet sheet, int excelRow, String account, LocalDate ended, String why) {
        Row row = sheet.getRow(excelRow - 1);
        if (account != null) row.getCell(0).setCellValue(account);
        if (ended != null) row.getCell(2).setCellValue(ended);
        if (why != null) row.getCell(3).setCellValue(why);
    }

    private static String text(Workbook workbook, Cell cell) {
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        var value = evaluator.evaluate(cell);
        return value == null ? "" : value.getStringValue();
    }

    @Test
    void theFileFilledInIsReadBackRowForRowInTheLendersOwnRowNumbers() throws Exception {
        byte[] filled;
        try (XSSFWorkbook workbook = open(template())) {
            Sheet sheet = workbook.getSheet(CreditLifeExitsTemplateXlsx.SHEET);
            type(sheet, 8, "LN-0417", LocalDate.of(2026, 10, 3), "Settled early");
            type(sheet, 9, "LN-0512", LocalDate.of(2026, 10, 9), "Written off");
            sheet.getRow(8).getCell(4).setCellValue(350000);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            filled = out.toByteArray();
        }

        String csv = XlsxToCsv.convertSchedule(new ByteArrayInputStream(filled), ExitCsvParser::looksLikeHeader);
        var parsed = ExitCsvParser.parse(new StringReader(csv));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0).loanAccountNumber()).isEqualTo("LN-0417");
        assertThat(parsed.rows().get(0).memberReference()).isNull();
        assertThat(parsed.rows().get(0).exitDate()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(parsed.rows().get(0).exitReason()).isEqualTo(ExitReason.SETTLED_EARLY);
        assertThat(parsed.rows().get(0).lineNumber()).isEqualTo(8);
        assertThat(parsed.rows().get(1).outstandingBalanceAtExit()).isEqualByComparingTo("350000");
    }

    @Test
    void theCheckColumnSaysWhatIsWrongBeforeTheFileIsSent() throws Exception {
        try (XSSFWorkbook workbook = open(template())) {
            Sheet sheet = workbook.getSheet(CreditLifeExitsTemplateXlsx.SHEET);
            type(sheet, 8, "LN-1", LocalDate.of(2026, 10, 3), "Refinanced");
            type(sheet, 9, null, LocalDate.of(2026, 10, 3), "Refinanced");
            type(sheet, 10, "LN-3", null, "Refinanced");
            type(sheet, 11, "LN-4", LocalDate.now().plusDays(3), "Refinanced");
            type(sheet, 12, "LN-5", LocalDate.of(2026, 10, 3), null);

            assertThat(text(workbook, sheet.getRow(7).getCell(6))).isEqualTo("OK");
            assertThat(text(workbook, sheet.getRow(8).getCell(6))).isEqualTo("Give the loan account no.");
            assertThat(text(workbook, sheet.getRow(9).getCell(6))).isEqualTo("Fill in the date the loan ended");
            assertThat(text(workbook, sheet.getRow(10).getCell(6))).isEqualTo("That date has not come yet");
            assertThat(text(workbook, sheet.getRow(11).getCell(6))).isEqualTo("Choose why it ended");
            // An untouched row says nothing at all.
            assertThat(text(workbook, sheet.getRow(30).getCell(6))).isEmpty();
        }
    }

    @Test
    void theListOffersTheFourReasonsALenderMayStateAndNeverAClaim() throws Exception {
        try (XSSFWorkbook workbook = open(template())) {
            Sheet guide = workbook.getSheet(CreditLifeExitsTemplateXlsx.GUIDE);
            String words = StreamSupport.stream(guide.spliterator(), false)
                .flatMap(row -> StreamSupport.stream(row.spliterator(), false))
                .map(Cell::getStringCellValue)
                .reduce("", (a, b) -> a + " " + b);

            assertThat(words).contains("Settled early, Refinanced, Written off, Cancelled");
            // A death is not a lender-stated exit: it would suppress the refund and the clawback on a loan merely repaid.
            assertThat(words).doesNotContainIgnoringCase("claim settled");
            assertThat(words).contains("Mkombozi Bank").contains("GRP-1234ABCD");
            assertThat(workbook.getSheet(CreditLifeExitsTemplateXlsx.SHEET).getDataValidations()).hasSize(3);
        }
    }
}
