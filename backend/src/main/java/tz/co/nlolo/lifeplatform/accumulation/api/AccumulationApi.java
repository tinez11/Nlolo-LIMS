package tz.co.nlolo.lifeplatform.accumulation.api;

import java.util.List;
import java.util.Optional;

/**
 * The accumulation engine's published face (product step 3). It OWNS an account's balance and every
 * transaction that changes it: other modules ask through here or by event, and nothing else writes
 * the balance. Tenant from {@code TenantContext} throughout. Tasks 3-8 add the acting methods.
 */
public interface AccumulationApi {

    /** Empty for a policy on a SCALE version -- every policy sold before this step. */
    Optional<AccountView> findAccount(String policyNumber);

    boolean isAccount(String policyNumber);

    /** Every entry, in sequence order. Immutable history: corrections appear as REVERSAL entries. */
    List<LedgerEntryView> entries(String policyNumber);

    /** ADMIN proposes; a DIFFERENT ADMIN or a FINANCE_OFFICER approves (spec §10.11). */
    RateDeclarationView proposeRate(java.util.UUID productId, java.math.BigDecimal ratePercent,
                                    java.time.LocalDate effectiveFrom, String proposedBy);

    /**
     * Refused when the proposer approves, and when the rate would take effect on or before interest
     * already credited on any account of the product -- checked again here, because the month-end
     * run may have passed the date since the proposal.
     */
    RateDeclarationView approveRate(java.util.UUID declarationId, String approvedBy);

    /** Only a PROPOSED rate: an approved one may already have earned interest. */
    RateDeclarationView withdrawRate(java.util.UUID declarationId, String withdrawnBy);

    /** Newest effective date first. */
    List<RateDeclarationView> listRates(java.util.UUID productId);
}
