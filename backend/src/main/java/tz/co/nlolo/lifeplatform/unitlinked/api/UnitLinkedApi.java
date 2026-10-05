package tz.co.nlolo.lifeplatform.unitlinked.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The unit-linked module's surface (product step 6, U1). Task 1: the fund register and its two-person prices.
 */
public interface UnitLinkedApi {

    FundView createFund(CreateFund fund, String createdBy);

    FundView closeFund(String code, String closedBy);

    List<FundView> listFunds();

    FundView getFund(String code);

    /**
     * One price for one fund and date. A move beyond UL_PRICE_MOVE_ALERT_PERCENT from the last approved price
     * needs a {@code moveReason}.
     */
    FundPriceView proposePrice(String fundCode, LocalDate valuationDate, BigDecimal price, String moveReason,
                               String proposedBy);

    /**
     * {@code fund_code,valuation_date,price[,move_reason]} with a header row; all or nothing -- one bad row
     * proposes none of them.
     */
    List<FundPriceView> proposePrices(String csv, String proposedBy);

    /** By someone other than the proposer, and never before the valuation date's cut-off. */
    FundPriceView approvePrice(UUID priceId, String approvedBy);

    FundPriceView withdrawPrice(UUID priceId, String withdrawnBy);

    List<FundPriceView> listPrices(String fundCode, LocalDate from, LocalDate to);

    /** A unit-linked policy's fund split as issued; empty for any other policy. */
    List<AllocationView> allocationOf(String policyNumber);

    record AllocationView(String fundCode, int percent) {}

    /** Holdings, waiting orders and entries; an empty view for a policy with no units. */
    PolicyUnitsView units(String policyNumber);

    /** Orders waiting for a price, per bound date, for one fund: the funds screen's counts. */
    List<WaitingCount> waiting(String fundCode);

    record WaitingCount(LocalDate boundDate, long orders) {}

    /** A corrected price for a date already approved, with its reason; a second person approves it (spec §3). */
    FundPriceView proposeCorrection(UUID approvedPriceId, BigDecimal price, String reason, String proposedBy);

    /** {@code status} OPEN, SETTLED or WAIVED; null for all. */
    List<AdjustmentView> listAdjustments(String status);

    /**
     * Owed to the customer: paid to {@code payeeRef}. Owed by them: recorded as collected, {@code payeeRef} being the
     * reference it was collected under. By someone other than whoever approved the correction.
     */
    AdjustmentView settleAdjustment(UUID adjustmentId, String payeeRef, String settledBy);

    AdjustmentView waiveAdjustment(UUID adjustmentId, String reason, String waivedBy);

    /** Whether this module decides what a death on this policy pays: a unit-linked policy. */
    boolean decidesDeath(String policyNumber);

    /**
     * What the death claim pays, once the units it froze at registration are sold. Throws
     * {@link UnitsNotYetPricedException} until then (spec §7).
     */
    DeathValueView deathValue(java.util.UUID claimId, java.time.LocalDate dateOfDeath);

    /** Staff set the payee of a maturity or lapse payout the policyholder's record could not supply. */
    void payAwaitingExit(String policyNumber, String payeeRef, String by);

    /**
     * Premium redirection (U2, spec §4): where premiums received from now on go. One staff member, audited by its own
     * row; a premium already waiting keeps the split it arrived under, and nothing already bought moves.
     */
    PremiumSplitView redirect(String policyNumber, java.util.List<tz.co.nlolo.lifeplatform.underwriting.api.UnitLinkedChoice.Split> split,
                              String by);

    /** Every split the policy has had, newest first. */
    java.util.List<PremiumSplitView> splitHistory(String policyNumber);

    /**
     * A fund switch (U2, spec §2): one staff member, audited. Both legs are priced on one date, the first after the
     * request on which every involved fund is priced; beyond the version's free switches a year, its fee applies.
     */
    SwitchView requestSwitch(String policyNumber, SwitchInput input, String by);

    java.util.List<SwitchView> listSwitches(String policyNumber);

    /**
     * A partial withdrawal (U2, spec §3), requested by one person: a gross amount from named funds or pro rata, checked
     * against the version's minimums. Nothing is sold until a second person approves.
     */
    WithdrawalView requestWithdrawal(String policyNumber, WithdrawalInput input, String by);

    /** A second person approves; the sale binds at this instant and is priced at the first price after it. */
    WithdrawalView approveWithdrawal(java.util.UUID withdrawalId, String by);

    java.util.List<WithdrawalView> listWithdrawals(String policyNumber);

    /**
     * A top-up (U2, spec §4), once per Idempotency-Key: a repeat of the key answers with the first top-up and collects
     * nothing again. Allocated at the version's top-up percent when payment confirms the money.
     */
    TopUpView requestTopUp(String policyNumber, TopUpInput input, String by, String idempotencyKey);

    java.util.List<TopUpView> listTopUps(String policyNumber);
}
