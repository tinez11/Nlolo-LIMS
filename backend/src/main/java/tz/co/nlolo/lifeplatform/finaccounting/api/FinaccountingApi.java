package tz.co.nlolo.lifeplatform.finaccounting.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * The `finaccounting` module's public surface.
 *
 * <p><b>Journal entries and GL postings stay READ ONLY, permanently</b> -- every posting is
 * derived from a domain event by this module's own listeners, nothing hand-enters one, and a
 * correction is a future reversal entry (deferred, see the design spec's §9), never an edit. That
 * absence is what makes the ledger trustworthy, and nothing below changes it.
 *
 * <p><b>The chart of accounts is NOT read-only</b> (added after M9 shipped, on explicit request):
 * {@link #createAccount}/{@link #renameAccount}/{@link #deleteAccount} give it a real CRUD
 * surface. This was always possible at the database level -- finaccounting/V2 granted app_role
 * full SELECT/INSERT/UPDATE/DELETE on {@code chart_of_account} from the start, unlike
 * {@code journal_entry}/{@code gl_posting}'s deliberate REVOKE -- only the application/API layer
 * was missing. {@code accountType}/{@code normalBalance} stay derived from the account code's
 * leading digit ({@link tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRule#accountTypeFor})
 * and are never independently settable, and delete is blocked by a real foreign key
 * ({@code fk_gl_posting_account_code}, finaccounting/V3) once any posting references the account
 * -- retiring an in-use account is a distinct, deferred concern (V3's own comment), not built here.
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

    /**
     * @param accountCode must match the five-block convention ({@code ^[1-5]\d{3}$}) -- enforced
     *        by the caller (bean validation on the wire DTO), not re-checked here, the same split
     *        {@code ReinsuranceApiImpl.createTreaty} uses between framework- and domain-level rules
     * @throws tz.co.nlolo.lifeplatform.finaccounting.api.DuplicateAccountCodeException if
     *         {@code (tenant, accountCode)} already exists
     */
    ChartOfAccountView createAccount(String accountCode, String name, String createdBy);

    /** A plain rename. {@code accountCode}, {@code accountType} and {@code normalBalance} are
     *  never editable -- see this interface's own javadoc for why.
     *  @throws AccountNotFoundException if no such account exists in this tenant */
    ChartOfAccountView renameAccount(String accountCode, String newName, String updatedBy);

    /**
     * @throws AccountNotFoundException if no such account exists in this tenant
     * @throws AccountInUseException if at least one real {@code gl_posting} row references this
     *         account -- deleting it would violate {@code fk_gl_posting_account_code} or, worse,
     *         orphan historical postings from the account they were booked to
     */
    void deleteAccount(String accountCode);
}
