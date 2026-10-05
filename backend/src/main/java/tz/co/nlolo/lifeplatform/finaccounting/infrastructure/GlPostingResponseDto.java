package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.api.JournalSource;
import tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions;
import tz.co.nlolo.lifeplatform.finaccounting.api.GlPostingView;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;

import java.util.UUID;

/** One leg of a journal entry ({@code finaccounting.gl_posting}). {@code amount} is always a
 * positive magnitude on the wire, same as in the DB -- {@code direction} carries the sign, never
 * a negative amount (see {@code PostingDirection}'s javadoc). */
public record GlPostingResponseDto(UUID postingId, UUID journalEntryId, String accountCode,
                                    PostingDirection direction, MoneyDto amount,
                                    String period, String policyNumber,
                                    String sourceEvent, String sourceRef, LineDimensions dimensions) {

    public static GlPostingResponseDto from(GlPostingView view) {
        return new GlPostingResponseDto(view.postingId(), view.journalEntryId(), view.accountCode(),
            view.direction(), new MoneyDto(view.amount().toPlainString(), view.currency()),
            view.period(), view.policyNumber(), view.sourceEvent(), view.sourceRef(), view.dimensions());
    }
}
