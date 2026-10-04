package tz.co.nlolo.lifeplatform.annuity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import tz.co.nlolo.lifeplatform.annuity.api.AnnuityContractView;
import tz.co.nlolo.lifeplatform.annuity.api.ContractStatus;
import tz.co.nlolo.lifeplatform.product.api.AnnuityForm;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPrice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An annuity contract (product step 5). The form is copied from the version at issue; the income is
 * locked once, from the rate table in force on the day the single premium arrives, and never changes
 * after -- annuity V1's trigger refuses it even for the table owner.
 */
@Entity
@Table(name = "contract", schema = "annuity")
public class AnnuityContract {
    @Id @Column(name = "policy_number") private String policyNumber;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(nullable = false) private String status;
    @Column(name = "form_code") private String formCode;
    @Column(name = "guarantee_years") private Integer guaranteeYears;
    @Column(nullable = false) private boolean joint;
    @Column(name = "survivor_percent") private BigDecimal survivorPercent;
    @Column(name = "escalation_percent") private BigDecimal escalationPercent;
    @Column(name = "capital_protected", nullable = false) private boolean capitalProtected;
    @Column(name = "rate_basis") private String rateBasis;
    @Column private String timing;
    @Column(name = "proof_of_life_interval_months") private Integer proofOfLifeIntervalMonths;
    @Column private String frequency;
    @Column(name = "annuitant_party_id", nullable = false) private UUID annuitantPartyId;
    @Column(name = "joint_life_party_id") private UUID jointLifePartyId;
    /** Null on a deferred annuity until it vests: the price is the balance that day, less any lump sum. */
    @Column(name = "purchase_price") private BigDecimal purchasePrice;
    @Column(nullable = false) private String currency;
    @Column(name = "locked_on") private LocalDate lockedOn;
    @Column(name = "annuitant_age") private Integer annuitantAge;
    @Column(name = "joint_age") private Integer jointAge;
    @Column(name = "rate_sex") private String rateSex;
    @Column(name = "annual_rate_per_mille") private BigDecimal annualRatePerMille;
    @Column private BigDecimal factor;
    @Column(name = "annual_income") private BigDecimal annualIncome;
    @Column private BigDecimal instalment;
    @Column(name = "first_due_date") private LocalDate firstDueDate;
    @Column(name = "guarantee_end_date") private LocalDate guaranteeEndDate;
    @Column(name = "first_death_party_id") private UUID firstDeathPartyId;
    @Column(name = "first_death_date") private LocalDate firstDeathDate;
    @Column(name = "first_death_claim_id") private UUID firstDeathClaimId;
    @Column(name = "last_death_date") private LocalDate lastDeathDate;
    @Column(name = "last_death_claim_id") private UUID lastDeathClaimId;
    @Column(name = "overpayment_owed", nullable = false) private BigDecimal overpaymentOwed = BigDecimal.ZERO;
    @Column(name = "lock_failure_reason") private String lockFailureReason;
    // D2 (annuity V2): a deferred annuity, the day it vested, the version whose rates priced it, and
    // why it ended or was cancelled before vesting.
    @Column(nullable = false) private boolean deferred;
    @Column(name = "vested_on") private LocalDate vestedOn;
    @Column(name = "priced_version_id") private UUID pricedVersionId;
    @Column(name = "end_reason") private String endReason;
    @Version private long version;

    protected AnnuityContract() {}

    /**
     * A deferred annuity at issue (D2): saving in its account, with no form, price or lock until it
     * vests. {@code soldVersionId} is the version it was SOLD on -- its vesting terms bind; the annuity
     * it buys is priced on the product's current version at vesting.
     */
    public static AnnuityContract accumulating(UUID tenantId, String policyNumber, UUID soldVersionId,
                                               UUID annuitantPartyId, String currency) {
        AnnuityContract c = base(tenantId, policyNumber, soldVersionId, annuitantPartyId, null, currency);
        c.status = ContractStatus.ACCUMULATING.name();
        c.deferred = true;
        return c;
    }

    /** A death approved before vesting: the account pays its balance; the annuity is never bought. */
    public void endedBeforeVesting(String reason) {
        requireAccumulating("end before vesting");
        this.status = ContractStatus.ENDED.name();
        this.endReason = reason;
    }

    /** A surrender before vesting, on an unlocked version: the account pays out; the annuity is never bought. */
    public void cancelledBeforeVesting(String reason) {
        requireAccumulating("be cancelled before vesting");
        this.status = ContractStatus.CANCELLED.name();
        this.endReason = reason;
    }

    private void requireAccumulating(String action) {
        if (status() != ContractStatus.ACCUMULATING) {
            throw new IllegalStateException("Annuity " + policyNumber + " is " + status + "; only one still saving can " + action);
        }
    }

    /** Issued with its form, frequency and lives; nothing is locked until the premium arrives. */
    public static AnnuityContract issued(UUID tenantId, String policyNumber, UUID productVersionId, AnnuityForm form,
                                         String timing, int proofOfLifeIntervalMonths, String frequency,
                                         UUID annuitantPartyId, UUID jointLifePartyId, BigDecimal purchasePrice, String currency) {
        AnnuityContract c = base(tenantId, policyNumber, productVersionId, annuitantPartyId, purchasePrice, currency);
        c.status = ContractStatus.AWAITING_PAYMENT.name();
        c.formCode = form.formCode();
        c.guaranteeYears = form.guaranteeYears();
        c.joint = form.joint();
        c.survivorPercent = form.survivorPercent();
        c.escalationPercent = form.escalationPercent();
        c.capitalProtected = form.capitalProtected();
        c.rateBasis = form.rateBasis().name();
        c.timing = timing;
        c.proofOfLifeIntervalMonths = proofOfLifeIntervalMonths;
        c.frequency = frequency;
        c.jointLifePartyId = jointLifePartyId;
        return c;
    }

    /** An annuity that could not be set up at issue -- recorded, visible, and paying nothing. */
    public static AnnuityContract failedAtIssue(UUID tenantId, String policyNumber, UUID productVersionId,
                                                UUID annuitantPartyId, BigDecimal purchasePrice, String currency, String reason) {
        AnnuityContract c = base(tenantId, policyNumber, productVersionId, annuitantPartyId, purchasePrice, currency);
        c.status = ContractStatus.LOCK_FAILED.name();
        c.lockFailureReason = reason;
        return c;
    }

    private static AnnuityContract base(UUID tenantId, String policyNumber, UUID productVersionId, UUID annuitantPartyId,
                                        BigDecimal purchasePrice, String currency) {
        AnnuityContract c = new AnnuityContract();
        c.tenantId = tenantId;
        c.policyNumber = policyNumber;
        c.productVersionId = productVersionId;
        c.annuitantPartyId = annuitantPartyId;
        c.purchasePrice = purchasePrice;
        c.currency = currency;
        return c;
    }

    /**
     * A deferred annuity vests (D2): it takes its form from the version CURRENT on the vesting date
     * (spec Q7), its price is the balance less any lump sum, and the income locks that day -- the
     * same once-only lock D1 applies when a single premium arrives.
     */
    public void vest(AnnuityForm form, String timing, int proofOfLifeIntervalMonths, String frequency, UUID jointLifePartyId,
                     BigDecimal purchasePrice, AnnuityPrice price, LocalDate vestingDate, LocalDate firstDue,
                     LocalDate guaranteeEnd, UUID pricedVersionId) {
        requireAccumulating("vest");
        this.formCode = form.formCode();
        this.guaranteeYears = form.guaranteeYears();
        this.joint = form.joint();
        this.survivorPercent = form.survivorPercent();
        this.escalationPercent = form.escalationPercent();
        this.capitalProtected = form.capitalProtected();
        this.rateBasis = form.rateBasis().name();
        this.timing = timing;
        this.proofOfLifeIntervalMonths = proofOfLifeIntervalMonths;
        this.frequency = frequency;
        this.jointLifePartyId = jointLifePartyId;
        this.purchasePrice = purchasePrice;
        this.pricedVersionId = pricedVersionId;
        this.vestedOn = vestingDate;
        lock(price, vestingDate, firstDue, guaranteeEnd);
    }

    /** The lock: once, from the price on the collection date. */
    public void lock(AnnuityPrice price, LocalDate collectedOn, LocalDate firstDue, LocalDate guaranteeEnd) {
        this.lockedOn = collectedOn;
        this.annuitantAge = price.annuitantAge();
        this.jointAge = price.jointAge();
        this.rateSex = price.rateSex();
        this.annualRatePerMille = price.annualRatePerMille();
        this.factor = price.factor();
        this.annualIncome = price.annualIncome();
        this.instalment = price.instalment();
        this.firstDueDate = firstDue;
        this.guaranteeEndDate = guaranteeEnd;
        this.status = ContractStatus.IN_PAYMENT.name();
    }

    public void lockFailed(String reason) {
        this.status = ContractStatus.LOCK_FAILED.name();
        this.lockFailureReason = reason;
    }

    public void cancel() {
        this.status = ContractStatus.CANCELLED.name();
    }

    /** A joint annuity's first death: the survivor is paid the survivor percentage from here. */
    public void recordFirstDeath(UUID partyId, LocalDate date, UUID claimId) {
        this.firstDeathPartyId = partyId;
        this.firstDeathDate = date;
        this.firstDeathClaimId = claimId;
        this.status = ContractStatus.SURVIVOR.name();
    }

    /** The last (or only) life has died: GUARANTEE when instalments continue to beneficiaries, else ENDED. */
    public void recordLastDeath(LocalDate date, UUID claimId, boolean guaranteeContinues, BigDecimal overpayment) {
        this.lastDeathDate = date;
        this.lastDeathClaimId = claimId;
        this.overpaymentOwed = overpayment;
        this.status = (guaranteeContinues ? ContractStatus.GUARANTEE : ContractStatus.ENDED).name();
    }

    public void ended() {
        this.status = ContractStatus.ENDED.name();
    }

    public ContractStatus status() { return ContractStatus.valueOf(status); }
    public String getPolicyNumber() { return policyNumber; }
    public UUID getProductVersionId() { return productVersionId; }
    public String getFormCode() { return formCode; }
    public boolean isJoint() { return joint; }
    public BigDecimal getSurvivorPercent() { return survivorPercent; }
    public BigDecimal getEscalationPercent() { return escalationPercent; }
    public boolean isCapitalProtected() { return capitalProtected; }
    public String getTiming() { return timing; }
    public Integer getGuaranteeYears() { return guaranteeYears; }
    public Integer getProofOfLifeIntervalMonths() { return proofOfLifeIntervalMonths; }
    public String getFrequency() { return frequency; }
    public UUID getAnnuitantPartyId() { return annuitantPartyId; }
    public UUID getJointLifePartyId() { return jointLifePartyId; }
    public BigDecimal getPurchasePrice() { return purchasePrice; }
    public String getCurrency() { return currency; }
    public BigDecimal getInstalment() { return instalment; }
    public LocalDate getFirstDueDate() { return firstDueDate; }
    public LocalDate getGuaranteeEndDate() { return guaranteeEndDate; }
    public UUID getFirstDeathPartyId() { return firstDeathPartyId; }
    public UUID getFirstDeathClaimId() { return firstDeathClaimId; }
    public UUID getLastDeathClaimId() { return lastDeathClaimId; }
    public BigDecimal getOverpaymentOwed() { return overpaymentOwed; }
    public boolean isDeferred() { return deferred; }
    public LocalDate getVestedOn() { return vestedOn; }
    public UUID getPricedVersionId() { return pricedVersionId; }
    public String getEndReason() { return endReason; }

    public AnnuityContractView toView() {
        return new AnnuityContractView(policyNumber, status(), formCode, guaranteeYears, joint, survivorPercent,
            escalationPercent, capitalProtected, timing, frequency, annuitantPartyId, jointLifePartyId, purchasePrice,
            currency, lockedOn, annuitantAge, jointAge, rateSex, annualRatePerMille, factor, annualIncome, instalment,
            firstDueDate, guaranteeEndDate, firstDeathPartyId, firstDeathDate, lastDeathDate, overpaymentOwed,
            lockFailureReason, endReason);
    }
}
