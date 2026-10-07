package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One month's expense allocation (IFRS 17 I5b, P-19). While PREPARED the pool and the lines are computed on read; once
 * decided they are as posted. {@code staleExtract} is the period's latest extract number when every extract of the
 * period predates this POSTED allocation -- the engine has not been sent it yet.
 */
public record ExpenseAllocationView(UUID allocationId, String period, String status, BigDecimal maintenance,
                                    BigDecimal claimsHandling, BigDecimal acquisition, BigDecimal total, String currency,
                                    String studyReference, String note, String nilReason, BigDecimal pool,
                                    boolean overPool, String preparedBy, Instant preparedAt, String decidedBy,
                                    Instant decidedAt, String decisionReason, UUID replacesId, UUID journalEntryId,
                                    UUID reversalJournalId, Integer staleExtract, List<Line> lines) {

    /** A group's share of one category, the account it posts to and the driver it was shared by. */
    public record Line(String group, String measurementModel, String category, String account, String driver,
                       long driverCount, BigDecimal amount) {}
}
