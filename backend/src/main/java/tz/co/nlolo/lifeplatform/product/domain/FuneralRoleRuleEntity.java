package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.product.api.FuneralRoleRule;

import java.util.UUID;

/** Who a FUNERAL version covers in one role (V24). */
@Entity
@Table(name = "funeral_role_rule", schema = "product")
public class FuneralRoleRuleEntity {
    @Id @Column(name = "funeral_role_rule_id") private UUID funeralRoleRuleId = UUID.randomUUID();
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(nullable = false) private String role;
    @Column(name = "max_lives", nullable = false) private int maxLives;
    @Column(name = "min_entry_age", nullable = false) private int minEntryAge;
    @Column(name = "max_entry_age", nullable = false) private int maxEntryAge;
    @Column(name = "cover_stop_age") private Integer coverStopAge;
    @Column(name = "student_stop_age") private Integer studentStopAge;

    protected FuneralRoleRuleEntity() {}

    public FuneralRoleRuleEntity(UUID tenantId, UUID productVersionId, FuneralRoleRule rule) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.role = rule.role().name();
        this.maxLives = rule.maxLives();
        this.minEntryAge = rule.minEntryAge();
        this.maxEntryAge = rule.maxEntryAge();
        this.coverStopAge = rule.coverStopAge();
        this.studentStopAge = rule.studentStopAge();
    }

    public FuneralRoleRule toRule() {
        return new FuneralRoleRule(FuneralRole.valueOf(role), maxLives, minEntryAge, maxEntryAge, coverStopAge, studentStopAge);
    }
}
