package tz.co.nlolo.lifeplatform.reinsurance.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A unit-linked policy's risk premium, month by month (2026-11-12, finance finding 166): the cost of insurance the
 * charge run actually took from its units, as {@code unitlinked.ChargesTaken} reports it. A bordereau charges the
 * treaty's share of it, never of the premium that buys units. Once per charge (its source reference), so a repeated
 * event records nothing twice.
 */
@Component
class RiskCharges {

    private final JdbcTemplate jdbc;

    RiskCharges(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void record(Map<String, Object> payload) {
        BigDecimal coi = new BigDecimal(String.valueOf(payload.get("costOfInsurance")));
        if (coi.signum() <= 0) {
            return;
        }
        jdbc.update("INSERT INTO reinsurance.risk_charge (tenant_id, source_ref, policy_number, charge_date, amount, currency)"
                + " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, source_ref) DO NOTHING",
            TenantContext.get(), String.valueOf(payload.get("sourceRef")), String.valueOf(payload.get("policyNumber")),
            Date.valueOf(LocalDate.parse(String.valueOf(payload.get("chargeDate")))), coi,
            String.valueOf(payload.get("currencyCode")));
    }

    /** Each policy's cost of insurance charged in the month, by policy number. */
    Map<String, BigDecimal> in(UUID tenantId, YearMonth month) {
        Map<String, BigDecimal> byPolicy = new HashMap<>();
        jdbc.query("SELECT policy_number, SUM(amount) AS amount FROM reinsurance.risk_charge"
                + " WHERE tenant_id = ? AND charge_date >= ? AND charge_date <= ? GROUP BY policy_number",
            rs -> {
                byPolicy.put(rs.getString("policy_number"), rs.getBigDecimal("amount"));
            },
            tenantId, Date.valueOf(month.atDay(1)), Date.valueOf(month.atEndOfMonth()));
        return byPolicy;
    }
}
