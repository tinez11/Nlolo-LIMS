package tz.co.nlolo.lifeplatform.bonus.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.bonus.api.OutcomeKind;

import java.time.Instant;
import java.util.UUID;

/** What one declaration did to one policy (plan R4). Insert-only; one per (declaration, policy). */
@Entity
@Table(name = "declaration_outcome", schema = "bonus")
public class DeclarationOutcome {
    @Id @UuidGenerator @Column(name = "outcome_id") private UUID outcomeId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "declaration_id", nullable = false) private UUID declarationId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private String outcome;
    @Column private String reason;
    @Column(name = "decided_at", nullable = false) private Instant decidedAt = Instant.now();

    protected DeclarationOutcome() {}

    public DeclarationOutcome(UUID tenantId, UUID declarationId, String policyNumber, OutcomeKind outcome, String reason) {
        this.tenantId = tenantId;
        this.declarationId = declarationId;
        this.policyNumber = policyNumber;
        this.outcome = outcome.name();
        this.reason = reason;
    }

    public UUID getDeclarationId() { return declarationId; }
    public String getPolicyNumber() { return policyNumber; }
    public OutcomeKind outcome() { return OutcomeKind.valueOf(outcome); }
    public String getReason() { return reason; }
    public Instant getDecidedAt() { return decidedAt; }
}
