package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.time.Instant;

/** One accounting period (YYYY-MM) and who moved it last. A period never touched reads OPEN with no stamps. */
public record AccountingPeriodView(String period, PeriodStatus status, String closingStartedBy, Instant closingStartedAt,
                                   String lockedBy, Instant lockedAt, String reopenRequestedBy,
                                   Instant reopenRequestedAt, String reopenReason, String reopenedBy,
                                   Instant reopenedAt) {}
