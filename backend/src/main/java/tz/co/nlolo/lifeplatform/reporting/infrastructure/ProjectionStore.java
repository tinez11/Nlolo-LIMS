package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tz.co.nlolo.lifeplatform.reporting.domain.PolicyStatus;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Every write the reporting module makes. Each one is idempotent on its own (ON CONFLICT), and the
 * applied_event row makes a whole event idempotent. Callers run inside one transaction per event with
 * TenantContext set, and pass the tenant explicitly as well: tests connect as a superuser, where RLS is off.
 */
@Repository
public class ProjectionStore {

    private static final UUID UNKNOWN_PRODUCT = new UUID(0L, 0L);
    private static final List<String> TABLES = List.of("report_policy", "report_policy_status_change",
        "report_collection", "report_client", "report_lives_change", "applied_event");

    private final JdbcTemplate jdbc;

    public ProjectionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PolicyFacts(String policyNumber, UUID holderPartyId, UUID productId, UUID productVersionId,
                              String productCategory, String portfolioCode, boolean groupScheme, UUID agentId,
                              String salesChannel, String branchCode, BigDecimal sumAssured, BigDecimal premium,
                              String premiumFrequency, String currency, LocalDate issueDate) {}

    public record ClientFacts(UUID partyId, String partyType, String kycStatus, String displayName,
                              LocalDate registeredOn, LocalDate dateOfBirth, String sex) {}

    /** True when the event was not applied before. */
    public boolean markApplied(UUID tenantId, UUID eventId) {
        return jdbc.update("INSERT INTO reporting.applied_event (tenant_id, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            tenantId, eventId) == 1;
    }

    /**
     * Inserts the policy, or fills only the columns still empty. PolicyIssued and PolicyActivated can arrive
     * in either order (a replay orders by timestamp, and both can share one), so neither may overwrite what
     * the other already said. The UNKNOWN product placeholder is the one value that IS replaced.
     */
    public void upsertPolicy(UUID tenantId, PolicyFacts f) {
        jdbc.update("""
            INSERT INTO reporting.report_policy (tenant_id, policy_number, holder_party_id, product_id, product_version_id,
                product_category, portfolio_code, group_scheme, agent_id, sales_channel, branch_code, sum_assured, premium,
                premium_frequency, currency, issue_date)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, 'TZS'), ?)
            ON CONFLICT (tenant_id, policy_number) DO UPDATE SET
                holder_party_id    = COALESCE(reporting.report_policy.holder_party_id, EXCLUDED.holder_party_id),
                product_id         = CASE WHEN reporting.report_policy.product_id = '00000000-0000-0000-0000-000000000000'
                                          THEN EXCLUDED.product_id ELSE reporting.report_policy.product_id END,
                product_version_id = COALESCE(reporting.report_policy.product_version_id, EXCLUDED.product_version_id),
                product_category   = COALESCE(reporting.report_policy.product_category, EXCLUDED.product_category),
                portfolio_code     = COALESCE(reporting.report_policy.portfolio_code, EXCLUDED.portfolio_code),
                group_scheme       = reporting.report_policy.group_scheme OR EXCLUDED.group_scheme,
                agent_id           = COALESCE(reporting.report_policy.agent_id, EXCLUDED.agent_id),
                sales_channel      = COALESCE(reporting.report_policy.sales_channel, EXCLUDED.sales_channel),
                branch_code        = COALESCE(reporting.report_policy.branch_code, EXCLUDED.branch_code),
                sum_assured        = COALESCE(reporting.report_policy.sum_assured, EXCLUDED.sum_assured),
                premium            = COALESCE(reporting.report_policy.premium, EXCLUDED.premium),
                premium_frequency  = COALESCE(reporting.report_policy.premium_frequency, EXCLUDED.premium_frequency),
                issue_date         = COALESCE(reporting.report_policy.issue_date, EXCLUDED.issue_date)
            """,
            tenantId, f.policyNumber(), f.holderPartyId(), f.productId() == null ? UNKNOWN_PRODUCT : f.productId(),
            f.productVersionId(), f.productCategory(), f.portfolioCode(), f.groupScheme(), f.agentId(), f.salesChannel(),
            f.branchCode(), f.sumAssured(), f.premium(), f.premiumFrequency(), f.currency(), f.issueDate());
    }

    public void setActivated(UUID tenantId, String policyNumber, LocalDate on) {
        jdbc.update("UPDATE reporting.report_policy SET activated_on = COALESCE(activated_on, ?)"
            + " WHERE tenant_id = ? AND policy_number = ?", on, tenantId, policyNumber);
    }

    public void setNotTakenUp(UUID tenantId, String policyNumber, LocalDate on) {
        jdbc.update("UPDATE reporting.report_policy SET not_taken_up_on = COALESCE(not_taken_up_on, ?)"
            + " WHERE tenant_id = ? AND policy_number = ?", on, tenantId, policyNumber);
    }

    public void setSumAssured(UUID tenantId, String policyNumber, BigDecimal sumAssured) {
        jdbc.update("UPDATE reporting.report_policy SET sum_assured = ? WHERE tenant_id = ? AND policy_number = ?",
            sumAssured, tenantId, policyNumber);
    }

    public boolean isGroupScheme(UUID tenantId, String policyNumber) {
        List<Boolean> rows = jdbc.queryForList("SELECT group_scheme FROM reporting.report_policy"
            + " WHERE tenant_id = ? AND policy_number = ?", Boolean.class, tenantId, policyNumber);
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
    }

    /** Creates an UNKNOWN-product row for a policy first seen through a later event. True when it had to. */
    public boolean ensurePolicy(UUID tenantId, String policyNumber) {
        return jdbc.update("INSERT INTO reporting.report_policy (tenant_id, policy_number, product_id) VALUES (?, ?, ?)"
            + " ON CONFLICT DO NOTHING", tenantId, policyNumber, UNKNOWN_PRODUCT) == 1;
    }

    public void addStatusChange(UUID tenantId, UUID eventId, String policyNumber, PolicyStatus status, String cause,
                                LocalDate effectiveOn, Instant occurredAt) {
        jdbc.update("INSERT INTO reporting.report_policy_status_change (tenant_id, event_id, policy_number, to_status, cause,"
            + " effective_on, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            tenantId, eventId, policyNumber, status.name(), cause, effectiveOn, Timestamp.from(occurredAt));
    }

    public void addCollection(UUID tenantId, UUID eventId, UUID invoiceId, String policyNumber, BigDecimal amount,
                              String currency, LocalDate collectedOn, String channel) {
        jdbc.update("INSERT INTO reporting.report_collection (tenant_id, event_id, invoice_id, policy_number, amount, currency,"
            + " collected_on, channel) VALUES (?, ?, ?, ?, ?, COALESCE(?, 'TZS'), ?, ?) ON CONFLICT DO NOTHING",
            tenantId, eventId, invoiceId, policyNumber, amount, currency, collectedOn, channel);
    }

    public void upsertClient(UUID tenantId, ClientFacts c) {
        jdbc.update("""
            INSERT INTO reporting.report_client (tenant_id, party_id, party_type, kyc_status, display_name, registered_on,
                date_of_birth, sex)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, party_id) DO UPDATE SET
                party_type = EXCLUDED.party_type,
                kyc_status = COALESCE(EXCLUDED.kyc_status, reporting.report_client.kyc_status),
                display_name = COALESCE(EXCLUDED.display_name, reporting.report_client.display_name),
                registered_on = COALESCE(reporting.report_client.registered_on, EXCLUDED.registered_on),
                date_of_birth = COALESCE(EXCLUDED.date_of_birth, reporting.report_client.date_of_birth),
                sex = COALESCE(EXCLUDED.sex, reporting.report_client.sex)
            """, tenantId, c.partyId(), c.partyType(), c.kycStatus(), c.displayName(), c.registeredOn(),
            c.dateOfBirth(), c.sex());
    }

    /** False when the client row does not exist yet. */
    public boolean setKycStatus(UUID tenantId, UUID partyId, String kycStatus) {
        return jdbc.update("UPDATE reporting.report_client SET kyc_status = ? WHERE tenant_id = ? AND party_id = ?",
            kycStatus, tenantId, partyId) == 1;
    }

    public void addLivesChange(UUID tenantId, UUID eventId, String policyNumber, String kind, int lives,
                               BigDecimal schemeTotalCovered, LocalDate effectiveOn, Instant occurredAt) {
        jdbc.update("INSERT INTO reporting.report_lives_change (tenant_id, event_id, policy_number, kind, lives_covered,"
            + " scheme_total_covered, effective_on, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            tenantId, eventId, policyNumber, kind, lives, schemeTotalCovered, effectiveOn, Timestamp.from(occurredAt));
    }

    /** A rebuild starts here: every row this tenant has, including the record of which events were applied. */
    public void deleteTenant(UUID tenantId) {
        for (String table : TABLES) {
            jdbc.update("DELETE FROM reporting." + table + " WHERE tenant_id = ?", tenantId);
        }
    }
}
