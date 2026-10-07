package tz.co.nlolo.lifeplatform.finaccounting.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import tz.co.nlolo.lifeplatform.finaccounting.api.EngineExtractView;
import tz.co.nlolo.lifeplatform.finaccounting.api.EngineStateException;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus;
import tz.co.nlolo.lifeplatform.finaccounting.domain.EngineExtractSheets;
import tz.co.nlolo.lifeplatform.finaccounting.domain.EngineExtractSheets.PolicyRow;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The extract the IFRS 17 engine is sent (IFRS 17 I5a, month-end step 6). Only for a CLOSING period -- no event can
 * post into it any more -- with nothing unposted in it or before it: the engine must see every actual cash flow, once.
 * Numbered per period and kept whole, so what was sent can always be downloaded again exactly as it was.
 */
@Service
public class EngineExtracts {

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final JdbcTemplate jdbc;
    private final EngineLedger ledger;
    private final EnginePolicySnapshots snapshots;
    private final AccountingPeriods periods;
    private final UnpostedEvents unposted;
    private final DocumentApi documents;
    private final ObjectMapper json;

    EngineExtracts(JdbcTemplate jdbc, EngineLedger ledger, EnginePolicySnapshots snapshots, AccountingPeriods periods,
                   UnpostedEvents unposted, DocumentApi documents, ObjectMapper json) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.snapshots = snapshots;
        this.periods = periods;
        this.unposted = unposted;
        this.documents = documents;
        this.json = json;
    }

    @Transactional
    public EngineExtractView create(String period, String by) {
        UUID tenantId = TenantContext.get();
        PeriodStatus status = periods.view(period).status();
        if (status != PeriodStatus.CLOSING) {
            throw new EngineStateException("Period " + period + " is " + status + "; the engine is sent a period once it"
                + " is closing, so that nothing more posts into it");
        }
        int waiting = unposted.openUpTo(tenantId, period);
        if (waiting > 0) {
            throw new EngineStateException(waiting + (waiting == 1 ? " event is" : " events are") + " not posted in "
                + period + " or before; post or dismiss " + (waiting == 1 ? "it" : "them")
                + " first -- the engine must see every actual cash flow");
        }
        // Step 5 before step 6 (IFRS 17 I5b): the extract carries the month's allocated attributable expenses.
        Integer allocated = jdbc.queryForObject("SELECT count(*) FROM finaccounting.expense_allocation WHERE tenant_id = ?"
            + " AND period = ? AND status = 'POSTED'", Integer.class, tenantId, period);
        if (allocated == null || allocated == 0) {
            throw new EngineStateException("Step 5 first: " + period + " has no posted expense allocation; prepare one,"
                + " or record that there is none this month, and have it approved");
        }
        EngineExtractSheets sheets = new EngineExtractSheets(ledger.cashFlows(tenantId, period),
            ledger.balances(tenantId, period), policies(tenantId, period));
        Integer last = jdbc.queryForObject("SELECT max(number) FROM finaccounting.engine_extract WHERE tenant_id = ?"
            + " AND period = ?", Integer.class, tenantId, period);
        int number = last == null ? 1 : last + 1;
        UUID id = UUID.randomUUID();
        byte[] workbook = sheets.xlsx(period, number);
        String ref = documents.upload("extract:" + id, DocumentType.IFRS17_EXTRACT, by,
            new ByteArrayInputStream(workbook), workbook.length, XLSX, "ifrs17-extract-" + period + "-" + number + ".xlsx");
        jdbc.update("INSERT INTO finaccounting.engine_extract (extract_id, tenant_id, period, number, groups, cash_flows,"
                + " balances, policies, document_ref, created_by) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)",
            id, tenantId, period, number, sheets.groups().toArray(String[]::new), write(sheets.cashFlows()),
            write(sheets.balances()), write(sheets.policies()), ref, by);
        return find(tenantId, id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<EngineExtractView> list(String period) {
        return jdbc.query("SELECT * FROM finaccounting.engine_extract WHERE tenant_id = ? AND period = ? ORDER BY number DESC",
            this::view, TenantContext.get(), period);
    }

    @Transactional(readOnly = true)
    public EngineExtractView get(UUID id) {
        return find(TenantContext.get(), id).orElseThrow(() -> new tz.co.nlolo.lifeplatform.finaccounting.api.EngineNotFoundException("Extract " + id + " not found"));
    }

    /** The extract as it was sent: the workbook, or one sheet as CSV. */
    @Transactional(readOnly = true)
    public byte[] render(UUID id, String format, String sheet) {
        EngineExtractSheets sheets = sheets(TenantContext.get(), id);
        EngineExtractView view = get(id);
        if ("csv".equalsIgnoreCase(format)) {
            return sheets.csv(sheet == null ? "cash-flows" : sheet);
        }
        if (format == null || "xlsx".equalsIgnoreCase(format)) {
            return sheets.xlsx(view.period(), view.number());
        }
        throw new FinaccountingValidationException("A format is xlsx or csv, not '" + format + "'");
    }

    /** The groups an extract named: an engine run answering it may report on these only. */
    List<String> groups(UUID tenantId, UUID extractId) {
        return find(tenantId, extractId).map(EngineExtractView::groups).orElse(List.of());
    }

    Optional<EngineExtractView> byNumber(UUID tenantId, String period, int number) {
        return jdbc.query("SELECT * FROM finaccounting.engine_extract WHERE tenant_id = ? AND period = ? AND number = ?",
            this::view, tenantId, period, number).stream().findFirst();
    }

    private List<PolicyRow> policies(UUID tenantId, String period) {
        Map<String, String[]> classification = new HashMap<>();
        jdbc.query("SELECT DISTINCT ON (policy_number) policy_number, group_key, measurement_model"
                + " FROM finaccounting.policy_classification WHERE tenant_id = ? ORDER BY policy_number, effective_from DESC",
            rs -> {
                classification.put(rs.getString("policy_number"),
                    new String[] {rs.getString("group_key"), rs.getString("measurement_model")});
            },
            tenantId);
        Map<String, BigDecimal> fund = ledger.netByPolicy(tenantId, "2131", period);
        Map<String, BigDecimal> investment = ledger.netByPolicy(tenantId, "2124", period);
        List<PolicyRow> rows = new ArrayList<>();
        for (EnginePolicySnapshots.Snapshot s : snapshots.inForce(tenantId)) {
            String[] c = classification.get(s.policyNumber());
            if (c == null) {
                continue;   // not an IFRS 17 contract of any group (classified before I2, or never)
            }
            rows.add(new PolicyRow(s.policyNumber(), c[0], c[1], s.issueDate(), s.sumAssured(), s.premium(),
                s.frequency(), s.status(), "VFA".equals(c[1]) ? fund.getOrDefault(s.policyNumber(), BigDecimal.ZERO) : null,
                investment.getOrDefault(s.policyNumber(), BigDecimal.ZERO), s.currency()));
        }
        return rows;
    }

    private EngineExtractSheets sheets(UUID tenantId, UUID id) {
        return jdbc.query("SELECT cash_flows, balances, policies FROM finaccounting.engine_extract WHERE tenant_id = ?"
                + " AND extract_id = ?",
            (rs, i) -> new EngineExtractSheets(read(rs.getString("cash_flows"), new TypeReference<>() {}),
                read(rs.getString("balances"), new TypeReference<>() {}),
                read(rs.getString("policies"), new TypeReference<>() {})),
            tenantId, id).stream().findFirst().orElseThrow(() -> new tz.co.nlolo.lifeplatform.finaccounting.api.EngineNotFoundException("Extract " + id + " not found"));
    }

    private Optional<EngineExtractView> find(UUID tenantId, UUID id) {
        return jdbc.query("SELECT * FROM finaccounting.engine_extract WHERE tenant_id = ? AND extract_id = ?", this::view,
            tenantId, id).stream().findFirst();
    }

    private EngineExtractView view(ResultSet rs, int i) throws SQLException {
        Array groups = rs.getArray("groups");
        return new EngineExtractView(rs.getObject("extract_id", UUID.class), rs.getString("period"), rs.getInt("number"),
            groups == null ? List.of() : Arrays.asList((String[]) groups.getArray()),
            count(rs.getString("cash_flows")), count(rs.getString("balances")), count(rs.getString("policies")),
            rs.getString("document_ref"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant());
    }

    private int count(String array) {
        try {
            return json.readTree(array).size();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object rows) {
        try {
            return json.writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String raw, TypeReference<T> type) {
        try {
            return json.readValue(raw, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("An unreadable extract", e);
        }
    }
}
