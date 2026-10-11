package tz.co.nlolo.lifeplatform.reporting;

import tz.co.nlolo.lifeplatform.accumulation.DepositTestMigrations;

import java.util.Arrays;
import java.util.stream.Stream;

/** Every migration a reporting integration test needs: the issuance path (party, product, policy, billing,
 * audit -- DepositTestMigrations.ALL) plus this module's own schema. One list, so the next reporting
 * migration is added in one place. */
public final class ReportingTestMigrations {
    private ReportingTestMigrations() {}

    public static final String[] ALL = Stream.concat(Arrays.stream(DepositTestMigrations.ALL),
        Stream.of("db-migrations/reporting/V1__create_reporting_schema.sql")).toArray(String[]::new);
}
