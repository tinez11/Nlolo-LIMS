package tz.co.nlolo.lifeplatform.communication.api;

import java.util.List;
import java.util.UUID;

/**
 * One message template, as the console shows it.
 *
 * <p>{@code placeholders} is derived from the body rather than stored. An editor who does not
 * know {@code expiryDate} exists will delete it, and a reminder that lost its deadline is the
 * message failing silently -- it still sends, it just no longer says the thing it was for.
 */
public record NotificationTemplateView(
    UUID templateId,
    String templateKey,
    String channel,
    String language,
    String bodyTemplate,
    List<String> placeholders) {}
