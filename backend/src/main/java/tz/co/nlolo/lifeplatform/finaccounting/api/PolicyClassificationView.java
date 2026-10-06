package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.time.Instant;
import java.time.LocalDate;

/**
 * How a contract is classified for IFRS 17 (spec §6, §7.2): the facts it was sold with and what finaccounting decided
 * from them. Never changed; a vesting pension has a second row (reason VESTING), a new contract in a new cohort.
 */
public record PolicyClassificationView(String policyNumber, String reason, LocalDate effectiveFrom, String groupKey,
                                       String measurementModel, ModelBasis modelBasis, String requestedOverride,
                                       int registerVersion, String portfolioCode, int cohortYear,
                                       String profitabilityBucket, String salesChannel, String branchCode,
                                       Instant classifiedAt) {}
