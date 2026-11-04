package tz.co.nlolo.lifeplatform.approvals.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.approvals.api.ActionSource;
import tz.co.nlolo.lifeplatform.approvals.api.ActionsView;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalsView;
import tz.co.nlolo.lifeplatform.iam.api.CurrentAccess;
import tz.co.nlolo.lifeplatform.iam.api.Permission;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Gathers the jobs waiting for the viewer from every {@link ActionSource} they answer to (2026-11-03): an agent's own
 * work, or for staff the kinds their permissions act on, and the signatures the approvals screen holds for them.
 *
 * <p>A source that fails is skipped and the view says it is incomplete: "nothing waiting" and "could not ask" must not
 * look alike.
 */
@Service
public class Actions {

    private static final Logger log = LoggerFactory.getLogger(Actions.class);

    /** Jobs each kind lists; its total is always every job. */
    static final int PER_SOURCE = 20;

    private final List<ActionSource> sources;
    private final CurrentAccess access;
    private final Approvals approvals;

    public Actions(List<ActionSource> sources, CurrentAccess access, Approvals approvals) {
        this.sources = sources;
        this.access = access;
        this.approvals = approvals;
    }

    public ActionsView forViewer(ActionSource.Viewer viewer, boolean agent) {
        long total = 0;
        boolean incomplete = false;
        List<ActionsView.Group> groups = new ArrayList<>();
        for (ActionSource source : sources) {
            if (agent ? source.permission() != null : !mayAct(source)) continue;
            try {
                ActionSource.Pending pending = source.waiting(viewer, PER_SOURCE);
                if (pending.total() == 0) continue;
                total += pending.total();
                groups.add(new ActionsView.Group(source.kind(), source.act(), pending.total(), pending.newest().stream()
                    .map(i -> new ActionsView.Item(i.reference(), i.policyNumber(), i.title(), i.detail(), i.since()))
                    .toList()));
            } catch (RuntimeException e) {
                log.warn("actions could not read {}", source.kind(), e);
                incomplete = true;
            }
        }
        if (!agent) {
            // The approvals screen's own count, less the viewer's own requests: one row per kind it lists, each
            // opening that screen.
            try {
                ApprovalsView signatures = approvals.forViewer(viewer.subject());
                if (signatures.actionable() > 0) {
                    total += signatures.actionable();
                    List<ActionsView.Item> kinds = signatures.groups().stream().filter(g -> g.actionable() > 0)
                        .map(g -> new ActionsView.Item("approvals", null, g.label(), g.actionable() + " waiting",
                            g.rows().stream().filter(r -> !r.yours()).map(ApprovalsView.Row::requestedAt)
                                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null)))
                        .sorted(NEWEST_FIRST).toList();
                    groups.add(new ActionsView.Group("signatures", "Sign them", signatures.actionable(), kinds));
                }
                incomplete |= signatures.groups().stream().anyMatch(ApprovalsView.Group::unreadable);
            } catch (RuntimeException e) {
                log.warn("actions could not read the approvals", e);
                incomplete = true;
            }
        }
        // The kind with the newest job first: new work rises to the top.
        groups.sort(Comparator.comparing(
            (ActionsView.Group g) -> g.items().isEmpty() ? null : g.items().get(0).since(),
            Comparator.nullsLast(Comparator.reverseOrder())));
        return new ActionsView(total, incomplete, groups);
    }

    private static final Comparator<ActionsView.Item> NEWEST_FIRST =
        Comparator.comparing(ActionsView.Item::since, Comparator.nullsLast(Comparator.reverseOrder()));

    private boolean mayAct(ActionSource source) {
        return source.permission() != null && Permission.fromCode(source.permission()).map(access::can).orElse(false);
    }
}
