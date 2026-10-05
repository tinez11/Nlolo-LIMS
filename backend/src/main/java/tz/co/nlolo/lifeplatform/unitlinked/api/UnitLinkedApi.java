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
}
