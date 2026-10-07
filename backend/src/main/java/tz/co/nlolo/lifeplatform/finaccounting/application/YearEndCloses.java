package tz.co.nlolo.lifeplatform.finaccounting.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.YearEndCloseNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.YearEndCloseStateException;
import tz.co.nlolo.lifeplatform.finaccounting.api.YearEndCloseView;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.domain.YearEndCloseJournal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The year-end close (IFRS 17 I6, guide 5.7). A FINANCE_OFFICER prepares the close of a calendar year once December is
 * closing and everything else about it is done; a FINANCE_APPROVER who did not prepare it approves, and one SYSTEM
 * journal in December clears classes 4-8 to 3310, moves the result to 3210 (M-07) and closes dividends declared (M-11).
 * A close of a year already closed replaces it: the old journal is reversed first. December locks only once its year is
 * closed ({@link YearEndLockGate}).
 */
@Service
public class YearEndCloses {

    static final String EVENT = "ifrs17.YearEndClose";
    static final String REVERSAL_EVENT = "ifrs17.YearEndCloseReversal";
    static final String CURRENCY = "TZS";

    private static final Map<String, String> NAMES = ChartOfAccountBlueprint.accounts().stream()
        .collect(Collectors.toUnmodifiableMap(ChartOfAccountBlueprint.Seed::code, ChartOfAccountBlueprint.Seed::name));

    private final JdbcTemplate jdbc;
    private final AccountingPeriods periods;
    private final YearEndLockGate gate;
    private final FinaccountingApiImpl postings;
    private final jakarta.persistence.EntityManager entityManager;
    private final ObjectMapper json;

    YearEndCloses(JdbcTemplate jdbc, AccountingPeriods periods, YearEndLockGate gate, FinaccountingApiImpl postings,
                  jakarta.persistence.EntityManager entityManager, ObjectMapper json) {
        this.jdbc = jdbc;
        this.periods = periods;
        this.gate = gate;
        this.postings = postings;
        this.entityManager = entityManager;
        this.json = json;
    }

    /** The year's accounts and the journal as it would post now. Any year; computing it changes nothing. */
    @Transactional
    public YearEndCloseView preview(int year) {
        requireYear(year);
        YearEndCloseJournal.Result r = compute(TenantContext.get(), year);
        return new YearEndCloseView(null, year, null, r.classTotals(), r.profit(), r.dividends(), accounts(r.lines()),
            lines(r.lines()), false, null, null, null, null, null, null, null, null);
    }

    @Transactional
    public YearEndCloseView prepare(int year, String by) {
        UUID tenantId = TenantContext.get();
        requireYear(year);
        requireCloseable(tenantId, year);
        if (compute(tenantId, year).lines().isEmpty()) {
            throw new YearEndCloseStateException("Nothing to close in " + year + ": no class 4-8 or dividend postings");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO finaccounting.year_end_close (close_id, tenant_id, year, status, prepared_by)"
                + " VALUES (?, ?, ?, 'PREPARED', ?)", id, tenantId, year, by);
        } catch (DuplicateKeyException e) {
            throw new YearEndCloseStateException("A close of " + year + " is already awaiting a decision; approve or"
                + " reject it first");
        }
        return get(id);
    }

    /** Posts the close: figures recomputed now; a POSTED close of the year reversed first and marked REPLACED. */
    @Transactional
    public YearEndCloseView approve(UUID id, String by) {
        UUID tenantId = TenantContext.get();
        Row row = decidable(load(id), by);
        requireCloseable(tenantId, row.year());
        YearEndCloseJournal.Result r = compute(tenantId, row.year());
        if (r.lines().isEmpty()) {
            throw new YearEndCloseStateException("Nothing to close in " + row.year() + ": no class 4-8 or dividend postings");
        }
        String period = row.year() + "-12";
        Optional<Row> previous = posted(tenantId, row.year());
        UUID reversal = previous.map(old -> reverse(tenantId, old, id, period, by)).orElse(null);
        JournalEntry entry = new JournalEntry(tenantId, EVENT, id.toString(), period, null, by).fromYearEndClose(id);
        for (YearEndCloseJournal.Line l : r.lines()) {
            entry.addLeg(l.account(), PostingDirection.valueOf(l.side()), l.amount(), CURRENCY);
        }
        postings.postEntry(entry);
        entityManager.flush();
        jdbc.update("UPDATE finaccounting.year_end_close SET status = 'POSTED', decided_by = ?, decided_at = now(),"
                + " class_totals = ?::jsonb, profit = ?, dividends = ?, replaces_id = ?, journal_entry_id = ?,"
                + " reversal_journal_id = ?, version = version + 1 WHERE tenant_id = ? AND close_id = ?"
                + " AND status = 'PREPARED'",
            by, write(r.classTotals()), r.profit(), r.dividends(), previous.map(Row::id).orElse(null),
            entry.getJournalEntryId(), reversal, tenantId, id);
        return get(id);
    }

    @Transactional
    public YearEndCloseView reject(UUID id, String reason, String by) {
        decidable(load(id), by);
        if (reason == null || reason.isBlank()) {
            throw new FinaccountingValidationException("Say why the close is rejected");
        }
        String r = reason.trim();
        if (r.length() > 500) {
            throw new FinaccountingValidationException("A reason is at most 500 characters");
        }
        jdbc.update("UPDATE finaccounting.year_end_close SET status = 'REJECTED', decided_by = ?, decided_at = now(),"
                + " decision_reason = ?, version = version + 1 WHERE tenant_id = ? AND close_id = ? AND status = 'PREPARED'",
            by, r, TenantContext.get(), id);
        return get(id);
    }

    @Transactional
    public YearEndCloseView get(UUID id) {
        return view(load(id));
    }

    /** The year's closes, newest first. */
    @Transactional
    public List<YearEndCloseView> list(int year) {
        requireYear(year);
        return jdbc.query("SELECT * FROM finaccounting.year_end_close WHERE tenant_id = ? AND year = ?"
            + " ORDER BY prepared_at DESC", this::row, TenantContext.get(), year).stream().map(this::view).toList();
    }

    // ---- the year's figures ----

    private YearEndCloseJournal.Result compute(UUID tenantId, int year) {
        List<YearEndCloseJournal.Balance> balances = jdbc.query("SELECT p.account_code,"
                + " sum(CASE WHEN p.direction = 'DR' THEN p.amount ELSE -p.amount END) AS net"
                + " FROM finaccounting.gl_posting p JOIN finaccounting.journal_entry j ON j.journal_entry_id = p.journal_entry_id"
                + " WHERE p.tenant_id = ? AND p.period BETWEEN ? AND ? AND j.year_end_close_id IS NULL"
                + " AND substr(p.account_code, 1, 1) IN ('4','5','6','7','8')"
                + " GROUP BY p.account_code HAVING sum(CASE WHEN p.direction = 'DR' THEN p.amount ELSE -p.amount END) <> 0"
                + " ORDER BY p.account_code",
            (rs, i) -> new YearEndCloseJournal.Balance(rs.getString("account_code"), rs.getBigDecimal("net")),
            tenantId, year + "-01", year + "-12");
        BigDecimal dividends = jdbc.queryForObject("SELECT COALESCE(sum(CASE WHEN p.direction = 'DR' THEN p.amount"
                + " ELSE -p.amount END), 0) FROM finaccounting.gl_posting p JOIN finaccounting.journal_entry j"
                + " ON j.journal_entry_id = p.journal_entry_id WHERE p.tenant_id = ? AND p.period BETWEEN ? AND ?"
                + " AND j.year_end_close_id IS NULL AND p.account_code = ?",
            BigDecimal.class, tenantId, year + "-01", year + "-12", YearEndCloseJournal.DIVIDENDS);
        return YearEndCloseJournal.build(balances, dividends);
    }

    /** December closing, and nothing but its year-end close standing in the way of its lock. */
    private void requireCloseable(UUID tenantId, int year) {
        String december = year + "-12";
        PeriodStatus status = periods.view(december).status();
        if (status != PeriodStatus.CLOSING) {
            throw new YearEndCloseStateException("Period " + december + " is " + status + "; the year closes once"
                + " December is closing");
        }
        List<String> blockers = periods.blockers(tenantId, december);
        if (!blockers.isEmpty()) {
            throw new YearEndCloseStateException(blockers.get(0));
        }
    }

    private static void requireYear(int year) {
        if (year < 1900 || year > 2999) {
            throw new FinaccountingValidationException("A year is between 1900 and 2999, not " + year);
        }
    }

    // ---- rows ----

    private record Row(UUID id, int year, String status, String classTotals, BigDecimal profit, BigDecimal dividends,
                       String preparedBy, Instant preparedAt, String decidedBy, Instant decidedAt, Timestamp decidedTs,
                       String decisionReason, UUID replacesId, UUID journalEntryId, UUID reversalJournalId) {}

    private Row load(UUID id) {
        return jdbc.query("SELECT * FROM finaccounting.year_end_close WHERE tenant_id = ? AND close_id = ?", this::row,
                TenantContext.get(), id).stream().findFirst()
            .orElseThrow(() -> new YearEndCloseNotFoundException("Year-end close " + id + " not found"));
    }

    private Optional<Row> posted(UUID tenantId, int year) {
        return jdbc.query("SELECT * FROM finaccounting.year_end_close WHERE tenant_id = ? AND year = ?"
            + " AND status = 'POSTED'", this::row, tenantId, year).stream().findFirst();
    }

    private static Row decidable(Row row, String by) {
        if (!"PREPARED".equals(row.status())) {
            throw new YearEndCloseStateException("Only a prepared close can be decided; this one is " + row.status());
        }
        if (row.preparedBy().equals(by)) {
            throw new YearEndCloseStateException("You prepared this close; a FINANCE_APPROVER other than you decides it");
        }
        return row;
    }

    /** The posted close's journal reversed -- sides swapped -- under the new close, and the old one REPLACED. */
    private UUID reverse(UUID tenantId, Row old, UUID replacement, String period, String by) {
        JournalEntry reversal = new JournalEntry(tenantId, REVERSAL_EVENT, old.journalEntryId().toString(), period, null,
            by).fromYearEndClose(replacement).reversing(old.journalEntryId());
        jdbc.query("SELECT account_code, direction, amount, currency FROM finaccounting.gl_posting"
                + " WHERE tenant_id = ? AND journal_entry_id = ?",
            rs -> {
                reversal.addLeg(rs.getString("account_code"),
                    "DR".equals(rs.getString("direction")) ? PostingDirection.CR : PostingDirection.DR,
                    rs.getBigDecimal("amount"), rs.getString("currency").trim());
            },
            tenantId, old.journalEntryId());
        postings.postEntry(reversal);
        entityManager.flush();
        jdbc.update("UPDATE finaccounting.year_end_close SET status = 'REPLACED', version = version + 1"
            + " WHERE tenant_id = ? AND close_id = ? AND status = 'POSTED'", tenantId, old.id());
        return reversal.getJournalEntryId();
    }

    private YearEndCloseView view(Row r) {
        UUID tenantId = TenantContext.get();
        if ("PREPARED".equals(r.status())) {
            YearEndCloseJournal.Result now = compute(tenantId, r.year());
            return new YearEndCloseView(r.id(), r.year(), r.status(), now.classTotals(), now.profit(), now.dividends(),
                accounts(now.lines()), lines(now.lines()), false, r.preparedBy(), r.preparedAt(), null, null, null,
                r.replacesId(), null, null);
        }
        List<YearEndCloseJournal.Line> posted = r.journalEntryId() == null ? List.of()
            : jdbc.query("SELECT account_code, direction, amount FROM finaccounting.gl_posting WHERE tenant_id = ?"
                    + " AND journal_entry_id = ? ORDER BY CASE WHEN account_code IN ('3310','3210','3320') THEN 1 ELSE 0 END,"
                    + " account_code, direction DESC",
                (rs, i) -> new YearEndCloseJournal.Line(rs.getString("account_code"), rs.getString("direction"),
                    rs.getBigDecimal("amount")),
                tenantId, r.journalEntryId());
        boolean stale = "POSTED".equals(r.status()) && r.decidedTs() != null && gate.stale(tenantId, r.year(), r.decidedTs());
        return new YearEndCloseView(r.id(), r.year(), r.status(), read(r.classTotals()), r.profit(), r.dividends(),
            accounts(posted), lines(posted), stale, r.preparedBy(), r.preparedAt(), r.decidedBy(), r.decidedAt(),
            r.decisionReason(), r.replacesId(), r.journalEntryId(), r.reversalJournalId());
    }

    /** The accounts closed, from the journal's lines: every line not on 3310, 3210 or 3320, its balance restored. */
    private static List<YearEndCloseView.Account> accounts(List<YearEndCloseJournal.Line> lines) {
        return lines.stream()
            .filter(l -> !List.of(YearEndCloseJournal.CURRENT_YEAR, YearEndCloseJournal.RETAINED,
                YearEndCloseJournal.DIVIDENDS).contains(l.account()))
            .map(l -> new YearEndCloseView.Account(l.account(), NAMES.getOrDefault(l.account(), l.account()),
                l.account().substring(0, 1), "CR".equals(l.side()) ? l.amount() : l.amount().negate()))
            .toList();
    }

    private static List<YearEndCloseView.Line> lines(List<YearEndCloseJournal.Line> lines) {
        return lines.stream().map(l -> new YearEndCloseView.Line(l.account(), l.side(), l.amount())).toList();
    }

    private String write(Map<String, BigDecimal> totals) {
        try {
            return json.writeValueAsString(totals);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, BigDecimal> read(String totals) {
        if (totals == null) {
            return Map.of();
        }
        try {
            return json.readValue(totals, new TypeReference<java.util.TreeMap<String, BigDecimal>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable class totals", e);
        }
    }

    private Row row(ResultSet rs, int i) throws SQLException {
        Timestamp prepared = rs.getTimestamp("prepared_at");
        Timestamp decided = rs.getTimestamp("decided_at");
        return new Row(rs.getObject("close_id", UUID.class), rs.getInt("year"), rs.getString("status"),
            rs.getString("class_totals"), rs.getBigDecimal("profit"), rs.getBigDecimal("dividends"),
            rs.getString("prepared_by"), prepared.toInstant(), rs.getString("decided_by"),
            decided == null ? null : decided.toInstant(), decided, rs.getString("decision_reason"),
            rs.getObject("replaces_id", UUID.class), rs.getObject("journal_entry_id", UUID.class),
            rs.getObject("reversal_journal_id", UUID.class));
    }
}
