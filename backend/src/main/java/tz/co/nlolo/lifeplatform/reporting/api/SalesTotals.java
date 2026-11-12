package tz.co.nlolo.lifeplatform.reporting.api;

import java.math.BigDecimal;

/**
 * One bucket of an agent's sales in money (2026-11-12, analytics plan 3), counted on the day cover started (D1), in one
 * currency. Every figure is the server's.
 *
 * @param bucket          "2026-11" (month), "2026-Q4" (quarter) or "2026" (year)
 * @param premiumAYear    the yearly premium of the regular-premium policies sold -- monthly x 12, quarterly x 4 --
 *                        savings and fixed deposits left out
 * @param singlePremiums  one-time premiums of policies sold, savings and fixed deposits left out (D2)
 * @param cover           the sum assured of the policies sold; savings and fixed deposits give no cover
 * @param savingsDeposits the opening deposit of each savings or fixed-deposit policy sold: its single deposit, or one
 *                        regular deposit (D3). Never added into premium
 */
public record SalesTotals(String bucket, String currency, long policies, BigDecimal premiumAYear,
                          BigDecimal singlePremiums, BigDecimal cover, BigDecimal savingsDeposits) {}
