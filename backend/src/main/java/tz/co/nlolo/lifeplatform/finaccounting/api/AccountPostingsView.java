package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The postings behind an account across a span of periods, one page of them, with the totals of the WHOLE span
 * (2026-11-09, the reports' computation tree: a report line opens to its accounts, an account to its postings).
 *
 * <p>An account with child accounts includes every descendant's postings, so a summary account opens to the same
 * figure its balance states. {@code debitTotal}, {@code creditTotal} and {@code count} cover every posting in the
 * span, not just this page -- a check row that summed a page would not reconcile to anything.
 */
public record AccountPostingsView(String accountCode, String fromPeriod, String toPeriod, BigDecimal debitTotal,
                                  BigDecimal creditTotal, long count, int page, int size, List<Posting> postings) {

    public record Posting(UUID postingId, UUID journalEntryId, String accountCode, String period, Instant postedAt,
                          PostingDirection direction, BigDecimal amount, String currency, String policyNumber,
                          String sourceEvent, String sourceRef) {}
}
