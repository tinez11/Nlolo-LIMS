package tz.co.nlolo.lifeplatform.payment.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Deliverable 3 Rev 2 §7.3 (Pay1): holds DisbursementInstruction references BY ID only, never
 * embedded -- "avoiding the same large-aggregate trap just fixed in `party`".
 *
 * <p>The doc says batch status is "derived (all-succeeded / partial-failure / in-progress) from
 * the referenced instructions' individual statuses, not duplicated", while V1's DDL stores a
 * CHECK-constrained `status` column. Both are honoured here without contradiction: deriveStatus
 * is the single source of truth and computes the value from the member instructions; the stored
 * column is a materialization of that derivation, written only by recomputeStatus, never set
 * independently. That keeps the read endpoint a single-row fetch while making the derivation
 * authoritative.
 */
@Entity
@Table(name = "payout_batch", schema = "payment")
public class PayoutBatch {

    @Id
    @Column(name = "batch_id")
    private UUID batchId = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "batch_type", nullable = false)
    private String batchType;

    @Column(nullable = false)
    private String status = "IN_PROGRESS";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    protected PayoutBatch() {}

    public PayoutBatch(UUID tenantId, String batchType) {
        this.tenantId = tenantId;
        this.batchType = batchType;
    }

    /**
     * Partial-failure handling (roadmap acceptance criterion 3), stated as one rule: a batch is
     * IN_PROGRESS while any member is still PENDING, COMPLETED only if every member COMPLETED,
     * and PARTIAL_FAILURE if all members are terminal but at least one FAILED. An all-FAILED
     * batch is also PARTIAL_FAILURE -- the DDL's CHECK offers no ALL_FAILED value, and
     * "some money didn't move" is the operationally actionable distinction, not "how much".
     * An empty batch stays IN_PROGRESS: nothing has succeeded, so COMPLETED would be a lie.
     */
    public static String deriveStatus(List<String> memberStatuses) {
        if (memberStatuses.isEmpty() || memberStatuses.contains("PENDING")) {
            return "IN_PROGRESS";
        }
        return memberStatuses.contains("FAILED") ? "PARTIAL_FAILURE" : "COMPLETED";
    }

    public void recomputeStatus(List<String> memberStatuses) {
        this.status = deriveStatus(memberStatuses);
    }

    public UUID getBatchId() { return batchId; }
    public UUID getTenantId() { return tenantId; }
    public String getBatchType() { return batchType; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
