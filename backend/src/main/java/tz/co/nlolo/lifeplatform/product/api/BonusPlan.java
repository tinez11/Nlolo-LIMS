package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * A version's with-profits terms (product step 4). {@link #none()} is a non-participating version,
 * which every version published before this step is.
 *
 * @param paidUpParticipates the contract rule (Q3): whether a policy paid-up on a valuation date
 *     still receives that declaration. False unless the version says so.
 */
public record BonusPlan(boolean participating, BonusMethod method, boolean paidUpParticipates,
                        BonusSurrenderBasis surrenderBasis, List<BonusSurrenderRow> surrenderRows) {

    public BonusPlan {
        surrenderRows = surrenderRows != null ? List.copyOf(surrenderRows) : List.of();
    }

    public static BonusPlan none() {
        return new BonusPlan(false, null, false, null, List.of());
    }

    /** OWN_SCALE: the row started most recently at or before these years; zero before the first. */
    public BigDecimal ownScalePerMille(int completedYears) {
        return surrenderRows.stream()
            .filter(r -> r.fromCompletedYears() <= completedYears)
            .max(Comparator.comparingInt(BonusSurrenderRow::fromCompletedYears))
            .map(BonusSurrenderRow::perMille)
            .orElse(BigDecimal.ZERO);
    }
}
