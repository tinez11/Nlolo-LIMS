package tz.co.nlolo.lifeplatform.communication.api;

import java.util.UUID;

/**
 * No such template for this tenant.
 *
 * <p>Deliberately does not distinguish "does not exist" from "belongs to another tenant": the
 * second would let a caller probe for template ids outside their own tenant, and the answer is
 * the same 404 either way.
 */
public class NotificationTemplateNotFoundException extends RuntimeException {
    public NotificationTemplateNotFoundException(UUID templateId) {
        super("No notification template " + templateId);
    }
}
