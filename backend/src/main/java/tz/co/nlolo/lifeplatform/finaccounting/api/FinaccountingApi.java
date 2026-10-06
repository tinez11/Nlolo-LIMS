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
 * {@link #createAccount}/{@link #updateAccount}/{@link #setAccountStatus}/{@link #deleteAccount}
 * give it a real CRUD surface. This was always possible at the database level -- finaccounting/V2
 * granted app_role full SELECT/INSERT/UPDATE/DELETE on {@code chart_of_account} from the start,
 * unlike {@code journal_entry}/{@code gl_posting}'s deliberate REVOKE -- only the
 * application/API layer was missing. {@code accountType}/{@code normalBalance} stay derived from
 * the account code's leading digit
 * ({@link tz.co.nlolo.lifeplatform.finaccounting.domain.AccountClasses#accountTypeFor}) and are never
 * independently settable, and delete is blocked by a real foreign key
 * ({@code fk_gl_posting_account_code}, finaccounting/V3) once any posting references the account.
 *
 * <p><b>Retiring an account is now built</b> (finaccounting/V5), and is deliberately a different
 * operation from deleting one: {@link #setAccountStatus} to {@code INACTIVE} refuses every new
 * posting leg naming the account while leaving its history resolvable, which is what an
 * append-only ledger requires. DELETE remains for accounts nothing has ever posted to.
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
    /**
     * @param accountCode restricts to entries with at least one LEG against this account.
     *     Every posting already carries an {@code account_code}, so "what hit Premium
     *     Receivables" was answerable from the data and unanswerable through this API -- which
     *     is also why the chart of accounts could not link to the postings behind a balance.
     *     Null for no account filter.
     */
    Page<JournalEntryView> listJournalEntries(String period, String policyNumber, String accountCode,
                                               Pageable pageable);

    /** Pre-account-filter overload, kept so existing callers and tests read unchanged. */
    default Page<JournalEntryView> listJournalEntries(String period, String policyNumber, Pageable pageable) {
        return listJournalEntries(period, policyNumber, null, pageable);
    }

    JournalEntryView getJournalEntry(UUID journalEntryId);

    /**
     * The chart with its balances, and whether the ledger balances.
     *
     * <p>The aggregation this module never had. Nothing anywhere summed a posting, so a ledger
     * holding thousands of balanced entries could not state the balance of a single account --
     * and the chart of accounts, hierarchy and all, was a structural reference list rather than
     * a ledger view.
     *
     * <p>Balances ROLL UP the hierarchy: a parent reports itself plus every descendant. That is
     * the standard convention and the one this chart is shaped for -- {@code postingAllowed}
     * marks the accounts that accept direct postings, which makes every other account a summary
     * of the ones beneath it. {@link AccountBalanceView} carries the un-rolled figures beside
     * the rolled ones so the two are never confused.
     *
     * @param period {@code YYYY-MM}, or null for inception-to-date. Echoed back on the result,
     *     because a balance with no period beside it cannot be reconciled against anything.
     */
    TrialBalanceView trialBalance(String period);

    /** Not paged, deliberately: a chart of accounts is bounded reference data (36 rows per tenant
     * as {@code ChartOfAccountBlueprint} seeds it, a few hundred at most for a real
     * Finance-authored chart), so there is no unbounded-growth exposure here of the kind
     * {@link #listJournalEntries} has. The flat array is also what lets the console assemble the
     * tree client-side and switch between its tree and table views without a round trip. */
    List<ChartOfAccountView> listChartOfAccounts();

    /**
     * @param accountCode must match the posting guide's nine classes ({@code ^[1-9]\d{3}$}) -- enforced
     *        by the caller (bean validation on the wire DTO), not re-checked here, the same split
     *        {@code ReinsuranceApiImpl.createTreaty} uses between framework- and domain-level rules
     * @param parentCode the parent account, or {@code null} for a block root. Creating a child
     *        turns its parent into a header in the same transaction, because a parent never
     *        receives postings.
     * @throws DuplicateAccountCodeException if {@code (tenant, accountCode)} already exists
     * @throws AccountNotFoundException if {@code parentCode} names no account in this tenant
     * @throws AccountInUseException if {@code parentCode} names an account that already carries
     *         postings -- making it a header would strand them under an account that, by this
     *         chart's own rule, cannot hold any
     * @throws FinaccountingValidationException if {@code accountCode} falls outside the parent's
     *         block (see {@code ChartOfAccount.childOf})
     */
    ChartOfAccountView createAccount(String accountCode, String parentCode, String name,
                                      String description, String currency, boolean postingAllowed,
                                      String createdBy);

    /** Name and description. {@code accountCode}, {@code accountType} and {@code normalBalance}
     *  are never editable -- see this interface's own javadoc for why -- and neither are
     *  {@code parentCode}/{@code level}: moving an account is a distinct, deferred concern.
     *  @throws AccountNotFoundException if no such account exists in this tenant */
    ChartOfAccountView updateAccount(String accountCode, String name, String description,
                                      String updatedBy);

    /**
     * Retire an account from new postings, or return it to service.
     *
     * <p>This is the correct answer for an account that has history, and the reason
     * {@link #deleteAccount} stays narrow: deactivating refuses every new leg naming the account
     * while leaving each posting already booked to it resolvable.
     *
     * @throws AccountNotFoundException if no such account exists in this tenant
     */
    ChartOfAccountView setAccountStatus(String accountCode, AccountStatus status, String updatedBy);

    /**
     * @throws AccountNotFoundException if no such account exists in this tenant
     * @throws AccountInUseException if at least one real {@code gl_posting} row references this
     *         account -- deleting it would violate {@code fk_gl_posting_account_code} or, worse,
     *         orphan historical postings from the account they were booked to. Use
     *         {@link #setAccountStatus} instead.
     * @throws AccountHasChildrenException if the account is a parent
     */
    void deleteAccount(String accountCode);

    // ---- Accounting periods (IFRS 17 spec §5.4) ----

    /** A period (YYYY-MM); one never touched reads OPEN. */
    AccountingPeriodView period(String period);

    /** Every period that has been moved from OPEN at least once, latest first. */
    java.util.List<AccountingPeriodView> periods();

    AccountingPeriodView startClosing(String period, String by);

    /** Refused while a clearing account (9xxx) is not at zero in it, or an earlier period with postings is unlocked. */
    AccountingPeriodView lockPeriod(String period, String by);

    AccountingPeriodView requestReopen(String period, String reason, String by);

    /** A second person approves; the period is OPEN again. */
    AccountingPeriodView approveReopen(String period, String by);

    // ---- The accounting policy register (IFRS 17 spec §3, decision D7) ----

    /** The elections in force on {@code asOf}, those approved to take effect after it, and every one still proposed. */
    java.util.List<PolicyElectionView> policyElections(java.time.LocalDate asOf);

    /** The election in force for a key and scope on a date, falling back to scope "*". */
    java.util.Optional<PolicyElectionView> policyElectionInForce(String key, String scope, java.time.LocalDate on);

    /**
     * A configured rate in force on {@code on} (IFRS 17 I3b) -- COMMISSION_WITHHOLDING_RATE or PREMIUM_LEVY_RATE -- as a
     * fraction (5% is 0.05). Empty when no election is in force or it is NONE: nothing is withheld or levied, never a
     * default rate (user decisions 6 and 7).
     */
    java.util.Optional<java.math.BigDecimal> rateInForce(String key, java.time.LocalDate on);

    /**
     * The investment component rule in force for a portfolio on a date (the register's INVESTMENT_COMPONENT_RULE,
     * falling back to "*"): NONE, PREMIUMS_RETURNED, SURRENDER_VALUE ... The emitting module computes the amount by it
     * (user decision 4); empty when the register has none for the portfolio.
     */
    java.util.Optional<String> investmentComponentRule(String portfolioCode, java.time.LocalDate on);

    /** A contract's IFRS 17 classification (I2), oldest first; empty for a contract not classified (yet). */
    java.util.List<PolicyClassificationView> policyClassifications(String policyNumber);

    PolicyElectionView proposePolicyElection(PolicyElectionInput input, String by);

    PolicyElectionView approvePolicyElection(java.util.UUID electionId, String signOffRef, String by);

    PolicyElectionView rejectPolicyElection(java.util.UUID electionId, String reason, String by);
}
