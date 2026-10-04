package tz.co.nlolo.lifeplatform.annuity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import tz.co.nlolo.lifeplatform.product.api.VestingTerms;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A deferred annuity's vesting (product step 5 D2, annuity V2): its target and window, what it vests
 * into when nobody says otherwise, what was confirmed at sale, and any hold. The window and defaults
 * are copied from the version it was SOLD on (spec Q2); the dates are derived from the CONFIRMED date
 * of birth, so a re-confirmed one moves all three.
 */
@Entity
@Table(name = "vesting", schema = "annuity")
public class Vesting {
    @Id @Column(name = "policy_number") private String policyNumber;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "retirement_age", nullable = false) private int retirementAge;
    @Column(name = "min_vesting_age", nullable = false) private int minVestingAge;
    @Column(name = "max_vesting_age", nullable = false) private int maxVestingAge;
    @Column(name = "target_date", nullable = false) private LocalDate targetDate;
    @Column(name = "earliest_vesting_date", nullable = false) private LocalDate earliestVestingDate;
    @Column(name = "latest_vesting_date", nullable = false) private LocalDate latestVestingDate;
    @Column(name = "max_commutation_percent", nullable = false) private BigDecimal maxCommutationPercent;
    @Column(name = "default_form_code", nullable = false) private String defaultFormCode;
    @Column(name = "default_frequency", nullable = false) private String defaultFrequency;
    @Column(name = "confirmed_date_of_birth", nullable = false) private LocalDate confirmedDateOfBirth;
    @Column(name = "confirmed_sex") private String confirmedSex;
    @Column(name = "age_confirmed_by", nullable = false) private String ageConfirmedBy;
    @Column(name = "age_confirmed_at", nullable = false) private Instant ageConfirmedAt;
    @Column(name = "death_reported_claim_id") private UUID deathReportedClaimId;
    @Column(name = "hold_reason") private String holdReason;
    @Column(name = "held_at") private Instant heldAt;
    @Column(name = "reminded_90_for") private LocalDate reminded90For;
    @Column(name = "reminded_30_for") private LocalDate reminded30For;
    @Column(name = "vested_balance") private BigDecimal vestedBalance;
    @Column(name = "lump_sum") private BigDecimal lumpSum;
    @Version private long version;

    protected Vesting() {}

    public Vesting(UUID tenantId, String policyNumber, int retirementAge, VestingTerms soldTerms,
                   LocalDate confirmedDateOfBirth, String confirmedSex, String ageConfirmedBy, Instant ageConfirmedAt) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.retirementAge = retirementAge;
        this.minVestingAge = soldTerms.minVestingAge();
        this.maxVestingAge = soldTerms.maxVestingAge();
        this.maxCommutationPercent = soldTerms.maxCommutationPercent();
        this.defaultFormCode = soldTerms.defaultFormCode();
        this.defaultFrequency = soldTerms.defaultFrequency();
        confirm(confirmedDateOfBirth, confirmedSex, ageConfirmedBy, ageConfirmedAt);
    }

    /** Proof of age seen: the pair it was seen for, and every date derived from it. */
    private void confirm(LocalDate dateOfBirth, String sex, String by, Instant at) {
        this.confirmedDateOfBirth = dateOfBirth;
        this.confirmedSex = sex;
        this.ageConfirmedBy = by;
        this.ageConfirmedAt = at;
        this.targetDate = dateOfBirth.plusYears(retirementAge);
        this.earliestVestingDate = dateOfBirth.plusYears(minVestingAge);
        this.latestVestingDate = dateOfBirth.plusYears(maxVestingAge);
    }

    /** Re-confirmed after the party record changed (spec Q8): the new pair, and the dates moved with it. */
    public void reconfirm(LocalDate dateOfBirth, String sex, String by) {
        confirm(dateOfBirth, sex, by, Instant.now());
        clearHold();
    }

    /** Whether the party's record still says what was confirmed. */
    public boolean ageStillConfirmed(LocalDate dateOfBirth, String sex) {
        return confirmedDateOfBirth.equals(dateOfBirth) && java.util.Objects.equals(confirmedSex, sex);
    }

    public void hold(String reason) {
        this.holdReason = reason;
        this.heldAt = Instant.now();
    }

    public void clearHold() {
        this.holdReason = null;
        this.heldAt = null;
    }

    public void deathReported(UUID claimId) {
        this.deathReportedClaimId = claimId;
    }

    /** The reported death's claim was rejected: nothing stands in the way any more. */
    public void deathCleared(UUID claimId) {
        if (claimId.equals(deathReportedClaimId)) {
            this.deathReportedClaimId = null;
        }
    }

    public void vested(BigDecimal balance, BigDecimal lumpSum) {
        this.vestedBalance = balance;
        this.lumpSum = lumpSum;
        clearHold();
    }

    /** Re-arm both reminders: the date they were sent for has moved. */
    public void rearmReminders() {
        this.reminded90For = null;
        this.reminded30For = null;
    }

    /** Record a reminder as sent for {@code forDate}; false when it already was. */
    public boolean remind(int daysBefore, LocalDate forDate) {
        if (daysBefore == 90) {
            if (forDate.equals(reminded90For)) return false;
            this.reminded90For = forDate;
            return true;
        }
        if (forDate.equals(reminded30For)) return false;
        this.reminded30For = forDate;
        return true;
    }

    public LocalDate getReminded90For() { return reminded90For; }
    public LocalDate getReminded30For() { return reminded30For; }
    public String getPolicyNumber() { return policyNumber; }
    public int getRetirementAge() { return retirementAge; }
    public LocalDate getTargetDate() { return targetDate; }
    public LocalDate getEarliestVestingDate() { return earliestVestingDate; }
    public LocalDate getLatestVestingDate() { return latestVestingDate; }
    public BigDecimal getMaxCommutationPercent() { return maxCommutationPercent; }
    public String getDefaultFormCode() { return defaultFormCode; }
    public String getDefaultFrequency() { return defaultFrequency; }
    public LocalDate getConfirmedDateOfBirth() { return confirmedDateOfBirth; }
    public String getConfirmedSex() { return confirmedSex; }
    public String getAgeConfirmedBy() { return ageConfirmedBy; }
    public UUID getDeathReportedClaimId() { return deathReportedClaimId; }
    public String getHoldReason() { return holdReason; }
    public Instant getHeldAt() { return heldAt; }
    public BigDecimal getVestedBalance() { return vestedBalance; }
    public BigDecimal getLumpSum() { return lumpSum; }
}
