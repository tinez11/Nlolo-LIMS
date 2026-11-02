package tz.co.nlolo.lifeplatform.policy.api;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A lender's file priced before acceptance (2026-10-30): what the lender pays, what they earn, and where their own
 * figures differ from ours. Amounts are decimal strings on the wire, as all money on this platform travels.
 *
 * @param borrowers the rows that will be covered on acceptance; refused rows are in none of these figures
 * @param statedPremium what the lender's own file said for those rows, or null where it carried no premium column
 * @param difference {@code premium} less {@code statedPremium}; positive means the lender pays more than they worked
 *     out. Null where there is nothing to compare.
 * @param commissionPercent the agreement's commission, or null when the file's loans span agreements with different
 *     ones (the commission is then each agreement's on its own loans) or the scheme has no agreement
 * @param commission what the lender earns on this file, paid after the premium is received; zero with no agreement
 * @param amountToPay the full premium: the lender pays it all and is paid the commission afterwards
 * @param mismatches the rows whose stated premium is not ours, so the lender can be told which
 * @param lines every borrower who will be covered, with their loan and premium (2026-11-02): the quotation the lender
 *     is sent lists them, and the file's rows show the premium before acceptance
 */
public record EnrolmentQuotationView(UUID submissionId,
                                     String policyNumber,
                                     String currency,
                                     int borrowers,
                                     @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalLoanAmount,
                                     @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal premium,
                                     @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal statedPremium,
                                     @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal difference,
                                     @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal commissionPercent,
                                     @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal commission,
                                     @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amountToPay,
                                     List<Mismatch> mismatches,
                                     List<Line> lines) {

    public record Mismatch(int lineNumber, String borrowerFullName,
                           @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal premium,
                           @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal statedPremium) {}

    /** One borrower to be covered: the line in the lender's file, the loan, and its premium. */
    public record Line(int lineNumber, String borrowerFullName,
                       @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal loanAmount,
                       LocalDate loanStartDate, Integer loanTermMonths,
                       @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal premium) {}
}
