package tz.co.nlolo.lifeplatform.finaccounting.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.XlsxToCsv;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalInput;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalStateException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccount;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import tz.co.nlolo.lifeplatform.finaccounting.domain.GuideTemplates;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ManualJournalLineFile;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ManualJournalRules;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountRepository;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountSeeder;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceCodeView;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manual journals (IFRS 17 I4): drafts prepared by one person and posted only when a second, holding
 * FINANCE_APPROVER (checked at the endpoint), approves them. See {@link ManualJournalApi}.
 *
 * <p>Posting is a MANUAL journal through {@link FinaccountingApiImpl#postEntry}: preparer, approver, reason, reason
 * code and documents on it, source event {@code finaccounting.ManualJournal}, source ref the draft's id. The
 * database's own guards (I1) still decide -- an AUTO account, a missing reason code on a BOTH account, the preparer
 * as approver, a LOCKED period -- and a refusal is turned into a 409 with the guard's words, not a 500. That mapping
 * is the one I1 deferred here.
 */
@Service
public class ManualJournalApiImpl implements ManualJournalApi {

    static final String EVENT = "finaccounting.ManualJournal";
    static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");
    private static final String TEMPLATES = "/finaccounting/manual-journal-templates.yaml";

    private final ManualJournals drafts;
    private final ChartOfAccountRepository chart;
    private final ChartOfAccountSeeder seeder;
    private final AccountingPeriods periods;
    private final ReferenceDataApi referenceData;
    private final FinaccountingApiImpl ledger;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final List<GuideTemplates.Template> guide;

    ManualJournalApiImpl(ManualJournals drafts, ChartOfAccountRepository chart, ChartOfAccountSeeder seeder,
                         AccountingPeriods periods, ReferenceDataApi referenceData, FinaccountingApiImpl ledger,
                         JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.drafts = drafts;
        this.chart = chart;
        this.seeder = seeder;
        this.periods = periods;
        this.referenceData = referenceData;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
        var in = ManualJournalApiImpl.class.getResourceAsStream(TEMPLATES);
        if (in == null) {
            throw new IllegalStateException(TEMPLATES + " is missing from the classpath");
        }
        this.guide = GuideTemplates.load(in, ChartOfAccountBlueprint.accounts());
    }

    // ---- drafts ---------------------------------------------------------------------------------------------------

    @Override
    @Transactional
    public ManualJournalView create(ManualJournalInput input, String preparer) {
        UUID tenantId = TenantContext.get();
        seeder.seedIfAbsent(tenantId, preparer);
        UUID id = drafts.insert(tenantId, periodOf(input), input, null, preparer);
        return view(load(id));
    }

    @Override
    @Transactional
    public ManualJournalView update(UUID id, ManualJournalInput input, String by) {
        ManualJournals.Draft d = editableBy(id, by);
        drafts.updateDraft(TenantContext.get(), d.header().id(), periodOf(input), input);
        return view(load(id));
    }

    @Override
    @Transactional(readOnly = true)
    public ManualJournalView get(UUID id) {
        return view(load(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ManualJournalView> list(String status, String period) {
        Map<String, ChartOfAccount> accounts = accounts(TenantContext.get());
        return drafts.list(TenantContext.get(), blankToNull(status), blankToNull(period)).stream()
            .map(d -> view(d, accounts)).toList();
    }

    // ---- the workflow ---------------------------------------------------------------------------------------------

    @Override
    @Transactional
    public ManualJournalView submit(UUID id, String by) {
        ManualJournals.Draft d = editableBy(id, by);
        refuseProblems(d);
        if (!drafts.move(TenantContext.get(), id, "DRAFT", "SUBMITTED")) {
            throw new ManualJournalStateException("This journal is no longer a draft");
        }
        return view(load(id));
    }

    @Override
    @Transactional
    public ManualJournalView withdraw(UUID id, String by) {
        ManualJournals.Draft d = load(id);
        if (!d.header().preparer().equals(by)) {
            throw new ManualJournalStateException("Only " + d.header().preparer() + ", who prepared it, can withdraw it");
        }
        if (!drafts.move(TenantContext.get(), id, "SUBMITTED", "DRAFT")) {
            throw new ManualJournalStateException("Only a submitted journal can be withdrawn; this one is "
                + d.header().status());
        }
        return view(load(id));
    }

    /**
     * Checked again (a period may have locked, an account retired, since it was submitted), then posted, in one
     * transaction with the draft's approval and its auto-reversal: the journal is posted and approved, or neither.
     */
    @Override
    public ManualJournalView approve(UUID id, String approver) {
        UUID tenantId = TenantContext.get();
        ManualJournals.Draft d = load(id);
        decidable(d, approver, "approve");
        refuseProblems(d);
        try {
            tx.executeWithoutResult(status -> {
                JournalEntry entry = journalOf(tenantId, d, approver);
                JournalEntry posted = ledger.postEntry(entry).orElseThrow(() ->
                    new ManualJournalStateException("This journal was already posted"));
                if (!drafts.approve(tenantId, id, approver, posted.getJournalEntryId())) {
                    throw new ManualJournalStateException("This journal is no longer awaiting approval");
                }
                if (d.header().autoReverseOn() != null) {
                    jdbc.update("INSERT INTO finaccounting.auto_reversal (tenant_id, original_journal_id, reverse_on)"
                        + " VALUES (?, ?, ?)", tenantId, posted.getJournalEntryId(), Date.valueOf(d.header().autoReverseOn()));
                }
            });
        } catch (ManualJournalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            String guard = ledgerGuard(e);
            if (guard != null) {
                throw new ManualJournalStateException("The ledger refused this journal: " + guard);
            }
            throw e;
        }
        return view(load(id));
    }

    @Override
    @Transactional
    public ManualJournalView reject(UUID id, String reason, String by) {
        ManualJournals.Draft d = load(id);
        decidable(d, by, "reject");
        if (reason == null || reason.isBlank()) {
            throw new FinaccountingValidationException("Say why the journal is rejected");
        }
        if (!drafts.reject(TenantContext.get(), id, by, reason.trim())) {
            throw new ManualJournalStateException("This journal is no longer awaiting a decision");
        }
        return view(load(id));
    }

    /**
     * A correction: a new draft with every line's side swapped, dated in the current open period, needing approval
     * like any journal. Copies the original's documents, reason code and dimensions. Once per journal.
     */
    @Override
    @Transactional
    public ManualJournalView reverse(UUID id, String preparer) {
        UUID tenantId = TenantContext.get();
        ManualJournals.Draft original = load(id);
        ManualJournals.Header h = original.header();
        if (!"APPROVED".equals(h.status())) {
            throw new ManualJournalStateException("Only a posted (approved) journal can be reversed; this one is " + h.status());
        }
        if (h.reversesJournalId() != null) {
            throw new ManualJournalStateException("This journal is itself a reversal; post a new journal instead");
        }
        drafts.liveReversalOf(tenantId, h.journalEntryId()).ifPresent(existing -> {
            throw new ManualJournalStateException("This journal already has a reversal (" + existing + ")");
        });
        List<ManualJournalInput.Line> swapped = original.lines().stream().map(l -> new ManualJournalInput.Line(
            l.accountCode(), l.side() == PostingDirection.DR ? PostingDirection.CR : PostingDirection.DR, l.amount(),
            l.description(), l.branch(), l.fund(), l.referenceType(), l.reference())).toList();
        ManualJournalInput in = new ManualJournalInput(YearMonth.now(CIVIL).toString(), h.currency(),
            trim("Reversal of " + h.title(), 200), "Reverses journal " + h.journalEntryId() + ": " + h.reason(),
            h.reasonCode(), h.templateId(), null, swapped);
        UUID reversal = drafts.insert(tenantId, in.period(), in, h.journalEntryId(), preparer);
        for (String ref : h.documentRefs()) {
            drafts.addDocument(tenantId, reversal, ref);
        }
        return view(load(reversal));
    }

    // ---- documents and lines --------------------------------------------------------------------------------------

    @Override
    @Transactional
    public ManualJournalView attachDocument(UUID id, String documentRef, String by) {
        editableBy(id, by);
        drafts.addDocument(TenantContext.get(), id, documentRef);
        return view(load(id));
    }

    @Override
    @Transactional
    public ManualJournalView detachDocument(UUID id, String documentRef, String by) {
        editableBy(id, by);
        drafts.removeDocument(TenantContext.get(), id, documentRef);
        return view(load(id));
    }

    @Override
    @Transactional
    public ManualJournalView uploadLines(UUID id, byte[] content, String fileName, String by) {
        editableBy(id, by);
        boolean excel = (fileName != null && fileName.toLowerCase().endsWith(".xlsx"))
            || (content.length > 1 && content[0] == 'P' && content[1] == 'K');   // a zip: an .xlsx whatever its name
        String csv = excel ? XlsxToCsv.convert(new ByteArrayInputStream(content)) : new String(content, StandardCharsets.UTF_8);
        ManualJournalLineFile.Result parsed = ManualJournalLineFile.parse(csv);
        if (!parsed.errors().isEmpty()) {
            throw new FinaccountingValidationException(String.join("; ", parsed.errors()));
        }
        drafts.replaceLines(TenantContext.get(), id, parsed.lines());
        return view(load(id));
    }

    // ---- templates ------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<JournalTemplateView> templates() {
        Map<String, String> names = ChartOfAccountBlueprint.accounts().stream()
            .collect(Collectors.toMap(ChartOfAccountBlueprint.Seed::code, ChartOfAccountBlueprint.Seed::name, (a, b) -> a));
        List<JournalTemplateView> all = new ArrayList<>();
        for (GuideTemplates.Template t : guide) {
            all.add(new JournalTemplateView(t.id(), "GUIDE", t.title(), t.when(), t.postedBy(), null,
                t.lines().stream().map(l -> new JournalTemplateView.TemplateLine(l.side(), l.account(),
                    names.get(l.account()), null, null)).toList()));
        }
        all.addAll(jdbc.query("SELECT * FROM finaccounting.journal_template WHERE tenant_id = ? AND retired_at IS NULL"
                + " ORDER BY name",
            (rs, i) -> new JournalTemplateView(rs.getString("template_id"), "SAVED", rs.getString("name"),
                rs.getString("description"), null, rs.getString("reason_code"), savedLines(rs.getString("lines"), names)),
            TenantContext.get()));
        return all;
    }

    @Override
    @Transactional
    public JournalTemplateView saveTemplate(String name, String description, List<ManualJournalInput.Line> lines,
                                            String reasonCode, String by) {
        if (name == null || name.isBlank()) {
            throw new FinaccountingValidationException("A template has a name");
        }
        if (lines == null || lines.isEmpty()) {
            throw new FinaccountingValidationException("A template has lines");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO finaccounting.journal_template (template_id, tenant_id, name, description, lines,"
                    + " reason_code, created_by) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)",
                id, TenantContext.get(), trim(name, 200), trim(description, 500), json.writeValueAsString(lines),
                blankToNull(reasonCode), by);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ManualJournalStateException("A template named '" + name.trim() + "' already exists");
        }
        return templates().stream().filter(t -> t.id().equals(id.toString())).findFirst().orElseThrow();
    }

    private List<JournalTemplateView.TemplateLine> savedLines(String raw, Map<String, String> names) {
        try {
            List<ManualJournalInput.Line> lines = json.readValue(raw, new TypeReference<>() {});
            return lines.stream().map(l -> new JournalTemplateView.TemplateLine(l.side(), l.accountCode(),
                names.get(l.accountCode()), l.amount(), l.description())).toList();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unreadable saved template", e);
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private ManualJournals.Draft load(UUID id) {
        return drafts.find(TenantContext.get(), id)
            .orElseThrow(() -> new ManualJournalNotFoundException("Manual journal " + id + " not found"));
    }

    /** A draft its preparer may still change. */
    private ManualJournals.Draft editableBy(UUID id, String by) {
        ManualJournals.Draft d = load(id);
        if (!"DRAFT".equals(d.header().status())) {
            throw new ManualJournalStateException("This journal is " + d.header().status()
                + "; only a draft can be changed (withdraw a submitted one first)");
        }
        if (!d.header().preparer().equals(by)) {
            throw new ManualJournalStateException("Only " + d.header().preparer() + ", who prepared it, can change it");
        }
        return d;
    }

    /** A submitted journal, and a decider who is not its preparer (maker-checker, guide 5.4). */
    private static void decidable(ManualJournals.Draft d, String by, String action) {
        if (!"SUBMITTED".equals(d.header().status())) {
            throw new ManualJournalStateException("Only a submitted journal can be " + action + "d; this one is "
                + d.header().status());
        }
        if (d.header().preparer().equals(by)) {
            throw new ManualJournalStateException("You prepared this journal; a second person must " + action + " it");
        }
    }

    private void refuseProblems(ManualJournals.Draft d) {
        ManualJournals.Header h = d.header();
        UUID tenantId = TenantContext.get();
        Map<String, ManualJournalRules.Account> accounts = new LinkedHashMap<>();
        for (ChartOfAccount a : chart.findByTenantIdOrderByAccountCodeAsc(tenantId)) {
            accounts.put(a.getAccountCode(), new ManualJournalRules.Account(a.getAccountCode(), a.getName(), a.getMode(),
                a.isPostingAllowed(), a.getStatus() == AccountStatus.ACTIVE, a.getCurrency()));
        }
        Set<String> reasonCodes = referenceData.getCodes("JOURNAL_REASON").stream().map(ReferenceCodeView::code)
            .collect(Collectors.toSet());
        ManualJournalInput input = new ManualJournalInput(h.period(), h.currency(), h.title(), h.reason(),
            h.reasonCode(), h.templateId(), h.autoReverseOn(), d.lines());
        List<String> problems = ManualJournalRules.problems(input, h.period(), accounts, reasonCodes,
            periods.view(h.period()).status(), h.documentRefs().size());
        if (!problems.isEmpty()) {
            throw new FinaccountingValidationException(String.join("; ", problems));
        }
    }

    private static JournalEntry journalOf(UUID tenantId, ManualJournals.Draft d, String approver) {
        ManualJournals.Header h = d.header();
        JournalEntry entry = new JournalEntry(tenantId, EVENT, h.id().toString(), h.period(), null, approver)
            .asManual(h.preparer(), approver, h.reason(), h.reasonCode(), String.join(",", h.documentRefs()));
        if (h.reversesJournalId() != null) {
            entry.reversing(h.reversesJournalId());
        }
        if (h.autoReverseOn() != null) {
            entry.autoReverseOn(h.autoReverseOn());
        }
        for (ManualJournalInput.Line l : d.lines()) {
            entry.addLeg(l.accountCode(), l.side(), l.amount(), h.currency(), new LineDimensions(null, null, null, null,
                null, null, l.branch(), l.fund(), l.referenceType(), l.reference()));
        }
        return entry;
    }

    /** The ledger guard's own words (V10: "LEDGER_PERIOD_LOCKED: period 2026-08 is locked"), or null. */
    static String ledgerGuard(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m != null && m.contains("LEDGER_")) {
                String from = m.substring(m.indexOf("LEDGER_"));
                int end = from.indexOf('\n');
                return end > 0 ? from.substring(0, end).trim() : from.trim();
            }
        }
        return null;
    }

    private String periodOf(ManualJournalInput input) {
        String period = input.period() == null || input.period().isBlank() ? YearMonth.now(CIVIL).toString() : input.period();
        if (!period.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            throw new FinaccountingValidationException("A period is YYYY-MM, for example 2026-10");
        }
        return period;
    }

    private Map<String, ChartOfAccount> accounts(UUID tenantId) {
        Map<String, ChartOfAccount> map = new LinkedHashMap<>();
        for (ChartOfAccount a : chart.findByTenantIdOrderByAccountCodeAsc(tenantId)) {
            map.put(a.getAccountCode(), a);
        }
        return map;
    }

    private ManualJournalView view(ManualJournals.Draft d) {
        return view(d, accounts(TenantContext.get()));
    }

    private static ManualJournalView view(ManualJournals.Draft d, Map<String, ChartOfAccount> accounts) {
        ManualJournals.Header h = d.header();
        List<ManualJournalView.Line> lines = new ArrayList<>();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        int no = 0;
        for (ManualJournalInput.Line l : d.lines()) {
            no++;
            ChartOfAccount a = accounts.get(l.accountCode());
            lines.add(new ManualJournalView.Line(no, l.accountCode(), a == null ? null : a.getName(),
                a == null ? null : a.getMode().name(), l.side(), l.amount(), l.description(), l.branch(), l.fund(),
                l.referenceType(), l.reference()));
            if (l.amount() != null && l.side() == PostingDirection.DR) {
                debit = debit.add(l.amount());
            } else if (l.amount() != null && l.side() == PostingDirection.CR) {
                credit = credit.add(l.amount());
            }
        }
        return new ManualJournalView(h.id(), h.status(), h.period(), h.currency(), h.title(), h.reason(), h.reasonCode(),
            h.templateId(), h.reversesJournalId(), h.autoReverseOn(), h.documentRefs(), h.preparer(), h.preparedAt(),
            h.submittedAt(), h.decidedBy(), h.decidedAt(), h.decisionReason(), h.journalEntryId(), lines, debit, credit);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String trim(String s, int max) {
        String v = blankToNull(s);
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }
}
