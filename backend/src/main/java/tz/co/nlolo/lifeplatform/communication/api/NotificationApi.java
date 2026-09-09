package tz.co.nlolo.lifeplatform.communication.api;

import java.util.Map;
import java.util.UUID;

/**
 * Tell a customer something.
 *
 * <p>One method, because there is one thing this module does. Which channels a message goes out
 * on is not the caller's decision: it follows from what the party has on file, and a listener
 * reacting to a policy event has no business knowing whether somebody gave us an email address.
 */
public interface NotificationApi {

    /**
     * Send one message on every channel this party can be reached on, and record each attempt.
     *
     * <p><b>Idempotent on {@code eventId}, and that is the point.</b> Everywhere else on this
     * platform a redelivered event is absorbed silently because the write is idempotent — the
     * same projection row written twice is the same row. Here it is not: a second SMS is a second
     * SMS on somebody's phone, and a customer told twice that their offer is closing learns that
     * this platform's messages are noise. A repeat call with an {@code eventId} already seen
     * sends nothing and records nothing.
     *
     * <p><b>Never throws for a delivery problem.</b> Callers are AFTER_COMMIT listeners reacting
     * to facts that are already durable — a policy issued, a premium cleared. An unreachable
     * aggregator, a party with no contact details, a template with a hole in it: each is recorded
     * as a FAILED dispatch that an operator can see and act on, and none of them may unwind the
     * thing the message was about.
     *
     * @param eventId the domain event that caused this. The dedup key, so it must be the
     *     envelope's own id and never a freshly generated one.
     * @param partyId who to tell. Their contact details are resolved here, not passed in.
     * @param policyNumber what this is about, or null when it is about nothing in particular.
     * @param templateKey which message, e.g. {@code OFFER_MADE}.
     * @param values the placeholders that message declares. A missing one is a FAILED dispatch,
     *     not a half-rendered message.
     */
    void notify(UUID eventId, UUID partyId, String policyNumber, String templateKey, Map<String, String> values);
}
