package tz.co.nlolo.lifeplatform.communication.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One attempt to tell one person one thing, as the outbox shows it.
 *
 * <p>{@code failureReason} is on the view and not only in a log, because a FAILED row with no
 * reason tells an operator that something went wrong and gives them no way to tell an unreachable
 * aggregator from a customer with no phone number on file -- different problems, different fixes,
 * and nothing retries either of them automatically.
 */
public record NotificationDispatchView(
    UUID dispatchId,
    UUID partyId,
    String policyNumber,
    String templateKey,
    String channel,
    String status,
    String failureReason,
    Instant dispatchedAt,
    Instant createdAt) {}
