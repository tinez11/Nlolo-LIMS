package tz.co.nlolo.lifeplatform.finaccounting.domain;

import tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code finaccounting.gl_posting} -- ONE LEG of a journal entry. Append-only at the DB
 * level ({@code REVOKE UPDATE, DELETE}), and partitioned by {@code created_at}, which is why its
 * primary key is composite ({@code posting_id, created_at}) -- Postgres requires the partition key
 * in the PK.
 *
 * <p>{@code amount} is always a POSITIVE magnitude; {@link PostingDirection} carries the sign.
 * {@code groupId} is deliberately null throughout M9 -- a group of insurance contracts is the
 * C1-governed IFRS 17 unit of account.
 */
@Entity
@Table(name = "gl_posting", schema = "finaccounting")
@IdClass(GlPostingId.class)
public class GlPosting {

    @Id
    @Column(name = "posting_id")
    private UUID postingId = UUID.randomUUID();

    @Id
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "journal_entry_id", nullable = false)
    private UUID journalEntryId;

    /** Null for all of M9 -- see the class javadoc. */
    @Column(name = "group_id")
    private UUID groupId;

    @Column(name = "account_code", nullable = false)
    private String accountCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private PostingDirection direction;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "period", nullable = false)
    private String period;

    @Column(name = "policy_number")
    private String policyNumber;

    @Column(name = "posting_type", nullable = false)
    private String postingType;

    @Column(name = "source_event", nullable = false)
    private String sourceEvent;

    @Column(name = "source_ref", nullable = false)
    private String sourceRef;

    // The guide's line dimensions (2.2, finaccounting V10).
    @Column(name = "ifrs17_group") private String ifrs17Group;
    @Column(name = "measurement_model") private String measurementModel;
    @Column(name = "movement_type") private String movementType;
    @Column(name = "product_id") private UUID productId;
    @Column(name = "portfolio") private String portfolio;
    @Column(name = "channel") private String channel;
    @Column(name = "branch") private String branch;
    @Column(name = "fund") private String fund;
    @Column(name = "reference_type") private String referenceType;
    @Column(name = "reference") private String reference;

    protected GlPosting() {}

    public GlPosting(UUID tenantId, UUID journalEntryId, String accountCode, PostingDirection direction,
                      BigDecimal amount, String currency, String period, String policyNumber,
                      String sourceEvent, String sourceRef, LineDimensions dimensions) {
        this.tenantId = tenantId;
        this.journalEntryId = journalEntryId;
        this.accountCode = accountCode;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
        this.period = period;
        this.policyNumber = policyNumber;
        // V1 declares posting_type NOT NULL with no default. It predates account_code/direction and
        // is redundant now, but V1 is immutable, so it is populated with the source event rather
        // than left to fail the NOT NULL. V2 widens it from VARCHAR(30) to VARCHAR(60) to match
        // source_event -- required, not tidying: 'billing.PremiumInvoiceGenerated' is 31 characters.
        this.postingType = sourceEvent;
        this.sourceEvent = sourceEvent;
        this.sourceRef = sourceRef;
        LineDimensions d = dimensions == null ? LineDimensions.NONE : dimensions;
        this.ifrs17Group = d.ifrs17Group();
        this.measurementModel = d.measurementModel();
        this.movementType = d.movementType();
        this.productId = d.productId();
        this.portfolio = d.portfolio();
        this.channel = d.channel();
        this.branch = d.branch();
        this.fund = d.fund();
        this.referenceType = d.referenceType();
        this.reference = d.reference();
    }

    public LineDimensions getDimensions() {
        return new LineDimensions(ifrs17Group, measurementModel, movementType, productId, portfolio, channel, branch,
            fund, referenceType, reference);
    }

    public UUID getPostingId() { return postingId; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getTenantId() { return tenantId; }
    public UUID getJournalEntryId() { return journalEntryId; }
    public UUID getGroupId() { return groupId; }
    public String getAccountCode() { return accountCode; }
    public PostingDirection getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getPeriod() { return period; }
    public String getPolicyNumber() { return policyNumber; }
    public String getPostingType() { return postingType; }
    public String getSourceEvent() { return sourceEvent; }
    public String getSourceRef() { return sourceRef; }
}
