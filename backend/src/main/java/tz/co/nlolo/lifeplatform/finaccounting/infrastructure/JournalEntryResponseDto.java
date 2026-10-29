package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.api.JournalSource;
import tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalEntryView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A journal entry and its legs. An entry always has exactly two legs in M9, and their DR and CR
 * totals are always equal -- see {@code JournalEntry}'s own invariant. */
public record JournalEntryResponseDto(UUID journalEntryId, String description, MoneyDto amount, String sourceEvent,
                                       String sourceRef,
                                       String period, String policyNumber, Instant postedAt,
                                       List<GlPostingResponseDto> postings, JournalSource sourceType,
                                       int policyRegisterVersion, String ruleVersion) {

    public static JournalEntryResponseDto from(JournalEntryView view) {
        return from(view, java.util.Map.of());
    }

    /**
     * With what it records in words, how much it moved and each line's account name (2026-10-27). The amount is the
     * entry's debits, added up here on the server -- the browser never sums money.
     */
    public static JournalEntryResponseDto from(JournalEntryView view, java.util.Map<String, String> accountNames) {
        java.math.BigDecimal debits = view.postings().stream()
            .filter(p -> p.direction() == tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection.DR)
            .map(p -> p.amount()).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        String currency = view.postings().isEmpty() ? "TZS" : view.postings().get(0).currency();
        return new JournalEntryResponseDto(view.journalEntryId(), JournalWords.describe(view.sourceEvent(), view.sourceType()),
            new MoneyDto(debits.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString(), currency),
            view.sourceEvent(), view.sourceRef(),
            view.period(), view.policyNumber(), view.postedAt(),
            view.postings().stream().map(p -> GlPostingResponseDto.from(p, accountNames)).toList(), view.sourceType(),
            view.policyRegisterVersion(), view.ruleVersion());
    }
}
