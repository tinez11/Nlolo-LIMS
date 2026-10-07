package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import tz.co.nlolo.lifeplatform.finaccounting.api.EngineNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.EngineRunView;
import tz.co.nlolo.lifeplatform.finaccounting.api.EngineStateException;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import tz.co.nlolo.lifeplatform.finaccounting.domain.EngineReconciler;
import tz.co.nlolo.lifeplatform.finaccounting.domain.EngineResults;
import tz.co.nlolo.lifeplatform.finaccounting.domain.EngineRunJournals;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * IFRS 17 engine runs (IFRS 17 I5a, month-end step 7; spec section 9.3). The actuary uploads the engine's results; they
 * are validated at once against the extract they answer, every error kept (REJECTED) or the run VALIDATED. A
 * FINANCE_APPROVER who did not upload it approves it with the appointed actuary's sign-off reference and report: in one
 * transaction a run already posted for the period is reversed in full (REPLACED), the new run posts one ENGINE_RUN
 * journal per group with every line counter-posted through 9160, 9160 is proved back at zero, and the ledger is
 * reconciled to the engine's closing figures. A difference beyond TZS 1.00 is an exception a finance officer explains and
 * a second person accepts -- until then {@link EngineLockGate} keeps the period from locking.
 */
@Service
public class EngineRuns {

    static final String EVENT = "ifrs17.EngineRun";
    static final String REVERSAL_EVENT = "ifrs17.EngineRunReversal";
    /** The platform's functional currency; the template states amounts in it. */
    static final String CURRENCY = "TZS";

    private static final Set<String> POSTING_ACCOUNTS = ChartOfAccountBlueprint.accounts().stream()
        .filter(ChartOfAccountBlueprint.Seed::postingAllowed).map(ChartOfAccountBlueprint.Seed::code)
        .collect(Collectors.toUnmodifiableSet());

    private final JdbcTemplate jdbc;
    private final EngineExtracts extracts;
    private final EngineLedger ledger;
    private final AccountingPeriods periods;
    private final FinaccountingApiImpl postings;
    private final DocumentApi documents;
    private final jakarta.persistence.EntityManager entityManager;

    EngineRuns(JdbcTemplate jdbc, EngineExtracts extracts, EngineLedger ledger, AccountingPeriods periods,
               FinaccountingApiImpl postings, DocumentApi documents, jakarta.persistence.EntityManager entityManager) {
        this.jdbc = jdbc;
        this.extracts = extracts;
        this.ledger = ledger;
        this.periods = periods;
        this.postings = postings;
        this.documents = documents;
        this.entityManager = entityManager;
    }

    // ---- upload -----------------------------------------------------------------------------------------------------

    /** Stores the file, reads and checks it: VALIDATED, or REJECTED with every error. Never refused for its content. */
    @Transactional
    public EngineRunView upload(byte[] content, String fileName, String by) {
        UUID tenantId = TenantContext.get();
        UUID id = UUID.randomUUID();
        String ref = documents.upload("enginerun:" + id, DocumentType.IFRS17_RESULTS, by, new ByteArrayInputStream(content),
            content.length, EngineExtracts.XLSX, fileName == null ? "engine-results.xlsx" : fileName);
        EngineResults.Parsed parsed = EngineResults.parse(content);
        List<String> errors = new ArrayList<>(parsed.errors());
        EngineResults results = parsed.results();
        if (results != null) {
            errors.addAll(results.problems(context(tenantId, results.header(), null)));
            staleExtract(tenantId, results.header()).ifPresent(errors::add);
        }
        EngineResults.Header h = results == null ? new EngineResults.Header(null, null, null, null, null) : results.header();
        String status = errors.isEmpty() ? "VALIDATED" : "REJECTED";
        try {
            jdbc.update("INSERT INTO finaccounting.engine_run (run_id, tenant_id, period, extract_number, engine_reference,"
                    + " engine_name, measurement_date, status, errors, results_document_ref, file_name, uploaded_by)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, h.period(), h.extractNumber(), h.engineReference(), h.engineName(), h.measurementDate(),
                status, errors.toArray(String[]::new), ref, fileName, by);
        } catch (DuplicateKeyException e) {
            throw new EngineStateException("Engine reference " + h.engineReference() + " was just loaded by another upload");
        }
        if (results != null && errors.isEmpty()) {
            for (EngineResults.Line l : results.lines()) {
                jdbc.update("INSERT INTO finaccounting.engine_run_line (run_id, tenant_id, row_no, group_key, entry,"
                        + " account_code, direction, amount, movement, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, tenantId, l.row(), l.group(), l.entry(), l.account(), l.side(), l.amount(), l.movement(),
                    l.note() == null || l.note().length() <= 300 ? l.note() : l.note().substring(0, 300));
            }
            for (EngineResults.Closing c : results.closings()) {
                jdbc.update("INSERT INTO finaccounting.engine_run_closing (run_id, tenant_id, group_key, lrc, lic, csm, arc,"
                        + " aic, ri_csm) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, tenantId, c.group(), c.lrc(), c.lic(), c.csm(), c.arc(), c.aic(), c.riCsm());
            }
        }
        return get(id);
    }

    // ---- the decision -----------------------------------------------------------------------------------------------

    /** Refuses unless {@code by} may decide the run: VALIDATED, and not uploaded by them. Asked before a report is stored. */
    @Transactional(readOnly = true)
    public void requireDecidable(UUID runId, String by) {
        decidable(load(runId), by);
    }

    @Transactional
    public EngineRunView approve(UUID runId, String signOffReference, String reportDocumentRef, String approver) {
        UUID tenantId = TenantContext.get();
        Run run = decidable(load(runId), approver);
        List<String> problems = new ArrayList<>();
        if (signOffReference == null || signOffReference.isBlank()) {
            problems.add("Give the appointed actuary's sign-off reference");
        } else if (signOffReference.length() > 200) {
            problems.add("A sign-off reference is at most 200 characters");
        }
        if (reportDocumentRef == null || reportDocumentRef.isBlank()) {
            problems.add("Attach the actuary's report");
        }
        EngineResults results = stored(tenantId, run);
        problems.addAll(results.problems(context(tenantId, results.header(), runId)));
        staleExtract(tenantId, results.header()).ifPresent(problems::add);
        if (!problems.isEmpty()) {
            throw new FinaccountingValidationException(String.join("; ", problems));
        }

        Optional<Run> replaced = posted(tenantId, run.period());
        replaced.ifPresent(old -> reverse(tenantId, old, run, approver));

        Map<String, String> models = models(tenantId);
        String reason = "Engine run " + run.engineReference() + "; actuary sign-off " + signOffReference.trim();
        for (Map.Entry<String, List<EngineRunJournals.Leg>> group : EngineRunJournals.legs(results.lines()).entrySet()) {
            JournalEntry entry = new JournalEntry(tenantId, EVENT, runId + ":" + group.getKey(), run.period(), null, approver)
                .asManual(run.uploadedBy(), approver, reason, null, reportDocumentRef).fromEngineRun(runId);
            for (EngineRunJournals.Leg leg : group.getValue()) {
                entry.addLeg(leg.account(), PostingDirection.valueOf(leg.side()), leg.amount(), CURRENCY,
                    new LineDimensions(group.getKey(), models.get(group.getKey()), leg.movement(), null, null, null, null,
                        null, "ENGINE_RUN", runId.toString()));
            }
            postings.postEntry(entry);
        }
        // The journals go through Hibernate, which writes them when it flushes; what follows reads them back with JDBC.
        // Unflushed, the last group's lines are invisible -- the 9160 check would pass on nothing, and the
        // reconciliation would read that group's ledger as zero.
        entityManager.flush();
        BigDecimal clearing = jdbc.queryForObject("SELECT COALESCE(sum(CASE WHEN p.direction = 'DR' THEN p.amount ELSE -p.amount END), 0)"
                + " FROM finaccounting.gl_posting p JOIN finaccounting.journal_entry j ON j.journal_entry_id = p.journal_entry_id"
                + " WHERE j.tenant_id = ? AND j.engine_run_id = ? AND p.account_code = ?",
            BigDecimal.class, tenantId, runId, EngineRunJournals.CLEARING);
        if (clearing == null || clearing.signum() != 0) {
            throw new IllegalStateException("Engine run " + run.engineReference() + " leaves " + clearing + " on 9160; nothing"
                + " is posted -- every line must post");
        }
        jdbc.update("UPDATE finaccounting.engine_run SET status = 'POSTED', decided_by = ?, decided_at = now(),"
                + " signoff_reference = ?, report_document_ref = ?, replaces_run_id = ?, version = version + 1"
                + " WHERE tenant_id = ? AND run_id = ? AND status = 'VALIDATED'",
            approver, signOffReference.trim(), reportDocumentRef, replaced.map(Run::id).orElse(null), tenantId, runId);
        reconcile(tenantId, runId, run.period(), results.closings());
        return get(runId);
    }

    @Transactional
    public EngineRunView reject(UUID runId, String reason, String by) {
        decidable(load(runId), by);
        if (reason == null || reason.isBlank()) {
            throw new FinaccountingValidationException("Say why the run is rejected");
        }
        jdbc.update("UPDATE finaccounting.engine_run SET status = 'REJECTED', decided_by = ?, decided_at = now(),"
                + " decision_reason = ?, version = version + 1 WHERE tenant_id = ? AND run_id = ? AND status = 'VALIDATED'",
            by, reason.trim().length() <= 500 ? reason.trim() : reason.trim().substring(0, 500), TenantContext.get(), runId);
        return get(runId);
    }

    // ---- the reconciliation's exceptions ----------------------------------------------------------------------------

    @Transactional
    public EngineRunView explain(UUID runId, String group, String figure, String explanation, String by) {
        if (explanation == null || explanation.isBlank()) {
            throw new FinaccountingValidationException("Explain the difference");
        }
        int changed = jdbc.update("UPDATE finaccounting.engine_reconciliation SET status = 'EXPLAINED', explanation = ?,"
                + " explained_by = ?, explained_at = now(), accepted_by = NULL, accepted_at = NULL"
                + " WHERE tenant_id = ? AND run_id = ? AND group_key = ? AND figure = ? AND status IN ('EXCEPTION','EXPLAINED')",
            explanation.trim().length() <= 1000 ? explanation.trim() : explanation.trim().substring(0, 1000), by,
            TenantContext.get(), runId, group, figure);
        if (changed == 0) {
            throw new EngineStateException(group + " " + figure + " of this run is not an open exception");
        }
        return get(runId);
    }

    @Transactional
    public EngineRunView accept(UUID runId, String group, String figure, String by) {
        List<String> explainedBy = jdbc.queryForList("SELECT explained_by FROM finaccounting.engine_reconciliation"
                + " WHERE tenant_id = ? AND run_id = ? AND group_key = ? AND figure = ? AND status = 'EXPLAINED'",
            String.class, TenantContext.get(), runId, group, figure);
        if (explainedBy.isEmpty()) {
            throw new EngineStateException(group + " " + figure + " has no explanation waiting to be accepted");
        }
        if (by.equals(explainedBy.get(0))) {
            throw new EngineStateException("You explained this difference; a FINANCE_APPROVER other than you accepts it");
        }
        jdbc.update("UPDATE finaccounting.engine_reconciliation SET status = 'ACCEPTED', accepted_by = ?, accepted_at = now()"
                + " WHERE tenant_id = ? AND run_id = ? AND group_key = ? AND figure = ? AND status = 'EXPLAINED'",
            by, TenantContext.get(), runId, group, figure);
        return get(runId);
    }

    // ---- reads ------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public EngineRunView get(UUID runId) {
        UUID tenantId = TenantContext.get();
        Run run = load(runId);
        Map<String, List<EngineRunView.Line>> lines = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM finaccounting.engine_run_line WHERE tenant_id = ? AND run_id = ? ORDER BY row_no",
            rs -> {
                lines.computeIfAbsent(rs.getString("group_key"), g -> new ArrayList<>()).add(new EngineRunView.Line(
                    rs.getInt("row_no"), rs.getString("entry"), rs.getString("account_code"), rs.getString("direction"),
                    rs.getBigDecimal("amount"), rs.getString("movement"), rs.getString("note")));
            },
            tenantId, runId);
        Map<String, EngineRunView.Closing> closings = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM finaccounting.engine_run_closing WHERE tenant_id = ? AND run_id = ? ORDER BY group_key",
            rs -> {
                closings.put(rs.getString("group_key"), new EngineRunView.Closing(rs.getBigDecimal("lrc"),
                    rs.getBigDecimal("lic"), rs.getBigDecimal("csm"), rs.getBigDecimal("arc"), rs.getBigDecimal("aic"),
                    rs.getBigDecimal("ri_csm")));
            },
            tenantId, runId);
        Set<String> groupKeys = new java.util.TreeSet<>(lines.keySet());
        groupKeys.addAll(closings.keySet());
        List<EngineRunView.Group> groups = groupKeys.stream().map(g -> new EngineRunView.Group(g,
            EngineResults.isReinsurance(g), lines.getOrDefault(g, List.of()), closings.get(g))).toList();
        List<EngineRunView.Reconciliation> reconciliation = jdbc.query("SELECT * FROM finaccounting.engine_reconciliation"
                + " WHERE tenant_id = ? AND run_id = ? ORDER BY group_key, figure",
            (rs, i) -> new EngineRunView.Reconciliation(rs.getString("group_key"), rs.getString("figure"),
                rs.getBigDecimal("ledger"), rs.getBigDecimal("engine"), rs.getBigDecimal("difference"),
                rs.getString("status"), rs.getString("explanation"), rs.getString("explained_by"),
                rs.getString("accepted_by")),
            tenantId, runId);
        UUID replacedBy = jdbc.queryForList("SELECT run_id FROM finaccounting.engine_run WHERE tenant_id = ?"
            + " AND replaces_run_id = ?", UUID.class, tenantId, runId).stream().findFirst().orElse(null);
        return new EngineRunView(run.id(), run.period(), run.extractNumber(), run.engineReference(), run.engineName(),
            run.measurementDate(), run.status(), run.errors(), run.fileName(), run.resultsDocumentRef(), run.uploadedBy(),
            run.uploadedAt(), run.decidedBy(), run.decidedAt(), run.decisionReason(), run.signOffReference(),
            run.reportDocumentRef(), run.replacesRunId(), replacedBy, groups, reconciliation);
    }

    @Transactional(readOnly = true)
    public List<EngineRunView> list(String period, String status) {
        List<UUID> ids = jdbc.queryForList("SELECT run_id FROM finaccounting.engine_run WHERE tenant_id = ?"
                + " AND (?::text IS NULL OR period = ?) AND (?::text IS NULL OR status = ?) ORDER BY uploaded_at DESC LIMIT 200",
            UUID.class, TenantContext.get(), period, period, status, status);
        return ids.stream().map(this::get).toList();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private record Run(UUID id, String period, Integer extractNumber, String engineReference, String engineName,
                       String measurementDate, String status, List<String> errors, String fileName,
                       String resultsDocumentRef, String uploadedBy, Instant uploadedAt, String decidedBy, Instant decidedAt,
                       String decisionReason, String signOffReference, String reportDocumentRef, UUID replacesRunId) {}

    private Run load(UUID runId) {
        return jdbc.query("SELECT * FROM finaccounting.engine_run WHERE tenant_id = ? AND run_id = ?", this::run,
                TenantContext.get(), runId).stream().findFirst()
            .orElseThrow(() -> new EngineNotFoundException("Engine run " + runId + " not found"));
    }

    private Optional<Run> posted(UUID tenantId, String period) {
        return jdbc.query("SELECT * FROM finaccounting.engine_run WHERE tenant_id = ? AND period = ? AND status = 'POSTED'",
            this::run, tenantId, period).stream().findFirst();
    }

    private static Run decidable(Run run, String by) {
        if (!"VALIDATED".equals(run.status())) {
            throw new EngineStateException("Only a validated run can be decided; this one is " + run.status());
        }
        if (run.uploadedBy().equals(by)) {
            throw new EngineStateException("You uploaded this run; a FINANCE_APPROVER other than you approves it");
        }
        return run;
    }

    /**
     * What the results are checked against now: the period's state, the extract they answer, the references already
     * loaded (but this run's own, on re-validation at approval), the chart's posting accounts.
     */
    private EngineResults.Context context(UUID tenantId, EngineResults.Header h, UUID self) {
        PeriodStatus status = h.period() != null && h.period().matches("\\d{4}-(0[1-9]|1[0-2])")
            ? periods.view(h.period()).status() : null;
        List<String> groups = status == null || h.extractNumber() == null ? null
            : extracts.byNumber(tenantId, h.period(), h.extractNumber())
                .map(tz.co.nlolo.lifeplatform.finaccounting.api.EngineExtractView::groups).orElse(null);
        Set<String> known = new HashSet<>(jdbc.queryForList("SELECT engine_reference FROM finaccounting.engine_run"
            + " WHERE tenant_id = ? AND status <> 'REJECTED' AND engine_reference IS NOT NULL"
            + " AND (?::uuid IS NULL OR run_id <> ?)", String.class, tenantId, self, self));
        return new EngineResults.Context(status, groups, known, POSTING_ACCOUNTS);
    }

    /**
     * Results answer the period's latest extract (IFRS 17 I5b): an older one no longer shows the month -- a replaced
     * expense allocation, say, after it was made.
     */
    private Optional<String> staleExtract(UUID tenantId, EngineResults.Header h) {
        if (h.period() == null || h.extractNumber() == null) {
            return Optional.empty();
        }
        Integer latest = jdbc.queryForObject("SELECT max(number) FROM finaccounting.engine_extract WHERE tenant_id = ?"
            + " AND period = ?", Integer.class, tenantId, h.period());
        return latest != null && h.extractNumber() < latest
            ? Optional.of("Header: extract #" + h.extractNumber() + " is not the latest (#" + latest
                + "); results answer the latest extract")
            : Optional.empty();
    }

    /** The run as stored, read back into the shape the validator checks. */
    private EngineResults stored(UUID tenantId, Run run) {
        List<EngineResults.Line> lines = jdbc.query("SELECT * FROM finaccounting.engine_run_line WHERE tenant_id = ?"
                + " AND run_id = ? ORDER BY row_no",
            (rs, i) -> new EngineResults.Line(rs.getInt("row_no"), rs.getString("group_key"), rs.getString("entry"),
                rs.getString("account_code"), rs.getString("direction"), rs.getBigDecimal("amount"),
                rs.getString("movement"), rs.getString("note")),
            tenantId, run.id());
        List<EngineResults.Closing> closings = jdbc.query("SELECT * FROM finaccounting.engine_run_closing WHERE tenant_id = ?"
                + " AND run_id = ? ORDER BY group_key",
            (rs, i) -> new EngineResults.Closing(0, rs.getString("group_key"), rs.getBigDecimal("lrc"), rs.getBigDecimal("lic"),
                rs.getBigDecimal("csm"), rs.getBigDecimal("arc"), rs.getBigDecimal("aic"), rs.getBigDecimal("ri_csm")),
            tenantId, run.id());
        return new EngineResults(new EngineResults.Header(run.period(), run.extractNumber(), run.engineReference(),
            run.engineName(), run.measurementDate()), lines, closings);
    }

    /** Every journal of the run being replaced, reversed in full -- legs swapped, dimensions kept -- under the new run. */
    private void reverse(UUID tenantId, Run old, Run replacement, String approver) {
        List<UUID> journals = jdbc.queryForList("SELECT journal_entry_id FROM finaccounting.journal_entry WHERE tenant_id = ?"
            + " AND engine_run_id = ? AND source_event = ?", UUID.class, tenantId, old.id(), EVENT);
        for (UUID journal : journals) {
            JournalEntry reversal = new JournalEntry(tenantId, REVERSAL_EVENT, journal.toString(), replacement.period(), null,
                approver).asManual(replacement.uploadedBy(), approver, "Replaced by engine run "
                    + replacement.engineReference(), null, null)
                .fromEngineRun(replacement.id()).reversing(journal);
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
                tenantId, journal);
            postings.postEntry(reversal);
        }
        jdbc.update("UPDATE finaccounting.engine_run SET status = 'REPLACED', version = version + 1 WHERE tenant_id = ?"
            + " AND run_id = ? AND status = 'POSTED'", tenantId, old.id());
    }

    private void reconcile(UUID tenantId, UUID runId, String period, List<EngineResults.Closing> closings) {
        for (EngineResults.Closing c : closings) {
            for (EngineReconciler.Row row : EngineReconciler.reconcile(c.group(),
                    ledger.closingByAccount(tenantId, c.group(), period), c)) {
                jdbc.update("INSERT INTO finaccounting.engine_reconciliation (run_id, tenant_id, group_key, figure, ledger,"
                        + " engine, difference, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    runId, tenantId, row.group(), row.figure(), row.ledger(), row.engine(), row.difference(), row.status());
            }
        }
    }

    private Map<String, String> models(UUID tenantId) {
        Map<String, String> models = new HashMap<>();
        jdbc.query("SELECT group_key, measurement_model FROM finaccounting.group_of_contracts WHERE tenant_id = ?",
            rs -> {
                models.put(rs.getString("group_key"), rs.getString("measurement_model"));
            },
            tenantId);
        return models;
    }

    private Run run(ResultSet rs, int i) throws SQLException {
        Array errors = rs.getArray("errors");
        return new Run(rs.getObject("run_id", UUID.class), rs.getString("period"),
            (Integer) rs.getObject("extract_number"), rs.getString("engine_reference"), rs.getString("engine_name"),
            rs.getString("measurement_date"), rs.getString("status"),
            errors == null ? List.of() : Arrays.asList((String[]) errors.getArray()), rs.getString("file_name"),
            rs.getString("results_document_ref"), rs.getString("uploaded_by"), instant(rs, "uploaded_at"),
            rs.getString("decided_by"), instant(rs, "decided_at"), rs.getString("decision_reason"),
            rs.getString("signoff_reference"), rs.getString("report_document_ref"),
            rs.getObject("replaces_run_id", UUID.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
