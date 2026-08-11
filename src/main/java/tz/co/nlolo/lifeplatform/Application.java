package tz.co.nlolo.lifeplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// First @Scheduled use in this codebase (billing.infrastructure.OverdueMetricsGauge). Every
// cross-tenant business-state sweep in this platform runs via pg_cron instead (never a plain
// Java @Scheduled) because a background thread with no TenantContext sees zero rows on every
// RLS-protected table and app_role must never bypass RLS -- see BillingApiImpl's own Global
// Constraints reasoning. That does not apply here: OverdueMetricsGauge only refreshes a single,
// non-tenant-scoped, already-RLS-exempt snapshot row (billing.overdue_metrics_snapshot), so a
// plain fixed-rate refresh introduces no cross-tenant risk.
@EnableScheduling
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
