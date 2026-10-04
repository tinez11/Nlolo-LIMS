package tz.co.nlolo.lifeplatform.accumulation.application;

import tz.co.nlolo.lifeplatform.accumulation.api.EntryType;
import tz.co.nlolo.lifeplatform.accumulation.api.LedgerEntryView;
import tz.co.nlolo.lifeplatform.accumulation.api.StatementView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A period of an account, from its entries alone. Pure, so the console and the PDF cannot disagree. */
public final class StatementBuilder {
    private StatementBuilder() {}

    /** The order a customer reads a statement in: money in, charges, interest, money out, corrections. */
    private static final List<EntryType> ORDER = List.of(
        EntryType.CONTRIBUTION, EntryType.TOP_UP, EntryType.TRANSFER_IN,
        EntryType.ALLOCATION_CHARGE, EntryType.POLICY_FEE, EntryType.INTEREST,
        EntryType.WITHDRAWAL, EntryType.SURRENDER, EntryType.MATURITY, EntryType.VESTING, EntryType.DEATH_CLAIM,
        EntryType.FREE_LOOK_REFUND,
        EntryType.ADJUSTMENT, EntryType.REVERSAL);

    public static StatementView build(String policyNumber, String currency, List<LedgerEntryView> entries,
                                      LocalDate from, LocalDate to, int lastSeq) {
        List<LedgerEntryView> upTo = entries.stream().filter(e -> e.seq() <= lastSeq).toList();
        BigDecimal opening = upTo.stream().filter(e -> e.effectiveDate().isBefore(from))
            .map(LedgerEntryView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<LedgerEntryView> within = upTo.stream()
            .filter(e -> !e.effectiveDate().isBefore(from) && !e.effectiveDate().isAfter(to))
            .sorted(Comparator.comparing(LedgerEntryView::effectiveDate).thenComparingInt(LedgerEntryView::seq))
            .toList();
        List<StatementView.Group> groups = new ArrayList<>();
        BigDecimal closing = opening;
        for (EntryType type : ORDER) {
            List<LedgerEntryView> ofType = within.stream().filter(e -> e.type() == type).toList();
            if (ofType.isEmpty()) continue;
            BigDecimal total = ofType.stream().map(LedgerEntryView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            groups.add(new StatementView.Group(type, total, ofType));
            closing = closing.add(total);
        }
        return new StatementView(policyNumber, from, to, lastSeq, opening.setScale(2), List.copyOf(groups),
            closing.setScale(2), currency);
    }
}
