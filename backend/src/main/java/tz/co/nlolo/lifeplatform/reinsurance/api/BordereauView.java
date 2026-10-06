package tz.co.nlolo.lifeplatform.reinsurance.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One treaty's bordereau for one month (IFRS 17 I3c, posting guide K-01..K-03): the ceded premium and the commission
 * not contingent on claims it posts, and the recoveries recorded that month, which are only matched.
 *
 * <p>{@code bordereauId} and {@code createdAt} are null on a preview, which is computed and not stored; {@code lines}
 * is empty on a list row.
 */
public record BordereauView(UUID bordereauId, UUID treatyId, String period, String currency, int policyCount,
                            BigDecimal premium, BigDecimal commission, BigDecimal recoveries, Instant createdAt,
                            List<Line> lines) {

    /** {@code type}: PREMIUM (one ceded policy), XOL_PREMIUM (the treaty's monthly twelfth), RECOVERY (matched). */
    public record Line(String type, String policyNumber, UUID claimId, BigDecimal premiumShare,
                       BigDecimal policyPremium, BigDecimal premium, BigDecimal commission, BigDecimal recovery) {}
}
