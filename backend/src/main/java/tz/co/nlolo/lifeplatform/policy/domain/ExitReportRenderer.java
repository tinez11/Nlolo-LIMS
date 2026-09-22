package tz.co.nlolo.lifeplatform.policy.domain;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import tz.co.nlolo.lifeplatform.policy.api.ExitRowView;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * The report that goes back to the lender after an exits file.
 *
 * <p>Printed with Commons CSV rather than by hand, for the reason
 * {@link EnrolmentReportRenderer} is: the reason text quotes references, dates and the
 * lender's own values back at them, so it routinely contains commas.
 */
public final class ExitReportRenderer {

    /**
     * The sentence this report exists to deliver, and the mirror of the enrolment report's.
     *
     * <p>A lender who believes a loan came off cover stops expecting to be charged for it and
     * stops chasing the refund that should follow. So a refused exit has to say, in words, that
     * nothing changed — otherwise the silence reads as agreement.
     */
    public static final String STILL_ON_COVER = "THIS LOAN IS STILL ON COVER.";

    private static final String[] HEADER = {
        "row_number", "member_reference", "exit_date", "exit_reason",
        "outstanding_balance_at_exit", "outcome", "reason_code", "reason"
    };

    private ExitReportRenderer() {}

    public static String toCsv(List<ExitRowView> rows) {
        StringWriter out = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(out,
                CSVFormat.DEFAULT.builder().setHeader(HEADER).build())) {
            for (ExitRowView row : rows) {
                printer.printRecord(
                    row.lineNumber(),
                    row.memberReference() == null ? "" : row.memberReference(),
                    row.exitDate() == null ? "" : row.exitDate().toString(),
                    row.exitReason() == null ? "" : row.exitReason().name(),
                    row.outstandingBalanceAtExit() == null ? ""
                        : row.outstandingBalanceAtExit().toPlainString(),
                    row.outcome(),
                    row.reasonCode() == null ? "" : row.reasonCode().name(),
                    row.reason() == null ? "" : row.reason());
            }
        } catch (IOException e) {
            // A StringWriter does not do IO. If this ever fires, something is very wrong.
            throw new UncheckedIOException("Could not render the exits report", e);
        }
        return out.toString();
    }
}
