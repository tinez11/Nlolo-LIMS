package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the engine's policies sheet needs of each policy (IFRS 17 I5a, finaccounting V15 section B): sum assured,
 * premium and frequency from {@code policy.PolicyActivated}, and its status as the lifecycle events move it. Kept here
 * because finaccounting cannot read {@code policy::api}: finaccounting -> policy -> distribution -> finaccounting would
 * be a module cycle.
 */
@Component
public class EnginePolicySnapshots {

    private static final Logger log = LoggerFactory.getLogger(EnginePolicySnapshots.class);

    /** In force for the engine: on risk now, paying or paid up. */
    static final List<String> IN_FORCE = List.of("ACTIVE", "REINSTATED", "PAID_UP");

    private static final Map<String, String> STATUS = Map.of(
        "policy.PolicyLapsed", "LAPSED",
        "policy.PolicySurrendered", "SURRENDERED",
        "policy.PolicyMatured", "MATURED",
        "policy.PolicyExpired", "EXPIRED",
        "policy.PolicyCancelledFreeLook", "CANCELLED_FREE_LOOK",
        "policy.AnnuityEnded", "ENDED",
        "policy.PolicyReinstated", "REINSTATED",
        "policy.PolicyMadePaidUp", "PAID_UP");

    record Snapshot(String policyNumber, LocalDate issueDate, BigDecimal sumAssured, BigDecimal premium,
                    String frequency, String currency, String status) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    EnginePolicySnapshots(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        String type = envelope.eventType();
        if (!"policy.PolicyActivated".equals(type) && !STATUS.containsKey(type)) {
            return;
        }
        if (!(envelope.payload() instanceof Map<?, ?> p) || p.get("policyNumber") == null) {
            return;
        }
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            requiresNew.executeWithoutResult(status -> {
                if ("policy.PolicyActivated".equals(type)) {
                    activated(envelope.tenantId(), p);
                } else {
                    jdbc.update("UPDATE finaccounting.policy_snapshot SET status = ?, updated_at = now()"
                        + " WHERE tenant_id = ? AND policy_number = ?", STATUS.get(type), envelope.tenantId(),
                        p.get("policyNumber").toString());
                }
            });
        } catch (RuntimeException e) {
            log.error("Could not keep the engine's snapshot of {} on {}", p.get("policyNumber"), type, e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }

    private void activated(UUID tenantId, Map<?, ?> p) {
        Object issue = p.get("issueDate");
        jdbc.update("INSERT INTO finaccounting.policy_snapshot (tenant_id, policy_number, issue_date, sum_assured, premium,"
                + " premium_frequency, currency, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')"
                + " ON CONFLICT (tenant_id, policy_number) DO UPDATE SET issue_date = EXCLUDED.issue_date,"
                + " sum_assured = EXCLUDED.sum_assured, premium = EXCLUDED.premium,"
                + " premium_frequency = EXCLUDED.premium_frequency, currency = EXCLUDED.currency, status = 'ACTIVE',"
                + " updated_at = now()",
            tenantId, p.get("policyNumber").toString(), issue == null ? null : Date.valueOf(LocalDate.parse(issue.toString())),
            amount(p.get("sumAssured")), amount(p.get("premium")),
            p.get("premiumFrequency") == null ? null : p.get("premiumFrequency").toString(), currency(p.get("premium")));
    }

    /** Policies in force for the engine, by policy number. */
    List<Snapshot> inForce(UUID tenantId) {
        return jdbc.query("SELECT * FROM finaccounting.policy_snapshot WHERE tenant_id = ?"
                + " AND status IN ('ACTIVE','REINSTATED','PAID_UP') ORDER BY policy_number",
            (rs, i) -> new Snapshot(rs.getString("policy_number"),
                rs.getDate("issue_date") == null ? null : rs.getDate("issue_date").toLocalDate(),
                rs.getBigDecimal("sum_assured"), rs.getBigDecimal("premium"), rs.getString("premium_frequency"),
                rs.getString("currency") == null ? null : rs.getString("currency").trim(), rs.getString("status")),
            tenantId);
    }

    private static BigDecimal amount(Object money) {
        return money instanceof Map<?, ?> m && m.get("amount") != null ? new BigDecimal(m.get("amount").toString()) : null;
    }

    private static String currency(Object money) {
        return money instanceof Map<?, ?> m && m.get("currencyCode") != null ? m.get("currencyCode").toString() : null;
    }
}
