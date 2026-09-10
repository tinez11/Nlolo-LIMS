package tz.co.nlolo.lifeplatform.underwriting.domain;

import java.math.BigDecimal;

/**
 * What the engine concluded, and what it rated the applicant at.
 *
 * <p>{@code ratingMultiplier} is the combined product.rating_table multiplier the engine
 * actually used (age band x sum assured band, rounded to the 4dp the table itself stores).
 * Returned rather than recomputed by the caller so the number recorded on the case is
 * provably the number the decision was reached with -- and so it can reach the premium,
 * which until now it never did.
 *
 * <p>Populated on EVERY outcome, including DECLINED and POSTPONED. A declined case was still
 * rated, and the rating is what explains the decline.
 */
public record UnderwritingDecision(Outcome outcome, BigDecimal loadingPercent, String reason,
                                    BigDecimal ratingMultiplier) {
    public enum Outcome { ACCEPT, LOADED, DECLINED, POSTPONED }
}
