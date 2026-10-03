package tz.co.nlolo.lifeplatform.bonus.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.bonus.api.BonusValuation;
import tz.co.nlolo.lifeplatform.bonus.api.ExitType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The bonus an exit was paid with, recorded once per exit (ux_settlement_exit). Insert-only. */
@Entity
@Table(name = "settlement", schema = "bonus")
public class Settlement {
    @Id @UuidGenerator @Column(name = "settlement_id") private UUID settlementId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "exit_type", nullable = false) private String exitType;
    @Column(name = "exit_ref", nullable = false) private String exitRef;
    @Column(name = "exit_date", nullable = false) private LocalDate exitDate;
    @Column(name = "attached_amount", nullable = false) private BigDecimal attachedAmount;
    @Column(name = "interim_amount", nullable = false) private BigDecimal interimAmount;
    @Column(name = "terminal_amount", nullable = false) private BigDecimal terminalAmount;
    @Column(name = "interim_rate_percent") private BigDecimal interimRatePercent;
    @Column(name = "terminal_rate_percent") private BigDecimal terminalRatePercent;
    @Column(name = "declaration_id") private UUID declarationId;
    @Column(name = "recorded_at", nullable = false) private Instant recordedAt = Instant.now();

    protected Settlement() {}

    public Settlement(UUID tenantId, String policyNumber, ExitType type, String exitRef, LocalDate exitDate, BonusValuation v) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.exitType = type.name();
        this.exitRef = exitRef;
        this.exitDate = exitDate;
        this.attachedAmount = v.attached();
        this.interimAmount = v.interim();
        this.terminalAmount = v.terminal();
        this.interimRatePercent = v.interimRatePercent();
        this.terminalRatePercent = v.terminalRatePercent();
        this.declarationId = v.declarationId();
    }

    public BonusValuation toValuation() {
        return new BonusValuation(attachedAmount, interimAmount, terminalAmount, interimRatePercent, terminalRatePercent, declarationId);
    }

    public String getPolicyNumber() { return policyNumber; }
    public ExitType type() { return ExitType.valueOf(exitType); }
    public String getExitRef() { return exitRef; }
    public LocalDate getExitDate() { return exitDate; }
    public Instant getRecordedAt() { return recordedAt; }
}
