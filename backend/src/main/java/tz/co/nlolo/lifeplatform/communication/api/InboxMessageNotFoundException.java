package tz.co.nlolo.lifeplatform.communication.api;

import java.util.UUID;

/** No such message for this customer -- including one that is somebody else's, which is the same 404. */
public class InboxMessageNotFoundException extends RuntimeException {
    public InboxMessageNotFoundException(UUID messageId) {
        super("No message " + messageId);
    }
}
