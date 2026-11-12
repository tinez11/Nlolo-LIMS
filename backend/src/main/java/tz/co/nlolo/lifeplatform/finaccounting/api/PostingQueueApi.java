package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.util.List;
import java.util.UUID;

/**
 * The posting rules and the events they could not post (IFRS 17 I3a). Separate from {@link FinaccountingApi} because
 * posting itself sits behind it: the engine posts through the ledger, and the queue retries through the engine.
 */
public interface PostingQueueApi {

    /** The rules in force, read-only. */
    PostingRulesView postingRules();

    /** Open first (oldest first, as a work list), then the most recently resolved; or only the open ones. */
    List<UnpostedEventView> unpostedEvents(boolean openOnly);

    /**
     * Posts a queued event with the rules in force now, in the period it is retried in. Still unpostable, it stays
     * open with the new reason.
     *
     * @throws UnpostedEventNotFoundException if no such event is queued for this tenant
     * @throws UnpostedEventResolvedException if it was already posted or dismissed
     */
    UnpostedEventView retryUnpostedEvent(UUID id, String by);

    /**
     * Asks to close a queued event without posting it -- it should never have posted, or it was posted by hand. It
     * stays open until a second person confirms (2026-11-12, findings 74 and 116: dismissing removes an amount from
     * the books for good, and one person could do it).
     *
     * @throws FinaccountingValidationException without a reason
     * @throws UnpostedEventNotFoundException if no such event is queued for this tenant
     * @throws UnpostedEventResolvedException if it was already posted or dismissed, or a dismissal is already asked
     */
    UnpostedEventView dismissUnpostedEvent(UUID id, String reason, String by);

    /**
     * A second person confirms the dismissal asked for. The one who asked may confirm only holding ledger.approve-own,
     * recorded as acting alone.
     */
    UnpostedEventView confirmDismissal(UUID id, String by);

    /** A dismissal asked for is refused: the event waits to be posted again. */
    UnpostedEventView refuseDismissal(UUID id, String by);
}
