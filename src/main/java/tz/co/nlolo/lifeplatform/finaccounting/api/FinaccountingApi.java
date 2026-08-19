package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.util.List;
import java.util.UUID;

/**
 * The `finaccounting` module's public surface: READ ONLY.
 *
 * <p>There is deliberately no write method. Every posting is derived from a domain event by this
 * module's own listeners -- nothing hand-enters a journal entry, which is what makes the ledger
 * trustworthy. A correction is a future reversal entry (deferred, see the design spec's §9), not
 * an edit.
 *
 * <p>IFRS 17 measurement (CSM roll-forward, LRC, LIC) is absent by design: it is blocked on C1
 * (Actuarial). M9 is the GL posting layer only.
 */
public interface FinaccountingApi {

    List<JournalEntryView> listJournalEntries(String period, String policyNumber);
    JournalEntryView getJournalEntry(UUID journalEntryId);
    List<ChartOfAccountView> listChartOfAccounts();
}
