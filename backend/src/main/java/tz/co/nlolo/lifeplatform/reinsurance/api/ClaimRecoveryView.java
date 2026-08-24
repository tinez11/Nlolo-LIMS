package tz.co.nlolo.lifeplatform.reinsurance.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read view of {@code reinsurance.domain.ClaimRecovery}. {@code confirmedAt} is null until staff
 * confirm the reinsurer actually paid. */
public record ClaimRecoveryView(UUID recoveryId, UUID claimId, UUID treatyId,
                                 BigDecimal recoverableAmount, String recoverableCurrency,
                                 Instant confirmedAt) {}
