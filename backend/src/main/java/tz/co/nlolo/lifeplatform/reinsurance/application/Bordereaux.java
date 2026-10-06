package tz.co.nlolo.lifeplatform.reinsurance.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reinsurance.api.BordereauView;
import tz.co.nlolo.lifeplatform.reinsurance.domain.BordereauCalculator;
import tz.co.nlolo.lifeplatform.reinsurance.domain.BordereauCalculator.CededPolicy;
import tz.co.nlolo.lifeplatform.reinsurance.domain.BordereauCalculator.Cover;
import tz.co.nlolo.lifeplatform.reinsurance.domain.BordereauCalculator.Recovery;

import java.sql.Timestamp;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The bordereaux and what they are built from (IFRS 17 I3c, reinsurance V5). JDBC, under the caller's tenant and
 * transaction; RLS scopes every read.
 */
@Component
class Bordereaux {

    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");

    private final JdbcTemplate jdbc;

    Bordereaux(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every policy ceded to the treaty, with when it was on risk. Schemes are never ceded, so never here. */
    List<CededPolicy> cededTo(UUID tenantId, UUID treatyId) {
        Map<String, List<Cover>> cover = new LinkedHashMap<>();
        jdbc.query("SELECT cp.policy_number, cp.starts_on, cp.ends_on FROM reinsurance.cover_period cp"
                + " JOIN reinsurance.cession c ON c.tenant_id = cp.tenant_id AND c.policy_number = cp.policy_number"
                + " WHERE c.tenant_id = ? AND c.treaty_id = ? ORDER BY cp.starts_on",
            rs -> {
                cover.computeIfAbsent(rs.getString("policy_number"), k -> new ArrayList<>())
                    .add(new Cover(rs.getDate("starts_on").toLocalDate(),
                        rs.getDate("ends_on") == null ? null : rs.getDate("ends_on").toLocalDate()));
            }, tenantId, treatyId);
        return jdbc.query("SELECT c.policy_number, c.premium_share, p.premium_amount, p.premium_currency,"
                + " p.premium_frequency, p.premiums_end_on FROM reinsurance.cession c"
                + " JOIN reinsurance.policy_projection p ON p.tenant_id = c.tenant_id AND p.policy_number = c.policy_number"
                + " WHERE c.tenant_id = ? AND c.treaty_id = ? ORDER BY c.policy_number",
            (rs, i) -> new CededPolicy(rs.getString("policy_number"), rs.getBigDecimal("premium_share"),
                rs.getBigDecimal("premium_amount"), trim(rs.getString("premium_currency")),
                rs.getString("premium_frequency"),
                rs.getDate("premiums_end_on") == null ? null : rs.getDate("premiums_end_on").toLocalDate(),
                cover.getOrDefault(rs.getString("policy_number"), List.of())),
            tenantId, treatyId);
    }

    /** The recoveries recorded against the treaty in the month (civil), to be matched on its bordereau (K-03). */
    List<Recovery> recoveriesIn(UUID tenantId, UUID treatyId, YearMonth month) {
        return jdbc.query("SELECT claim_id, recoverable_amount, recoverable_currency FROM reinsurance.claim_recovery"
                + " WHERE tenant_id = ? AND treaty_id = ? AND created_at >= ? AND created_at < ? ORDER BY created_at",
            (rs, i) -> new Recovery(rs.getObject("claim_id", UUID.class), null, rs.getBigDecimal("recoverable_amount"),
                trim(rs.getString("recoverable_currency"))),
            tenantId, treatyId, start(month), start(month.plusMonths(1)));
    }

    boolean exists(UUID tenantId, UUID treatyId, YearMonth month) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM reinsurance.bordereau"
            + " WHERE tenant_id = ? AND treaty_id = ? AND period = ?", Integer.class, tenantId, treatyId, month.toString());
        return n != null && n > 0;
    }

    /** Writes the bordereau and its lines; empty when the month was already written (a racing run). */
    Optional<UUID> insert(UUID tenantId, UUID treatyId, YearMonth month, String currency,
                          BordereauCalculator.Result result, String by) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("INSERT INTO reinsurance.bordereau (bordereau_id, tenant_id, treaty_id, period, currency,"
                + " policy_count, premium, commission, recoveries, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (tenant_id, treaty_id, period) DO NOTHING",
            id, tenantId, treatyId, month.toString(), currency, result.policyCount(), result.premium(),
            result.commission(), result.recoveries(), by);
        if (inserted == 0) {
            return Optional.empty();
        }
        for (BordereauCalculator.Line l : result.lines()) {
            jdbc.update("INSERT INTO reinsurance.bordereau_line (bordereau_id, tenant_id, line_type, policy_number,"
                    + " claim_id, premium_share, policy_premium, premium, commission, recovery)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, l.type().name(), l.policyNumber(), l.claimId(), l.premiumShare(), l.policyPremium(),
                l.premium(), l.commission(), l.recovery());
        }
        return Optional.of(id);
    }

    List<BordereauView> list(UUID tenantId, UUID treatyId) {
        return jdbc.query("SELECT * FROM reinsurance.bordereau WHERE tenant_id = ? AND treaty_id = ? ORDER BY period DESC",
            (rs, i) -> header(rs, List.of()), tenantId, treatyId);
    }

    Optional<BordereauView> find(UUID tenantId, UUID bordereauId) {
        List<BordereauView> found = jdbc.query("SELECT * FROM reinsurance.bordereau WHERE tenant_id = ? AND bordereau_id = ?",
            (rs, i) -> header(rs, lines(tenantId, bordereauId)), tenantId, bordereauId);
        return found.stream().findFirst();
    }

    Optional<UUID> idOf(UUID tenantId, UUID treatyId, YearMonth month) {
        return jdbc.query("SELECT bordereau_id FROM reinsurance.bordereau WHERE tenant_id = ? AND treaty_id = ? AND period = ?",
            (rs, i) -> rs.getObject("bordereau_id", UUID.class), tenantId, treatyId, month.toString()).stream().findFirst();
    }

    private List<BordereauView.Line> lines(UUID tenantId, UUID bordereauId) {
        return jdbc.query("SELECT * FROM reinsurance.bordereau_line WHERE tenant_id = ? AND bordereau_id = ?"
                + " ORDER BY CASE line_type WHEN 'PREMIUM' THEN 0 WHEN 'XOL_PREMIUM' THEN 1 ELSE 2 END, policy_number",
            (rs, i) -> new BordereauView.Line(rs.getString("line_type"), rs.getString("policy_number"),
                rs.getObject("claim_id", UUID.class), rs.getBigDecimal("premium_share"),
                rs.getBigDecimal("policy_premium"), rs.getBigDecimal("premium"), rs.getBigDecimal("commission"),
                rs.getBigDecimal("recovery")),
            tenantId, bordereauId);
    }

    private static BordereauView header(java.sql.ResultSet rs, List<BordereauView.Line> lines) throws java.sql.SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new BordereauView(rs.getObject("bordereau_id", UUID.class), rs.getObject("treaty_id", UUID.class),
            rs.getString("period"), trim(rs.getString("currency")), rs.getInt("policy_count"), rs.getBigDecimal("premium"),
            rs.getBigDecimal("commission"), rs.getBigDecimal("recoveries"), created == null ? null : created.toInstant(),
            lines);
    }

    private static Timestamp start(YearMonth month) {
        return Timestamp.from(month.atDay(1).atStartOfDay(CIVIL).toInstant());
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
