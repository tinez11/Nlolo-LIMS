package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountingPeriodView;
import tz.co.nlolo.lifeplatform.finaccounting.api.MonthEndView;
import tz.co.nlolo.lifeplatform.finaccounting.api.MonthEndView.Step;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.GlPostingRepository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where a month's close stands (finance visibility step 5, 2026-10-27): the guide's eight month-end steps, each read from
 * the records that step leaves -- the unposted queue, the month's manual journals, the period, its expense allocation,
 * the engine's extract, run and reconciliation, and the lock's own checks. Read only: every act stays on its own page.
 */
@Service
public class MonthEnd {

    private static final String DONE = "DONE";
    private static final String WAITING = "WAITING";
    private static final String TODO = "TODO";
    private static final String BLOCKED = "BLOCKED";

    private final AccountingPeriods periods;
    private final UnpostedEvents unposted;
    private final YearEndLockGate yearEndGate;
    private final GlPostingRepository postings;
    private final JdbcTemplate jdbc;

    MonthEnd(AccountingPeriods periods, UnpostedEvents unposted, YearEndLockGate yearEndGate,
             GlPostingRepository postings, JdbcTemplate jdbc) {
        this.periods = periods;
        this.unposted = unposted;
        this.yearEndGate = yearEndGate;
        this.postings = postings;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public MonthEndView of(String period) {
        AccountingPeriodView p = periods.view(period);
        UUID tenantId = TenantContext.get();
        List<Step> steps = new ArrayList<>();

        // 1. The queue -- this month's and every earlier month's, as the lock counts it.
        int waiting = unposted.openUpTo(tenantId, period);
        steps.add(new Step(1, "queue", "Clear unposted events", waiting == 0 ? DONE : TODO,
            waiting == 0 ? "No event is waiting to be posted."
                : waiting + (waiting == 1 ? " event waits" : " events wait") + " to be posted or dismissed.",
            null, null));

        // 2. Manual journals: done when none of the month's is still a draft or waiting for its approver.
        Map<String, Object> journals = jdbc.queryForMap("SELECT"
            + " count(*) FILTER (WHERE status = 'DRAFT') AS drafts,"
            + " count(*) FILTER (WHERE status = 'SUBMITTED') AS submitted,"
            + " count(*) FILTER (WHERE status = 'APPROVED') AS approved"
            + " FROM finaccounting.manual_journal WHERE tenant_id = ? AND period = ?", tenantId, period);
        long drafts = ((Number) journals.get("drafts")).longValue();
        long submitted = ((Number) journals.get("submitted")).longValue();
        long approved = ((Number) journals.get("approved")).longValue();
        steps.add(new Step(2, "journals", "Enter and approve manual journals",
            submitted > 0 ? WAITING : drafts > 0 ? TODO : DONE,
            submitted > 0 ? count(submitted, "journal waits", "journals wait") + " for a finance approver."
                : drafts > 0 ? count(drafts, "draft is", "drafts are") + " not yet submitted."
                : approved == 0 ? "None this month. Accruals and anything outside the policy system go here."
                : count(approved, "journal", "journals") + " approved.",
            null, null));

        // 3. Start closing.
        boolean closing = p.status() != PeriodStatus.OPEN;
        steps.add(new Step(3, "closing", "Start closing the month", closing ? DONE : TODO,
            closing ? "Event postings dated in the month now wait in Unposted events."
                : "The month still takes every posting. Start closing it in Accounting periods.",
            p.closingStartedBy(), p.closingStartedAt()));

        // 4. Expense allocation (the guide's step 5).
        List<Map<String, Object>> allocations = jdbc.queryForList("SELECT status, prepared_by, prepared_at, decided_by,"
            + " decided_at FROM finaccounting.expense_allocation WHERE tenant_id = ? AND period = ?"
            + " AND status IN ('PREPARED','POSTED') ORDER BY prepared_at DESC LIMIT 1", tenantId, period);
        if (allocations.isEmpty()) {
            steps.add(new Step(4, "allocation", "Allocate the month's expenses", TODO,
                closing ? "Not prepared yet." : "Prepared once the month is closing.", null, null));
        } else {
            Map<String, Object> a = allocations.get(0);
            boolean posted = "POSTED".equals(a.get("status"));
            steps.add(new Step(4, "allocation", "Allocate the month's expenses", posted ? DONE : WAITING,
                posted ? "Approved and posted." : "Prepared; waiting for a finance approver's decision.",
                (String) (posted ? a.get("decided_by") : a.get("prepared_by")),
                instant(posted ? a.get("decided_at") : a.get("prepared_at"))));
        }

        // 5. The extract the engine is sent (the guide's step 6).
        List<Map<String, Object>> extracts = jdbc.queryForList("SELECT number, created_by, created_at"
            + " FROM finaccounting.engine_extract WHERE tenant_id = ? AND period = ? ORDER BY number DESC LIMIT 1",
            tenantId, period);
        steps.add(extracts.isEmpty()
            ? new Step(5, "extract", "Send the engine its figures", TODO,
                "No extract yet. It can be made once the steps above are done.", null, null)
            : new Step(5, "extract", "Send the engine its figures", DONE,
                "Extract " + extracts.get(0).get("number") + " made.",
                (String) extracts.get(0).get("created_by"), instant(extracts.get(0).get("created_at"))));

        // 6. The engine's results (the guide's step 7), through 9160.
        List<Map<String, Object>> runs = jdbc.queryForList("SELECT engine_reference, status, cardinality(errors) AS errors,"
            + " uploaded_by, uploaded_at, decided_by, decided_at FROM finaccounting.engine_run"
            + " WHERE tenant_id = ? AND period = ? AND status <> 'REPLACED' ORDER BY uploaded_at DESC LIMIT 1",
            tenantId, period);
        boolean runPosted = false;
        if (runs.isEmpty()) {
            steps.add(new Step(6, "results", "Load the engine's results", TODO, "No results uploaded yet.", null, null));
        } else {
            Map<String, Object> r = runs.get(0);
            String status = (String) r.get("status");
            String ref = (String) r.get("engine_reference");
            runPosted = "POSTED".equals(status);
            boolean clearingOpen = runPosted && !postings.nonZeroNetByAccountPrefix(tenantId, period, "9160").isEmpty();
            steps.add(switch (status) {
                case "POSTED" -> new Step(6, "results", "Load the engine's results", clearingOpen ? BLOCKED : DONE,
                    "Run " + ref + " approved and posted"
                        + (clearingOpen ? "; clearing account 9160 is not back at zero." : "; 9160 is back at zero."),
                    (String) r.get("decided_by"), instant(r.get("decided_at")));
                case "VALIDATED" -> new Step(6, "results", "Load the engine's results", WAITING,
                    "Run " + ref + " validated; waiting for a finance approver with the actuary's sign-off.",
                    (String) r.get("uploaded_by"), instant(r.get("uploaded_at")));
                default -> new Step(6, "results", "Load the engine's results", TODO,
                    "The last upload was rejected with " + count(((Number) r.get("errors")).longValue(), "error", "errors")
                        + ". Correct the file and upload it again.",
                    (String) r.get("uploaded_by"), instant(r.get("uploaded_at")));
            });
        }

        // 7. Reconcile: the posted run's figures against the ledger.
        if (!runPosted) {
            steps.add(new Step(7, "reconcile", "Reconcile with the engine", TODO,
                "Reconciled once the engine's results are posted.", null, null));
        } else {
            Map<String, Object> rec = jdbc.queryForMap("SELECT"
                + " count(*) FILTER (WHERE c.status IN ('EXCEPTION','EXPLAINED')) AS open,"
                + " count(*) FILTER (WHERE c.status = 'ACCEPTED') AS accepted"
                + " FROM finaccounting.engine_reconciliation c JOIN finaccounting.engine_run r ON r.run_id = c.run_id"
                + " WHERE c.tenant_id = ? AND r.period = ? AND r.status = 'POSTED'", tenantId, period);
            long open = ((Number) rec.get("open")).longValue();
            long accepted = ((Number) rec.get("accepted")).longValue();
            steps.add(new Step(7, "reconcile", "Reconcile with the engine", open > 0 ? BLOCKED : DONE,
                open > 0 ? count(open, "figure differs", "figures differ") + " from the ledger: explain and accept, or"
                        + " post a replacement run."
                    : accepted > 0 ? "Agrees, with " + count(accepted, "explained difference", "explained differences")
                        + " accepted by two people."
                    : "The ledger agrees with the engine's closing figures.",
                null, null));
        }

        // 8. Lock -- with the lock's own reasons, the year-end close's included.
        List<String> blockers = new ArrayList<>();
        if (p.status() != PeriodStatus.LOCKED) {
            blockers.addAll(periods.blockers(tenantId, period));
            yearEndGate.blocking(tenantId, period).ifPresent(blockers::add);
        }
        boolean locked = p.status() == PeriodStatus.LOCKED;
        steps.add(new Step(8, "lock", "Lock the month", locked ? DONE : TODO,
            locked ? "Locked. Nothing more posts to it; a correction is a reversal in the open month."
                : !closing ? "Locks once it is closing and nothing stops it."
                : blockers.isEmpty() ? "Nothing stops the lock. Lock it in Accounting periods."
                : "Not yet: " + blockers.get(0),
            p.lockedBy(), p.lockedAt()));

        int current = steps.stream().filter(s -> !DONE.equals(s.state())).mapToInt(Step::number).findFirst().orElse(9);
        return new MonthEndView(period, p.status(), current, steps, blockers);
    }

    private static String count(long n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof java.time.OffsetDateTime o) return o.toInstant();
        return value instanceof Instant i ? i : null;
    }
}
