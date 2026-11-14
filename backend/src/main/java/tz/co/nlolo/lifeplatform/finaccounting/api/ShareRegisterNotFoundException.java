package tz.co.nlolo.lifeplatform.finaccounting.api;

/** No such shareholder or share issue for this company (2026-11-14, shareholders register). 404. */
public class ShareRegisterNotFoundException extends RuntimeException {
    public ShareRegisterNotFoundException(String message) {
        super(message);
    }
}
