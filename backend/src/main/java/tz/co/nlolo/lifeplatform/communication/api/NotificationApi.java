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

    /**
     * Sends one SMS now, outside the templates, recording {@code loggedBody} in place of {@code body} -- for a message
     * carrying a secret the message log must never hold, such as a portal one-time password (2026-10-13, the
     * deployment pilot design §6). The ordinary path keeps every body it sends, which is right for a premium reminder
     * and wrong for a password: the customer's own inbox and any staff member reading the outbox would show it.
     *
     * <p>Not deduplicated -- every call sends, because re-sending an invite is a deliberate act. The caller words both
     * texts and is responsible for them matching apart from the secret.
     *
     * @return null when the gateway accepted the message, otherwise why it did not
     */
    String sendUnloggedSms(UUID partyId, String phoneNumber, String templateKey, String body, String loggedBody);

    /**
     * Sends one SMS now to someone the platform holds no client record for -- a staff member (2026-11-06: a staff
     * password reset, and "your password was changed"). The message log is keyed by client, so nothing is recorded
     * here; the caller keeps its own record (the staff access history does). Never throws.
     *
     * @return null when the gateway accepted the message, otherwise why it did not
     */
    String sendSystemSms(String phoneNumber, String templateKey, String body);

    /** Every template for the current tenant, ordered so one message's variants sit together. */
    java.util.List<NotificationTemplateView> listTemplates();

    /**
     * Change what a message says, without changing which message it is.
     *
     * <p>Body text only. Key, channel and language are identity: a template that changed its key
     * would silently stop being the one the sender looks up, and one that changed its channel
     * would be an SMS rendered into an email.
     *
     * @throws IllegalArgumentException if the new body introduces a placeholder nothing supplies.
     *     Caught here rather than at send time, where the message is already owed to a customer
     *     and the only remaining outcome is a FAILED dispatch.
     */
    NotificationTemplateView rewordTemplate(UUID templateId, String bodyTemplate);

    /**
     * The outbox, newest first, filtered by whichever of these is given.
     *
     * <p>{@code policyNumber} is the filter the desk reaches for: looking at an offer, the
     * question is "has this customer been told about this policy, and did it arrive".
     *
     * <p>{@code limit} keeps the newest rows only; null keeps every row. The whole outbox is thousands
     * of rows on a working tenant, so the register asks for a limit.
     */
    java.util.List<NotificationDispatchView> listDispatches(UUID partyId, String policyNumber, String status, Integer limit);

    // ---- 2026-11-08: the desk's view of messages, at any volume -------------------------------------------------

    /** The outbox filtered in the database, newest first, never older than {@code sinceDays}. */
    record DispatchQuery(UUID partyId, String policyNumber, String status, String channel, String templateKey,
                         Integer sinceDays, Integer limit) {}

    java.util.List<NotificationDispatchView> searchDispatches(DispatchQuery query);

    /** How many messages in the last {@code sinceDays}, by channel and status, and why the ones not sent were not. */
    record DispatchSummary(int sinceDays, java.util.List<Count> counts, java.util.List<Reason> reasons) {
        public record Count(String channel, String status, long count) {}
        public record Reason(String channel, String status, String reason, long count) {}
    }

    DispatchSummary summarise(int sinceDays);

    /** Whether each channel reaches customers: LIVE, LIMITED (an allowlist), TEST (a local mailbox or mock), OFF. */
    record ChannelStatus(String channel, String mode) {}

    java.util.List<ChannelStatus> channels();

    /** What a test send did -- SENT, NOT_SENT or FAILED -- the text it sent, and why not when it did not. */
    record TestResult(String outcome, String detail, String body) {}

    /**
     * Renders a template with the values given and sends it to one address the staff member names -- their own
     * phone or mailbox -- recording nothing in the outbox. The channel's own rules still hold: switched off or outside
     * a testing allowlist, nothing goes.
     */
    TestResult sendTest(UUID templateId, String to, Map<String, String> values);

    /**
     * Sends a message again that failed or was not sent, with the text it was worded with, to the party's contact on
     * file today. A new outbox row; the old one stays as it was.
     */
    NotificationDispatchView resend(UUID dispatchId);

    /** The party's messages, newest first, one per event (customer portal step 7). */
    java.util.List<InboxMessageView> inbox(UUID partyId);

    /** Marks a message read -- every channel's copy of it. A message that is not this party's is not found. */
    InboxMessageView markRead(UUID partyId, UUID messageId);
}
