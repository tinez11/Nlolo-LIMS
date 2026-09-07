package tz.co.nlolo.lifeplatform.finaccounting.domain;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Maps {@code finaccounting.chart_of_account} (V2 section 5, V5 hierarchy columns). Every
 * account is a PLACEHOLDER pending Finance sign-off -- no document on this platform specifies
 * account codes. */
@Entity
@Table(name = "chart_of_account", schema = "finaccounting")
@IdClass(ChartOfAccountId.class)
public class ChartOfAccount {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "account_code")
    private String accountCode;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "normal_balance", nullable = false)
    private PostingDirection normalBalance;

    @Column(name = "parent_code")
    private String parentCode;

    @Column(name = "level", nullable = false)
    private short level;

    @Column(name = "posting_allowed", nullable = false)
    private boolean postingAllowed;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "control_of")
    private String controlOf;

    @Column(name = "description")
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected ChartOfAccount() {}

    private ChartOfAccount(UUID tenantId, String accountCode, String name,
                            AccountType accountType, PostingDirection normalBalance, String createdBy) {
        this.tenantId = tenantId;
        this.accountCode = accountCode;
        this.name = name;
        this.accountType = accountType;
        this.normalBalance = normalBalance;
        this.createdBy = createdBy;
    }

    /**
     * A block root -- {@code parentCode} null, {@code level} 1. Used by the seeder for the five
     * 1000/2000/3000/4000/5000 rows, and by {@code createAccount} when no parent is supplied.
     */
    public static ChartOfAccount root(UUID tenantId, String accountCode, String name,
                                       boolean postingAllowed, String currency, String createdBy) {
        ChartOfAccount account = new ChartOfAccount(tenantId, accountCode, name,
            PostingRule.accountTypeFor(accountCode), PostingRule.normalBalanceFor(accountCode), createdBy);
        account.level = 1;
        account.postingAllowed = postingAllowed;
        account.currency = currency;
        return account;
    }

    /**
     * A child of {@code parent}, one level deeper.
     *
     * <p><b>The code-prefix rule is what keeps the numbering and the hierarchy from contradicting
     * each other.</b> A child's code must begin with its parent's SIGNIFICANT prefix -- the
     * parent's code with trailing zeros stripped -- so 1210 may hang off 1200 but 2110 may not.
     * Because a child's code always strictly extends its parent's prefix, a cycle is structurally
     * impossible and needs no separate check.
     *
     * <p>The rule deliberately does NOT forbid SKIPPING a level: 1110 may attach directly to 1000.
     * A skipped level is a flat branch, not an inconsistency.
     *
     * @throws FinaccountingValidationException if {@code accountCode} falls outside the parent's
     *         block, or equals the parent's own code
     */
    public static ChartOfAccount childOf(ChartOfAccount parent, String accountCode, String name,
                                          boolean postingAllowed, String currency, String controlOf,
                                          String description, String createdBy) {
        if (accountCode.equals(parent.accountCode)) {
            throw new FinaccountingValidationException(
                "An account cannot be its own parent: " + accountCode);
        }
        String prefix = significantPrefix(parent.accountCode);
        if (!accountCode.startsWith(prefix)) {
            throw new FinaccountingValidationException("Account " + accountCode
                + " cannot hang off " + parent.accountCode + ": a child's code must begin with \""
                + prefix + "\"");
        }
        ChartOfAccount account = new ChartOfAccount(parent.tenantId, accountCode, name,
            PostingRule.accountTypeFor(accountCode), PostingRule.normalBalanceFor(accountCode), createdBy);
        account.parentCode = parent.accountCode;
        account.level = (short) (parent.level + 1);
        account.postingAllowed = postingAllowed;
        account.currency = currency;
        account.controlOf = controlOf;
        account.description = description;
        return account;
    }

    /** The account code with trailing zeros stripped: 1000 -> "1", 1200 -> "12", 1210 -> "121". */
    public static String significantPrefix(String accountCode) {
        int end = accountCode.length();
        while (end > 1 && accountCode.charAt(end - 1) == '0') {
            end--;
        }
        return accountCode.substring(0, end);
    }

    public UUID getTenantId() { return tenantId; }
    public String getAccountCode() { return accountCode; }
    public String getName() { return name; }
    public AccountType getAccountType() { return accountType; }
    public PostingDirection getNormalBalance() { return normalBalance; }
    public String getParentCode() { return parentCode; }
    public short getLevel() { return level; }
    public boolean isPostingAllowed() { return postingAllowed; }
    public AccountStatus getStatus() { return status; }
    public String getCurrency() { return currency; }
    public String getControlOf() { return controlOf; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }

    /**
     * True only when this account may receive a new posting leg. BOTH conditions, independently:
     * a header never posts however active it is, and a retired leaf never posts however postable
     * it once was.
     */
    public boolean acceptsPostings() {
        return postingAllowed && status == AccountStatus.ACTIVE;
    }

    /** Called when this account gains its first child: a parent never receives postings. */
    public void becomeHeader(String updatedBy) {
        this.postingAllowed = false;
        touch(updatedBy);
    }

    public void deactivate(String updatedBy) {
        this.status = AccountStatus.INACTIVE;
        touch(updatedBy);
    }

    public void activate(String updatedBy) {
        this.status = AccountStatus.ACTIVE;
        touch(updatedBy);
    }

    public void describe(String description, String updatedBy) {
        this.description = description;
        touch(updatedBy);
    }

    /** A plain rename -- {@code accountCode} is this entity's own primary key (composite with
     *  {@code tenantId}) and {@code accountType}/{@code normalBalance} stay derived from its
     *  leading digit (see {@link PostingRule}), so none is, or should be, independently editable.
     *  {@code parentCode} and {@code level} are equally fixed: moving an account is a distinct,
     *  deferred concern that carries real risk to historical reporting. */
    public void rename(String newName, String updatedBy) {
        this.name = newName;
        touch(updatedBy);
    }

    private void touch(String updatedBy) {
        this.updatedAt = Instant.now();
        this.updatedBy = updatedBy;
    }
}
