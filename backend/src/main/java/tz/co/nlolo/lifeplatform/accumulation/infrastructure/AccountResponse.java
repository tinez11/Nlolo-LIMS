package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import tz.co.nlolo.lifeplatform.accumulation.api.AccountView;
import tz.co.nlolo.lifeplatform.accumulation.api.EntryType;
import tz.co.nlolo.lifeplatform.accumulation.api.LedgerEntryView;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** A savings account and its whole ledger, in sequence order, with what its ledger adds up to. */
public record AccountResponse(String policyNumber, String status, MoneyResponse balance, String openedOn,
                              String closedReason, String closedOn, Totals totals, List<EntryResponse> entries) {

    /**
     * The ledger summed by what each line did (2026-10-28), so the Account tab can lead with money in, money out,
     * interest and charges without the browser adding anything up. A reversal counts against the line it reverses: a
     * failed withdrawal put back is not money in. Adjustments are in none of the four -- they are
     * corrections, and the ledger shows each one.
     *
     * @param paidIn premiums, top-ups and transfers in
     * @param paidOut withdrawals, surrenders, maturities, death claims, free-look refunds and vesting; positive
     * @param interest interest credited
     * @param charges allocation charges and policy fees; positive
     */
    public record Totals(MoneyResponse paidIn, MoneyResponse paidOut, MoneyResponse interest, MoneyResponse charges) {}

    private static final Set<EntryType> IN = EnumSet.of(EntryType.CONTRIBUTION, EntryType.TOP_UP, EntryType.TRANSFER_IN);
    private static final Set<EntryType> OUT = EnumSet.of(EntryType.WITHDRAWAL, EntryType.SURRENDER, EntryType.MATURITY,
        EntryType.DEATH_CLAIM, EntryType.FREE_LOOK_REFUND, EntryType.VESTING);
    private static final Set<EntryType> CHARGES = EnumSet.of(EntryType.ALLOCATION_CHARGE, EntryType.POLICY_FEE);

    static Totals totals(List<LedgerEntryView> entries, String currency) {
        Map<UUID, EntryType> typeOf = entries.stream()
            .collect(Collectors.toMap(LedgerEntryView::entryId, LedgerEntryView::type, (a, b) -> a));
        Function<Set<EntryType>, BigDecimal> sum = kinds -> entries.stream()
            .filter(e -> kinds.contains(e.type() == EntryType.REVERSAL && e.reversesEntryId() != null
                ? typeOf.getOrDefault(e.reversesEntryId(), EntryType.REVERSAL) : e.type()))
            .map(LedgerEntryView::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Totals(MoneyResponse.of(sum.apply(IN), currency), MoneyResponse.of(sum.apply(OUT).negate(), currency),
            MoneyResponse.of(sum.apply(EnumSet.of(EntryType.INTEREST)), currency),
            MoneyResponse.of(sum.apply(CHARGES).negate(), currency));
    }

    public record EntryResponse(String entryId, int seq, String type, MoneyResponse amount, MoneyResponse balanceAfter,
                                String effectiveDate, String postedAt, String sourceType, String sourceRef,
                                String reversesEntryId, String reason, String createdBy, String approvedBy) {
        static EntryResponse from(LedgerEntryView e, String currency) {
            return new EntryResponse(e.entryId().toString(), e.seq(), e.type().name(), MoneyResponse.of(e.amount(), currency),
                MoneyResponse.of(e.balanceAfter(), currency), e.effectiveDate().toString(), e.postedAt().toString(),
                e.sourceType(), e.sourceRef(), e.reversesEntryId() != null ? e.reversesEntryId().toString() : null,
                e.reason(), e.createdBy(), e.approvedBy());
        }
    }

    static AccountResponse from(AccountView a, List<LedgerEntryView> entries) {
        return new AccountResponse(a.policyNumber(), a.status().name(), MoneyResponse.of(a.balance(), a.currency()),
            a.openedOn().toString(), a.closedReason(), a.closedOn() != null ? a.closedOn().toString() : null,
            totals(entries, a.currency()), entries.stream().map(e -> EntryResponse.from(e, a.currency())).toList());
    }
}
