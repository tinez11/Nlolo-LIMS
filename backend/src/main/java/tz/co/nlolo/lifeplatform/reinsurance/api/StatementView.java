package tz.co.nlolo.lifeplatform.reinsurance.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A treaty's quarterly statement (IFRS 17 I3d). {@code premium}, {@code commission} and {@code recoveries} are the
 * platform's, summed from {@code items}; {@code fundsWithheld} and {@code profitCommission} come from the reinsurer's
 * statement. {@code owedToUs}/{@code owedByUs} is where the current account (1434) falls, and {@code journal} the
 * lines approval posts (R-04, R-01, R-03).
 */
public record StatementView(java.util.UUID statementId, java.util.UUID treatyId, String reinsurerName, String quarter,
                            String currency, String status, BigDecimal premium, BigDecimal commission,
                            BigDecimal recoveries, BigDecimal fundsWithheld, BigDecimal profitCommission,
                            BigDecimal owedToUs, BigDecimal owedByUs, String reason, List<String> documentRefs,
                            String preparer, Instant preparedAt, Instant submittedAt, String decidedBy,
                            Instant decidedAt, String decisionReason, List<Item> items, List<JournalLine> journal) {

    /** A bordereau or a recovery the statement settles. */
    public record Item(String type, java.util.UUID id, String label, BigDecimal amount) {}

    /** One line of the journal approval posts: the guide entry, the account, DR or CR, the amount. */
    public record JournalLine(String entry, String account, String side, BigDecimal amount) {}
}
