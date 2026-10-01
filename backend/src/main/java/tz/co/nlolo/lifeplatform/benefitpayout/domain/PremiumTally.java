package tz.co.nlolo.lifeplatform.benefitpayout.domain;

import jakarta.persistence.*;
import tz.co.nlolo.lifeplatform.product.api.PremiumFrequency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What billing has reported about one policy's premiums.
 *
 * <p>It exists because {@code benefitpayout} may not depend on {@code billing} (both already
 * depend on {@code policy}), so the figures arrive by event and land here. Two questions are
 * asked of it: how much has been collected -- which values a premium return and the
 * higher-of-premiums death benefit -- and {@link #isPaidUpTo}, which decides whether a payout
 * falling due today is held for arrears (decision Q3).
 */
@Entity
@Table(name = "premium_tally", schema = "benefitpayout")
public class PremiumTally {

    @Id
    @Column(name = "policy_number")
    private String policyNumber;

    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "premium_frequency", nullable = false) private String premiumFrequency;
    @Column(name = "issue_date", nullable = false) private LocalDate issueDate;
    @Column(name = "premium_paying_until") private LocalDate premiumPayingUntil;
    @Column(name = "premiums_collected", nullable = false) private BigDecimal premiumsCollected = BigDecimal.ZERO;
    @Column(nullable = false) private String currency = "TZS";
    @Column(name = "paid_to_date") private LocalDate paidToDate;
    @Version private long version;

    protected PremiumTally() {}

    public PremiumTally(UUID tenantId, String policyNumber, String premiumFrequency, LocalDate issueDate,
                        LocalDate premiumPayingUntil, String currency) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.premiumFrequency = premiumFrequency;
        this.issueDate = issueDate;
        this.premiumPayingUntil = premiumPayingUntil;
        this.currency = currency;
    }

    public void record(BigDecimal collected, LocalDate newPaidToDate) {
        this.premiumsCollected = this.premiumsCollected.add(collected);
        if (newPaidToDate != null) {
            this.paidToDate = newPaidToDate;
        }
    }

    /**
     * Is the policy paid up to the date this payout falls due?
     *
     * <p>A single-premium contract asks only whether that one premium arrived. Otherwise the test
     * is the last instalment that fell due on or before the payout -- never past the premium-paying
     * term, since a limited-pay policy owes nothing after it -- against billing's own CONTIGUOUS
     * paid-to date. Contiguous matters: a policy that paid month 12 but missed month 3 is in
     * arrears, and a naive "latest payment" test would call it current.
     */
    public boolean isPaidUpTo(LocalDate payoutDate) {
        PremiumFrequency frequency = PremiumFrequency.valueOf(premiumFrequency);
        if (frequency == PremiumFrequency.SINGLE) {
            return premiumsCollected.signum() > 0;
        }
        LocalDate lastRequired = lastPremiumDueOnOrBefore(frequency, payoutDate);
        return lastRequired == null || (paidToDate != null && !paidToDate.isBefore(lastRequired));
    }

    /** Billing's due dates run issue + k periods, k >= 1 (premium in arrears), up to the paying end. */
    private LocalDate lastPremiumDueOnOrBefore(PremiumFrequency frequency, LocalDate payoutDate) {
        LocalDate limit = premiumPayingUntil != null && premiumPayingUntil.isBefore(payoutDate)
            ? premiumPayingUntil : payoutDate;
        int months = 12 / frequency.instalmentsPerYear();
        LocalDate last = null;
        for (LocalDate d = issueDate.plusMonths(months); !d.isAfter(limit); d = d.plusMonths(months)) {
            last = d;
        }
        return last;
    }

    public BigDecimal getPremiumsCollected() { return premiumsCollected; }
    public String getCurrency() { return currency; }
    public LocalDate getPaidToDate() { return paidToDate; }
}
