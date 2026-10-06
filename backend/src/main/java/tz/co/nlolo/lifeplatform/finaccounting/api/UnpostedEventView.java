package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An event the posting rules could not post (IFRS 17 I3a): UNMAPPED (no rule for it), REFUSED (the ledger refused the
 * journal -- a locked or closing period, say) or ERROR. Open until finance retries it into a journal (POSTED) or
 * dismisses it with a reason (DISMISSED). An open one in a period stops that period locking.
 */
public record UnpostedEventView(UUID id, String eventType, String sourceRef, String policyNumber, String period,
                                String currency, Map<String, BigDecimal> amounts, Map<String, String> attributes,
                                String reason, String detail, String ruleVersion, int attempts, Instant createdAt,
                                Instant lastAttemptAt, String resolution, String resolutionReason, String resolvedBy,
                                Instant resolvedAt, UUID journalEntryId) {}
