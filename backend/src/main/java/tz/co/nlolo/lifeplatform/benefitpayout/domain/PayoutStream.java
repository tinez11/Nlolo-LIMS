package tz.co.nlolo.lifeplatform.benefitpayout.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutStateException;
import tz.co.nlolo.lifeplatform.benefitpayout.api.StreamStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A recurring income stream: the instalments of one INCOME row, and the proof-of-life clock that
 * governs them (decision Q7, guide §10).
 *
 * <p>This is what makes an income plan different from a run of lump sums. Its first instalment is
 * approved by two people, which activates the stream; after that the instalments are batched into
 * daily payment runs and approved as a batch, because twelve approvals per policy per year across
 * a book is a queue nobody clears. The control that replaces the second signature is the
 * proof-of-life interval: let it lapse and the stream suspends.
 */
@Entity
@Table(name = "payout_stream", schema = "benefitpayout")
public class PayoutStream {

    @Id
    @UuidGenerator
    @Column(name = "stream_id")
    private UUID streamId;

    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "row_order", nullable = false) private int rowOrder;
    @Column(nullable = false) private String status = StreamStatus.PENDING_ACTIVATION.name();
    @Column(name = "proof_of_life_interval_months", nullable = false) private int proofOfLifeIntervalMonths;
    @Column(name = "proof_of_life_due_date") private LocalDate proofOfLifeDueDate;
    // An annuity's open-ended stream (product step 5, benefitpayout V2). Null on every INCOME stream.
    @Column(name = "open_ended", nullable = false) private boolean openEnded;
    @Column private String frequency;
    @Column(name = "first_due_date") private LocalDate firstDueDate;
    @Column(name = "base_amount") private BigDecimal baseAmount;
    @Column private String currency;
    @Column(name = "escalation_percent") private BigDecimal escalationPercent;
    @Column(name = "amount_multiplier", nullable = false) private BigDecimal amountMultiplier = BigDecimal.ONE;
    @Column(name = "expanded_through") private LocalDate expandedThrough;
    @Column(name = "redirect_from") private LocalDate redirectFrom;
    @Column(name = "redirect_until") private LocalDate redirectUntil;
    @Column(name = "redirect_payee_ref") private String redirectPayeeRef;
    @Column(name = "proof_of_life_stopped", nullable = false) private boolean proofOfLifeStopped;
    @Version private long version;

    protected PayoutStream() {}

    /**
     * An annuity's income for life (product step 5): no end date, so it is expanded a horizon at a
     * time from the locked base. Row 0, because an annuity authors no payout rows.
     */
    public static PayoutStream annuity(UUID tenantId, String policyNumber, int proofOfLifeIntervalMonths, String frequency,
                                       LocalDate firstDueDate, BigDecimal baseAmount, String currency, BigDecimal escalationPercent) {
        PayoutStream s = new PayoutStream(tenantId, policyNumber, 0, proofOfLifeIntervalMonths);
        s.openEnded = true;
        s.frequency = frequency;
        s.firstDueDate = firstDueDate;
        s.baseAmount = baseAmount;
        s.currency = currency;
        s.escalationPercent = escalationPercent;
        s.expandedThrough = firstDueDate.minusDays(1);
        return s;
    }

    public void expandedThrough(LocalDate horizon) {
        if (expandedThrough == null || horizon.isAfter(expandedThrough)) {
            this.expandedThrough = horizon;
        }
    }

    /** A joint annuity's first death: every instalment from here pays the survivor percentage. */
    public void reduceTo(BigDecimal multiplier) {
        this.amountMultiplier = multiplier;
    }

    /**
     * The last death inside a guarantee: from {@code from} to {@code until} the instalments go to the
     * beneficiaries, and proof of life stops. A null payee means none is known, so the next
     * instalment waits for a reviewer -- the stream goes back to awaiting activation, and its first
     * payment to the new payee earns two signatures like the annuitant's first did.
     */
    public void redirect(LocalDate from, LocalDate until, String payeeRef) {
        this.redirectFrom = from;
        this.redirectUntil = until;
        this.redirectPayeeRef = payeeRef;
        this.proofOfLifeStopped = true;
        if (payeeRef == null && status() != StreamStatus.ENDED) {
            this.status = StreamStatus.PENDING_ACTIVATION.name();
            this.proofOfLifeDueDate = null;
        }
    }

    public boolean isOpenEnded() { return openEnded; }
    public String getFrequency() { return frequency; }
    public LocalDate getFirstDueDate() { return firstDueDate; }
    public BigDecimal getBaseAmount() { return baseAmount; }
    public String getCurrency() { return currency; }
    public BigDecimal getEscalationPercent() { return escalationPercent; }
    public BigDecimal getAmountMultiplier() { return amountMultiplier; }
    public LocalDate getExpandedThrough() { return expandedThrough; }
    public LocalDate getRedirectFrom() { return redirectFrom; }
    public LocalDate getRedirectUntil() { return redirectUntil; }
    public String getRedirectPayeeRef() { return redirectPayeeRef; }
    public boolean isProofOfLifeStopped() { return proofOfLifeStopped; }
    public UUID getTenantId() { return tenantId; }

    public PayoutStream(UUID tenantId, String policyNumber, int rowOrder, int proofOfLifeIntervalMonths) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.rowOrder = rowOrder;
        this.proofOfLifeIntervalMonths = proofOfLifeIntervalMonths;
    }

    public StreamStatus status() { return StreamStatus.valueOf(status); }

    /** The first instalment was approved, with proof of life taken on {@code provenOn}. */
    public void activate(LocalDate provenOn) {
        if (status() != StreamStatus.PENDING_ACTIVATION) {
            throw new PayoutStateException("Stream " + streamId + " is " + status + ", not awaiting activation");
        }
        this.status = StreamStatus.ACTIVE.name();
        // A redirected annuity's beneficiaries prove nothing: the life it proved is over.
        this.proofOfLifeDueDate = proofOfLifeStopped ? null : provenOn.plusMonths(proofOfLifeIntervalMonths);
    }

    /** Proof of life is overdue. Idempotent, so a drain may say it twice without complaint. */
    public void suspendForProofOfLife() {
        if (status() == StreamStatus.ACTIVE) {
            this.status = StreamStatus.SUSPENDED.name();
        }
    }

    /** Fresh proof: an ACTIVE or SUSPENDED stream runs again until the next interval falls due. */
    public void recordProofOfLife(LocalDate provenOn) {
        if (status() != StreamStatus.ACTIVE && status() != StreamStatus.SUSPENDED) {
            throw new PayoutStateException("Stream " + streamId + " is " + status + "; proof of life cannot be recorded");
        }
        this.status = StreamStatus.ACTIVE.name();
        this.proofOfLifeDueDate = provenOn.plusMonths(proofOfLifeIntervalMonths);
    }

    /** The policy ended -- lapsed, surrendered, cancelled or claimed. Nothing further is owed. */
    public void end() { this.status = StreamStatus.ENDED.name(); }

    /**
     * Reinstatement after a lapse. The stream goes back to awaiting activation rather than straight
     * to ACTIVE, because the proof of life taken before the lapse says nothing about a person who
     * has been out of contact since: the next instalment earns its two signatures and fresh proof.
     */
    public void reopen() {
        if (status() == StreamStatus.ENDED) {
            this.status = StreamStatus.PENDING_ACTIVATION.name();
            this.proofOfLifeDueDate = null;
        }
    }

    public UUID getStreamId() { return streamId; }
    public String getPolicyNumber() { return policyNumber; }
    public int getRowOrder() { return rowOrder; }
    public LocalDate getProofOfLifeDueDate() { return proofOfLifeDueDate; }
}
