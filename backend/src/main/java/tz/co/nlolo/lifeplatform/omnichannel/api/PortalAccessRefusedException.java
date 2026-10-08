package tz.co.nlolo.lifeplatform.omnichannel.api;

/**
 * An invite, re-send or revoke the client's situation does not allow -- a corporate client, no policy held, no way to
 * reach them, access already open. Mapped to 422 (409 where the access already exists), in words staff can act on.
 */
public class PortalAccessRefusedException extends RuntimeException {

    private final boolean conflict;

    public PortalAccessRefusedException(String message) {
        this(message, false);
    }

    public PortalAccessRefusedException(String message, boolean conflict) {
        super(message);
        this.conflict = conflict;
    }

    public boolean conflict() {
        return conflict;
    }
}
