package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;

import java.util.List;

/** SQL fragments every report shares. Each fragment states the parameters it binds, in order. */
public final class ReportSql {
    private ReportSql() {}

    public static final String IN_FORCE_STATUSES = "('ACTIVE','SUSPENDED','PAID_UP')";

    /** Binds (tenant, as-of date). The latest status change per policy on or before the date, ordered as
     * StatusTimeline orders it. */
    public static final String LAST_CHANGE_CTE = """
        last_change AS (
            SELECT DISTINCT ON (s.policy_number) s.policy_number, s.to_status
            FROM reporting.report_policy_status_change s
            WHERE s.tenant_id = ? AND s.effective_on <= ?
            ORDER BY s.policy_number, s.effective_on DESC, s.occurred_at DESC, s.event_id DESC)
        """;

    /** Binds nothing. Alias c, joined to policy alias p. */
    public static final String CLIENT_JOIN =
        " LEFT JOIN reporting.report_client c ON c.tenant_id = p.tenant_id AND c.party_id = p.holder_party_id";

    /** Annualised premium as sold (AnnualisedPremium in SQL). An unrecognised frequency is NULL, so SUM skips it. */
    public static final String ANNUALISED_AT_SALE =
        "(CASE p.premium_frequency WHEN 'MONTHLY' THEN p.premium * 12 WHEN 'QUARTERLY' THEN p.premium * 4"
            + " WHEN 'ANNUALLY' THEN p.premium END)";

    /** Needs last_change alias l: a paid-up policy pays nothing. */
    public static final String ANNUALISED_IN_FORCE =
        "(CASE WHEN l.to_status = 'PAID_UP' THEN 0 ELSE " + ANNUALISED_AT_SALE + " END)";

    public static final String SINGLE_PREMIUM = "(CASE WHEN p.premium_frequency = 'SINGLE' THEN p.premium END)";

    /** The grouping column, from a closed list -- never from request text. */
    public static String dimension(GroupBy groupBy) {
        return switch (groupBy) {
            case PRODUCT -> "COALESCE(p.product_id, '00000000-0000-0000-0000-000000000000'::uuid)::text";
            case PRODUCT_CATEGORY -> "COALESCE(p.product_category, 'UNKNOWN')";
            case AGENT -> "COALESCE(p.agent_id::text, 'DIRECT')";
            case CHANNEL -> "COALESCE(p.sales_channel, 'UNSPECIFIED')";
            case BRANCH -> "COALESCE(p.branch_code, 'UNSPECIFIED')";
        };
    }

    /** Appends the common filters on policy alias p and client alias c, binding their values. */
    public static void appendFilters(StringBuilder sql, List<Object> args, ReportFilter f) {
        if (f.productId() != null) { sql.append(" AND p.product_id = ?"); args.add(f.productId()); }
        if (f.productCategory() != null) { sql.append(" AND p.product_category = ?"); args.add(f.productCategory()); }
        if (f.agentId() != null) { sql.append(" AND p.agent_id = ?"); args.add(f.agentId()); }
        if (f.salesChannel() != null) { sql.append(" AND p.sales_channel = ?"); args.add(f.salesChannel()); }
        if (f.branchCode() != null) { sql.append(" AND p.branch_code = ?"); args.add(f.branchCode()); }
        if (f.clientType() != null) { sql.append(" AND c.party_type = ?"); args.add(f.clientType()); }
    }
}
