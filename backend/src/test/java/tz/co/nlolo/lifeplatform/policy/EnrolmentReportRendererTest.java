package tz.co.nlolo.lifeplatform.policy;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentRejection;
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentRowView;
import tz.co.nlolo.lifeplatform.policy.api.RowOutcome;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentReportRenderer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The report a lender reads. Pure, because escaping is where this goes wrong. */
class EnrolmentReportRendererTest {

    @Test
    void theReportCarriesOneLinePerRowWithItsOutcome() {
        String csv = EnrolmentReportRenderer.toCsv(List.of(
            new EnrolmentRowView(2, "LN-A", "Amina Hassan Mwinyi", RowOutcome.ENROLLED,
                null, null, UUID.randomUUID()),
            new EnrolmentRowView(3, "LN-B", "", RowOutcome.REJECTED,
                EnrolmentRejection.MISSING_REQUIRED_FIELD,
                "borrower_full_name is blank. THIS BORROWER IS NOT COVERED.", null)));

        assertThat(csv.lines().findFirst().orElseThrow())
            .isEqualTo("row_number,loan_account_number,borrower_full_name,outcome,reason_code,reason");
        assertThat(csv).contains("2,LN-A,Amina Hassan Mwinyi,ENROLLED,,");
        assertThat(csv).contains("MISSING_REQUIRED_FIELD");
        assertThat(csv).contains("THIS BORROWER IS NOT COVERED.");
    }

    @Test
    void aReasonContainingACommaOrAQuoteIsEscaped() {
        // The reason quotes amounts, column names and the lender's own values back at
        // them, so commas and quotes are routine. An unescaped comma silently shifts
        // every later column -- on a report about who is insured, worse than no report.
        String csv = EnrolmentReportRenderer.toCsv(List.of(
            new EnrolmentRowView(2, "LN-A", "Mwinyi, Amina", RowOutcome.REJECTED,
                EnrolmentRejection.MALFORMED_VALUE,
                "loan_principal_amount \"8.5E+06\" is not an amount. THIS BORROWER IS NOT COVERED.",
                null)));

        assertThat(csv).contains("\"Mwinyi, Amina\"");
        assertThat(csv).contains("\"\"8.5E+06\"\"");
        // Six fields on the data line, not eight: the comma inside the name must not
        // have become a delimiter.
        assertThat(csv.lines().skip(1).findFirst().orElseThrow().split("\",\"|,").length)
            .isLessThanOrEqualTo(7);
    }

    @Test
    void aCappedRowCarriesNoReasonCodeBecauseItsOutcomeAlreadySaysSo() {
        String csv = EnrolmentReportRenderer.toCsv(List.of(
            new EnrolmentRowView(2, "LN-BIG", "Peter Massawe", RowOutcome.ENROLLED_CAPPED,
                null, "Cover limited to the free cover limit.", UUID.randomUUID())));

        assertThat(csv).contains("ENROLLED_CAPPED,,");
    }
}
