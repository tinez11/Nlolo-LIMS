package tz.co.nlolo.lifeplatform.payment.infrastructure;

import tz.co.nlolo.lifeplatform.payment.api.DisbursementStatusView;

import java.time.Instant;
import java.util.UUID;

/**
 * One payout, as the page that ORDERED it reads it back -- a claim reading its own settlement.
 *
 * <p>Deliberately carries no {@code executedBy}. That column holds the finance officer's
 * identity-provider subject, and printing a uuid as "recorded by" is the defect the claims pages
 * have just been cleared of. {@code executedAt} says the part a claims manager needs: that it
 * was done, and when.
 *
 * @param reference the gateway's reference on mobile money, the bank's own reference on an EFT
 *     once finance records it; null until then
 */
public record DisbursementResponseDto(UUID disbursementId, String method, String status, MoneyDto amount,
                                       String payeeRef, String reference, Instant createdAt,
                                       Instant executedAt) {

    public static DisbursementResponseDto from(DisbursementStatusView view) {
        return new DisbursementResponseDto(view.disbursementId(), view.method(), view.status().name(),
            new MoneyDto(view.amount().toPlainString(), view.currency()), view.payeeRef(),
            view.gatewayReference(), view.createdAt(), view.executedAt());
    }
}
