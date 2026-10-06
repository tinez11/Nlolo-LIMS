package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An IFRS 17 engine run (IFRS 17 I5a, month-end step 7): the results file uploaded, VALIDATED or REJECTED with every
 * error, then POSTED through 9160 by a FINANCE_APPROVER who did not upload it -- with the appointed actuary's sign-off
 * reference and report -- and REPLACED if a later run supersedes it. {@code groups} carries each group's journal lines
 * and closing figures; {@code reconciliation}, once posted, each figure of the ledger against the engine.
 */
public record EngineRunView(UUID runId, String period, Integer extractNumber, String engineReference, String engineName,
                            String measurementDate, String status, List<String> errors, String fileName,
                            String resultsDocumentRef, String uploadedBy, Instant uploadedAt, String decidedBy,
                            Instant decidedAt, String decisionReason, String signOffReference, String reportDocumentRef,
                            UUID replacesRunId, UUID replacedByRunId, List<Group> groups,
                            List<Reconciliation> reconciliation) {

    public record Group(String group, boolean reinsurance, List<Line> lines, Closing closing) {}

    public record Line(int row, String entry, String account, String side, BigDecimal amount, String movement,
                       String note) {}

    public record Closing(BigDecimal lrc, BigDecimal lic, BigDecimal csm, BigDecimal arc, BigDecimal aic,
                          BigDecimal riCsm) {}

    /**
     * One figure of one group: AGREED within TZS 1.00, else an EXCEPTION, EXPLAINED by a finance officer, ACCEPTED by
     * a FINANCE_APPROVER who did not explain it. An unaccepted exception blocks the period lock.
     */
    public record Reconciliation(String group, String figure, BigDecimal ledger, BigDecimal engine,
                                 BigDecimal difference, String status, String explanation, String explainedBy,
                                 String acceptedBy) {}
}
