package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A year's close (IFRS 17 I6, guide 5.7): PREPARED (its figures computed on read), POSTED as one SYSTEM journal in Y-12,
 * REJECTED, or REPLACED by a later close of the year. A preview has no record fields (closeId, status, preparedBy ... are
 * null). Balances net Dr - Cr; {@code profit} positive for a profit, negative for a loss. {@code stale}: classes 4-8 or
 * 3320 posted in the year since this close was approved.
 */
public record YearEndCloseView(UUID closeId, int year, String status, Map<String, BigDecimal> classTotals,
                               BigDecimal profit, BigDecimal dividends, List<Account> accounts, List<Line> lines,
                               boolean stale, String preparedBy, Instant preparedAt, String decidedBy,
                               Instant decidedAt, String decisionReason, UUID replacesId, UUID journalEntryId,
                               UUID reversalJournalId) {

    /** An account closed, and its balance for the year. */
    public record Account(String code, String name, String accountClass, BigDecimal balance) {}

    /** One line of the closing journal; side DR or CR. */
    public record Line(String account, String side, BigDecimal amount) {}
}
