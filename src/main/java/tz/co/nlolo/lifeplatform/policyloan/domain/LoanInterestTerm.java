package tz.co.nlolo.lifeplatform.policyloan.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Deliverable 3 Rev 2, L1: effective-dated rather than a single immutable field, so
 * "locked at origination" vs "floating" is a data question once B2 is finally decided. */
@Entity
@Table(name = "loan_interest_term", schema = "policyloan")
public class LoanInterestTerm {

    @Id
    @Column(name = "loan_interest_term_id")
    private UUID loanInterestTermId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "loan_id", nullable = false)
    private UUID loanId;

    @Column(nullable = false)
    private BigDecimal rate;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected LoanInterestTerm() {}

    public LoanInterestTerm(UUID tenantId, UUID loanId, BigDecimal rate, LocalDate effectiveFrom) {
        this.loanInterestTermId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.loanId = loanId;
        this.rate = rate;
        this.effectiveFrom = effectiveFrom;
    }

    public UUID getLoanId() { return loanId; }
    public BigDecimal getRate() { return rate; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
}
