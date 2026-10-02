package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One term of a fixed-term deposit. {@code ratePercent} is for the whole term, not a year. */
public record DepositPeriodView(UUID periodId, String policyNumber, int seq, BigDecimal principal, int termMonths,
                                BigDecimal ratePercent, UUID rateVersionId, LocalDate startDate, LocalDate maturityDate,
                                DepositPeriodStatus status, BigDecimal interestPosted, LocalDate closedOn) {}
