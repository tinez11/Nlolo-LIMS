package tz.co.nlolo.lifeplatform.claims.infrastructure;

import tz.co.nlolo.lifeplatform.claims.api.ClaimCoverView;

/**
 * The claim's ceiling, as a browser reads it.
 *
 * <p>Wrapped in an object rather than returned as a bare {@code Money} so the response has room
 * to grow — what the cover is measured from, when it was last recalculated — without every
 * client having to change shape.
 */
public record ClaimCoverResponseDto(MoneyDto claimableCover) {

    public static ClaimCoverResponseDto from(ClaimCoverView view) {
        return new ClaimCoverResponseDto(
            new MoneyDto(view.amount().toPlainString(), view.currencyCode()));
    }
}
