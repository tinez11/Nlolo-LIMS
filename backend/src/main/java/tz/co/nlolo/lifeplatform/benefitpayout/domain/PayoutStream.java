package tz.co.nlolo.lifeplatform.benefitpayout.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutStateException;
import tz.co.nlolo.lifeplatform.benefitpayout.api.StreamStatus;

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
    @Version private long version;

    protected PayoutStream() {}

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
        this.proofOfLifeDueDate = provenOn.plusMonths(proofOfLifeIntervalMonths);
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

    public UUID getStreamId() { return streamId; }
    public String getPolicyNumber() { return policyNumber; }
    public int getRowOrder() { return rowOrder; }
    public LocalDate getProofOfLifeDueDate() { return proofOfLifeDueDate; }
}
