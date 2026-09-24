package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.ExitRejection;
import tz.co.nlolo.lifeplatform.policy.api.ExitReason;
import tz.co.nlolo.lifeplatform.policy.api.ExitRowView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One row of a lender's exits file, as a browser reads it.
 *
 * <p><b>This DTO did not exist, and the controller returned the domain view straight to the
 * wire.</b> Every sibling endpoint has one — {@code PolicyMemberResponseDto},
 * {@code AwaitingEftResponseDto} — and that hop is precisely where a {@code BigDecimal} becomes
 * {@code {amount, currencyCode}}. Skipping it sent {@code "outstandingBalanceAtExit": 100000}: a
 * bare JSON number, with no currency, where the contract promised Money. The console read it with
 * {@code formatMoney}, which destructures those two fields, and rendered <b>"undefined
 * undefined"</b> on screen.
 *
 * <p>Two rules were broken at once, and the second is the one that matters beyond this screen.
 * Money on this platform never travels as a JSON number — a decimal that survives Postgres and
 * Java exactly should not become a double on the last hop — and an amount with no currency is not
 * money at all, merely a quantity.
 *
 * <p>The number came from an XLSX numeric cell, read correctly all the way through the parser and
 * the judge, and lost its type only on the way out.
 */
public record ExitRowResponseDto(int lineNumber, String memberReference, LocalDate exitDate,
                                  ExitReason exitReason, MoneyDto outstandingBalanceAtExit,
                                  String outcome, ExitRejection reasonCode, String reason,
                                  UUID policyMemberId) {

    public static ExitRowResponseDto from(ExitRowView view) {
        return new ExitRowResponseDto(view.lineNumber(), view.memberReference(), view.exitDate(),
            view.exitReason(), money(view.outstandingBalanceAtExit(), view.currency()),
            view.outcome(), view.reasonCode(), view.reason(), view.policyMemberId());
    }

    public static List<ExitRowResponseDto> from(List<ExitRowView> views) {
        return views.stream().map(ExitRowResponseDto::from).toList();
    }

    /**
     * Null stays null: a lender may legitimately not track the balance, and rendering a zero
     * there would state that nothing was owed.
     */
    private static MoneyDto money(BigDecimal amount, String currency) {
        return amount != null ? new MoneyDto(amount.toPlainString(), currency) : null;
    }
}
