package tz.co.nlolo.lifeplatform.billing.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Never mutated on a premium change -- old schedule TERMINATED, new one created ACTIVE
 * (db-migrations/billing/V1's own column comment). One ACTIVE schedule per in-force policy at
 * a time; billing.application.BillingApiImpl enforces that invariant, not a DB constraint
 * (mirrors policy.domain.Policy's own state-machine-in-code-not-DB convention).
 */
@Entity
@Table(name = "billing_schedule", schema = "billing")
public class BillingSchedule {

    @Id
    @Column(name = "billing_schedule_id")
    private UUID billingScheduleId = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "premium_frequency", nullable = false)
    private String premiumFrequency;

    @Column(name = "premium_amount", nullable = false)
    private BigDecimal premiumAmount;

    @Column(name = "premium_currency", nullable = false)
    private String premiumCurrency = "TZS";

    @Column(name = "next_due_date")
    private LocalDate nextDueDate;

    /**
     * The last date a premium may fall due (V8). Null where the contract does not term -- whole
     * life, an annually renewable scheme -- which the roll-forward drain reads as "no end". The
     * roll-forward stops raising invoices once {@code nextDueDate} passes this.
     */
    @Column(name = "premium_paying_until")
    private LocalDate premiumPayingUntil;

    /**
     * Each instalment falls due at the START of the period it pays for (2026-10-08, billing V11). Every schedule created
     * from then on; one created before keeps billing in arrears -- the period ENDING the day before it falls due -- so
     * nothing already raised, paid or owed moves.
     */
    /**
     * The first due date every later one is counted from (billing V12, 2026-10-29): due dates are this plus whole
     * periods, clamped to the month's end -- never the previous due date plus one, which slid a 31st to the 28th after
     * February for good.
     */
    @Column(name = "anchor_date")
    private LocalDate anchorDate;

    @Column(name = "billed_in_advance", nullable = false)
    private boolean billedInAdvance;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected BillingSchedule() {}

    public BillingSchedule(UUID tenantId, String policyNumber, String premiumFrequency,
                            BigDecimal premiumAmount, String premiumCurrency, LocalDate nextDueDate) {
        this(tenantId, policyNumber, premiumFrequency, premiumAmount, premiumCurrency, nextDueDate, null);
    }

    public BillingSchedule(UUID tenantId, String policyNumber, String premiumFrequency,
                            BigDecimal premiumAmount, String premiumCurrency, LocalDate nextDueDate,
                            LocalDate premiumPayingUntil) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.premiumFrequency = premiumFrequency;
        this.premiumAmount = premiumAmount;
        this.premiumCurrency = premiumCurrency;
        this.nextDueDate = nextDueDate;
        this.anchorDate = nextDueDate;
        this.premiumPayingUntil = premiumPayingUntil;
    }

    /** A new policy's schedule: billed in advance, its first instalment due the day cover starts. */
    public static BillingSchedule inAdvance(UUID tenantId, String policyNumber, String premiumFrequency,
                                            BigDecimal premiumAmount, String premiumCurrency, LocalDate firstDueDate,
                                            LocalDate premiumPayingUntil) {
        BillingSchedule schedule = new BillingSchedule(tenantId, policyNumber, premiumFrequency, premiumAmount,
            premiumCurrency, firstDueDate, premiumPayingUntil);
        schedule.billedInAdvance = true;
        return schedule;
    }

    /**
     * This schedule at a new premium, from where it stands: the same anchor, paying end and convention. A restated
     * schedule used to be built afresh, which anchored it on its next due date and billed it in arrears whatever it was.
     */
    public BillingSchedule restatedAt(BigDecimal amount) {
        BillingSchedule next = new BillingSchedule(tenantId, policyNumber, premiumFrequency, amount, premiumCurrency,
            nextDueDate, premiumPayingUntil);
        next.billedInAdvance = billedInAdvance;
        next.anchorDate = anchorDate != null ? anchorDate : nextDueDate;
        return next;
    }

    /**
     * The due date one period after {@code due}, counted from the anchor: the anchor plus whole periods, so 31 January
     * is followed by 28 February and then 31 March -- not 28 March, as stepping from the previous date gave. The period
     * {@code due} falls in is read by calendar month, which also returns a schedule that had already drifted to its
     * anchor day.
     */
    public LocalDate periodAfter(LocalDate due) {
        LocalDate anchor = getAnchorDate();
        return anchor.plusMonths(monthsFromAnchor(anchor, due) + monthsPerPeriod());
    }

    /** The due date one period before {@code due}, counted from the anchor the same way. */
    public LocalDate periodBefore(LocalDate due) {
        LocalDate anchor = getAnchorDate();
        return anchor.plusMonths(monthsFromAnchor(anchor, due) - monthsPerPeriod());
    }

    private static long monthsFromAnchor(LocalDate anchor, LocalDate due) {
        return java.time.temporal.ChronoUnit.MONTHS.between(java.time.YearMonth.from(anchor), java.time.YearMonth.from(due));
    }

    private int monthsPerPeriod() {
        return switch (premiumFrequency) {
            case "MONTHLY" -> 1;
            case "QUARTERLY" -> 3;
            case "ANNUALLY" -> 12;
            // Reaching here means something tried to schedule a contract that has no next period. Named rather than
            // silently stepping a year, because the symptom of getting this wrong is a lender being dunned for premium
            // nobody agreed.
            case "SINGLE" -> throw new IllegalArgumentException(
                "A SINGLE premium has no next period; this policy should never have been given a "
                    + "billing schedule (see billing.PolicyEventListener.handlePolicyIssued)");
            default -> throw new IllegalArgumentException("Unknown premium frequency: " + premiumFrequency);
        };
    }

    public boolean isBilledInAdvance() { return billedInAdvance; }

    /**
     * Whether an instalment falling due on {@code due} is still within the paying term. In advance it pays for the period
     * STARTING on its due date, so it must fall before the paying end; in arrears for the period ENDING the day before,
     * so it may fall on it. No paying end: always.
     */
    public boolean dueWithinPayingTerm(LocalDate due) {
        if (premiumPayingUntil == null) return true;
        return billedInAdvance ? due.isBefore(premiumPayingUntil) : !due.isAfter(premiumPayingUntil);
    }

    public void suspend() { this.status = "SUSPENDED"; }
    public void terminate() { this.status = "TERMINATED"; }

    /** A deferral with contributions continuing (product step 5 D2): the roll-forward raises the rest. */
    public void restatePremiumPayingUntil(LocalDate until) { this.premiumPayingUntil = until; }
    public void reactivate() { this.status = "ACTIVE"; }
    public void advanceNextDueDate(LocalDate next) { this.nextDueDate = next; }

    public UUID getBillingScheduleId() { return billingScheduleId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getPremiumFrequency() { return premiumFrequency; }
    public BigDecimal getPremiumAmount() { return premiumAmount; }
    public String getPremiumCurrency() { return premiumCurrency; }
    public LocalDate getNextDueDate() { return nextDueDate; }
    /** Null only on a schedule read before V12 ran; the next due date stands in for it. */
    public LocalDate getAnchorDate() { return anchorDate != null ? anchorDate : nextDueDate; }
    public LocalDate getPremiumPayingUntil() { return premiumPayingUntil; }
    public String getStatus() { return status; }
}
