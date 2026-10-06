package tz.co.nlolo.lifeplatform.policy.domain;

/**
 * The facts a contract is classified by for IFRS 17, as sold (IFRS 17 spec §6): the product's portfolio, the year of
 * issue, the version's expected profitability and model override, and the channel and branch of the sale. Stamped on
 * the policy at issue and never changed; finaccounting decides the measurement model and group from them.
 *
 * @param measurementModelOverride null for the accounting policy register's model, the ordinary case
 * @param branchCode null only where nothing named a branch and no head office is configured
 */
public record SaleClassification(String portfolioCode, int cohortYear, String profitabilityBucket,
                                 String measurementModelOverride, String salesChannel, String branchCode) {}
