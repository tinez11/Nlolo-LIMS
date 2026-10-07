package tz.co.nlolo.lifeplatform.finaccounting.domain;

import tz.co.nlolo.lifeplatform.finaccounting.api.JournalSource;
import tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Maps {@code finaccounting.journal_entry} -- the aggregate root of a double-entry transaction,
 * and where idempotency lives: {@code ux_journal_entry_once (tenant_id, source_event, source_ref)}
 * makes a redelivered event a no-op.
 *
 * <p><b>Why the unique index is here and not on {@code gl_posting}.</b> {@code gl_posting} is
 * {@code PARTITION BY RANGE (created_at)}, and Postgres requires every unique index on a
 * partitioned table to include all partition-key columns -- verified empirically. Omitting
 * {@code created_at} is rejected outright; including it would apply cleanly and then silently
 * permit a double-post, since a redelivered event at a different timestamp satisfies it.
 *
 * <p>Legs are held {@link Transient} as plain {@link Leg} values rather than as a JPA
 * {@code @OneToMany} of {@link GlPosting}: {@code gl_posting} is append-only with a composite
 * partition-aware key, and a cascading collection would fight both. It also keeps the balance
 * invariant testable without a database.
 *
 * <p>Holding VALUES rather than entities is what avoids a null-id window: {@code journalEntryId} is
 * {@code @GeneratedValue} (this codebase's convention -- see {@code reinsurance.Cession}), so it does
 * not exist until the entry is persisted, while {@code gl_posting.journal_entry_id} is
 * {@code NOT NULL}. {@code FinaccountingApiImpl.postEntry} therefore saves the entry first and builds
 * {@link GlPosting} rows from these legs afterwards, when the id is real.
 */
@Entity
@Table(name = "journal_entry", schema = "finaccounting")
public class JournalEntry {

    @Id
    @GeneratedValue
    @Column(name = "journal_entry_id")
    private UUID journalEntryId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "source_event", nullable = false)
    private String sourceEvent;

    @Column(name = "source_ref", nullable = false)
    private String sourceRef;

    @Column(name = "period", nullable = false)
    private String period;

    @Column(name = "policy_number")
    private String policyNumber;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    // IFRS 17 I1 (finaccounting V10): who wrote the journal, the people and reason behind a manual one, what it
    // reverses, and the accounting policy register version in force when it was posted. created_xid is the
    // database's own (a default), never written from here.
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private JournalSource sourceType = JournalSource.EVENT;

    @Column(name = "preparer")
    private String preparer;

    @Column(name = "approver")
    private String approver;

    @Column(name = "reason")
    private String reason;

    @Column(name = "reason_code")
    private String reasonCode;

    @Column(name = "document_refs")
    private String documentRefs;

    @Column(name = "reverses_journal_id")
    private UUID reversesJournalId;

    @Column(name = "auto_reverse_on")
    private LocalDate autoReverseOn;

    @Column(name = "policy_register_version", nullable = false)
    private int policyRegisterVersion;

    @Column(name = "rule_version")
    private String ruleVersion;

    @Column(name = "engine_run_id")
    private UUID engineRunId;

    /** The P-19 expense allocation the journal posts or reverses (IFRS 17 I5b, finaccounting V16). */
    @Column(name = "expense_allocation_id")
    private UUID expenseAllocationId;

    /** The year-end close the journal posts or reverses (IFRS 17 I6, finaccounting V17). */
    @Column(name = "year_end_close_id")
    private UUID yearEndCloseId;

    /** One leg's facts, before it becomes a persistent {@link GlPosting} row. */
    public record Leg(String accountCode, PostingDirection direction, BigDecimal amount, String currency,
                      LineDimensions dimensions) {}

    @Transient
    private final List<Leg> legs = new ArrayList<>();

    protected JournalEntry() {}

    public JournalEntry(UUID tenantId, String sourceEvent, String sourceRef, String period,
                         String policyNumber, String createdBy) {
        this.tenantId = tenantId;
        this.sourceEvent = sourceEvent;
        this.sourceRef = sourceRef;
        this.period = period;
        this.policyNumber = policyNumber;
        this.createdBy = createdBy;
    }

    /**
     * @throws IllegalArgumentException if the amount is not a positive magnitude (the direction
     *         carries the sign -- see V2's {@code gl_posting_amount_positive}), or if the currency
     *         differs from an existing leg's (no FX table exists on this platform, so a
     *         mixed-currency entry could never be meaningfully balanced)
     */
    public void addLeg(String accountCode, PostingDirection direction, BigDecimal amount, String currency) {
        addLeg(accountCode, direction, amount, currency, LineDimensions.NONE);
    }

    /** A leg with the guide's line dimensions (2.2). */
    public void addLeg(String accountCode, PostingDirection direction, BigDecimal amount, String currency,
                       LineDimensions dimensions) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("A posting amount must be a positive magnitude; "
                + "direction carries the sign. Got: " + amount);
        }
        if (!legs.isEmpty() && !legs.get(0).currency().equals(currency)) {
            throw new IllegalArgumentException("All legs of one journal entry must share a currency; "
                + "entry is " + legs.get(0).currency() + ", leg is " + currency);
        }
        legs.add(new Leg(accountCode, direction, amount, currency, dimensions == null ? LineDimensions.NONE : dimensions));
    }

    /**
     * True only when there is at least one leg on EACH side and the two sides' totals are equal.
     * The at-least-one-per-side requirement matters: an entry with no legs would otherwise report
     * balanced on 0 == 0, and an entry with two same-side legs would report balanced only if both
     * were zero (which addLeg forbids) -- both are invalid entries, not balanced ones.
     */
    public boolean isBalanced() {
        BigDecimal debits = total(PostingDirection.DR);
        BigDecimal credits = total(PostingDirection.CR);
        if (debits.signum() == 0 || credits.signum() == 0) {
            return false;
        }
        return debits.compareTo(credits) == 0;
    }

    private BigDecimal total(PostingDirection direction) {
        return legs.stream()
            .filter(l -> l.direction() == direction)
            .map(Leg::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<Leg> getLegs() { return Collections.unmodifiableList(legs); }

    public UUID getJournalEntryId() { return journalEntryId; }
    public UUID getTenantId() { return tenantId; }
    public String getSourceEvent() { return sourceEvent; }
    public String getSourceRef() { return sourceRef; }
    public String getPeriod() { return period; }
    public String getPolicyNumber() { return policyNumber; }
    public Instant getPostedAt() { return postedAt; }
    public String getCreatedBy() { return createdBy; }
    public JournalSource getSourceType() { return sourceType; }
    public String getPreparer() { return preparer; }
    public String getApprover() { return approver; }
    public String getReason() { return reason; }
    public String getReasonCode() { return reasonCode; }
    public String getDocumentRefs() { return documentRefs; }
    public UUID getReversesJournalId() { return reversesJournalId; }
    public LocalDate getAutoReverseOn() { return autoReverseOn; }
    public int getPolicyRegisterVersion() { return policyRegisterVersion; }
    public String getRuleVersion() { return ruleVersion; }
    public UUID getEngineRunId() { return engineRunId; }
    public UUID getExpenseAllocationId() { return expenseAllocationId; }
    public UUID getYearEndCloseId() { return yearEndCloseId; }

    /** A journal the platform or the IFRS 17 engine writes, not an event's. */
    public JournalEntry withSource(JournalSource source) {
        this.sourceType = source;
        return this;
    }

    /** A manual journal: two people, a reason, and on a BOTH account a reason code (the database checks all of it). */
    public JournalEntry asManual(String preparer, String approver, String reason, String reasonCode, String documentRefs) {
        this.sourceType = JournalSource.MANUAL;
        this.preparer = preparer;
        this.approver = approver;
        this.reason = reason;
        this.reasonCode = reasonCode;
        this.documentRefs = documentRefs;
        return this;
    }

    public JournalEntry reversing(UUID journalEntryId) {
        this.reversesJournalId = journalEntryId;
        return this;
    }

    public JournalEntry autoReverseOn(LocalDate date) {
        this.autoReverseOn = date;
        return this;
    }

    public JournalEntry fromEngineRun(UUID runId) {
        this.sourceType = JournalSource.ENGINE_RUN;
        this.engineRunId = runId;
        return this;
    }

    /** A P-19 expense allocation's journal (IFRS 17 I5b): the platform's own approved run, so SYSTEM. */
    public JournalEntry fromExpenseAllocation(UUID allocationId) {
        this.sourceType = JournalSource.SYSTEM;
        this.expenseAllocationId = allocationId;
        return this;
    }

    /** A year-end close's journal (IFRS 17 I6): the platform's own approved run, so SYSTEM. */
    public JournalEntry fromYearEndClose(UUID closeId) {
        this.sourceType = JournalSource.SYSTEM;
        this.yearEndCloseId = closeId;
        return this;
    }

    public JournalEntry underRuleVersion(String version) {
        this.ruleVersion = version;
        return this;
    }

    /** Stamped when posted: the accounting policy register version the journal was posted under (spec D7). */
    public void stampPolicyRegisterVersion(int version) {
        this.policyRegisterVersion = version;
    }
}
