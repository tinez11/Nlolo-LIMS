package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tz.co.nlolo.lifeplatform.reporting.api.NewSolvencyFactorSet;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactorSetView;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactors;

import java.sql.Date;
import java.util.List;
import java.util.UUID;

/** The published solvency factor sets, newest first. Insert only: a version is never changed. */
@Repository
public class SolvencyFactorStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public SolvencyFactorStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<SolvencyFactorSetView> all(UUID tenantId) {
        return jdbc.query("SELECT version, name, effective_from, placeholder, factors::text AS factors, created_by,"
                + " created_at FROM reporting.solvency_factor_set WHERE tenant_id = ? ORDER BY version DESC",
            (rs, i) -> new SolvencyFactorSetView(rs.getInt("version"), rs.getString("name"),
                rs.getDate("effective_from").toLocalDate(), rs.getBoolean("placeholder"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(), read(rs.getString("factors"))), tenantId);
    }

    /** The next version number, written; the primary key refuses a second publish racing this one to it. */
    public int insert(UUID tenantId, NewSolvencyFactorSet set, String createdBy) {
        Integer last = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) FROM reporting.solvency_factor_set"
            + " WHERE tenant_id = ?", Integer.class, tenantId);
        int version = (last == null ? 0 : last) + 1;
        jdbc.update("INSERT INTO reporting.solvency_factor_set (tenant_id, version, name, effective_from, placeholder,"
                + " factors, created_by) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)",
            tenantId, version, set.name(), Date.valueOf(set.effectiveFrom()), set.placeholder(), write(set.factors()),
            createdBy);
        return version;
    }

    private SolvencyFactors read(String text) {
        try {
            return json.readValue(text, SolvencyFactors.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored solvency factor set could not be read", e);
        }
    }

    private String write(SolvencyFactors factors) {
        try {
            return json.writeValueAsString(factors);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A solvency factor set could not be written", e);
        }
    }
}
