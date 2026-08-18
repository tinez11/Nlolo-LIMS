package tz.co.nlolo.lifeplatform.reinsurance.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The `reinsurance` module's public surface: treaty authoring (staff/finance-only) plus the read
 * paths and the one imperative action -- confirming a recovery -- that back-office staff need.
 *
 * <p>Cession and recovery CALCULATION is not here: both are event-driven
 * ({@code PolicyEventListener}, {@code ClaimEventListener}), because `reinsurance` may call only
 * `refdata` synchronously and therefore learns about policies and claims exclusively by event.
 */
public interface ReinsuranceApi {

    record CreateTreatyRequest(String reinsurerName, TreatyType treatyType,
                                BigDecimal retentionLimitAmount, String retentionLimitCurrency,
                                BigDecimal cessionPercent, LocalDate effectiveFrom, LocalDate effectiveTo) {}

    TreatyView createTreaty(CreateTreatyRequest request, String createdBy);
    TreatyView getTreaty(UUID treatyId);
    List<TreatyView> listTreaties(TreatyStatus status);

    List<CessionView> listCessionsForPolicy(String policyNumber);
    List<ClaimRecoveryView> listRecoveriesForClaim(UUID claimId);

    /** Stamps {@code confirmed_at} and publishes {@code reinsurance.RecoveryConfirmed} -- but only
     * on the genuine transition, so a repeated call emits no second event. */
    ClaimRecoveryView confirmRecovery(UUID recoveryId, String confirmedBy);
}
