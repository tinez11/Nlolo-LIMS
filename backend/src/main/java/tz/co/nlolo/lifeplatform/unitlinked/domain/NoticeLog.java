package tz.co.nlolo.lifeplatform.unitlinked.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** A notice already sent this month (unitlinked V2): the low-fund warning goes at most once a month per policy. */
@Entity
@Table(name = "notice_log", schema = "unitlinked")
public class NoticeLog {
    @Id @Column(name = "notice_log_id") private UUID noticeLogId = UUID.randomUUID();
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "kind", nullable = false) private String kind;
    @Column(name = "month", nullable = false) private String month;

    protected NoticeLog() {}

    public NoticeLog(UUID tenantId, String policyNumber, String kind, String month) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.kind = kind;
        this.month = month;
    }
}
