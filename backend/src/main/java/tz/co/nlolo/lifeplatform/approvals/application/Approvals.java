package tz.co.nlolo.lifeplatform.approvals.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalSource;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalsView;
import tz.co.nlolo.lifeplatform.iam.api.AccessApi;
import tz.co.nlolo.lifeplatform.iam.api.CurrentAccess;
import tz.co.nlolo.lifeplatform.iam.api.Permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Gathers every {@link ApprovalSource} the viewer may act on.
 *
 * <p>A source that fails costs its own group, shown as unreadable, never the page -- and never a 0: "nothing waiting"
 * and "could not ask" must not look alike (the sidebar badges' rule).
 */
@Service
public class Approvals {

    private static final Logger log = LoggerFactory.getLogger(Approvals.class);

    /** Rows shown per group; the count is always the whole queue's. */
    static final int ROWS_PER_GROUP = 25;

    private final List<ApprovalSource> sources;
    private final CurrentAccess access;
    private final AccessApi staff;

    public Approvals(List<ApprovalSource> sources, CurrentAccess access, AccessApi staff) {
        this.sources = sources;
        this.access = access;
        this.staff = staff;
    }

    /**
     * The order groups are read in: money waiting to leave first, then servicing, prices and rates, then the books. A
     * kind not named here (a module's new one) follows, in bean order, rather than being dropped.
     */
    static final List<String> ORDER = List.of(
        "bank-transfer", "receipt", "payout-approval", "payment-run", "free-look", "surrender", "account-withdrawal",
        "unit-withdrawal", "deposit-payee", "term-account-payee", "payout-review", "account-adjustment",
        "field-receipt", "price-adjustment", "fund-price", "rate-declaration", "bonus-declaration", "withholding-rule",
        "manual-journal", "expense-allocation", "engine-run", "year-end-close", "reinsurance-statement",
        "accounting-policy", "period-reopen");

    public ApprovalsView forViewer(String viewerSubject) {
        List<ApprovalSource> mine = sources.stream()
            .filter(s -> Permission.fromCode(s.permission()).map(access::can).orElse(false))
            .sorted(java.util.Comparator.comparingInt(s -> {
                int at = ORDER.indexOf(s.kind());
                return at < 0 ? ORDER.size() : at;
            }))
            .toList();

        List<Read> reads = new ArrayList<>();
        for (ApprovalSource source : mine) {
            try {
                reads.add(new Read(source, source.pending(ROWS_PER_GROUP)));
            } catch (RuntimeException e) {
                log.warn("approvals could not read {}", source.kind(), e);
                reads.add(new Read(source, null));
            }
        }

        Map<String, String> names = names(reads.stream()
            .filter(r -> r.pending() != null)
            .flatMap(r -> r.pending().oldest().stream())
            .map(ApprovalSource.Item::requestedBy)
            .filter(Objects::nonNull)
            .toList());

        List<ApprovalsView.Group> groups = new ArrayList<>();
        long actionable = 0;
        for (Read read : reads) {
            ApprovalSource source = read.source();
            if (read.pending() == null) {
                groups.add(new ApprovalsView.Group(source.kind(), source.label(), source.act(), 0, 0, true, List.of()));
                continue;
            }
            List<ApprovalsView.Row> rows = read.pending().oldest().stream()
                .map(i -> new ApprovalsView.Row(i.reference(), i.policyNumber(), i.summary(),
                    ApprovalsView.Money.of(i.amount(), i.currency()), i.requestedBy(), i.requestedBy() == null ? null : names.get(i.requestedBy()), i.requestedAt(),
                    source.twoPeople() && viewerSubject != null && viewerSubject.equals(i.requestedBy())))
                .toList();
            // Own requests are counted from the rows in hand: a queue longer than one page could hide more of the
            // viewer's own beyond it, so the actionable figure errs high, never low.
            long own = rows.stream().filter(ApprovalsView.Row::yours).count();
            long canAct = Math.max(0, read.pending().total() - own);
            actionable += canAct;
            groups.add(new ApprovalsView.Group(source.kind(), source.label(), source.act(), read.pending().total(),
                canAct, false, rows));
        }
        return new ApprovalsView(actionable, groups);
    }

    /** Names are a courtesy: without them a row still says who asked, by subject, and the page still loads. */
    private Map<String, String> names(List<String> subjects) {
        try {
            return staff.displayNames(subjects);
        } catch (RuntimeException e) {
            log.warn("approvals could not name who asked", e);
            return Map.of();
        }
    }

    private record Read(ApprovalSource source, ApprovalSource.Pending pending) {}
}
