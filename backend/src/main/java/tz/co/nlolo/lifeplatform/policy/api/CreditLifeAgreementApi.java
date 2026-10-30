package tz.co.nlolo.lifeplatform.policy.api;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A lender's yearly agreement on a credit-life scheme (2026-10-30, the user's description of how a bank and the insurer
 * work): the rate and how it is applied, the least a loan pays, the oldest borrower, the shortest and longest loan, the
 * lender's commission, and whether cover includes the loan's interest. One per year; a borrower is priced on the one in
 * force on the day their loan started, so a renewal never re-prices anybody already on cover.
 */
public interface CreditLifeAgreementApi {

    /** Every agreement on the scheme, the earliest first. */
    List<AgreementView> agreements(String policyNumber);

    /** The agreement in force on {@code day}; empty when none covers it. */
    Optional<AgreementView> inForce(String policyNumber, LocalDate day);

    /**
     * A new year's agreement. Refused on a scheme that is not credit life, for a period that overlaps one already
     * agreed, and for commission above TIRA's 15% cap.
     */
    AgreementView add(String policyNumber, AgreementInput input, String createdBy);

    /**
     * @param premiumRatePercent per annum on the loan amount, except on FLAT_ON_PRINCIPAL, where it is the whole price
     * @param minimumPremium the least one loan pays; zero for none
     * @param maxAge the oldest a borrower may be when their loan starts
     * @param minTermMonths the shortest loan taken -- twelve, the user: "they all start with one year"
     * @param commissionPercent the lender's commission on premium, at most 15 (TIRA)
     * @param coverIncludesInterest whether a claim pays the balance with its interest or the principal alone
     */
    record AgreementInput(LocalDate validFrom, LocalDate validTo, BigDecimal premiumRatePercent,
                          CreditLifePremiumBasis premiumBasis, BigDecimal minimumPremium, int maxAge,
                          int minTermMonths, int maxTermMonths, BigDecimal commissionPercent,
                          boolean coverIncludesInterest) {}

    /** Decimals as strings on the wire, as all money on this platform travels: a double cannot hold every decimal. */
    record AgreementView(UUID agreementId, String policyNumber, LocalDate validFrom, LocalDate validTo,
                         @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal premiumRatePercent,
                         CreditLifePremiumBasis premiumBasis,
                         @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal minimumPremium,
                         int maxAge, int minTermMonths, int maxTermMonths,
                         @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal commissionPercent,
                         boolean coverIncludesInterest, Instant createdAt, String createdBy) {}
}
