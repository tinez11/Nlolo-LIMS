package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.unitlinked.api.FundNotFoundException;
import tz.co.nlolo.lifeplatform.unitlinked.api.StatementData;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitStatementView;
import tz.co.nlolo.lifeplatform.unitlinked.domain.BindingRule;
import tz.co.nlolo.lifeplatform.unitlinked.domain.Fund;
import tz.co.nlolo.lifeplatform.unitlinked.domain.FundPrice;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitArithmetic;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitEntry;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitStatement;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundPriceRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.UnitEntryRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.UnitStatementRepository;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Unit statements (U2, spec §6, Q9 = C): the calendar-year statement every unit-linked policy gets, plus one on demand
 * for any period staff ask for -- the second adds to the first, never replaces it. Built from the unit ledger alone;
 * each position priced at the latest approved price ON OR BEFORE its day (plan D4) and shown with that price's date,
 * a fund with no price yet shown as exactly that.
 */
@Component
class Statements {

    static final String INSURER = "Nlolo Life";

    /** Money the customer put in, and money paid out to them: a switch's legs are neither, they stay invested. */
    private static final Set<UnitEntry.Type> PAID_OUT = EnumSet.of(UnitEntry.Type.DEATH_SALE, UnitEntry.Type.SURRENDER_SALE,
        UnitEntry.Type.MATURITY_SALE, UnitEntry.Type.LAPSE_SALE, UnitEntry.Type.FREE_LOOK_SALE, UnitEntry.Type.WITHDRAWAL_SALE);
    private static final Set<UnitEntry.Type> CHARGES = EnumSet.of(UnitEntry.Type.ALLOCATION_CHARGE, UnitEntry.Type.POLICY_FEE,
        UnitEntry.Type.COST_OF_INSURANCE, UnitEntry.Type.SWITCH_FEE, UnitEntry.Type.SURRENDER_CHARGE);

    private final UnitEntryRepository entries;
    private final FundRepository funds;
    private final FundPriceRepository prices;
    private final UnitStatementRepository statements;
    private final DocumentApi documentApi;
    private final PolicyApi policyApi;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    Statements(UnitEntryRepository entries, FundRepository funds, FundPriceRepository prices,
               UnitStatementRepository statements, DocumentApi documentApi, PolicyApi policyApi,
               ApplicationEventPublisher events, @Qualifier("unitLinkedClock") Clock clock) {
        this.entries = entries;
        this.funds = funds;
        this.prices = prices;
        this.statements = statements;
        this.documentApi = documentApi;
        this.policyApi = policyApi;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    StatementData build(String policyNumber, LocalDate from, LocalDate to) {
        UUID tenantId = TenantContext.get();
        PolicyView policy = policyApi.getPolicy(policyNumber);
        Map<UUID, Fund> held = new LinkedHashMap<>();
        for (Object[] row : entries.holdings(tenantId, policyNumber)) {
            UUID fundId = (UUID) row[0];
            funds.findByTenantIdAndFundId(tenantId, fundId).ifPresent(f -> held.put(fundId, f));
        }
        List<Fund> ordered = held.values().stream().sorted(Comparator.comparing(Fund::getCode)).toList();
        List<StatementData.Position> opening = new ArrayList<>();
        List<StatementData.Position> closing = new ArrayList<>();
        for (Fund f : ordered) {
            opening.add(position(tenantId, policyNumber, f, from.minusDays(1)));
            closing.add(position(tenantId, policyNumber, f, to));
        }
        Map<UUID, String> codes = new HashMap<>();
        held.forEach((id, f) -> codes.put(id, f.getCode()));
        List<StatementData.Line> lines = new ArrayList<>();
        BigDecimal paidIn = BigDecimal.ZERO.setScale(2);
        BigDecimal paidOut = BigDecimal.ZERO.setScale(2);
        Map<String, BigDecimal> charges = new LinkedHashMap<>();
        for (UnitEntry e : entries.findByTenantIdAndPolicyNumberAndValuationDateBetweenOrderByValuationDateAscCreatedAtAsc(
                tenantId, policyNumber, from, to)) {
            UnitEntry.Type type = e.getType();
            lines.add(new StatementData.Line(e.getValuationDate(), type.name(),
                e.getFundId() == null ? null : codes.getOrDefault(e.getFundId(), "?"),
                type.moneyOnly() ? null : e.getUnits(), e.getPrice(), e.getAmount()));
            if (type == UnitEntry.Type.ALLOCATION) {
                paidIn = paidIn.add(e.getAmount());
            } else if (PAID_OUT.contains(type)) {
                paidOut = paidOut.add(e.getAmount().negate());
            } else if (CHARGES.contains(type)) {
                charges.merge(type.name(), e.getAmount().negate(), BigDecimal::add);
            } else if (type == UnitEntry.Type.CHARGE_REFUND) {
                charges.merge(UnitEntry.Type.ALLOCATION_CHARGE.name(), e.getAmount().negate(), BigDecimal::add);
            }
        }
        return new StatementData(policyNumber, from, to, opening, closing, lines, paidIn, charges, paidOut,
            policy.premiumCurrency());
    }

    /** The units at the end of {@code day}, at that day's price -- or none, if the fund had no approved price by then. */
    private StatementData.Position position(UUID tenantId, String policyNumber, Fund fund, LocalDate day) {
        BigDecimal units = entries.sumUnitsAsOf(tenantId, policyNumber, fund.getFundId(), day);
        // findLatestApprovedBefore is strictly before (plan D4): the day's own price counts.
        Optional<FundPrice> price = prices.findLatestApprovedBefore(tenantId, fund.getFundId(), day.plusDays(1));
        return price.map(p -> new StatementData.Position(fund.getCode(), units, p.getPrice(), p.getValuationDate(),
                UnitArithmetic.proceeds(units, p.getPrice())))
            .orElseGet(() -> new StatementData.Position(fund.getCode(), units, null, null, null));
    }

    /** Builds, renders and files one statement, and tells the policyholder when it is the yearly one. */
    @Transactional
    UnitStatementView file(String policyNumber, LocalDate from, LocalDate to, UnitStatement.Kind kind, String by) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("A statement needs the first and last day of its period");
        }
        if (to.isAfter(BindingRule.civilDate(clock.instant()))) {
            throw new IllegalArgumentException("A statement period ends today at the latest");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("A statement period starts on or before its last day");
        }
        UUID tenantId = TenantContext.get();
        PolicyView policy = policyApi.getPolicy(policyNumber);
        StatementData data = build(policyNumber, from, to);
        byte[] pdf = StatementPdf.render(data, INSURER);
        // The upload is the one side effect a rollback cannot undo (accumulation's statements make the same trade).
        String ref = documentApi.upload("policy:" + policyNumber, DocumentType.ACCOUNT_STATEMENT, by,
            new ByteArrayInputStream(pdf), pdf.length, "application/pdf",
            "unit-statement-" + policyNumber + "-" + from + "-" + to + ".pdf");
        UnitStatement saved = statements.save(new UnitStatement(tenantId, policyNumber, from, to, kind, ref, by,
            clock.instant()));
        LocalDate priceDate = StatementData.priceDateOf(data.closing());
        Map<String, Object> payload = new HashMap<>();
        payload.put("statementId", saved.getStatementId().toString());
        payload.put("policyNumber", policyNumber);
        payload.put("policyholderPartyId", policy.policyholderPartyId().toString());
        payload.put("annual", kind == UnitStatement.Kind.ANNUAL);
        payload.put("year", to.getYear());
        payload.put("periodFrom", from.toString());
        payload.put("periodTo", to.toString());
        payload.put("closingValue", StatementData.valueOf(data.closing()).toPlainString());
        payload.put("currencyCode", data.currency());
        payload.put("priceDate", priceDate == null ? null : priceDate.toString());
        events.publishEvent(DomainEventEnvelope.of("unitlinked.StatementIssued", tenantId, payload));
        return view(saved);
    }

    /**
     * Last calendar year's statement for one policy, once: only a policy that held units at some point of that year,
     * and none already filed to its 31 December. Returns whether one was filed.
     */
    @Transactional
    boolean fileAnnual(String policyNumber, LocalDate today) {
        UUID tenantId = TenantContext.get();
        LocalDate yearEnd = today.withDayOfYear(1).minusDays(1);
        LocalDate yearStart = yearEnd.withDayOfYear(1);
        if (statements.existsByTenantIdAndPolicyNumberAndKindAndPeriodTo(tenantId, policyNumber,
                UnitStatement.Kind.ANNUAL.name(), yearEnd)
            || !entries.existsByTenantIdAndPolicyNumberAndValuationDateLessThanEqual(tenantId, policyNumber, yearEnd)) {
            return false;
        }
        boolean heldAtStart = entries.holdings(tenantId, policyNumber).stream()
            .anyMatch(row -> entries.sumUnitsAsOf(tenantId, policyNumber, (UUID) row[0], yearStart.minusDays(1)).signum() != 0);
        boolean movedInYear = !entries.findByTenantIdAndPolicyNumberAndValuationDateBetweenOrderByValuationDateAscCreatedAtAsc(
            tenantId, policyNumber, yearStart, yearEnd).isEmpty();
        if (!heldAtStart && !movedInYear) {
            return false; // emptied before the year began: there is nothing to tell
        }
        file(policyNumber, yearStart, yearEnd, UnitStatement.Kind.ANNUAL, UnitLedger.SYSTEM);
        return true;
    }

    @Transactional(readOnly = true)
    List<UnitStatementView> list(String policyNumber) {
        return statements.findByTenantIdAndPolicyNumberOrderByPeriodToDescGeneratedAtDesc(TenantContext.get(), policyNumber)
            .stream().map(Statements::view).toList();
    }

    @Transactional(readOnly = true)
    byte[] pdf(UUID statementId) {
        UnitStatement s = statements.findByTenantIdAndStatementId(TenantContext.get(), statementId)
            .orElseThrow(() -> new FundNotFoundException("Statement " + statementId));
        return documentApi.download(s.getDocumentRef());
    }

    private static UnitStatementView view(UnitStatement s) {
        return new UnitStatementView(s.getStatementId(), s.getPolicyNumber(), s.getPeriodFrom(), s.getPeriodTo(),
            s.getKind(), s.getGeneratedBy(), s.getGeneratedAt());
    }
}
