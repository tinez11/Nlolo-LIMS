package tz.co.nlolo.lifeplatform.reinsurance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps {@code reinsurance.policy_projection} (V2 section 11) -- reinsurance's OWN state, not a
 * cache of policy's. `policy` is not in this module's allowedDependencies, so this projection
 * (built solely from {@code policy.PolicyIssued}) is how reinsurance learns a policy's sum assured
 * and premium without ever calling {@code PolicyApi}.
 *
 * <p>Composite primary key {@code (tenant_id, policy_number)} -- see {@link PolicyProjectionId}.
 */
// Explicit entity name: distribution's own PolicyProjection entity shares this simple class name
// (same reasoning as ReinsurancePolicyProjectionRepository's rename -- Hibernate derives an
// entity's name from the simple class name by default, and two distinct @Entity classes on the
// classpath sharing one name is a hard DuplicateMappingException at context refresh). Qualifying
// the JPA entity name (not the Java class/file) is enough: nothing in this codebase references
// either entity by JPQL name string (checked before this change), so this is a safe, isolated fix
// on reinsurance's side only -- distribution's already-shipped entity stays untouched.
@Entity(name = "ReinsurancePolicyProjection")
@Table(name = "policy_projection", schema = "reinsurance")
@IdClass(PolicyProjectionId.class)
public class PolicyProjection {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "policy_number")
    private String policyNumber;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "sum_assured_amount", nullable = false)
    private BigDecimal sumAssuredAmount;

    @Column(name = "sum_assured_currency", nullable = false)
    private String sumAssuredCurrency;

    @Column(name = "premium_amount", nullable = false)
    private BigDecimal premiumAmount;

    @Column(name = "premium_currency", nullable = false)
    private String premiumCurrency;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    /** GROUP_LIFE and CREDIT_LIFE are never ceded and never recovered against. NULL on any row
     * written before reinsurance/V4 -- which means "not known to be a scheme", not "individual".
     * See that migration's header for why a backfill would have been dishonest. */
    @Column(name = "product_category")
    private String productCategory;

    /** IFRS 17 I3c: how often the policy pays (MONTHLY, QUARTERLY, ANNUALLY, SINGLE) -- what turns its premium into
     * the monthly amount a bordereau charges. Null only on a row nothing could backfill. */
    @Column(name = "premium_frequency")
    private String premiumFrequency;

    /** IFRS 17 I3c: the day premiums stopped (paid up, premium term over). On original terms the reinsurer's premium
     * stops with the policy's: no bordereau after this month charges it, though cover -- and recovery -- go on. */
    @Column(name = "premiums_end_on")
    private LocalDate premiumsEndOn;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PolicyProjection() {}

    public PolicyProjection(UUID tenantId, String policyNumber, UUID productId,
                             BigDecimal sumAssuredAmount, String sumAssuredCurrency,
                             BigDecimal premiumAmount, String premiumCurrency, LocalDate issueDate,
                             String productCategory, String premiumFrequency) {
        this(tenantId, policyNumber, productId, sumAssuredAmount, sumAssuredCurrency, premiumAmount, premiumCurrency,
            issueDate, productCategory);
        this.premiumFrequency = premiumFrequency;
    }

    /** The premium from a restatement on (a funeral family changing): later bordereaux charge the share of it. */
    public void restatePremium(BigDecimal premium) {
        if (premium != null && premium.signum() > 0) {
            this.premiumAmount = premium;
        }
    }

    /** Premiums stopped on this day; the first one wins, as a redelivery must not move it. */
    public void endPremiums(LocalDate on) {
        if (premiumsEndOn == null) {
            this.premiumsEndOn = on;
        }
    }

    /** A reinstatement puts premiums back in payment. */
    public void resumePremiums() {
        this.premiumsEndOn = null;
    }

    public PolicyProjection(UUID tenantId, String policyNumber, UUID productId,
                             BigDecimal sumAssuredAmount, String sumAssuredCurrency,
                             BigDecimal premiumAmount, String premiumCurrency, LocalDate issueDate,
                             String productCategory) {
        this.productCategory = productCategory;
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.productId = productId;
        this.sumAssuredAmount = sumAssuredAmount;
        this.sumAssuredCurrency = sumAssuredCurrency;
        this.premiumAmount = premiumAmount;
        this.premiumCurrency = premiumCurrency;
        this.issueDate = issueDate;
    }

    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public UUID getProductId() { return productId; }
    public BigDecimal getSumAssuredAmount() { return sumAssuredAmount; }
    public String getSumAssuredCurrency() { return sumAssuredCurrency; }
    public BigDecimal getPremiumAmount() { return premiumAmount; }
    public String getPremiumCurrency() { return premiumCurrency; }
    public LocalDate getIssueDate() { return issueDate; }
    public String getProductCategory() { return productCategory; }
    public String getPremiumFrequency() { return premiumFrequency; }
    public LocalDate getPremiumsEndOn() { return premiumsEndOn; }

    /**
     * Whether this policy is a scheme, and therefore outside reinsurance entirely.
     *
     * <p>Lives on the entity rather than in either listener because BOTH need it and they reached
     * the question by different routes: PolicyEventListener asks before ceding, ClaimEventListener
     * before recovering. Two copies of the category list is how the cession side came to be
     * guarded while the recovery side was not.
     *
     * <p>NULL is false, deliberately. A row written before reinsurance/V4 has no recorded
     * category, and treating "unknown" as "scheme" would silently stop recoveries on ordinary
     * individual policies that have always had them.
     */
    public boolean isScheme() { return isScheme(productCategory); }

    /** This module's own name for a group funeral scheme: a FUNERAL product sold to an association's families. */
    public static final String GROUP_FUNERAL = "GROUP_FUNERAL";

    public static boolean isScheme(String productCategory) {
        return "GROUP_LIFE".equals(productCategory) || "CREDIT_LIFE".equals(productCategory)
            || GROUP_FUNERAL.equals(productCategory);
    }
    public Instant getCreatedAt() { return createdAt; }
}
