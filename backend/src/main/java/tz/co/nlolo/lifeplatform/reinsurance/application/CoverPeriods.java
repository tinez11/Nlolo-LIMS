package tz.co.nlolo.lifeplatform.reinsurance.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reinsurance.domain.BordereauCalculator.Cover;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * When each policy was on risk, as reinsurance learns it from policy's events (IFRS 17 I3c, reinsurance V5). A
 * bordereau charges a month only for a policy on risk at some point in it (user answer Q2). Callers run inside a
 * transaction under the policy's tenant.
 */
@Component
class CoverPeriods {

    private final JdbcTemplate jdbc;

    CoverPeriods(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Cover begins (activation) or begins again (reinstatement). A policy already on risk opens nothing new --
     * {@code ux_cover_period_one_open} makes a redelivery a no-op. */
    void open(UUID tenantId, String policyNumber, LocalDate on) {
        jdbc.update("INSERT INTO reinsurance.cover_period (tenant_id, policy_number, starts_on) VALUES (?, ?, ?)"
            + " ON CONFLICT (tenant_id, policy_number) WHERE ends_on IS NULL DO NOTHING",
            tenantId, policyNumber, Date.valueOf(on));
    }

    /** Cover ends (lapse, surrender or a death claim, maturity, expiry, an annuity's end). Never before it began. */
    void close(UUID tenantId, String policyNumber, LocalDate on) {
        jdbc.update("UPDATE reinsurance.cover_period SET ends_on = GREATEST(?, starts_on)"
            + " WHERE tenant_id = ? AND policy_number = ? AND ends_on IS NULL",
            Date.valueOf(on), tenantId, policyNumber);
    }

    /** A free-look cancellation voids the contract from inception: it was never on risk. */
    void voidAll(UUID tenantId, String policyNumber) {
        jdbc.update("DELETE FROM reinsurance.cover_period WHERE tenant_id = ? AND policy_number = ?",
            tenantId, policyNumber);
    }

    List<Cover> of(UUID tenantId, String policyNumber) {
        return jdbc.query("SELECT starts_on, ends_on FROM reinsurance.cover_period"
                + " WHERE tenant_id = ? AND policy_number = ? ORDER BY starts_on",
            (rs, i) -> new Cover(rs.getDate("starts_on").toLocalDate(),
                rs.getDate("ends_on") == null ? null : rs.getDate("ends_on").toLocalDate()),
            tenantId, policyNumber);
    }
}
