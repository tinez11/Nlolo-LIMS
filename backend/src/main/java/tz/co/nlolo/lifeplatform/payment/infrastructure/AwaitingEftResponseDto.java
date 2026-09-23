package tz.co.nlolo.lifeplatform.payment.infrastructure;

import tz.co.nlolo.lifeplatform.payment.api.DisbursementStatusView;

import java.time.Instant;
import java.util.UUID;

/**
 * One bank transfer somebody has to go and make.
 *
 * <p><b>Its own shape rather than {@link PaymentStatusResponseDto}</b>, which is the generic
 * "what is the status of this thing" envelope and carries an id, a kind, a status and an amount.
 * That answers a question about a payment somebody already knows about. This queue is the
 * opposite: nothing else tells a finance officer these transfers exist, so the row has to carry
 * everything the act needs — who is being paid, what for, how much, and since when.
 *
 * <p>{@code payeeRef} is the payee as the ordering module recorded them, not a resolved name.
 * The platform has no party-name lookup on this path and inventing one here would be worse than
 * printing the reference the claim itself was settled against.
 *
 * <p>{@code waitingSince} is not decoration. Every row in this queue is money the insurer owes
 * and has not paid; an ageing payout is a complaint, and on a credit-life claim it is a lender
 * still carrying a dead borrower's loan on their own book.
 */
public record AwaitingEftResponseDto(UUID disbursementId, MoneyDto amount, String payeeRef,
                                      String purpose, String sourceRef, Instant waitingSince) {

    public static AwaitingEftResponseDto from(DisbursementStatusView view) {
        return new AwaitingEftResponseDto(view.disbursementId(),
            new MoneyDto(view.amount().toPlainString(), view.currency()),
            view.payeeRef(), view.purpose(), view.sourceRef(), view.createdAt());
    }
}
