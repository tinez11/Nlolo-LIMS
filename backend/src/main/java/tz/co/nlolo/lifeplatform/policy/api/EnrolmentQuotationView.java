package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A lender's file priced before acceptance (2026-10-30): what the lender pays, what they earn, and where their own
 * figures differ from ours.
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
 */
public record EnrolmentQuotationView(UUID submissionId,
                                     String policyNumber,
                                     String currency,
                                     int borrowers,
                                     BigDecimal totalLoanAmount,
                                     BigDecimal premium,
                                     BigDecimal statedPremium,
                                     BigDecimal difference,
                                     BigDecimal commissionPercent,
                                     BigDecimal commission,
                                     BigDecimal amountToPay,
                                     List<Mismatch> mismatches) {

    public record Mismatch(int lineNumber, String borrowerFullName, BigDecimal premium, BigDecimal statedPremium) {}
}
