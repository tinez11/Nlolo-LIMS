package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.accumulation.api.MaturityAction;

import java.time.Instant;
import java.util.UUID;

/** What the client asked for at the end of a term (V3). Superseded, never edited. */
@Entity
@Table(name = "maturity_instruction", schema = "accumulation")
public class MaturityInstruction {
    @Id @UuidGenerator @Column(name = "instruction_id") private UUID instructionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "period_id", nullable = false) private UUID periodId;
    @Column(nullable = false) private String action;
    @Column(name = "term_months") private Integer termMonths;
    @Column(name = "payee_ref") private String payeeRef;
    @Column(name = "recorded_by", nullable = false) private String recordedBy;
    @Column(name = "recorded_at", nullable = false) private Instant recordedAt;
    @Column(name = "superseded_at") private Instant supersededAt;
    @Version private long version;

    protected MaturityInstruction() {}

    public MaturityInstruction(UUID tenantId, String policyNumber, UUID periodId, MaturityAction action, Integer termMonths,
                               String payeeRef, String recordedBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.periodId = periodId;
        this.action = action.name();
        this.termMonths = termMonths;
        this.payeeRef = payeeRef;
        this.recordedBy = recordedBy;
        this.recordedAt = Instant.now();
    }

    public void supersede() { this.supersededAt = Instant.now(); }

    public MaturityAction action() { return MaturityAction.valueOf(action); }
    public UUID getInstructionId() { return instructionId; }
    public UUID getPeriodId() { return periodId; }
    public Integer getTermMonths() { return termMonths; }
    public String getPayeeRef() { return payeeRef; }
    public String getRecordedBy() { return recordedBy; }
    public Instant getRecordedAt() { return recordedAt; }
}
