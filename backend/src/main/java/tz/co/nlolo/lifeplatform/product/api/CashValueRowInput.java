package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/**
 * One cell of a cash-value scale: per 1,000 of sum assured at a policy year, optionally for an
 * entry-age band. {@code paidUpPerMille} is the paid-up sum assured per 1,000, required on a TABLE
 * paid-up basis and absent on PROPORTIONATE.
 */
public record CashValueRowInput(int policyYear, Integer ageFrom, Integer ageTo,
                                BigDecimal cashValuePerMille, BigDecimal paidUpPerMille) {}
