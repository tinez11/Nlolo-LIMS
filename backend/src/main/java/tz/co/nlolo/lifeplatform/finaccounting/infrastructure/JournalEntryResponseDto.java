package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.api.JournalSource;
import tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalEntryView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A journal entry and its legs. An entry always has exactly two legs in M9, and their DR and CR
 * totals are always equal -- see {@code JournalEntry}'s own invariant. */
public record JournalEntryResponseDto(UUID journalEntryId, String sourceEvent, String sourceRef,
                                       String period, String policyNumber, Instant postedAt,
                                       List<GlPostingResponseDto> postings, JournalSource sourceType,
                                       int policyRegisterVersion) {

    public static JournalEntryResponseDto from(JournalEntryView view) {
        return new JournalEntryResponseDto(view.journalEntryId(), view.sourceEvent(), view.sourceRef(),
            view.period(), view.policyNumber(), view.postedAt(),
            view.postings().stream().map(GlPostingResponseDto::from).toList(), view.sourceType(),
            view.policyRegisterVersion());
    }
}
