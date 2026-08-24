package tz.co.nlolo.lifeplatform.document.api;

/** 404 at the REST boundary. Thrown for a missing ref AND for a cross-tenant ref, with an
 * identical message, so a caller cannot infer that another tenant's document exists. */
public class DocumentNotFoundException extends RuntimeException {
    public DocumentNotFoundException(String message) { super(message); }
}
