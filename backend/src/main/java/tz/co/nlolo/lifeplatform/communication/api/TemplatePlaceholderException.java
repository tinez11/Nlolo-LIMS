package tz.co.nlolo.lifeplatform.communication.api;

import java.util.Set;

/**
 * An edit that would put a hole in a customer's message.
 *
 * <p>Its own type rather than IllegalArgumentException, which the shared handler maps to 400.
 * The request is not malformed -- it is well-formed and semantically wrong, which is what 422
 * means on this platform (openapi-common's UnprocessableEntity is what "shares don't sum to
 * 100%" already returns).
 *
 * <p>Caught at edit time on purpose. TemplateRenderer would refuse the same body at send time,
 * but by then the message is owed to a customer and the only outcome left is a FAILED dispatch
 * nobody asked for. Here it is a form that will not submit.
 */
public class TemplatePlaceholderException extends RuntimeException {
    public TemplatePlaceholderException(String templateKey, Set<String> invented) {
        super("Template " + templateKey + " cannot introduce placeholders nothing supplies: " + invented);
    }
}
