package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One borrower on a lender's enrolment schedule, read and typed.
 *
 * <p>Nine columns, six of them required. The template was cut from thirteen after the
 * first real client files arrived carrying none of the four that were dropped:
 *
 * <ul>
 *   <li><b>interest rate</b> -- straight-line decline never reads one.</li>
 *   <li><b>repayment frequency</b> -- a lender's product repays on one cadence, so it
 *       belongs to the scheme, not to 400 rows that can disagree with each other.</li>
 *   <li><b>instalment amount</b> -- existed only to infer the interest method, which was
 *       withdrawn when the real files turned out to carry no instalment at all.</li>
 *   <li><b>first repayment date</b> -- derived as disbursement plus one period.</li>
 * </ul>
 *
 * <p>That left one genuinely new column for the lenders rather than four: BUMACO's sheet
 * already carries the name, sex, date of birth, disbursed amount, term and disbursed
 * date. <b>A moratorium can no longer be expressed</b>, which is the price of the cut and
 * is an open question with the client.
 *
 * @param lineNumber the line in the lender's OWN file, so a report can be read beside the
 *     spreadsheet that produced it. 1 is the header; data starts at 2.
 * @param memberReference OURS, issued at enrolment. Blank on a new borrower; quoted back
 *     by the lender to name an existing one. The lender has no identifier of their own.
 * @param borrowerSex carried for regulatory reporting; nothing prices on it.
 * @param borrowerNationalId optional, and absent in both real lender files -- which is
 *     why party de-duplication cannot fire on a borrower and the loan account number
 *     keys the member instead.
 */
public record EnrolmentRow(int lineNumber,
                            String memberReference,
                            String loanAccountNumber,
                            String borrowerFullName,
                            LocalDate borrowerDateOfBirth,
                            String borrowerSex,
                            String borrowerNationalId,
                            String borrowerPhone,
                            BigDecimal loanPrincipalAmount,
                            Integer loanTermMonths,
                            LocalDate disbursementDate,
                            /**
                             * What the LENDER said this loan's premium is, or null where they
                             * sent no such column.
                             *
                             * <p>Never used to price anything: the insurer prices from the
                             * scheme's own rate and basis, and a counterparty's figure is not an
                             * instruction. It is carried so the two can be compared, because a
                             * file whose premium column is discarded reconciles against nothing
                             * — and one-file-one-invoice exists precisely to be reconciled.
                             */
                            BigDecimal statedPremiumAmount) {

    /** Age on the day the loan was paid out, which is the day cover starts. */
    public int entryAge() {
        return java.time.Period.between(borrowerDateOfBirth, disbursementDate).getYears();
    }
}
