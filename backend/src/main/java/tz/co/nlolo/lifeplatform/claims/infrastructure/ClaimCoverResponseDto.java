package tz.co.nlolo.lifeplatform.claims.infrastructure;

import tz.co.nlolo.lifeplatform.claims.api.ClaimCoverView;

import java.math.BigDecimal;

/**
 * The claim's ceiling, as a browser reads it.
 *
 * <p>Wrapped in an object rather than returned as a bare {@code Money} so the response has room
 * to grow — what the cover is measured from, when it was last recalculated — without every
 * client having to change shape. It grew on credit life (2026-11-02): what the repayment schedule
 * says was still owed, what the lender's statement says, and what was lent. Each null where it
 * does not apply.
 */
public record ClaimCoverResponseDto(MoneyDto claimableCover, MoneyDto scheduledBalance, MoneyDto statementOwed,
                                    MoneyDto loanAmount) {

    public static ClaimCoverResponseDto from(ClaimCoverView view) {
        return new ClaimCoverResponseDto(money(view.amount(), view.currencyCode()),
            money(view.scheduledBalance(), view.currencyCode()), money(view.statementOwed(), view.currencyCode()),
            money(view.loanAmount(), view.currencyCode()));
    }

    private static MoneyDto money(BigDecimal amount, String currency) {
        return amount == null ? null : new MoneyDto(amount.toPlainString(), currency);
    }
}
