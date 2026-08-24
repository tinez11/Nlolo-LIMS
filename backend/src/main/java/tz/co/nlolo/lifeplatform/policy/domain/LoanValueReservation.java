package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Module-Architecture-B1 fix (docs/02-module-architecture.md §3.4/§3.5 -- see plan header
 * for the full disambiguation from the unrelated Aggregate-Design-doc "B1"). Status lifecycle:
 * RESERVED -(confirmReservation)-> CONFIRMED, RESERVED -(releaseReservation)-> RELEASED,
 * RESERVED -(TTL sweep, Task 3)-> EXPIRED. CONFIRMED/RELEASED/EXPIRED are all terminal.
 */
@Entity
@Table(name = "loan_value_reservation", schema = "policy")
public class LoanValueReservation {

    @Id
    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "TZS";

    @Column(nullable = false)
    private String status = "RESERVED";

    @Column(name = "ttl_expires_at", nullable = false)
    private Instant ttlExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected LoanValueReservation() {}

    public LoanValueReservation(UUID tenantId, String policyNumber, BigDecimal amount, String currency, Instant ttlExpiresAt) {
        this.reservationId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.amount = amount;
        this.currency = currency;
        this.ttlExpiresAt = ttlExpiresAt;
    }

    public UUID getReservationId() { return reservationId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public Instant getTtlExpiresAt() { return ttlExpiresAt; }

    public void confirm() { this.status = "CONFIRMED"; }
    public void release() { this.status = "RELEASED"; }
}
