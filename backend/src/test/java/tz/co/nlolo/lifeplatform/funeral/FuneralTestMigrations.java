package tz.co.nlolo.lifeplatform.funeral;

import tz.co.nlolo.lifeplatform.annuity.AnnuityTestMigrations;

import java.util.stream.Stream;

/**
 * Every migration a funeral policy's path reaches: the annuity list (issuance, billing, the payment rail,
 * claims V1-V9) plus the funeral ones. One list for every funeral test class; each later task appends here.
 */
public final class FuneralTestMigrations {
    private FuneralTestMigrations() {}

    private static final String[] FUNERAL = {
        // The ledger: a restated premium must move the receivable by exactly the difference.
        "db-migrations/finaccounting/V1__create_finaccounting_schema.sql",
        "db-migrations/finaccounting/V2__grants_rls_chart_of_accounts_journal_entry_and_posting_columns.sql",
        "db-migrations/finaccounting/V3__account_code_foreign_key.sql",
        "db-migrations/finaccounting/V4__chart_of_account_writable_via_api.sql",
        "db-migrations/finaccounting/V5__chart_of_account_hierarchy.sql",
        "db-migrations/finaccounting/V7__q4_2026_partitions.sql",
        "db-migrations/finaccounting/V8__withholding_tax_account.sql",
        "db-migrations/product/V24__funeral_terms.sql",
        "db-migrations/underwriting/V16__funeral_application.sql",
        "db-migrations/policy/V35__covered_life.sql",
        "db-migrations/claims/V10__funeral_claims.sql",
    };

    public static final String[] ALL = Stream.concat(Stream.of(AnnuityTestMigrations.ALL), Stream.of(FUNERAL))
        .toArray(String[]::new);
}
