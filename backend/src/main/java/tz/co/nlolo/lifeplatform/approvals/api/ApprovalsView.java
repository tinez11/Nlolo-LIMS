package tz.co.nlolo.lifeplatform.approvals.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Everything waiting for the viewer's signature, by kind, oldest first.
 *
 * @param actionable how many the viewer can act on now -- the sidebar's number. Their own requests wait for someone
 *                   else, so they are listed and not counted.
 */
public record ApprovalsView(long actionable, List<Group> groups) {

    /**
     * @param total      every row of this kind waiting, the viewer's own included
     * @param actionable those the viewer can act on: total less their own requests (on a two-person kind)
     * @param unreadable true when the owning module could not be read: the group is shown as unknown, never as 0
     */
    public record Group(String kind, String label, String act, long total, long actionable, boolean unreadable,
                        List<Row> rows) {}

    /**
     * @param amount null where the row moves no money (a price, an election)
     * @param yours  the viewer asked for this, so somebody else must sign it
     */
    public record Row(String reference, String policyNumber, String summary, Money amount, String requestedBy,
                      String requestedByName, Instant requestedAt, boolean yours) {}

    /** The platform's money: a decimal string and its currency, never a JSON number. */
    public record Money(String amount, String currencyCode) {

        /** Working currency where the owning row carries none -- every account on the platform is in TZS. */
        public static Money of(BigDecimal amount, String currency) {
            return amount == null ? null
                : new Money(amount.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString(),
                    currency == null ? "TZS" : currency);
        }
    }
}
