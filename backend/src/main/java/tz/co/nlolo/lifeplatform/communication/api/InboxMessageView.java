package tz.co.nlolo.lifeplatform.communication.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One message as the customer's portal inbox shows it (2026-10-08, the customer portal design step 7): what one event
 * told them, once, however many channels carried it. Never the transport's outcome or failure reason -- those are the
 * desk's.
 *
 * @param messageId the id to open or mark it read by
 * @param body the text they were sent; null on messages from before it was kept
 */
public record InboxMessageView(UUID messageId, String templateKey, String policyNumber, String body, Instant sentAt,
                               boolean read) {}
