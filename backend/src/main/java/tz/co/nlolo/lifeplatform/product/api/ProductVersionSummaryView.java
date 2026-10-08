package tz.co.nlolo.lifeplatform.product.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One published version of a product, as the product page lists them (2026-10-08). The page showed only the version
 * in force today, so a product with four versions -- one of them priced at its own benefit by mistake -- read as
 * having one, and nothing said which the earlier policies were sold on.
 *
 * @param current the version a sale today is priced on -- what the active snapshot resolves
 * @param publishedAt when it was published; publishedBy who published it
 */
public record ProductVersionSummaryView(UUID productVersionId, LocalDate effectiveDate, LocalDate retirementDate,
                                        boolean current, Instant publishedAt, String publishedBy, int gracePeriodDays,
                                        String expectedProfitabilityBucket, String measurementModelOverride,
                                        java.math.BigDecimal survivalInvestmentComponentPercent) {}
