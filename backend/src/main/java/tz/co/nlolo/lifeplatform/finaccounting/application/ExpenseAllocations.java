package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationInput;
import tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationPreview;
import tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationStateException;
import tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ExpenseAllocationSplit;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * P-19, the month's expense allocation (IFRS 17 I5b, month-end step 5). Finance types three totals for a closing month;
 * they are spread over the groups of insurance contracts by driver ({@link ExpenseAllocationSplit}); a FINANCE_APPROVER
 * who did not prepare it approves -- acknowledging a total above the month's pool -- and one SYSTEM journal posts
 * Dr 5210 / 5215 / 2123 or 5310, Cr 8490. A replacement reverses the posted one. The extract waits for a POSTED one.
 */
@Service
public class ExpenseAllocations {

    static final String EVENT = "ifrs17.ExpenseAllocation";
    static final String REVERSAL_EVENT = "ifrs17.ExpenseAllocationReversal";
    static final String CURRENCY = "TZS";
    private static final String REFERENCE_TYPE = "EXPENSE_ALLOCATION";
    private static final String IN_FORCE = "('ACTIVE','REINSTATED','PAID_UP')";

    private final JdbcTemplate jdbc;
    private final AccountingPeriods periods;
    private final FinaccountingApiImpl postings;
    private final jakarta.persistence.EntityManager entityManager;

    ExpenseAllocations(JdbcTemplate jdbc, AccountingPeriods periods, FinaccountingApiImpl postings,
                       jakarta.persistence.EntityManager entityManager) {
        this.jdbc = jdbc;
        this.periods = periods;
        this.postings = postings;
        this.entityManager = entityManager;
    }

    /**
     * The split as it would post now, beside the month's pool. Any period. Not read-only (nor are get and list): the
     * register read for the acquisition account seeds a tenant's baseline on first use.
     */
    @Transactional
    public ExpenseAllocationPreview preview(String period, BigDecimal maintenance, BigDecimal claimsHandling,
                                            BigDecimal acquisition) {
        UUID tenantId = TenantContext.get();
        requirePeriod(period);
        BigDecimal m = amount("Maintenance", maintenance);
        BigDecimal c = amount("Claims handling", claimsHandling);
        BigDecimal a = amount("Acquisition", acquisition);
        BigDecimal pool = pool(tenantId, period);
        BigDecimal total = m.add(c).add(a);
        return new ExpenseAllocationPreview(period, pool, total, total.compareTo(pool) > 0, lines(tenantId, period, m, c, a));
    }

    @Transactional
    public ExpenseAllocationView prepare(String period, ExpenseAllocationInput in, String by) {
        UUID tenantId = TenantContext.get();
        requirePeriod(period);
        if (in == null) {
            throw new FinaccountingValidationException("Give the three totals, or why there is no allocation this month");
        }
        BigDecimal m = amount("Maintenance", in.maintenance());
        BigDecimal c = amount("Claims handling", in.claimsHandling());
        BigDecimal a = amount("Acquisition", in.acquisition());
        boolean nil = m.add(c).add(a).signum() == 0;
        String study = trim(in.studyReference(), 200, "A study reference");
        String nilReason = trim(in.nilReason(), 500, "A reason");
        String note = trim(in.note(), 500, "A note");
        if (nil && nilReason == null) {
            throw new FinaccountingValidationException("All three totals are zero: say why there is no allocation this month");
        }
        if (!nil && study == null) {
            throw new FinaccountingValidationException("Give the expense allocation study the totals come from");
        }
        requireClosing(period);
        if (!nil && groups(tenantId, period).isEmpty()) {
            throw new ExpenseAllocationStateException("No group of insurance contracts to allocate to");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO finaccounting.expense_allocation (allocation_id, tenant_id, period, status, maintenance,"
                    + " claims_handling, acquisition, study_reference, note, nil_reason, prepared_by)"
                    + " VALUES (?, ?, ?, 'PREPARED', ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, period, m, c, a, nil ? null : study, note, nil ? nilReason : null, by);
        } catch (DuplicateKeyException e) {
            throw new ExpenseAllocationStateException("An expense allocation for " + period
                + " is already awaiting a decision; approve or reject it first");
        }
        return get(id);
    }

    /**
     * Posts the allocation: the split recomputed now, one SYSTEM journal, the lines stored. A POSTED allocation of the
     * month is reversed first and marked REPLACED. Above the month's pool only when {@code aboveThePool}.
     */
    @Transactional
    public ExpenseAllocationView approve(UUID id, boolean aboveThePool, String by) {
        UUID tenantId = TenantContext.get();
        Row row = decidable(load(id), by);
        requireClosing(row.period());
        BigDecimal pool = pool(tenantId, row.period());
        BigDecimal total = row.total();
        boolean over = total.compareTo(pool) > 0;
        if (over && !aboveThePool) {
            throw new ExpenseAllocationStateException("The total " + total.toPlainString() + " is above the month's pool of "
                + pool.toPlainString() + "; approve above the pool to post it");
        }
        List<ExpenseAllocationView.Line> lines = lines(tenantId, row.period(), row.maintenance(), row.claimsHandling(),
            row.acquisition());
        if (total.signum() > 0 && lines.isEmpty()) {
            throw new ExpenseAllocationStateException("No group of insurance contracts to allocate to");
        }
        Optional<Row> previous = posted(tenantId, row.period());
        UUID reversal = previous.map(old -> reverse(tenantId, old, row, by)).orElse(null);
        UUID journal = null;
        if (total.signum() > 0) {
            JournalEntry entry = new JournalEntry(tenantId, EVENT, id.toString(), row.period(), null, by)
                .fromExpenseAllocation(id);
            for (ExpenseAllocationView.Line l : lines) {
                entry.addLeg(l.account(), PostingDirection.DR, l.amount(), CURRENCY, new LineDimensions(l.group(),
                    l.measurementModel(), "2123".equals(l.account()) ? "EXP_ACQ" : null, null, null, null, null, null,
                    REFERENCE_TYPE, id.toString()));
            }
            entry.addLeg("8490", PostingDirection.CR, total, CURRENCY, new LineDimensions(null, null, null, null, null,
                null, null, null, REFERENCE_TYPE, id.toString()));
            postings.postEntry(entry);
            entityManager.flush();
            journal = entry.getJournalEntryId();
            for (ExpenseAllocationView.Line l : lines) {
                jdbc.update("INSERT INTO finaccounting.expense_allocation_line (allocation_id, tenant_id, group_key,"
                        + " measurement_model, category, account_code, driver, driver_count, amount)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, tenantId, l.group(), l.measurementModel(), l.category(), l.account(), l.driver(), l.driverCount(),
                    l.amount());
            }
        }
        jdbc.update("UPDATE finaccounting.expense_allocation SET status = 'POSTED', decided_by = ?, decided_at = now(),"
                + " pool_at_approval = ?, over_pool = ?, replaces_id = ?, journal_entry_id = ?, reversal_journal_id = ?,"
                + " version = version + 1 WHERE tenant_id = ? AND allocation_id = ? AND status = 'PREPARED'",
            by, pool, over, previous.map(Row::id).orElse(null), journal, reversal, tenantId, id);
        return get(id);
    }

    @Transactional
    public ExpenseAllocationView reject(UUID id, String reason, String by) {
        decidable(load(id), by);
        String r = trim(reason, 500, "A reason");
        if (r == null) {
            throw new FinaccountingValidationException("Say why the allocation is rejected");
        }
        jdbc.update("UPDATE finaccounting.expense_allocation SET status = 'REJECTED', decided_by = ?, decided_at = now(),"
                + " decision_reason = ?, version = version + 1 WHERE tenant_id = ? AND allocation_id = ?"
                + " AND status = 'PREPARED'",
            by, r, TenantContext.get(), id);
        return get(id);
    }

    @Transactional
    public ExpenseAllocationView get(UUID id) {
        return view(load(id));
    }

    /** The month's allocations, newest first. */
    @Transactional
    public List<ExpenseAllocationView> list(String period) {
        requirePeriod(period);
        return jdbc.query("SELECT * FROM finaccounting.expense_allocation WHERE tenant_id = ? AND period = ?"
                + " ORDER BY prepared_at DESC", this::row, TenantContext.get(), period).stream().map(this::view).toList();
    }

    // ---- the pool and the drivers ----

    /** The month's net Dr - Cr on the expense pool 8100-8499, leaving out 8490 and the allocations' own journals. */
    BigDecimal pool(UUID tenantId, String period) {
        BigDecimal pool = jdbc.queryForObject("SELECT COALESCE(sum(CASE WHEN p.direction = 'DR' THEN p.amount ELSE"
                + " -p.amount END), 0) FROM finaccounting.gl_posting p JOIN finaccounting.journal_entry j"
                + " ON j.journal_entry_id = p.journal_entry_id WHERE p.tenant_id = ? AND p.period = ?"
                + " AND p.account_code BETWEEN '8100' AND '8499' AND p.account_code <> '8490'"
                + " AND j.expense_allocation_id IS NULL", BigDecimal.class, tenantId, period);
        return (pool == null ? BigDecimal.ZERO : pool).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The groups with their driver counts: policies in force now (the snapshot keeps current status, not history),
     * distinct claims with 22xx postings in the month, policies issued in the month. Insurance groups only.
     */
    List<ExpenseAllocationSplit.Group> groups(UUID tenantId, String period) {
        YearMonth month = YearMonth.parse(period);
        Map<String, long[]> counts = new TreeMap<>();
        Map<String, String> models = new HashMap<>();
        jdbc.query("SELECT c.group_key, c.measurement_model, count(*) AS n FROM finaccounting.policy_snapshot s"
                + " JOIN (SELECT DISTINCT ON (policy_number) policy_number, group_key, measurement_model"
                + "       FROM finaccounting.policy_classification WHERE tenant_id = ?"
                + "       ORDER BY policy_number, effective_from DESC) c ON c.policy_number = s.policy_number"
                + " WHERE s.tenant_id = ? AND s.status IN " + IN_FORCE + " GROUP BY c.group_key, c.measurement_model",
            rs -> {
                add(counts, models, rs, 0);
            },
            tenantId, tenantId);
        jdbc.query("SELECT p.ifrs17_group AS group_key, g.measurement_model, count(DISTINCT p.reference) AS n"
                + " FROM finaccounting.gl_posting p JOIN finaccounting.group_of_contracts g"
                + " ON g.tenant_id = p.tenant_id AND g.group_key = p.ifrs17_group"
                + " WHERE p.tenant_id = ? AND p.period = ? AND p.account_code LIKE '22%' AND p.reference_type = 'CLAIM'"
                + " GROUP BY p.ifrs17_group, g.measurement_model",
            rs -> {
                add(counts, models, rs, 1);
            },
            tenantId, period);
        jdbc.query("SELECT group_key, measurement_model, count(DISTINCT policy_number) AS n"
                + " FROM finaccounting.policy_classification WHERE tenant_id = ? AND reason = 'ISSUE'"
                + " AND effective_from BETWEEN ? AND ? GROUP BY group_key, measurement_model",
            rs -> {
                add(counts, models, rs, 2);
            },
            tenantId, Date.valueOf(month.atDay(1)), Date.valueOf(month.atEndOfMonth()));
        return counts.entrySet().stream()
            .filter(e -> ExpenseAllocationSplit.MODELS.contains(models.get(e.getKey())))
            .map(e -> new ExpenseAllocationSplit.Group(e.getKey(), models.get(e.getKey()), e.getValue()[0],
                e.getValue()[1], e.getValue()[2]))
            .toList();
    }

    private static void add(Map<String, long[]> counts, Map<String, String> models, ResultSet rs, int slot)
            throws SQLException {
        String key = rs.getString("group_key");
        models.putIfAbsent(key, rs.getString("measurement_model"));
        counts.computeIfAbsent(key, k -> new long[3])[slot] += rs.getLong("n");
    }

    /** The split, acquisition going to 5310 for PAA when the register expenses it when incurred at the month's end. */
    private List<ExpenseAllocationView.Line> lines(UUID tenantId, String period, BigDecimal m, BigDecimal c, BigDecimal a) {
        if (m.add(c).add(a).signum() == 0) {
            return List.of();
        }
        LocalDate end = YearMonth.parse(period).atEndOfMonth();
        boolean paaExpensed = postings.policyElectionInForce("ACQUISITION_CASH_FLOWS", "PAA", end)
            .map(e -> "EXPENSE_WHEN_INCURRED".equals(e.value())).orElse(false);
        return ExpenseAllocationSplit.split(m, c, a, groups(tenantId, period), paaExpensed ? Set.of("PAA") : Set.of())
            .stream()
            .map(l -> new ExpenseAllocationView.Line(l.group(), l.model(), l.category(), l.account(), l.driver(),
                l.driverCount(), l.amount()))
            .toList();
    }

    // ---- rows, gates, the reversal ----

    private record Row(UUID id, String period, String status, BigDecimal maintenance, BigDecimal claimsHandling,
                       BigDecimal acquisition, String studyReference, String note, String nilReason, String preparedBy,
                       Instant preparedAt, String decidedBy, Instant decidedAt, String decisionReason,
                       BigDecimal poolAtApproval, Boolean overPool, UUID replacesId, UUID journalEntryId,
                       UUID reversalJournalId) {
        BigDecimal total() {
            return maintenance.add(claimsHandling).add(acquisition);
        }
    }

    private Row load(UUID id) {
        return jdbc.query("SELECT * FROM finaccounting.expense_allocation WHERE tenant_id = ? AND allocation_id = ?",
                this::row, TenantContext.get(), id).stream().findFirst()
            .orElseThrow(() -> new ExpenseAllocationNotFoundException("Expense allocation " + id + " not found"));
    }

    private Optional<Row> posted(UUID tenantId, String period) {
        return jdbc.query("SELECT * FROM finaccounting.expense_allocation WHERE tenant_id = ? AND period = ?"
            + " AND status = 'POSTED'", this::row, tenantId, period).stream().findFirst();
    }

    private static Row decidable(Row row, String by) {
        if (!"PREPARED".equals(row.status())) {
            throw new ExpenseAllocationStateException("Only a prepared allocation can be decided; this one is "
                + row.status());
        }
        if (row.preparedBy().equals(by)) {
            throw new ExpenseAllocationStateException("You prepared this allocation; a FINANCE_APPROVER other than you"
                + " decides it");
        }
        return row;
    }

    private void requireClosing(String period) {
        PeriodStatus status = periods.view(period).status();
        if (status != PeriodStatus.CLOSING) {
            throw new ExpenseAllocationStateException("Period " + period + " is " + status + "; expense allocation is"
                + " month-end step 5, made once the period is closing");
        }
    }

    private static void requirePeriod(String period) {
        if (period == null || !period.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            throw new FinaccountingValidationException("A period is YYYY-MM, not '" + period + "'");
        }
    }

    private static BigDecimal amount(String what, BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        if (v.signum() < 0) {
            throw new FinaccountingValidationException(what + " cannot be negative");
        }
        if (v.stripTrailingZeros().scale() > 2) {
            throw new FinaccountingValidationException(what + " has more than two decimals");
        }
        return v.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String trim(String s, int max, String what) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        if (t.length() > max) {
            throw new FinaccountingValidationException(what + " is at most " + max + " characters");
        }
        return t;
    }

    /** The posted allocation's journal reversed -- legs swapped, dimensions kept -- and the allocation REPLACED. */
    private UUID reverse(UUID tenantId, Row old, Row replacement, String by) {
        UUID reversalId = null;
        if (old.journalEntryId() != null) {
            JournalEntry reversal = new JournalEntry(tenantId, REVERSAL_EVENT, old.journalEntryId().toString(),
                replacement.period(), null, by).fromExpenseAllocation(replacement.id()).reversing(old.journalEntryId());
            jdbc.query("SELECT account_code, direction, amount, currency, ifrs17_group, measurement_model, movement_type,"
                    + " reference_type, reference FROM finaccounting.gl_posting WHERE tenant_id = ? AND journal_entry_id = ?",
                rs -> {
                    reversal.addLeg(rs.getString("account_code"),
                        "DR".equals(rs.getString("direction")) ? PostingDirection.CR : PostingDirection.DR,
                        rs.getBigDecimal("amount"), rs.getString("currency").trim(),
                        new LineDimensions(rs.getString("ifrs17_group"), rs.getString("measurement_model"),
                            rs.getString("movement_type"), null, null, null, null, null, rs.getString("reference_type"),
                            rs.getString("reference")));
                },
                tenantId, old.journalEntryId());
            postings.postEntry(reversal);
            entityManager.flush();
            reversalId = reversal.getJournalEntryId();
        }
        jdbc.update("UPDATE finaccounting.expense_allocation SET status = 'REPLACED', version = version + 1"
            + " WHERE tenant_id = ? AND allocation_id = ? AND status = 'POSTED'", tenantId, old.id());
        return reversalId;
    }

    private ExpenseAllocationView view(Row r) {
        UUID tenantId = TenantContext.get();
        boolean prepared = "PREPARED".equals(r.status());
        BigDecimal pool = prepared ? pool(tenantId, r.period()) : r.poolAtApproval();
        List<ExpenseAllocationView.Line> lines = prepared
            ? lines(tenantId, r.period(), r.maintenance(), r.claimsHandling(), r.acquisition())
            : jdbc.query("SELECT * FROM finaccounting.expense_allocation_line WHERE tenant_id = ? AND allocation_id = ?"
                    + " ORDER BY CASE category WHEN 'MAINTENANCE' THEN 1 WHEN 'CLAIMS_HANDLING' THEN 2 ELSE 3 END,"
                    + " group_key",
                (rs, i) -> new ExpenseAllocationView.Line(rs.getString("group_key"), rs.getString("measurement_model"),
                    rs.getString("category"), rs.getString("account_code"), rs.getString("driver"),
                    rs.getLong("driver_count"), rs.getBigDecimal("amount")),
                tenantId, r.id());
        boolean over = prepared ? r.total().compareTo(pool) > 0 : Boolean.TRUE.equals(r.overPool());
        return new ExpenseAllocationView(r.id(), r.period(), r.status(), r.maintenance(), r.claimsHandling(),
            r.acquisition(), r.total(), CURRENCY, r.studyReference(), r.note(), r.nilReason(), pool, over, r.preparedBy(),
            r.preparedAt(), r.decidedBy(), r.decidedAt(), r.decisionReason(), r.replacesId(), r.journalEntryId(),
            r.reversalJournalId(), staleExtract(tenantId, r), lines);
    }

    /** For a POSTED allocation: the latest extract's number when every extract of the month predates it. */
    private Integer staleExtract(UUID tenantId, Row r) {
        if (!"POSTED".equals(r.status()) || r.decidedAt() == null) {
            return null;
        }
        return jdbc.query("SELECT max(number) AS latest, bool_and(created_at < ?) AS all_before"
                + " FROM finaccounting.engine_extract WHERE tenant_id = ? AND period = ?",
            (org.springframework.jdbc.core.ResultSetExtractor<Integer>) rs -> rs.next() && rs.getBoolean("all_before")
                && rs.getObject("latest") != null ? rs.getInt("latest") : null,
            Timestamp.from(r.decidedAt()), tenantId, r.period());
    }

    private Row row(ResultSet rs, int i) throws SQLException {
        Timestamp prepared = rs.getTimestamp("prepared_at");
        Timestamp decided = rs.getTimestamp("decided_at");
        return new Row(rs.getObject("allocation_id", UUID.class), rs.getString("period"), rs.getString("status"),
            rs.getBigDecimal("maintenance"), rs.getBigDecimal("claims_handling"), rs.getBigDecimal("acquisition"),
            rs.getString("study_reference"), rs.getString("note"), rs.getString("nil_reason"),
            rs.getString("prepared_by"), prepared.toInstant(), rs.getString("decided_by"),
            decided == null ? null : decided.toInstant(), rs.getString("decision_reason"),
            rs.getBigDecimal("pool_at_approval"), (Boolean) rs.getObject("over_pool"),
            rs.getObject("replaces_id", UUID.class), rs.getObject("journal_entry_id", UUID.class),
            rs.getObject("reversal_journal_id", UUID.class));
    }
}
