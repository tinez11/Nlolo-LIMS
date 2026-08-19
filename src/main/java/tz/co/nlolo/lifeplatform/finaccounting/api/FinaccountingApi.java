package tz.co.nlolo.lifeplatform.finaccounting.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

    /**
     * One page of journal entries, newest first, each with both of its legs.
     *
     * <p><b>{@code pageable} is mandatory, and that is a correctness requirement rather than a
     * stylistic one</b> (M9 final review, finding I3). This method used to return an unbounded {@code
     * List}, so a call with no filters returned every journal entry the tenant had ever posted -- on
     * an append-only ledger fed by every money-moving event on the platform, the one table guaranteed
     * to grow without limit. It now follows the same {@code Pageable}/{@code Page} shape as {@code
     * ClaimsApi.searchClaims}, {@code PolicyApi.searchPolicies} and {@code PartyApi.listGroupMembers}.
     *
     * <p>Both filters are applied in the DATABASE, including when supplied together. An earlier
     * version filtered {@code policyNumber} in memory after a {@code period}-only query, which was
     * merely wasteful while results were unbounded and would have been outright wrong once paged --
     * it would have filtered within a page and silently returned short pages (finding M8).
     */
    Page<JournalEntryView> listJournalEntries(String period, String policyNumber, Pageable pageable);

    JournalEntryView getJournalEntry(UUID journalEntryId);

    /** Not paged, deliberately: a chart of accounts is bounded reference data (nine rows per tenant
     * as M9 seeds it, a few hundred at most for a real Finance-authored chart), so there is no
     * unbounded-growth exposure here of the kind {@link #listJournalEntries} has. */
    List<ChartOfAccountView> listChartOfAccounts();
}
