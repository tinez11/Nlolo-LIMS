package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Manual journals (IFRS 17 I4, spec §8, guide 2.3 and Part 4): the entries the policy system cannot make -- equity,
 * investments, operating costs, intermediary and reinsurance statements, opening balances. One person prepares a
 * draft; a second, holding FINANCE_APPROVER, approves it, and only then is it posted. A posted journal is never
 * edited: it is corrected by a reversal, itself approved.
 *
 * <p>Every refusal names what is wrong: {@link FinaccountingValidationException} (422) lists every problem with a
 * draft at once; {@link ManualJournalStateException} (409) is a step the draft's state or the ledger refuses.
 */
public interface ManualJournalApi {

    ManualJournalView create(ManualJournalInput input, String preparer);

    /** A DRAFT, by its preparer only. */
    ManualJournalView update(UUID id, ManualJournalInput input, String by);

    ManualJournalView get(UUID id);

    /** Newest first; null filters are ignored. At most 500. */
    List<ManualJournalView> list(String status, String period);

    /** DRAFT -> SUBMITTED, by its preparer, once every check passes (every problem listed otherwise). */
    ManualJournalView submit(UUID id, String by);

    /** SUBMITTED -> DRAFT, by its preparer. */
    ManualJournalView withdraw(UUID id, String by);

    /** SUBMITTED -> APPROVED: checked again, then posted. Never by the preparer. */
    ManualJournalView approve(UUID id, String approver);

    /** SUBMITTED -> REJECTED, with a reason. Never by the preparer. */
    ManualJournalView reject(UUID id, String reason, String by);

    /** A new DRAFT reversing an approved journal: lines swapped, dated in the current open period. Once per journal. */
    ManualJournalView reverse(UUID id, String preparer);

    /** A supporting document, already stored (document::api), recorded on a DRAFT. */
    ManualJournalView attachDocument(UUID id, String documentRef, String by);

    ManualJournalView detachDocument(UUID id, String documentRef, String by);

    /**
     * Replaces a DRAFT's lines with those in an uploaded CSV or Excel file -- columns account, side, amount,
     * description, branch, fund, reference. Every row is checked and every error listed before anything changes.
     */
    ManualJournalView uploadLines(UUID id, byte[] content, String fileName, String by);

    /** The guide's library (Part 4), then the tenant's saved templates. */
    List<JournalTemplateView> templates();

    /** Saves a recurring template from a journal's lines (amounts kept, editable on each use). */
    JournalTemplateView saveTemplate(String name, String description, List<ManualJournalInput.Line> lines,
                                     String reasonCode, String by);

    record ManualJournalView(UUID id, String status, String period, String currency, String title, String reason,
                             String reasonCode, String templateId, UUID reversesJournalId, LocalDate autoReverseOn,
                             List<String> documentRefs, String preparer, Instant preparedAt, Instant submittedAt,
                             String decidedBy, Instant decidedAt, String decisionReason, UUID journalEntryId,
                             List<Line> lines, BigDecimal totalDebit, BigDecimal totalCredit) {

        public record Line(int lineNo, String accountCode, String accountName, String accountMode, PostingDirection side,
                           BigDecimal amount, String description, String branch, String fund, String referenceType,
                           String reference) {}
    }

    /**
     * A template: the guide's (source GUIDE, id e.g. "N-04") or the tenant's (SAVED). {@code postedBy} is set on a
     * guide entry that cannot be a manual journal -- it posts on an AUTO account -- and says where it comes from.
     */
    record JournalTemplateView(String id, String source, String title, String when, String postedBy,
                               String reasonCode, List<TemplateLine> lines) {

        public record TemplateLine(PostingDirection side, String accountCode, String accountName, BigDecimal amount,
                                   String description) {}
    }
}
