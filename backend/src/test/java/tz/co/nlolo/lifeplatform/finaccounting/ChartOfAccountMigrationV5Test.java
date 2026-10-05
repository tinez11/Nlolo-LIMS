package tz.co.nlolo.lifeplatform.finaccounting;

import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint.Seed;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The remap is the highest-risk step in this whole change: three of the nine legacy codes ROTATE
 * (5000 to 5100, 5100 to 5200, 5200 to 5500), so a posting that lands on the wrong row is a
 * financial misstatement that still balances -- the trial balance would not notice. This test is
 * the reason that cannot ship silently.
 *
 * <p>TWO tenants, deliberately: the migration must be generic over {@code tenant_id}, and a
 * single-tenant fixture would let a hardcoded id pass.
 */
@Testcontainers
class ChartOfAccountMigrationV5Test {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID[] TENANTS = { TENANT_A, TENANT_B };

    /** Inside gl_posting_2026_09, which V1 creates. Pinned rather than left to now(), so this
     *  test does not start failing when the wall clock leaves the last declared partition. */
    private static final String POSTED_AT = "2026-09-15T00:00:00Z";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @BeforeAll
    static void migrateAndSeedLegacyData() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            "db-migrations/finaccounting/V1__create_finaccounting_schema.sql",
            "db-migrations/finaccounting/V2__grants_rls_chart_of_accounts_journal_entry_and_posting_columns.sql",
            "db-migrations/finaccounting/V3__account_code_foreign_key.sql",
            "db-migrations/finaccounting/V4__chart_of_account_writable_via_api.sql");

        for (UUID tenant : TENANTS) {
            seedLegacyChart(tenant);
            for (String oldCode : ChartOfAccountBlueprint.legacyRemap().keySet()) {
                writePosting(tenant, oldCode);
            }
        }

        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            "db-migrations/finaccounting/V5__chart_of_account_hierarchy.sql",
            "db-migrations/finaccounting/V7__q4_2026_partitions.sql",
            // Product step 5: V8 adds 2230 to every seeded chart, so the migrated chart still equals the blueprint.
            "db-migrations/finaccounting/V8__withholding_tax_account.sql",
            // Product step 6: V9 adds 2150, 4310 and 5600 the same way.
            "db-migrations/finaccounting/V9__unit_linked_accounts.sql");
    }

    /** The nine flat accounts as M9 seeded them -- no parent, no level, no status. */
    private static void seedLegacyChart(UUID tenant) throws Exception {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO finaccounting.chart_of_account "
                 + "(tenant_id, account_code, name, account_type, normal_balance, created_by) "
                 + "VALUES (?, ?, ?, ?, ?, 'legacy-seed')")) {
            for (String code : ChartOfAccountBlueprint.legacyRemap().keySet()) {
                ps.setObject(1, tenant);
                ps.setString(2, code);
                ps.setString(3, "Legacy " + code);
                ps.setString(4, switch (code.charAt(0)) {
                    case '1' -> "ASSET";
                    case '2' -> "LIABILITY";
                    case '5' -> "EXPENSE";
                    default -> throw new IllegalStateException("Unexpected legacy code " + code);
                });
                ps.setString(5, code.startsWith("2") ? "CR" : "DR");
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** One journal entry plus one posting leg carrying {@code accountCode}, so the remap has
     *  something real to move and the recreated foreign key has something real to validate. */
    private static void writePosting(UUID tenant, String accountCode) throws Exception {
        try (Connection c = connect()) {
            UUID entryId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO finaccounting.journal_entry "
                    + "(tenant_id, source_event, source_ref, period, created_by) "
                    + "VALUES (?, ?, ?, '2026-09', 'legacy-seed') RETURNING journal_entry_id")) {
                ps.setObject(1, tenant);
                ps.setString(2, "test.Legacy" + accountCode);
                ps.setString(3, tenant + ":" + accountCode);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    entryId = rs.getObject(1, UUID.class);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO finaccounting.gl_posting "
                    + "(tenant_id, journal_entry_id, account_code, direction, amount, currency, "
                    + " period, posting_type, source_event, source_ref, created_at) "
                    + "VALUES (?, ?, ?, 'DR', 100.00, 'TZS', '2026-09', ?, ?, ?, ?::timestamptz)")) {
                ps.setObject(1, tenant);
                ps.setObject(2, entryId);
                ps.setString(3, accountCode);
                ps.setString(4, "test.Legacy" + accountCode);
                ps.setString(5, "test.Legacy" + accountCode);
                ps.setString(6, tenant + ":" + accountCode);
                ps.setString(7, POSTED_AT);
                ps.executeUpdate();
            }
        }
    }

    @Test
    void everyLegacyPostingNowCarriesItsNewAccountCode() throws Exception {
        for (Map.Entry<String, String> remap : ChartOfAccountBlueprint.legacyRemap().entrySet()) {
            for (UUID tenant : TENANTS) {
                try (Connection c = connect();
                     PreparedStatement ps = c.prepareStatement(
                         "SELECT account_code FROM finaccounting.gl_posting "
                         + "WHERE tenant_id = ? AND source_ref = ?")) {
                    ps.setObject(1, tenant);
                    ps.setString(2, tenant + ":" + remap.getKey());
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next())
                            .as("the posting for legacy %s survived", remap.getKey()).isTrue();
                        assertThat(rs.getString(1))
                            .as("legacy %s must remap to %s", remap.getKey(), remap.getValue())
                            .isEqualTo(remap.getValue());
                    }
                }
            }
        }
    }

    @Test
    void noPostingWasOrphanedOrLost() throws Exception {
        int expected = ChartOfAccountBlueprint.legacyRemap().size() * TENANTS.length;
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM finaccounting.gl_posting")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(expected);
        }
        // The recreated FK is the real proof: it cannot exist if any posting names a missing code.
        // Scoped to the PARENT table by conrelid -- gl_posting is partitioned, so an unscoped
        // count returns one row per partition as well and would pass for the wrong reason.
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT count(*) FROM pg_constraint WHERE conname = 'fk_gl_posting_account_code' "
                 + "AND conrelid = 'finaccounting.gl_posting'::regclass")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void theMigratedChartMatchesTheJavaBlueprintExactly() throws Exception {
        Map<String, Seed> expected = new HashMap<>();
        ChartOfAccountBlueprint.accounts().forEach(s -> expected.put(s.code(), s));

        for (UUID tenant : TENANTS) {
            Map<String, Seed> actual = new HashMap<>();
            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT account_code, name, parent_code, posting_allowed, control_of "
                     + "FROM finaccounting.chart_of_account WHERE tenant_id = ?")) {
                ps.setObject(1, tenant);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        actual.put(rs.getString(1), new Seed(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getBoolean(4), rs.getString(5)));
                    }
                }
            }
            assertThat(actual).as("V5's SQL and ChartOfAccountBlueprint must not drift")
                .containsExactlyInAnyOrderEntriesOf(expected);
        }
    }

    @Test
    void levelsAreDerivedFromTheParentChain() throws Exception {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT account_code, level FROM finaccounting.chart_of_account "
                 + "WHERE tenant_id = ? AND account_code IN ('1000','1100','1110','5500')")) {
            ps.setObject(1, TENANT_A);
            Map<String, Integer> levels = new HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    levels.put(rs.getString(1), rs.getInt(2));
                }
            }
            assertThat(levels).containsEntry("1000", 1).containsEntry("1100", 2)
                .containsEntry("1110", 3).containsEntry("5500", 2);
        }
    }

    @Test
    void accountTypeAndNormalBalanceStayDerivedFromTheLeadingDigit() throws Exception {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT account_code, account_type, normal_balance "
                 + "FROM finaccounting.chart_of_account WHERE tenant_id = ?")) {
            ps.setObject(1, TENANT_A);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String code = rs.getString(1);
                    String expectedType = switch (code.charAt(0)) {
                        case '1' -> "ASSET";
                        case '2' -> "LIABILITY";
                        case '3' -> "EQUITY";
                        case '4' -> "INCOME";
                        default -> "EXPENSE";
                    };
                    assertThat(rs.getString(2)).as("type of %s", code).isEqualTo(expectedType);
                    assertThat(rs.getString(3)).as("normal balance of %s", code)
                        .isEqualTo("234".indexOf(code.charAt(0)) >= 0 ? "CR" : "DR");
                }
            }
        }
    }

    /** 1400 is the one legacy code with no counterpart in the new chart -- if it survived,
     *  the DELETE step did not run. */
    @Test
    void noLegacyAccountSurvives() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT count(*) FROM finaccounting.chart_of_account WHERE account_code = '1400'")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    void theParentForeignKeyAndIndexExist() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT count(*) FROM pg_constraint WHERE conname = 'fk_chart_of_account_parent'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_chart_of_account_parent'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }
}
