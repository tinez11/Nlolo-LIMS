package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.domain.PolicyStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportSql.ANNUALISED_AT_SALE;
import static tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportSql.ANNUALISED_IN_FORCE;
import static tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportSql.CLIENT_JOIN;
import static tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportSql.IN_FORCE_STATUSES;
import static tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportSql.LAST_CHANGE_CTE;
import static tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportSql.SINGLE_PREMIUM;
import static tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportSql.appendFilters;
import static tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportSql.dimension;

/** Every report read. Each query filters tenant_id explicitly. */
@Repository
public class ReportQueries {

    private final JdbcTemplate jdbc;

    public ReportQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record InForceRow(String dimension, String currency, long policies, BigDecimal sumAssured,
                             BigDecimal annualised, BigDecimal single) {}

    public record NewBusinessRow(String month, String dimension, String currency, long policies, BigDecimal sumAssured,
                                 BigDecimal annualised, BigDecimal single) {}

    public record MonthCount(String month, String dimension, String currency, long count) {}

    public record CohortRow(String policyNumber, LocalDate activatedOn, BigDecimal premium, String frequency,
                            String dimension, PolicyStatus status, LocalDate effectiveOn, Instant occurredAt, UUID eventId) {}

    public record CollectionRow(String month, String dimension, String channel, String currency, long receipts, BigDecimal amount) {}

    public record TopPolicyRow(String policyNumber, String currency, long receipts, BigDecimal amount) {}

    public record ClientSummaryRow(String partyType, long total, long newInPeriod, long verified, long pending,
                                   long rejected, long withoutInForce) {}

    public record IndividualRow(LocalDate dateOfBirth, String sex, long inForcePolicies) {}

    public record SchemeRow(String policyNumber, String holder, String productCategory, String status, Integer lives,
                            long joined, long exited, BigDecimal totalCovered, BigDecimal annualised, String currency) {}

    // ---- policies -----------------------------------------------------------------------------------------

    public List<InForceRow> inForce(UUID tenantId, LocalDate asOf, GroupBy groupBy, ReportFilter f) {
        List<Object> args = new ArrayList<>(List.of(tenantId, asOf, tenantId));
        StringBuilder sql = new StringBuilder("WITH " + LAST_CHANGE_CTE + " SELECT " + dimension(groupBy) + " AS dim,"
            + " p.currency, COUNT(*) AS policies, COALESCE(SUM(p.sum_assured), 0) AS sum_assured,"
            + " COALESCE(SUM(" + ANNUALISED_IN_FORCE + "), 0) AS annualised,"
            + " COALESCE(SUM(" + SINGLE_PREMIUM + "), 0) AS single_premium"
            + " FROM reporting.report_policy p JOIN last_change l ON l.policy_number = p.policy_number" + CLIENT_JOIN
            + " WHERE p.tenant_id = ? AND l.to_status IN " + IN_FORCE_STATUSES);
        appendFilters(sql, args, f);
        sql.append(" GROUP BY dim, p.currency ORDER BY dim, p.currency");
        return jdbc.query(sql.toString(), (rs, i) -> new InForceRow(rs.getString("dim"), rs.getString("currency").trim(),
            rs.getLong("policies"), rs.getBigDecimal("sum_assured"), rs.getBigDecimal("annualised"),
            rs.getBigDecimal("single_premium")), args.toArray());
    }

    public List<NewBusinessRow> activations(UUID tenantId, LocalDate from, LocalDate to, GroupBy groupBy, ReportFilter f) {
        List<Object> args = new ArrayList<>(List.of(tenantId, from, to));
        StringBuilder sql = new StringBuilder("SELECT to_char(p.activated_on, 'YYYY-MM') AS month, " + dimension(groupBy)
            + " AS dim, p.currency, COUNT(*) AS policies, COALESCE(SUM(p.sum_assured), 0) AS sum_assured,"
            + " COALESCE(SUM(" + ANNUALISED_AT_SALE + "), 0) AS annualised,"
            + " COALESCE(SUM(" + SINGLE_PREMIUM + "), 0) AS single_premium"
            + " FROM reporting.report_policy p" + CLIENT_JOIN
            + " WHERE p.tenant_id = ? AND p.activated_on BETWEEN ? AND ?");
        appendFilters(sql, args, f);
        sql.append(" GROUP BY month, dim, p.currency ORDER BY month, dim, p.currency");
        return jdbc.query(sql.toString(), (rs, i) -> new NewBusinessRow(rs.getString("month"), rs.getString("dim"),
            rs.getString("currency").trim(), rs.getLong("policies"), rs.getBigDecimal("sum_assured"),
            rs.getBigDecimal("annualised"), rs.getBigDecimal("single_premium")), args.toArray());
    }

    public List<MonthCount> notTakenUp(UUID tenantId, LocalDate from, LocalDate to, GroupBy groupBy, ReportFilter f) {
        List<Object> args = new ArrayList<>(List.of(tenantId, from, to));
        StringBuilder sql = new StringBuilder("SELECT to_char(p.not_taken_up_on, 'YYYY-MM') AS month, " + dimension(groupBy)
            + " AS dim, p.currency, COUNT(*) AS n FROM reporting.report_policy p" + CLIENT_JOIN
            + " WHERE p.tenant_id = ? AND p.not_taken_up_on BETWEEN ? AND ?");
        appendFilters(sql, args, f);
        sql.append(" GROUP BY month, dim, p.currency");
        return jdbc.query(sql.toString(), (rs, i) -> new MonthCount(rs.getString("month"), rs.getString("dim"),
            rs.getString("currency").trim(), rs.getLong("n")), args.toArray());
    }

    /** Status changes with this cause (an event type) whose effective date falls in the period, per dimension. */
    public Map<String, Long> causeCounts(UUID tenantId, String cause, LocalDate from, LocalDate to, GroupBy groupBy,
                                         ReportFilter f) {
        List<Object> args = new ArrayList<>(List.of(tenantId, cause, from, to));
        StringBuilder sql = new StringBuilder("SELECT " + dimension(groupBy) + " AS dim, COUNT(*) AS n"
            + " FROM reporting.report_policy_status_change s"
            + " JOIN reporting.report_policy p ON p.tenant_id = s.tenant_id AND p.policy_number = s.policy_number"
            + CLIENT_JOIN + " WHERE s.tenant_id = ? AND s.cause = ? AND s.effective_on BETWEEN ? AND ?");
        appendFilters(sql, args, f);
        sql.append(" GROUP BY dim");
        Map<String, Long> counts = new HashMap<>();
        jdbc.query(sql.toString(), rs -> {
            counts.put(rs.getString("dim"), rs.getLong("n"));
        }, args.toArray());
        return counts;
    }

    /**
     * Every status change of every policy activated in the cohort months -- one row per change -- leaving out
     * single premiums and investment portfolios, which cannot lapse. Free-look cancellations are dropped by the
     * caller from the history itself.
     */
    public List<CohortRow> cohort(UUID tenantId, YearMonth from, YearMonth to, GroupBy groupBy, ReportFilter f) {
        List<Object> args = new ArrayList<>(List.of(tenantId, from.atDay(1), to.atEndOfMonth()));
        StringBuilder sql = new StringBuilder("SELECT p.policy_number, p.activated_on, p.premium, p.premium_frequency, "
            + dimension(groupBy) + " AS dim, s.to_status, s.effective_on, s.occurred_at, s.event_id"
            + " FROM reporting.report_policy p"
            + " JOIN reporting.report_policy_status_change s ON s.tenant_id = p.tenant_id AND s.policy_number = p.policy_number"
            + CLIENT_JOIN + " WHERE p.tenant_id = ? AND p.activated_on BETWEEN ? AND ?"
            + " AND COALESCE(p.premium_frequency, '') <> 'SINGLE'"
            + " AND COALESCE(p.portfolio_code, '') NOT IN ('SAV','DEP','PEN','DANN')");
        appendFilters(sql, args, f);
        return jdbc.query(sql.toString(), (rs, i) -> new CohortRow(rs.getString("policy_number"),
            rs.getObject("activated_on", LocalDate.class), rs.getBigDecimal("premium"), rs.getString("premium_frequency"),
            rs.getString("dim"), PolicyStatus.valueOf(rs.getString("to_status")),
            rs.getObject("effective_on", LocalDate.class), rs.getTimestamp("occurred_at").toInstant(),
            rs.getObject("event_id", UUID.class)), args.toArray());
    }

    // ---- collections --------------------------------------------------------------------------------------

    private static final String COLLECTION_FROM = " FROM reporting.report_collection r"
        + " LEFT JOIN reporting.report_policy p ON p.tenant_id = r.tenant_id AND p.policy_number = r.policy_number"
        + CLIENT_JOIN + " WHERE r.tenant_id = ? AND r.collected_on BETWEEN ? AND ?";

    public List<CollectionRow> collections(UUID tenantId, LocalDate from, LocalDate to, GroupBy groupBy, ReportFilter f) {
        List<Object> args = new ArrayList<>(List.of(tenantId, from, to));
        StringBuilder sql = new StringBuilder("SELECT to_char(r.collected_on, 'YYYY-MM') AS month, " + dimension(groupBy)
            + " AS dim, r.channel, r.currency, COUNT(*) AS receipts, SUM(r.amount) AS amount" + COLLECTION_FROM);
        appendFilters(sql, args, f);
        sql.append(" GROUP BY month, dim, r.channel, r.currency ORDER BY month, dim, r.channel");
        return jdbc.query(sql.toString(), (rs, i) -> new CollectionRow(rs.getString("month"), rs.getString("dim"),
            rs.getString("channel"), rs.getString("currency").trim(), rs.getLong("receipts"), rs.getBigDecimal("amount")),
            args.toArray());
    }

    public List<TopPolicyRow> topPolicies(UUID tenantId, LocalDate from, LocalDate to, ReportFilter f, int limit) {
        List<Object> args = new ArrayList<>(List.of(tenantId, from, to));
        StringBuilder sql = new StringBuilder("SELECT r.policy_number, r.currency, COUNT(*) AS receipts, SUM(r.amount) AS amount"
            + COLLECTION_FROM);
        appendFilters(sql, args, f);
        sql.append(" GROUP BY r.policy_number, r.currency ORDER BY amount DESC, r.policy_number LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> new TopPolicyRow(rs.getString("policy_number"),
            rs.getString("currency").trim(), rs.getLong("receipts"), rs.getBigDecimal("amount")), args.toArray());
    }

    // ---- clients ------------------------------------------------------------------------------------------

    /** Binds (tenant). Holders of a policy in force on last_change's date. */
    private static final String HELD_CTE = ", held AS (SELECT DISTINCT p.holder_party_id FROM reporting.report_policy p"
        + " JOIN last_change l ON l.policy_number = p.policy_number"
        + " WHERE p.tenant_id = ? AND p.holder_party_id IS NOT NULL AND l.to_status IN " + IN_FORCE_STATUSES + ")";

    public List<ClientSummaryRow> clientSummary(UUID tenantId, LocalDate asOf, LocalDate from, LocalDate to, String clientType) {
        List<Object> args = new ArrayList<>(List.of(tenantId, asOf, tenantId, asOf, from, to, asOf, asOf, asOf, asOf, tenantId));
        StringBuilder sql = new StringBuilder("WITH " + LAST_CHANGE_CTE + HELD_CTE + " SELECT c.party_type,"
            + " COUNT(*) FILTER (WHERE c.registered_on <= ?) AS total,"
            + " COUNT(*) FILTER (WHERE c.registered_on BETWEEN ? AND ?) AS new_in_period,"
            + " COUNT(*) FILTER (WHERE c.registered_on <= ? AND c.kyc_status = 'VERIFIED') AS verified,"
            + " COUNT(*) FILTER (WHERE c.registered_on <= ? AND c.kyc_status = 'PENDING') AS pending,"
            + " COUNT(*) FILTER (WHERE c.registered_on <= ? AND c.kyc_status = 'REJECTED') AS rejected,"
            + " COUNT(*) FILTER (WHERE c.registered_on <= ? AND c.party_id NOT IN (SELECT holder_party_id FROM held)) AS without_in_force"
            + " FROM reporting.report_client c WHERE c.tenant_id = ?");
        if (clientType != null) {
            sql.append(" AND c.party_type = ?");
            args.add(clientType);
        }
        sql.append(" GROUP BY c.party_type ORDER BY c.party_type");
        return jdbc.query(sql.toString(), (rs, i) -> new ClientSummaryRow(rs.getString("party_type"), rs.getLong("total"),
            rs.getLong("new_in_period"), rs.getLong("verified"), rs.getLong("pending"), rs.getLong("rejected"),
            rs.getLong("without_in_force")), args.toArray());
    }

    public List<IndividualRow> individuals(UUID tenantId, LocalDate asOf) {
        return jdbc.query("WITH " + LAST_CHANGE_CTE + ", in_force AS (SELECT p.holder_party_id, COUNT(*) AS n"
                + " FROM reporting.report_policy p JOIN last_change l ON l.policy_number = p.policy_number"
                + " WHERE p.tenant_id = ? AND l.to_status IN " + IN_FORCE_STATUSES + " GROUP BY p.holder_party_id)"
                + " SELECT c.date_of_birth, c.sex, COALESCE(i.n, 0) AS n FROM reporting.report_client c"
                + " LEFT JOIN in_force i ON i.holder_party_id = c.party_id"
                + " WHERE c.tenant_id = ? AND c.party_type = 'INDIVIDUAL' AND c.registered_on <= ?",
            (rs, i) -> new IndividualRow(rs.getObject("date_of_birth", LocalDate.class), rs.getString("sex"), rs.getLong("n")),
            tenantId, asOf, tenantId, tenantId, asOf);
    }

    public List<SchemeRow> schemes(UUID tenantId, LocalDate asOf, LocalDate from, LocalDate to, ReportFilter f) {
        List<Object> args = new ArrayList<>(List.of(tenantId, asOf, tenantId, asOf, tenantId, from, to, tenantId, asOf));
        StringBuilder sql = new StringBuilder("WITH " + LAST_CHANGE_CTE + ","
            + " lives AS (SELECT DISTINCT ON (x.policy_number) x.policy_number, x.lives_covered, x.scheme_total_covered"
            + "   FROM reporting.report_lives_change x WHERE x.tenant_id = ? AND x.effective_on <= ?"
            + "   ORDER BY x.policy_number, x.effective_on DESC, x.occurred_at DESC, x.event_id DESC),"
            + " moves AS (SELECT x.policy_number, COUNT(*) FILTER (WHERE x.kind = 'JOINED') AS joined,"
            + "   COUNT(*) FILTER (WHERE x.kind = 'EXITED') AS exited FROM reporting.report_lives_change x"
            + "   WHERE x.tenant_id = ? AND x.effective_on BETWEEN ? AND ? GROUP BY x.policy_number)"
            + " SELECT p.policy_number, c.display_name, p.product_category, l.to_status, lv.lives_covered,"
            + " COALESCE(m.joined, 0) AS joined, COALESCE(m.exited, 0) AS exited,"
            + " COALESCE(lv.scheme_total_covered, p.sum_assured) AS total_covered, " + ANNUALISED_IN_FORCE + " AS annualised,"
            + " p.currency FROM reporting.report_policy p"
            + " LEFT JOIN last_change l ON l.policy_number = p.policy_number"
            + " LEFT JOIN lives lv ON lv.policy_number = p.policy_number"
            + " LEFT JOIN moves m ON m.policy_number = p.policy_number" + CLIENT_JOIN
            + " WHERE p.tenant_id = ? AND p.group_scheme AND p.activated_on <= ?");
        appendFilters(sql, args, f);
        sql.append(" ORDER BY p.policy_number");
        return jdbc.query(sql.toString(), (rs, i) -> new SchemeRow(rs.getString("policy_number"), rs.getString("display_name"),
            rs.getString("product_category"), rs.getString("to_status"), (Integer) rs.getObject("lives_covered"),
            rs.getLong("joined"), rs.getLong("exited"), rs.getBigDecimal("total_covered"), rs.getBigDecimal("annualised"),
            rs.getString("currency").trim()), args.toArray());
    }
}
