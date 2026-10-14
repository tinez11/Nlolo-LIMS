package tz.co.nlolo.lifeplatform.reporting;

import tz.co.nlolo.lifeplatform.accumulation.DepositTestMigrations;

import java.util.Arrays;
import java.util.stream.Stream;

/** Every migration a reporting integration test needs: the issuance path (party, product, policy, billing,
 * audit -- DepositTestMigrations.ALL), this module's own schema, and the general ledger. One list, so the next
 * reporting migration is added in one place. */
public final class ReportingTestMigrations {
    private ReportingTestMigrations() {}

    /**
     * The ledger, because the accounting reports read it LIVE rather than projecting it (batch 2a).
     *
     * <p>This is the visible cost of that decision and it is the right way round: a reporting context without
     * the ledger schema now fails loudly with "relation finaccounting.gl_posting does not exist" rather than
     * quietly serving a trial balance assembled from somewhere else.
     */
    private static final String[] FINACCOUNTING = {
        "db-migrations/finaccounting/V1__create_finaccounting_schema.sql",
        "db-migrations/finaccounting/V2__grants_rls_chart_of_accounts_journal_entry_and_posting_columns.sql",
        "db-migrations/finaccounting/V3__account_code_foreign_key.sql",
        "db-migrations/finaccounting/V4__chart_of_account_writable_via_api.sql",
        "db-migrations/finaccounting/V5__chart_of_account_hierarchy.sql",
        "db-migrations/finaccounting/V6__rls_fail_closed.sql",
        "db-migrations/finaccounting/V7__q4_2026_partitions.sql",
        "db-migrations/finaccounting/V8__withholding_tax_account.sql",
        "db-migrations/finaccounting/V9__unit_linked_accounts.sql",
        "db-migrations/finaccounting/V10__ifrs17_ledger_foundation.sql",
        "db-migrations/finaccounting/V11__groups_and_policy_classification.sql",
        "db-migrations/finaccounting/V12__unposted_events_and_paa_earning.sql",
        "db-migrations/finaccounting/V13__disbursement_method.sql",
        "db-migrations/finaccounting/V14__manual_journals.sql",
        "db-migrations/finaccounting/V15__engine_period_cycle.sql",
        "db-migrations/finaccounting/V16__expense_allocation.sql",
        "db-migrations/finaccounting/V17__year_end_close.sql",
        "db-migrations/finaccounting/V18__policy_snapshot_lives.sql",
    };

    public static final String[] ALL = Stream.of(
            Arrays.stream(DepositTestMigrations.ALL),
            Arrays.stream(FINACCOUNTING),
            Stream.of("db-migrations/reporting/V1__create_reporting_schema.sql"))
        .flatMap(s -> s).toArray(String[]::new);
}
