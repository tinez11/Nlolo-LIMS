package tz.co.nlolo.lifeplatform.finaccounting.domain;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
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

/** Maps {@code finaccounting.chart_of_account} (V2 section 5). Every account is a PLACEHOLDER
 * pending Finance sign-off -- no document on this platform specifies account codes. */
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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected ChartOfAccount() {}

    public ChartOfAccount(UUID tenantId, String accountCode, String name,
                           AccountType accountType, PostingDirection normalBalance, String createdBy) {
        this.tenantId = tenantId;
        this.accountCode = accountCode;
        this.name = name;
        this.accountType = accountType;
        this.normalBalance = normalBalance;
        this.createdBy = createdBy;
    }

    public UUID getTenantId() { return tenantId; }
    public String getAccountCode() { return accountCode; }
    public String getName() { return name; }
    public AccountType getAccountType() { return accountType; }
    public PostingDirection getNormalBalance() { return normalBalance; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }

    /** A plain rename -- {@code accountCode} is this entity's own primary key (composite with
     *  {@code tenantId}) and {@code accountType}/{@code normalBalance} stay derived from its
     *  leading digit (see {@link tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRule}), so
     *  neither is, or should be, independently editable -- {@code name} is the only field with no
     *  structural reason to stay fixed. */
    public void rename(String newName, String updatedBy) {
        this.name = newName;
        this.updatedAt = Instant.now();
        this.updatedBy = updatedBy;
    }
}
