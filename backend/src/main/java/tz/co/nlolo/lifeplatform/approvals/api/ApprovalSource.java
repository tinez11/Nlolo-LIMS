package tz.co.nlolo.lifeplatform.approvals.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One kind of work waiting for somebody's signature, as the module that owns it sees it.
 *
 * <p>A module implements this as a Spring bean for each kind it holds -- an account withdrawal, a surrender, a fund
 * price. The rows are the module's own, read the way its own screens read them, so a count here can never disagree
 * with the list it opens.
 */
public interface ApprovalSource {

    /** Stable key the console routes on, e.g. {@code account-withdrawal}. */
    String kind();

    /** The group's heading, in the words a finance officer uses: "Account withdrawals". */
    String label();

    /** What acting on one means, as a verb: "Approve", "Review", "Release", "Make the transfer". */
    String act();

    /** The permission whose holder can act; the group is shown only to them. */
    String permission();

    /**
     * Whether the person who asked may not act on their own request. True for every two-person act; false for a queue
     * nobody "asked" for (a bank transfer to make, a receipt to match), where the asker is null anyway.
     */
    default boolean twoPeople() {
        return true;
    }

    /** The oldest {@code limit} waiting, and how many wait in all. */
    Pending pending(int limit);

    /** A two-person source, declared as a bean in the owning module: {@code ApprovalSource.of(...)}. */
    static ApprovalSource of(String kind, String label, String act, String permission,
                             java.util.function.IntFunction<Pending> pending) {
        return of(kind, label, act, permission, true, pending);
    }

    static ApprovalSource of(String kind, String label, String act, String permission, boolean twoPeople,
                             java.util.function.IntFunction<Pending> pending) {
        return new ApprovalSource() {
            @Override public String kind() { return kind; }
            @Override public String label() { return label; }
            @Override public String act() { return act; }
            @Override public String permission() { return permission; }
            @Override public boolean twoPeople() { return twoPeople; }
            @Override public Pending pending(int limit) { return pending.apply(limit); }
        };
    }

    /** A day as the console prints one -- "Oct 6, 2026" -- for a summary line. Never the ISO form. */
    static String day(java.time.LocalDate date) {
        return date == null ? "" : date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy",
            java.util.Locale.ENGLISH));
    }

    record Pending(long total, List<Item> oldest) {

        public static Pending of(List<Item> all, int limit) {
            return new Pending(all.size(), all.stream().limit(limit).toList());
        }
    }

    /**
     * One row waiting.
     *
     * @param reference   the row's own id, which the console routes on with {@code policyNumber}
     * @param policyNumber the policy it belongs to, or null (a fund price, a journal, a run)
     * @param summary     one line saying what it is: "Withdrawal to +255700000009", "EQ-01 price for 26 Oct"
     * @param amount      the money it moves, or null where there is none (a price, an election)
     * @param requestedBy the asker's subject, or null for a queue nobody asked for
     * @param requestedAt when it started waiting
     */
    record Item(String reference, String policyNumber, String summary, BigDecimal amount, String currency,
                String requestedBy, Instant requestedAt) {}
}
