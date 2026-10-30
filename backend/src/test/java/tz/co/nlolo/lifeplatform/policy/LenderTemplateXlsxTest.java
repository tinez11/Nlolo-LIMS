package tz.co.nlolo.lifeplatform.policy;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.policy.domain.ExitCsvParser;
import tz.co.nlolo.lifeplatform.policy.domain.LenderTemplateXlsx;
import tz.co.nlolo.lifeplatform.XlsxToCsv;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exits file a lender actually receives. The enrolment workbook is {@link CreditLifeTemplateXlsxTest}'s.
 *
 * <p><b>Why any of this is in a workbook.</b> It is the only artefact that reaches the lender:
 * there is no portal and no automated delivery, so everything the console knows about the format
 * reaches them only if it is in the file or in somebody's covering email.
 */
class LenderTemplateXlsxTest {

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

    @Test
    void theExitsTemplateParsesAndNamesNoRealMember() throws Exception {
        String csv = XlsxToCsv.convert(new ByteArrayInputStream(
            LenderTemplateXlsx.exits("CL-60107C78-000000", LocalDate.of(2026, 9, 24))));

        var parsed = ExitCsvParser.parse(new StringReader(csv));
        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(1);
        /*
         * 000000, which the sequence starts past. A real reference here with a real date would take a living borrower
         * off cover, refund their premium and claw back the commission -- all of which look correct downstream.
         */
        assertThat(parsed.rows().get(0).memberReference()).endsWith("-000000");
        assertThat(parsed.rows().get(0).exitDate()).isEqualTo(LocalDate.of(2026, 9, 24));
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
        try (Workbook workbook = open(LenderTemplateXlsx.exits("CL-X-000000", LocalDate.now()))) {
            assertThat(workbook.getSheet("Loans ending").getDataValidations()).isNotEmpty();
        }
    }
}
