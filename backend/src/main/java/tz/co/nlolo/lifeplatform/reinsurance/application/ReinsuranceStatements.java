package tz.co.nlolo.lifeplatform.reinsurance.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceValidationException;
import tz.co.nlolo.lifeplatform.reinsurance.api.StatementNotFoundException;
import tz.co.nlolo.lifeplatform.reinsurance.api.StatementStateException;
import tz.co.nlolo.lifeplatform.reinsurance.api.StatementView;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyNotFoundException;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceTreaty;
import tz.co.nlolo.lifeplatform.reinsurance.domain.StatementCalculator;
import tz.co.nlolo.lifeplatform.reinsurance.domain.StatementCalculator.Figures;
import tz.co.nlolo.lifeplatform.reinsurance.domain.StatementCalculator.Quarter;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.ReinsuranceTreatyRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The quarterly reinsurance statement (IFRS 17 I3d, guide R-01, R-03, R-04). A finance officer prepares a quarter's
 * statement from the platform's own records -- its bordereaux and the recoveries that posted -- adds what the
 * reinsurer's statement states (funds withheld, profit commission), and submits it; a second person holding
 * FINANCE_APPROVER (checked at the endpoint) approves it, which publishes {@code reinsurance.StatementApproved} for
 * finaccounting to post as one SYSTEM journal (rules v4, R-STMT).
 *
 * <p>Public, with {@link #prepare(UUID, String, String, LocalDate)} naming "today", so the end-to-end tests can settle
 * a quarter whatever the calendar says.
 */
@Service
public class ReinsuranceStatements {

    static final String EVENT = "reinsurance.StatementApproved";

    private final StatementStore store;
    private final ReinsuranceTreatyRepository treaties;
    private final Bordereaux bordereaux;
    private final ApplicationEventPublisher events;

    ReinsuranceStatements(StatementStore store, ReinsuranceTreatyRepository treaties, Bordereaux bordereaux,
                          ApplicationEventPublisher events) {
        this.store = store;
        this.treaties = treaties;
        this.bordereaux = bordereaux;
        this.events = events;
    }

    /** A DRAFT for the treaty's quarter, as of {@code today} (Dar es Salaam). */
    @Transactional
    public StatementView prepare(UUID treatyId, String quarterText, String preparer, LocalDate today) {
        UUID tenantId = TenantContext.get();
        Quarter quarter;
        try {
            quarter = Quarter.parse(quarterText);
        } catch (IllegalArgumentException e) {
            throw new ReinsuranceValidationException(e.getMessage());
        }
        ReinsuranceTreaty treaty = treaties.findByTreatyIdAndTenantId(treatyId, tenantId)
            .orElseThrow(() -> new TreatyNotFoundException("Treaty " + treatyId + " not found"));
        if (!quarter.hasEnded(today)) {
            throw new StatementStateException("Quarter " + quarter + " has not ended; it settles from "
                + quarter.endExclusive());
        }
        for (YearMonth month : StatementCalculator.monthsRequired(quarter, treaty.getEffectiveFrom(), treaty.getEffectiveTo())) {
            if (!bordereaux.exists(tenantId, treatyId, month)) {
                throw new StatementStateException("The " + month + " bordereau of this treaty is not written yet;"
                    + " a quarter settles once all its months are");
            }
        }
        store.liveFor(tenantId, treatyId, quarter).ifPresent(live -> {
            throw new StatementStateException("Quarter " + quarter + " already has a statement (" + live.status() + ")");
        });

        List<StatementStore.BordereauRow> months = store.bordereauxOf(tenantId, treatyId, quarter);
        List<StatementStore.RecoveryRow> recoveries = store.recoveriesOf(tenantId, treatyId, quarter);
        BigDecimal premium = months.stream().map(StatementStore.BordereauRow::premium).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal commission = months.stream().map(StatementStore.BordereauRow::commission).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal recovered = recoveries.stream().map(StatementStore.RecoveryRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        UUID id;
        try {
            id = store.insert(tenantId, treatyId, quarter, treaty.getRetentionLimitCurrency(), premium, commission,
                recovered, preparer);
            for (StatementStore.BordereauRow b : months) {
                store.insertItem(tenantId, id, "BORDEREAU", b.id());
            }
            for (StatementStore.RecoveryRow r : recoveries) {
                store.insertItem(tenantId, id, "RECOVERY", r.id());
            }
        } catch (DuplicateKeyException e) {
            throw new StatementStateException("Quarter " + quarter + " already has a statement");
        }
        return view(load(id));
    }

    @Transactional
    public StatementView update(UUID id, BigDecimal withheld, BigDecimal profitCommission, String reason, String by) {
        StatementStore.Row row = editableBy(id, by);
        BigDecimal w = withheld == null ? BigDecimal.ZERO : withheld;
        BigDecimal pc = profitCommission == null ? BigDecimal.ZERO : profitCommission;
        List<String> problems = new Figures(row.premium(), row.commission(), row.recoveries(), w, pc).problems();
        if (!problems.isEmpty()) {
            throw new ReinsuranceValidationException(String.join("; ", problems));
        }
        if (!store.updateDraft(TenantContext.get(), id, w, pc, trim(reason))) {
            throw new StatementStateException("This statement is no longer a draft");
        }
        return view(load(id));
    }

    @Transactional
    public StatementView submit(UUID id, String by) {
        StatementStore.Row row = editableBy(id, by);
        List<String> problems = new ArrayList<>();
        if (row.reason() == null || row.reason().isBlank()) {
            problems.add("Say why (the statement's reference or what was agreed with the reinsurer)");
        }
        if (row.documentRefs().isEmpty()) {
            problems.add("Attach the reinsurer's statement");
        }
        problems.addAll(figures(row).problems());
        if (!problems.isEmpty()) {
            throw new ReinsuranceValidationException(String.join("; ", problems));
        }
        if (!store.move(TenantContext.get(), id, "DRAFT", "SUBMITTED")) {
            throw new StatementStateException("This statement is no longer a draft");
        }
        return view(load(id));
    }

    @Transactional
    public StatementView withdraw(UUID id, String by) {
        StatementStore.Row row = load(id);
        if (!row.preparer().equals(by)) {
            throw new StatementStateException("Only " + row.preparer() + ", who prepared it, can withdraw it");
        }
        if (!store.move(TenantContext.get(), id, "SUBMITTED", "DRAFT")) {
            throw new StatementStateException("Only a submitted statement can be withdrawn; this one is " + row.status());
        }
        return view(load(id));
    }

    /** Checked again, approved, and -- when it posts anything -- published for finaccounting to post. */
    @Transactional
    public StatementView approve(UUID id, String approver) {
        UUID tenantId = TenantContext.get();
        StatementStore.Row row = decidable(id, approver, "approve");
        Figures figures = figures(row);
        List<String> problems = figures.problems();
        if (!problems.isEmpty()) {
            throw new ReinsuranceValidationException(String.join("; ", problems));
        }
        if (!store.decide(tenantId, id, "APPROVED", approver, null)) {
            throw new StatementStateException("This statement is no longer awaiting a decision");
        }
        if (!figures.journal().isEmpty()) {
            ReinsuranceTreaty treaty = treaties.findByTreatyIdAndTenantId(row.treatyId(), tenantId).orElseThrow();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("statementId", id.toString());
            payload.put("treatyId", row.treatyId().toString());
            payload.put("reinsurerName", treaty.getReinsurerName());
            payload.put("quarter", row.quarter());
            payload.put("premium", money(row.premium(), row.currency()));
            payload.put("commission", money(row.commission(), row.currency()));
            payload.put("recoveries", money(row.recoveries(), row.currency()));
            payload.put("fundsWithheld", money(row.fundsWithheld(), row.currency()));
            payload.put("profitCommission", money(row.profitCommission(), row.currency()));
            payload.put("owedToUs", money(figures.owedToUs(), row.currency()));
            payload.put("owedByUs", money(figures.owedByUs(), row.currency()));
            events.publishEvent(DomainEventEnvelope.of(EVENT, tenantId, payload));
        }
        return view(load(id));
    }

    @Transactional
    public StatementView reject(UUID id, String reason, String by) {
        if (reason == null || reason.isBlank()) {
            throw new ReinsuranceValidationException("Say why the statement is rejected");
        }
        decidable(id, by, "reject");
        if (!store.decide(TenantContext.get(), id, "REJECTED", by, trim(reason))) {
            throw new StatementStateException("This statement is no longer awaiting a decision");
        }
        store.release(TenantContext.get(), id);
        return view(load(id));
    }

    @Transactional
    public StatementView attachDocument(UUID id, String ref, String by) {
        editableBy(id, by);
        store.addDocument(TenantContext.get(), id, ref);
        return view(load(id));
    }

    @Transactional(readOnly = true)
    public void requireEditable(UUID id, String by) {
        editableBy(id, by);
    }

    @Transactional(readOnly = true)
    public StatementView get(UUID id) {
        return view(load(id));
    }

    @Transactional(readOnly = true)
    public List<StatementView> list(String status, UUID treatyId) {
        return store.list(TenantContext.get(), blankToNull(status), treatyId).stream().map(this::view).toList();
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private StatementStore.Row load(UUID id) {
        return store.find(TenantContext.get(), id)
            .orElseThrow(() -> new StatementNotFoundException("Statement " + id + " not found"));
    }

    private StatementStore.Row editableBy(UUID id, String by) {
        StatementStore.Row row = load(id);
        if (!"DRAFT".equals(row.status())) {
            throw new StatementStateException("This statement is " + row.status()
                + "; only a draft can be changed (withdraw a submitted one first)");
        }
        if (!row.preparer().equals(by)) {
            throw new StatementStateException("Only " + row.preparer() + ", who prepared it, can change it");
        }
        return row;
    }

    /** A submitted statement, and a decider who is not its preparer (maker-checker). */
    private StatementStore.Row decidable(UUID id, String by, String action) {
        StatementStore.Row row = load(id);
        if (!"SUBMITTED".equals(row.status())) {
            throw new StatementStateException("Only a submitted statement can be " + action + "d; this one is "
                + row.status());
        }
        if (row.preparer().equals(by)) {
            throw new StatementStateException("You prepared this statement; a second person must " + action + " it");
        }
        return row;
    }

    private static Figures figures(StatementStore.Row row) {
        return new Figures(row.premium(), row.commission(), row.recoveries(), row.fundsWithheld(), row.profitCommission());
    }

    private StatementView view(StatementStore.Row row) {
        UUID tenantId = TenantContext.get();
        Figures f = figures(row);
        String reinsurer = treaties.findByTreatyIdAndTenantId(row.treatyId(), tenantId)
            .map(ReinsuranceTreaty::getReinsurerName).orElse(null);
        List<StatementView.Item> items = store.items(tenantId, row.id()).stream()
            .map(i -> new StatementView.Item(i.type(), i.id(), i.label(), i.amount())).toList();
        List<StatementView.JournalLine> journal = f.journal().stream()
            .map(l -> new StatementView.JournalLine(l.entry(), l.account(), l.side(), l.amount())).toList();
        return new StatementView(row.id(), row.treatyId(), reinsurer, row.quarter(), row.currency(), row.status(),
            row.premium(), row.commission(), row.recoveries(), row.fundsWithheld(), row.profitCommission(), f.owedToUs(),
            f.owedByUs(), row.reason(), row.documentRefs(), row.preparer(), row.preparedAt(), row.submittedAt(),
            row.decidedBy(), row.decidedAt(), row.decisionReason(), items, journal);
    }

    private static Map<String, String> money(BigDecimal amount, String currency) {
        return Map.of("amount", amount.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString(), "currencyCode", currency);
    }

    private static String trim(String s) {
        String v = blankToNull(s);
        return v == null || v.length() <= 500 ? v : v.substring(0, 500);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
