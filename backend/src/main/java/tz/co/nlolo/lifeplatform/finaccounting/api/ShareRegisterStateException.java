package tz.co.nlolo.lifeplatform.finaccounting.api;

/**
 * A register step its state, its people or the ledger refuses (2026-11-14, shareholders register): deciding one's own
 * issue without ledger.approve-own, a locked month, changing a nominal value once shares are issued. 409, with why.
 */
public class ShareRegisterStateException extends RuntimeException {
    public ShareRegisterStateException(String message) {
        super(message);
    }
}
