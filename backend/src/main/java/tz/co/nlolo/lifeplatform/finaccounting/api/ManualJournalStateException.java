package tz.co.nlolo.lifeplatform.finaccounting.api;

/**
 * A manual journal step its state, its people or the ledger refuses (IFRS 17 I4): editing a submitted draft,
 * approving one's own journal, reversing twice, posting into a period locked meanwhile. 409, with the reason.
 */
public class ManualJournalStateException extends RuntimeException {
    public ManualJournalStateException(String message) {
        super(message);
    }
}
