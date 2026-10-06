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
     * Closes a queued event without posting it -- it should never have posted, or it was posted by hand.
     *
     * @throws FinaccountingValidationException without a reason
     * @throws UnpostedEventNotFoundException if no such event is queued for this tenant
     * @throws UnpostedEventResolvedException if it was already posted or dismissed
     */
    UnpostedEventView dismissUnpostedEvent(UUID id, String reason, String by);
}
