package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Puts a month into CLOSING straight in the table, for tests of what a closing (or, after a lock, a locked) month
 * refuses. Since 2026-11-12 (finance finding 90) the API closes only a month that has ended, so a test that needs
 * THIS month closing -- to see a posting dated today refused -- sets the state here instead.
 */
final class ClosingThisMonth {

    private ClosingThisMonth() {}

    static void start(JdbcTemplate jdbc, UUID tenantId, String period, String by) {
        jdbc.update("INSERT INTO finaccounting.accounting_period (tenant_id, period, status, closing_started_by,"
                + " closing_started_at) VALUES (?, ?, 'CLOSING', ?, now()) ON CONFLICT (tenant_id, period) DO UPDATE SET"
                + " status = 'CLOSING', closing_started_by = EXCLUDED.closing_started_by,"
                + " closing_started_at = EXCLUDED.closing_started_at",
            tenantId, period, by);
    }
}
