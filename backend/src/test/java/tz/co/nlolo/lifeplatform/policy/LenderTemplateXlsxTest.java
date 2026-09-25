package tz.co.nlolo.lifeplatform.policy;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentCsvParser;
import tz.co.nlolo.lifeplatform.policy.domain.ExitCsvParser;
import tz.co.nlolo.lifeplatform.policy.domain.LenderTemplateXlsx;
import tz.co.nlolo.lifeplatform.policy.domain.XlsxToCsv;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The file a lender actually receives.
 *
 * <p><b>Why any of this is in a workbook.</b> It is the only artefact that reaches the lender:
 * there is no portal and no automated delivery, so everything the console knows about the format
 * reaches them only if it is in the file or in somebody's covering email. Two real files came back
 * refused entire -- the first with every date as {@code 1-Sep-00}, the second, after being told
 * the format, as {@code 9/1/2000} -- because the telling happened in prose and the correction
 * happened in Excel.
 *
 * <p>So the assertions below are about what survives being sent: the dates come back ISO through
 * the same reader the upload uses, the instructions travel on their own sheet, and the cells carry
 * rules Excel enforces while somebody types rather than after they send.
 */
class LenderTemplateXlsxTest {

    private static final LenderTemplateXlsx.EnrolmentExample EXAMPLE =
        new LenderTemplateXlsx.EnrolmentExample("CL-60107C78-000038", "Martin", LocalDate.of(2000, 9, 1),
            new BigDecimal("5000000.00"), 12, LocalDate.of(2026, 9, 24), "nlolo1");

    private static Workbook open(byte[] bytes) {
        try {
            return WorkbookFactory.create(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("The template is not a readable workbook", e);
        }
    }

    private static String sheetNames(Workbook workbook) {
        return StreamSupport.stream(workbook.spliterator(), false)
            .map(Sheet::getSheetName).reduce("", (a, b) -> a.isEmpty() ? b : a + ", " + b);
    }

    // ---- what the upload will make of it -----------------------------------

    @Test
    void theEnrolmentTemplateSurvivesTheRoundTripACsvDoesNot() throws Exception {
        String csv = XlsxToCsv.convert(new ByteArrayInputStream(LenderTemplateXlsx.enrolment(EXAMPLE)));

        // ISO, because the dates were written as date CELLS: what Excel chooses to display them
        // as never reaches the parser.
        assertThat(csv).contains("2000-09-01").contains("2026-09-24");
        assertThat(csv).doesNotContain("9/1/2000").doesNotContain("1-Sep-00");

        var parsed = EnrolmentCsvParser.parse(new StringReader(csv));
        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).borrowerDateOfBirth()).isEqualTo(LocalDate.of(2000, 9, 1));
        // The numeric trap this platform has been bitten by: 5000000.0 must not arrive as 5.0E6.
        assertThat(parsed.rows().get(0).loanPrincipalAmount()).isEqualByComparingTo("5000000.00");
        assertThat(parsed.rows().get(0).loanTermMonths()).isEqualTo(12);
    }

    @Test
    void theExitsTemplateParsesAndNamesNoRealMember() throws Exception {
        String csv = XlsxToCsv.convert(new ByteArrayInputStream(
            LenderTemplateXlsx.exits("CL-60107C78-000000", LocalDate.of(2026, 9, 24))));

        var parsed = ExitCsvParser.parse(new StringReader(csv));
        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(1);
        /*
         * 000000, which the sequence starts past. The asymmetry with the enrolment example is the
         * point: that one shows a REAL borrower, because returning it duplicates a covered loan
         * and is refused. A real reference here with a real date would take a living borrower off
         * cover, refund their premium and claw back the commission -- all of which look correct
         * downstream.
         */
        assertThat(parsed.rows().get(0).memberReference()).endsWith("-000000");
        assertThat(parsed.rows().get(0).exitDate()).isEqualTo(LocalDate.of(2026, 9, 24));
    }

    // ---- what the lender reads --------------------------------------------

    @Test
    void theInstructionsTravelWithTheFile() throws Exception {
        /*
         * The console's column guide is on a STAFF screen. The lender never sees it, and there is
         * no automated delivery -- so a second sheet is the only way the rules arrive with the
         * thing they are rules about.
         */
        try (Workbook workbook = open(LenderTemplateXlsx.enrolment(EXAMPLE))) {
            assertThat(sheetNames(workbook)).isEqualTo("Borrowers, How to fill this in");

            Sheet guide = workbook.getSheet("How to fill this in");
            String text = StreamSupport.stream(guide.spliterator(), false)
                .flatMap(row -> StreamSupport.stream(row.spliterator(), false))
                .map(cell -> cell.getStringCellValue())
                .reduce("", (a, b) -> a + " " + b);

            // The four things that actually refused a real file.
            assertThat(text).contains("2000-09-01");
            assertThat(text).contains("Do NOT save this as a CSV");
            assertThat(text).contains("LEAVE BLANK for a new borrower");
            assertThat(text).contains("COVER STARTS ON IT");
        }
    }

    @Test
    void theExitsInstructionsSayWhichReasonsALenderMayUse() throws Exception {
        try (Workbook workbook = open(LenderTemplateXlsx.exits("CL-X-000000", LocalDate.now()))) {
            assertThat(sheetNames(workbook)).isEqualTo("Loans ending, How to fill this in");

            Sheet guide = workbook.getSheet("How to fill this in");
            String text = StreamSupport.stream(guide.spliterator(), false)
                .flatMap(row -> StreamSupport.stream(row.spliterator(), false))
                .map(cell -> cell.getStringCellValue())
                .reduce("", (a, b) -> a + " " + b);

            assertThat(text).contains("SETTLED_EARLY, REFINANCED, WRITTEN_OFF, CANCELLED");
            // A death is NOT a lender-stated exit: it would suppress the refund and the clawback
            // on a loan that was merely repaid.
            assertThat(text).doesNotContain("CLAIM_SETTLED");
            assertThat(text).contains("REQUIRED here");
        }
    }

    @Test
    void theCellsCarryRulesExcelEnforcesWhileSomebodyTypes() throws Exception {
        // The server refuses all of this too. The point of doing it here as well is WHEN the
        // lender finds out: in the cell, rather than on a report after the file has been sent.
        try (Workbook workbook = open(LenderTemplateXlsx.enrolment(EXAMPLE))) {
            assertThat(workbook.getSheet("Borrowers").getDataValidations()).isNotEmpty();
        }
        try (Workbook workbook = open(LenderTemplateXlsx.exits("CL-X-000000", LocalDate.now()))) {
            assertThat(workbook.getSheet("Loans ending").getDataValidations()).isNotEmpty();
        }
    }

    @Test
    void theHeaderIsFrozenSoItSurvivesFourHundredRows() throws Exception {
        try (Workbook workbook = open(LenderTemplateXlsx.enrolment(EXAMPLE))) {
            assertThat(workbook.getSheet("Borrowers").getPaneInformation().isFreezePane()).isTrue();
        }
    }

    @Test
    void aSchemeWithNothingToShowStillGetsAUsableTemplate() throws Exception {
        // No example is a real state: a scheme nobody has enrolled onto yet. The header and the
        // instructions must still be there, or the lender gets an empty file.
        try (Workbook workbook = open(LenderTemplateXlsx.enrolment(null))) {
            assertThat(workbook.getSheet("Borrowers").getLastRowNum()).isZero();
            assertThat(workbook.getSheet("How to fill this in")).isNotNull();
        }
        String csv = XlsxToCsv.convert(new ByteArrayInputStream(LenderTemplateXlsx.enrolment(null)));
        assertThat(csv.lines()).hasSize(1);
        assertThat(csv).contains("borrower_full_name");
    }
}
