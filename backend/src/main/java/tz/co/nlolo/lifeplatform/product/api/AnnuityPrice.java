package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/**
 * A priced annuity purchase: the income, and every input it came from, so a lock can store exactly
 * what it was priced on.
 *
 * @param rateSex the sex the cell was looked up by; null on a UNISEX form
 */
public record AnnuityPrice(String formCode, String frequency, int annuitantAge, Integer jointAge,
                           Integer ageDifference, String rateSex, BigDecimal annualRatePerMille,
                           BigDecimal factor, BigDecimal annualIncome, BigDecimal instalment,
                           int paymentsPerYear, AnnuityTiming timing) {}
