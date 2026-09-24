package tz.co.nlolo.lifeplatform.policy;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentRejection;
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentRowView;
import tz.co.nlolo.lifeplatform.policy.api.RowOutcome;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentReportRenderer;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The report a lender reads. Pure, because escaping is where this goes wrong. */
class EnrolmentReportRendererTest {

    @Test
    void theReportCarriesOneLinePerRowWithItsOutcome() {
        String csv = EnrolmentReportRenderer.toCsv(List.of(
            new EnrolmentRowView(2, "LN-A", "Amina Hassan Mwinyi", RowOutcome.ENROLLED,
                null, null, UUID.randomUUID(), "CL-4F8DF58B-000417", new BigDecimal("18000.00"), "TZS"),
            new EnrolmentRowView(3, null, "", RowOutcome.REJECTED,
                EnrolmentRejection.MISSING_REQUIRED_FIELD,
                "borrower_full_name is blank. THIS BORROWER IS NOT COVERED.", null, null, null, "TZS")));

        assertThat(csv.lines().findFirst().orElseThrow())
            .isEqualTo("row_number,member_reference,loan_account_number,borrower_full_name,"
                + "outcome,premium_amount,reason_code,reason");
        assertThat(csv).contains("2,CL-4F8DF58B-000417,LN-A,Amina Hassan Mwinyi,ENROLLED,18000.00,,");
        assertThat(csv).contains("MISSING_REQUIRED_FIELD");
        assertThat(csv).contains("THIS BORROWER IS NOT COVERED.");
    }

    @Test
    void theReferenceIsHowTheLenderNamesABorrowerAfterwards() {
        // The lender has no identifier of their own, so this column IS the answer to
        // "which of these 400 people do you mean?" on every later file.
        String csv = EnrolmentReportRenderer.toCsv(List.of(
            new EnrolmentRowView(2, null, "Amina Hassan Mwinyi", RowOutcome.ENROLLED,
                null, null, UUID.randomUUID(), "CL-4F8DF58B-000417", new BigDecimal("18000.00"), "TZS")));

        assertThat(csv).contains("CL-4F8DF58B-000417");
    }

    @Test
    void thePremiumColumnIsWhatTheInvoiceForThisFileAddsUpTo() {
        // One file, one invoice (spec 2.8). A lender reconciling a single charge against four
        // hundred names needs the per-borrower breakdown, and this column is it -- the invoice
        // total is exactly the sum of this column.
        String csv = EnrolmentReportRenderer.toCsv(List.of(
            new EnrolmentRowView(2, null, "Amina", RowOutcome.ENROLLED, null, null,
                UUID.randomUUID(), "CL-A-000001", new BigDecimal("18000.00"), "TZS"),
            new EnrolmentRowView(3, null, "Joseph", RowOutcome.ENROLLED, null, null,
                UUID.randomUUID(), "CL-A-000002", new BigDecimal("6000.00"), "TZS")));

        assertThat(csv).contains(",18000.00,");
        assertThat(csv).contains(",6000.00,");
    }

    @Test
    void aRejectedRowIsChargedNothingAndItsPremiumColumnIsBlank() {
        // Not "0.00", which would read as a charge that happened to be nil. A rejected
        // borrower is not insured and not billed, and the blank says so.
        String csv = EnrolmentReportRenderer.toCsv(List.of(
            new EnrolmentRowView(2, null, "Nobody", RowOutcome.REJECTED,
                EnrolmentRejection.MISSING_REQUIRED_FIELD,
                "borrower_date_of_birth is blank. THIS BORROWER IS NOT COVERED.", null, null, null, "TZS")));

        assertThat(csv).contains("2,,,Nobody,REJECTED,,");
    }

    @Test
    void aCappedBorrowerIsStillChargedOnTheirWholeLoan() {
        // Capping limits what the insurer will PAY, not what the borrower took. The rate the
        // lender negotiated is a rate on the loan, so discounting a capped borrower would
        // quietly reward exactly the excess risk that sent them to underwriting.
        String csv = EnrolmentReportRenderer.toCsv(List.of(
            new EnrolmentRowView(2, null, "Peter Massawe", RowOutcome.ENROLLED_CAPPED,
                null, "Cover limited to the free cover limit.", UUID.randomUUID(),
                "CL-4F8DF58B-000418", new BigDecimal("150000.00"), "TZS")));

        assertThat(csv).contains("ENROLLED_CAPPED,150000.00,,");
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
                null, null, null, "TZS")));

        assertThat(csv).contains("\"Mwinyi, Amina\"");
        assertThat(csv).contains("\"\"8.5E+06\"\"");
    }
}
