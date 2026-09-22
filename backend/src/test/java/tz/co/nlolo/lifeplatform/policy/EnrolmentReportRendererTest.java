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
                null, null, UUID.randomUUID(), "CL-4F8DF58B-000417"),
            new EnrolmentRowView(3, null, "", RowOutcome.REJECTED,
                EnrolmentRejection.MISSING_REQUIRED_FIELD,
                "borrower_full_name is blank. THIS BORROWER IS NOT COVERED.", null, null)));

        assertThat(csv.lines().findFirst().orElseThrow())
            .isEqualTo("row_number,member_reference,loan_account_number,borrower_full_name,"
                + "outcome,reason_code,reason");
        assertThat(csv).contains("2,CL-4F8DF58B-000417,LN-A,Amina Hassan Mwinyi,ENROLLED,,");
        assertThat(csv).contains("MISSING_REQUIRED_FIELD");
        assertThat(csv).contains("THIS BORROWER IS NOT COVERED.");
    }

    @Test
    void theReferenceIsHowTheLenderNamesABorrowerAfterwards() {
        // The lender has no identifier of their own, so this column IS the answer to
        // "which of these 400 people do you mean?" on every later file.
        String csv = EnrolmentReportRenderer.toCsv(List.of(
            new EnrolmentRowView(2, null, "Amina Hassan Mwinyi", RowOutcome.ENROLLED,
                null, null, UUID.randomUUID(), "CL-4F8DF58B-000417")));

        assertThat(csv).contains("CL-4F8DF58B-000417");
    }

    @Test
    void aRejectedRowHasNoReferenceBecauseNobodyWasEnrolled() {
        String csv = EnrolmentReportRenderer.toCsv(List.of(
            new EnrolmentRowView(2, null, "Nobody", RowOutcome.REJECTED,
                EnrolmentRejection.MISSING_REQUIRED_FIELD,
                "borrower_date_of_birth is blank. THIS BORROWER IS NOT COVERED.", null, null)));

        assertThat(csv).contains("2,,,Nobody,REJECTED,");
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
                null, null)));

        assertThat(csv).contains("\"Mwinyi, Amina\"");
        assertThat(csv).contains("\"\"8.5E+06\"\"");
    }

    @Test
    void aCappedRowCarriesNoReasonCodeBecauseItsOutcomeAlreadySaysSo() {
        String csv = EnrolmentReportRenderer.toCsv(List.of(
            new EnrolmentRowView(2, null, "Peter Massawe", RowOutcome.ENROLLED_CAPPED,
                null, "Cover limited to the free cover limit.", UUID.randomUUID(),
                "CL-4F8DF58B-000418")));

        assertThat(csv).contains("ENROLLED_CAPPED,,");
    }
}
