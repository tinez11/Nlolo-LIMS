package tz.co.nlolo.lifeplatform.benefitpayout.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutStateException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One day's batch of income instalments for one tenant (decision Q7).
 *
 * <p>Why a batch exists at all: an income plan pays twelve times a year for twenty years, and
 * asking two people to sign each of those across a book is a queue nobody clears. The stream's
 * FIRST instalment is approved by two people in the ordinary way; after that the system assembles
 * the day's instalments and one person releases the batch. The control that replaces the second
 * signature is the proof-of-life interval on the stream -- let it lapse and the stream suspends, so
 * the batch cannot quietly keep paying a life nobody has confirmed.
 *
 * <p>Unique on (tenant, run date), so the drain running twice in a day adds to today's run rather
 * than opening a second one.
 */
@Entity
@Table(name = "payment_run", schema = "benefitpayout")
public class PaymentRun {

    @Id
    @UuidGenerator
    @Column(name = "payment_run_id")
    private UUID paymentRunId;

    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "run_date", nullable = false) private LocalDate runDate;
    @Column(nullable = false) private String status = "PREPARED";
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Version private long version;

    protected PaymentRun() {}

    public PaymentRun(UUID tenantId, LocalDate runDate) {
        this.tenantId = tenantId;
        this.runDate = runDate;
    }

    public void approve(String approver) {
        if (!"PREPARED".equals(status)) {
            throw new PayoutStateException("Payment run " + paymentRunId + " is already " + status);
        }
        if (approver == null || approver.isBlank()) {
            throw new PayoutStateException("A payment run needs an approver");
        }
        this.status = "APPROVED";
        this.approvedBy = approver;
        this.approvedAt = Instant.now();
    }

    public boolean isPrepared() { return "PREPARED".equals(status); }

    public UUID getPaymentRunId() { return paymentRunId; }
    public UUID getTenantId() { return tenantId; }
    public LocalDate getRunDate() { return runDate; }
    public String getStatus() { return status; }
    public String getApprovedBy() { return approvedBy; }
}
