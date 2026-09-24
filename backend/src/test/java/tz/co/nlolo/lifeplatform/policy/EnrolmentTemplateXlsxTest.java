package tz.co.nlolo.lifeplatform.policy;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentCsvParser;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentTemplateXlsx;
import tz.co.nlolo.lifeplatform.policy.domain.XlsxToCsv;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spreadsheet template, end to end through the readers that judge it.
 *
 * <p><b>Why this test exists, in one sentence: two real lender files were refused entire because
 * Excel rewrote every date in a CSV.</b> The first came back {@code 1-Sep-00}; the lender was told
 * the format and the second came back {@code 9/1/2000} — including the worked example row that was
 * put there to show them. Excel rewrites dates when it opens a CSV and again when it saves one,
 * and no amount of instruction changes that.
 *
 * <p>So the chain asserted here is the one that has to hold instead: the template is built with
 * real DATE cells, {@link XlsxToCsv} reads those back as ISO regardless of how they were
 * displayed, and {@link EnrolmentCsvParser} then accepts the result without a single rejection.
 * If any link stops being true, a lender's month bounces again and the reason is invisible until
 * somebody reads a rejection report.
 */
class EnrolmentTemplateXlsxTest {

    private static final EnrolmentTemplateXlsx.ExampleRow EXAMPLE =
        new EnrolmentTemplateXlsx.ExampleRow("CL-60107C78-000038", "Martin", LocalDate.of(2000, 9, 1),
            new BigDecimal("5000000.00"), 12, LocalDate.of(2026, 9, 24), "nlolo1");

    private static String throughTheReaders(byte[] workbook) {
        return XlsxToCsv.convert(new ByteArrayInputStream(workbook));
    }

    @Test
    void theTemplateSurvivesTheRoundTripACsvDoesNot() {
        String csv = throughTheReaders(EnrolmentTemplateXlsx.build(EXAMPLE));

        // The dates come back ISO. This is the whole point: they were written as date CELLS, so
        // what Excel chooses to display them as never reaches the parser.
        assertThat(csv).contains("2000-09-01");
        assertThat(csv).contains("2026-09-24");
        assertThat(csv).doesNotContain("9/1/2000");
        assertThat(csv).doesNotContain("1-Sep-00");

        var parsed = EnrolmentCsvParser.parse(new StringReader(csv));
        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).borrowerDateOfBirth()).isEqualTo(LocalDate.of(2000, 9, 1));
        assertThat(parsed.rows().get(0).disbursementDate()).isEqualTo(LocalDate.of(2026, 9, 24));
    }

    @Test
    void itCarriesTheSameColumnsTheParserRequires() {
        String csv = throughTheReaders(EnrolmentTemplateXlsx.build(EXAMPLE));
        String header = csv.lines().findFirst().orElseThrow();

        assertThat(EnrolmentCsvParser.requiredColumns())
            .allSatisfy(column -> assertThat(header).contains(column));
    }

    @Test
    void theAmountAndTermSurviveAsNumbersRatherThanAsScientificNotation() {
        // A principal written as a number comes back through POI as a double. 5000000.0 must not
        // reach the parser as 5.0E6, which is the XLSX coercion trap this platform has already
        // been bitten by once.
        String csv = throughTheReaders(EnrolmentTemplateXlsx.build(EXAMPLE));

        assertThat(csv).doesNotContain("E6").doesNotContain("E+");
        var parsed = EnrolmentCsvParser.parse(new StringReader(csv));
        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows().get(0).loanPrincipalAmount()).isEqualByComparingTo("5000000.00");
        assertThat(parsed.rows().get(0).loanTermMonths()).isEqualTo(12);
    }

    @Test
    void aSchemeWithNothingToShowStillGetsAUsableTemplate() {
        // No example is a real state -- a scheme whose only member is a PARTY the platform cannot
        // name. The header must still be there, or the lender gets an empty file.
        String csv = throughTheReaders(EnrolmentTemplateXlsx.build(null));

        assertThat(csv.lines()).hasSize(1);
        assertThat(csv).contains("borrower_full_name");
    }
}
