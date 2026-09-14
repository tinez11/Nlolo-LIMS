package tz.co.nlolo.lifeplatform.reinsurance.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

    /**
     * What has been ceded TO one treaty, newest first.
     *
     * <p>Cessions could previously be reached only through the policy they were made on, so a
     * treaty could not show its own book: the platform held 267 cessions, each carrying a
     * {@code treatyId}, and no query joined them to the treaty that accepted them.
     *
     * <p>Paged, unlike {@link #listCessionsForPolicy}. A policy has a handful of cessions; a
     * treaty gains one per policy it covers for as long as it runs.
     */
    Page<CessionView> listCessionsForTreaty(UUID treatyId, Pageable pageable);

    /**
     * The treaty's totals, summed in the database.
     *
     * <p>Separate from the paged list on purpose: a caller must never sum the page in hand and
     * call it the treaty's utilisation. That number is wrong in the way nobody notices -- always
     * too small, always plausible.
     */
    TreatyUtilisationView getTreatyUtilisation(UUID treatyId);
    List<ClaimRecoveryView> listRecoveriesForClaim(UUID claimId);

    /** Stamps {@code confirmed_at} and publishes {@code reinsurance.RecoveryConfirmed} -- but only
     * on the genuine transition, so a repeated call emits no second event. */
    ClaimRecoveryView confirmRecovery(UUID recoveryId, String confirmedBy);
}
