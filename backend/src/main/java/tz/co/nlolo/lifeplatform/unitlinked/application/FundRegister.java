package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import tz.co.nlolo.lifeplatform.unitlinked.api.CreateFund;
import tz.co.nlolo.lifeplatform.unitlinked.api.FundNotFoundException;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedStateException;
import tz.co.nlolo.lifeplatform.unitlinked.domain.Fund;
import tz.co.nlolo.lifeplatform.unitlinked.domain.FundPrice;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundPriceRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The fund register and its prices (spec §3): funds created and closed, prices proposed by one person and
 * approved by a second -- never before the date's cut-off, never out of date order, never edited. Nothing here
 * ever values anything at a zero, missing or carried-forward price; the pricing run (Task 4) only ever prices
 * at an APPROVED one.
 */
@Component
class FundRegister {

    static final String MOVE_ALERT_KEY = "UL_PRICE_MOVE_ALERT_PERCENT";
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,19}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Set<String> ASSET_CLASSES = Set.of("EQUITY", "BOND", "MONEY_MARKET", "BALANCED");

    private final FundRepository funds;
    private final FundPriceRepository prices;
    private final ReferenceDataApi referenceData;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final PricingRun pricingRun;

    FundRegister(FundRepository funds, FundPriceRepository prices, ReferenceDataApi referenceData,
                 ApplicationEventPublisher events, @Qualifier("unitLinkedClock") Clock clock, PricingRun pricingRun) {
        this.funds = funds;
        this.prices = prices;
        this.referenceData = referenceData;
        this.events = events;
        this.clock = clock;
        this.pricingRun = pricingRun;
    }

    @Transactional
    Fund create(CreateFund in, String by) {
        UUID tenantId = TenantContext.get();
        String code = in.code() == null ? "" : in.code().trim().toUpperCase();
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("A fund code is 2 to 20 capital letters, digits, '-' or '_', starting"
                + " with a letter or digit");
        }
        if (in.name() == null || in.name().isBlank()) {
            throw new IllegalArgumentException("A fund needs a name");
        }
        if (in.currency() == null || !CURRENCY.matcher(in.currency()).matches()) {
            throw new IllegalArgumentException("A fund's currency is a 3-letter code like TZS");
        }
        if (in.assetClass() == null || !ASSET_CLASSES.contains(in.assetClass())) {
            throw new IllegalArgumentException("A fund's asset class is one of " + ASSET_CLASSES);
        }
        BigDecimal charge = in.annualManagementChargePercent();
        if (charge == null || charge.signum() < 0 || charge.compareTo(BigDecimal.valueOf(100)) >= 0) {
            throw new IllegalArgumentException("A fund's annual management charge is a percent from 0 to under 100");
        }
        if (in.cutOffTime() == null) {
            throw new IllegalArgumentException("A fund needs a daily cut-off time");
        }
        if (funds.findByTenantIdAndCode(tenantId, code).isPresent()) {
            throw new UnitLinkedStateException("Fund " + code + " already exists; a fund code is never reused");
        }
        return funds.save(new Fund(tenantId, code, in.name().trim(), in.currency(), in.assetClass(), charge,
            in.cutOffTime(), by, clock.instant()));
    }

    @Transactional
    Fund close(String code, String by) {
        Fund fund = fund(code);
        fund.close(by, clock.instant());
        return funds.save(fund);
    }

    @Transactional
    FundPrice propose(String fundCode, LocalDate valuationDate, BigDecimal price, String moveReason, String by) {
        UUID tenantId = TenantContext.get();
        Fund fund = fund(fundCode);
        if (valuationDate == null) {
            throw new IllegalArgumentException("A fund price needs its valuation date");
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("A fund price must be greater than zero");
        }
        if (prices.findApproved(tenantId, fund.getFundId(), valuationDate).isPresent()) {
            throw new UnitLinkedStateException("Fund " + fund.getCode() + " already has an approved price for "
                + valuationDate + "; correct it rather than proposing another");
        }
        if (prices.findProposed(tenantId, fund.getFundId(), valuationDate).isPresent()) {
            throw new UnitLinkedStateException("A price for " + fund.getCode() + " on " + valuationDate
                + " is already waiting for approval");
        }
        requireReasonForALargeMove(tenantId, fund, valuationDate, price, moveReason);
        return prices.save(FundPrice.propose(tenantId, fund.getFundId(), valuationDate, price, moveReason, null, by,
            clock.instant()));
    }

    /** All rows parse and check first; only then is any proposed -- one bad row proposes nothing. */
    @Transactional
    List<FundPrice> proposeAll(String csv, String by) {
        if (csv == null || csv.isBlank()) {
            throw new IllegalArgumentException("The file is empty");
        }
        String[] lines = csv.strip().split("\\r?\\n");
        String header = lines[0].trim().toLowerCase();
        if (!header.startsWith("fund_code,valuation_date,price")) {
            throw new IllegalArgumentException("The first row must be the header fund_code,valuation_date,price[,move_reason]");
        }
        record Row(String code, LocalDate date, BigDecimal price, String reason) {}
        List<Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cells = line.split(",", 4);
            if (cells.length < 3) {
                throw new IllegalArgumentException("Row " + i + ": expected fund_code,valuation_date,price");
            }
            LocalDate date;
            BigDecimal price;
            try {
                date = LocalDate.parse(cells[1].trim());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Row " + i + ": '" + cells[1].trim() + "' is not a date like 2026-11-02");
            }
            try {
                price = new BigDecimal(cells[2].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Row " + i + ": '" + cells[2].trim() + "' is not a price");
            }
            String code = cells[0].trim().toUpperCase();
            if (!seen.add(code + "@" + date)) {
                throw new IllegalArgumentException("Row " + i + ": " + code + " on " + date + " appears twice");
            }
            rows.add(new Row(code, date, price, cells.length == 4 ? cells[3].trim() : null));
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("The file has a header but no prices");
        }
        List<FundPrice> proposed = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            try {
                proposed.add(propose(r.code(), r.date(), r.price(), r.reason(), by));
            } catch (IllegalArgumentException | UnitLinkedStateException | FundNotFoundException e) {
                // The transaction rolls back every row already proposed: all or nothing.
                throw new IllegalArgumentException("Row " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        return proposed;
    }

    @Transactional
    FundPrice approve(UUID priceId, String by) {
        UUID tenantId = TenantContext.get();
        FundPrice price = prices.findByTenantIdAndPriceId(tenantId, priceId)
            .orElseThrow(() -> new FundNotFoundException("Price " + priceId));
        Fund fund = funds.findByTenantIdAndFundId(tenantId, price.getFundId()).orElseThrow();
        if (price.getSupersedesPriceId() == null) {
            // Prices are approved in date order. An order bound to a date with no price is priced by the first
            // approved price after it (spec §5), so approving an EARLIER date afterwards would leave orders that
            // were meant for it already priced at the later one. A correction (Task 5) is the way to fix a date.
            Optional<FundPrice> later = prices.findApprovedAfter(tenantId, fund.getFundId(), price.getValuationDate(),
                Limit.of(1)).stream().findFirst();
            if (later.isPresent()) {
                throw new UnitLinkedStateException("A price for " + fund.getCode() + " on " + price.getValuationDate()
                    + " cannot be approved after the one for " + later.get().getValuationDate()
                    + ": orders waiting on " + price.getValuationDate() + " were already priced at it");
            }
        }
        price.approve(by, clock.instant(), fund.getCode(), fund.getCutOffTime());
        prices.saveAndFlush(price);
        // In the same transaction: the price and everything it prices commit together or not at all.
        pricingRun.onApproved(fund, price);
        Map<String, Object> payload = new HashMap<>();
        payload.put("priceId", price.getPriceId().toString());
        payload.put("fundId", fund.getFundId().toString());
        payload.put("fundCode", fund.getCode());
        payload.put("valuationDate", price.getValuationDate().toString());
        payload.put("price", price.getPrice().toPlainString());
        payload.put("currencyCode", fund.getCurrency());
        payload.put("approvedBy", by);
        events.publishEvent(DomainEventEnvelope.of("unitlinked.PriceApproved", tenantId, payload));
        return price;
    }

    @Transactional
    FundPrice withdraw(UUID priceId, String by) {
        UUID tenantId = TenantContext.get();
        FundPrice price = prices.findByTenantIdAndPriceId(tenantId, priceId)
            .orElseThrow(() -> new FundNotFoundException("Price " + priceId));
        Fund fund = funds.findByTenantIdAndFundId(tenantId, price.getFundId()).orElseThrow();
        price.withdraw(by, fund.getCode());
        return prices.save(price);
    }

    Fund fund(String code) {
        String c = code == null ? "" : code.trim().toUpperCase();
        return funds.findByTenantIdAndCode(TenantContext.get(), c).orElseThrow(() -> new FundNotFoundException("Fund " + c));
    }

    Instant now() {
        return clock.instant();
    }

    /** A prompt, not a block: markets jump, but a typo looks exactly like a jump (spec §3). */
    private void requireReasonForALargeMove(UUID tenantId, Fund fund, LocalDate date, BigDecimal price, String reason) {
        Optional<FundPrice> previous = prices.findLatestApprovedBefore(tenantId, fund.getFundId(), date);
        if (previous.isEmpty() || (reason != null && !reason.isBlank())) {
            return;
        }
        BigDecimal alert = new BigDecimal(referenceData.getValue(MOVE_ALERT_KEY, "TZ"));
        BigDecimal movePercent = price.divide(previous.get().getPrice(), 10, RoundingMode.HALF_EVEN)
            .subtract(BigDecimal.ONE).abs().multiply(BigDecimal.valueOf(100));
        if (movePercent.compareTo(alert) > 0) {
            throw new IllegalArgumentException("A move of " + movePercent.setScale(2, RoundingMode.HALF_EVEN) + "% from "
                + previous.get().getPrice().stripTrailingZeros().toPlainString() + " on " + previous.get().getValuationDate()
                + " needs a reason");
        }
    }
}
