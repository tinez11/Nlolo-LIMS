package tz.co.nlolo.lifeplatform.approvals.api;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * One kind of job waiting for somebody to act, as the module that owns it sees it (2026-11-03, the user: notify on
 * needed actions, not on what has been done).
 *
 * <p>Read live from the module's own records, like {@link ApprovalSource}: a returned proposal is a job for exactly as
 * long as it stays returned, so a job leaves the bell the moment anybody does it, with nothing to mark read and nothing
 * to keep in step.
 */
public interface ActionSource {

    /** Stable key the console routes on, e.g. {@code proposal-returned}. */
    String kind();

    /** The staff permission whose holders are asked; null for an agent's own work. */
    String permission();

    /** What acting on one means, as the viewer is asked it: "Review it", "Send clearer documents". */
    String act();

    /** The newest {@code limit} waiting for this viewer, and how many wait in all. */
    Pending waiting(Viewer viewer, int limit);

    /**
     * Who is asking.
     *
     * @param subject the sign-in's subject -- the staff member, or the agent, whose clients are theirs by it
     * @param partyId an agent's own party id, from their token; null for staff
     */
    record Viewer(String subject, UUID partyId) {}

    record Pending(long total, List<Item> newest) {

        /** The newest {@code limit} of {@code all}. */
        public static Pending of(List<Item> all, int limit) {
            return new Pending(all.size(), all.stream()
                .sorted(Comparator.comparing(Item::since, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit).toList());
        }
    }

    /**
     * One job.
     *
     * @param reference    the record's own id, which the console routes on with {@code policyNumber}
     * @param policyNumber the policy it is about, or null
     * @param title        who it is about, read first: "Asha Mollel"
     * @param detail       what it is, short: "Proposal PRO-0012 returned"
     * @param since        when it started waiting
     */
    record Item(String reference, String policyNumber, String title, String detail, Instant since) {}

    /** Work a staff member with {@code permission} is asked to do. */
    static ActionSource forStaff(String kind, String permission, String act,
                                 BiFunction<Viewer, Integer, Pending> waiting) {
        return of(kind, permission, act, waiting);
    }

    /** Work an agent is asked to do for their own clients. */
    static ActionSource forAgents(String kind, String act, BiFunction<Viewer, Integer, Pending> waiting) {
        return of(kind, null, act, waiting);
    }

    private static ActionSource of(String kind, String permission, String act,
                                   BiFunction<Viewer, Integer, Pending> waiting) {
        return new ActionSource() {
            @Override public String kind() { return kind; }
            @Override public String permission() { return permission; }
            @Override public String act() { return act; }
            @Override public Pending waiting(Viewer viewer, int limit) { return waiting.apply(viewer, limit); }
        };
    }
}
