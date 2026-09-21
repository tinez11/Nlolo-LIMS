package tz.co.nlolo.lifeplatform.policy.domain;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentRowView;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * The report that goes back to the lender.
 *
 * <p>Printed with Commons CSV rather than by hand. The reason text quotes amounts,
 * column names and the lender's own values back at them, so it routinely contains commas
 * and quotes -- and hand-rolled escaping is how a comma in a borrower's name silently
 * shifts every later column on a report about who is insured.
 */
public final class EnrolmentReportRenderer {

    /**
     * The sentence this whole feature exists to deliver.
     *
     * <p>One constant so it cannot drift between reason codes: a rejection that does not
     * say this plainly is how somebody dies uninsured because of a typo nobody read.
     */
    public static final String NOT_COVERED = "THIS BORROWER IS NOT COVERED.";

    private static final String[] HEADER = {
        "row_number", "loan_account_number", "borrower_full_name", "outcome", "reason_code", "reason"
    };

    private EnrolmentReportRenderer() {}

    public static String toCsv(List<EnrolmentRowView> rows) {
        StringWriter out = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(out,
                CSVFormat.DEFAULT.builder().setHeader(HEADER).build())) {
            for (EnrolmentRowView row : rows) {
                printer.printRecord(
                    row.lineNumber(),
                    row.loanAccountNumber(),
                    row.borrowerFullName(),
                    row.outcome(),
                    row.reasonCode() == null ? "" : row.reasonCode().name(),
                    row.reason() == null ? "" : row.reason());
            }
        } catch (IOException e) {
            // A StringWriter does not do IO. If this ever fires, something is very wrong.
            throw new UncheckedIOException("Could not render the enrolment report", e);
        }
        return out.toString();
    }
}
