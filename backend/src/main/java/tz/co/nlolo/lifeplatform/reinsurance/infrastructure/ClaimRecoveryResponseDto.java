package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.api.ClaimRecoveryView;

import java.time.Instant;
import java.util.UUID;

/** {@code confirmedAt} is null until staff confirm the reinsurer actually paid (see
 * {@code ClaimRecoveryView}'s javadoc). */
public record ClaimRecoveryResponseDto(UUID recoveryId, UUID claimId, UUID treatyId,
                                        MoneyDto recoverableAmount, Instant confirmedAt) {

    public static ClaimRecoveryResponseDto from(ClaimRecoveryView view) {
        return new ClaimRecoveryResponseDto(view.recoveryId(), view.claimId(), view.treatyId(),
            new MoneyDto(view.recoverableAmount().toPlainString(), view.recoverableCurrency()),
            view.confirmedAt());
    }
}
