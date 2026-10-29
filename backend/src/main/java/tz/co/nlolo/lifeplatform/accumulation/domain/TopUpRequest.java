package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** REQUESTED -> COLLECTED | FAILED. Credited only on COLLECTED: money is in when payment says so. */
@Entity
@Table(name = "top_up_request", schema = "accumulation")
public class TopUpRequest {
    @Id @UuidGenerator @Column(name = "top_up_id") private UUID topUpId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private BigDecimal amount;
    @Column(nullable = false) private String currency;
    @Column(name = "payer_ref", nullable = false) private String payerRef;
    // How it came in (V7, 2026-10-28): a mobile-money request, or money already received by hand with its reference.
    @Column(nullable = false) private String method = "MOBILE_MONEY";
    @Column private String reference;
    @Column(name = "received_on") private java.time.LocalDate receivedOn;
    @Column(nullable = false) private String status = "REQUESTED";
    @Column(name = "requested_by", nullable = false) private String requestedBy;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt = Instant.now();
    @Version private long version;

    protected TopUpRequest() {}

    public TopUpRequest(UUID tenantId, String policyNumber, BigDecimal amount, String currency, String payerRef, String requestedBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.amount = amount;
        this.currency = currency;
        this.payerRef = payerRef;
        this.requestedBy = requestedBy;
    }

    /** Money already received -- cash, a bank deposit, a cheque, a Lipa number or TANQR -- recorded by one person. */
    public static TopUpRequest received(UUID tenantId, String policyNumber, BigDecimal amount, String currency,
                                        String method, String reference, java.time.LocalDate receivedOn, String payerRef,
                                        String recordedBy) {
        TopUpRequest t = new TopUpRequest(tenantId, policyNumber, amount, currency, payerRef, recordedBy);
        t.method = method;
        t.reference = reference;
        t.receivedOn = receivedOn;
        return t;
    }

    public boolean markCollected() {
        if (!"REQUESTED".equals(status)) return false;
        this.status = "COLLECTED";
        return true;
    }

    public boolean markFailed() {
        if (!"REQUESTED".equals(status)) return false;
        this.status = "FAILED";
        return true;
    }

    public UUID getTopUpId() { return topUpId; }
    public String getPolicyNumber() { return policyNumber; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getPayerRef() { return payerRef; }
    public String getMethod() { return method; }
    public String getReference() { return reference; }
    public java.time.LocalDate getReceivedOn() { return receivedOn; }
    public String getStatus() { return status; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
}
