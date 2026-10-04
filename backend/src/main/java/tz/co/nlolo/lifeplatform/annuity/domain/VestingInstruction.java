package tz.co.nlolo.lifeplatform.annuity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A staff instruction for a pension's vesting (product step 5 D2): when, into which form and
 * frequency, with which joint life and lump sum, and on a deferral whether contributions continue.
 * Every instruction is kept; one is current (annuity V2's partial unique index).
 */
@Entity
@Table(name = "vesting_instruction", schema = "annuity")
public class VestingInstruction {
    @Id @Column(name = "instruction_id") private UUID instructionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "vesting_date", nullable = false) private LocalDate vestingDate;
    @Column(name = "form_code", nullable = false) private String formCode;
    @Column(nullable = false) private String frequency;
    @Column(name = "joint_life_party_id") private UUID jointLifePartyId;
    @Column(name = "lump_sum_percent", nullable = false) private BigDecimal lumpSumPercent;
    @Column private String contributions;
    @Column(name = "recorded_by", nullable = false) private String recordedBy;
    @Column(name = "recorded_at", nullable = false) private Instant recordedAt = Instant.now();
    @Column(nullable = false) private boolean current;

    protected VestingInstruction() {}

    public VestingInstruction(UUID tenantId, String policyNumber, LocalDate vestingDate, String formCode, String frequency,
                              UUID jointLifePartyId, BigDecimal lumpSumPercent, String contributions, String recordedBy) {
        this.instructionId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.vestingDate = vestingDate;
        this.formCode = formCode;
        this.frequency = frequency;
        this.jointLifePartyId = jointLifePartyId;
        this.lumpSumPercent = lumpSumPercent;
        this.contributions = contributions;
        this.recordedBy = recordedBy;
        this.current = true;
    }

    /** Superseded by a newer instruction; kept for the record. */
    public void supersede() {
        this.current = false;
    }

    public LocalDate getVestingDate() { return vestingDate; }
    public String getFormCode() { return formCode; }
    public String getFrequency() { return frequency; }
    public UUID getJointLifePartyId() { return jointLifePartyId; }
    public BigDecimal getLumpSumPercent() { return lumpSumPercent; }
    public String getContributions() { return contributions; }
}
