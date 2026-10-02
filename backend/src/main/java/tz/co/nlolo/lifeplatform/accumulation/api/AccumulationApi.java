package tz.co.nlolo.lifeplatform.accumulation.api;

import java.util.List;
import java.util.Optional;

/**
 * The accumulation engine's published face (product step 3). It OWNS an account's balance and every
 * transaction that changes it: other modules ask through here or by event, and nothing else writes
 * the balance. Tenant from {@code TenantContext} throughout. Tasks 3-8 add the acting methods.
 */
public interface AccumulationApi {

    /** Empty for a policy on a SCALE version -- every policy sold before this step. */
    Optional<AccountView> findAccount(String policyNumber);

    boolean isAccount(String policyNumber);

    /** Every entry, in sequence order. Immutable history: corrections appear as REVERSAL entries. */
    List<LedgerEntryView> entries(String policyNumber);

    /** ADMIN proposes; a DIFFERENT ADMIN or a FINANCE_OFFICER approves (spec §10.11). */
    RateDeclarationView proposeRate(java.util.UUID productId, java.math.BigDecimal ratePercent,
                                    java.time.LocalDate effectiveFrom, String proposedBy);

    /**
     * Refused when the proposer approves, and when the rate would take effect on or before interest
     * already credited on any account of the product -- checked again here, because the month-end
     * run may have passed the date since the proposal.
     */
    RateDeclarationView approveRate(java.util.UUID declarationId, String approvedBy);

    /** Only a PROPOSED rate: an approved one may already have earned interest. */
    RateDeclarationView withdrawRate(java.util.UUID declarationId, String withdrawnBy);

    /** Newest effective date first. */
    List<RateDeclarationView> listRates(java.util.UUID productId);

    // ---- Money in and out (task 6) ------------------------------------------------------------

    /**
     * Valued now and again at approval. Refused when it would leave less than the version's minimum
     * balance, net of any loan lien.
     */
    WithdrawalView requestWithdrawal(String policyNumber, java.math.BigDecimal amount, String payeeRef, String requestedBy);

    /** A second person. Posts the WITHDRAWAL entry and requests the payment; a failed payment is reversed. */
    WithdrawalView approveWithdrawal(java.util.UUID withdrawalId, String approvedBy);

    List<WithdrawalView> listWithdrawals(String policyNumber);

    /** Requests the collection from payment; credited, at the contribution rate, when payment confirms it. */
    TopUpView requestTopUp(String policyNumber, java.math.BigDecimal amount, String payerRef, String requestedBy);

    List<TopUpView> listTopUps(String policyNumber);

    /** Money already received from another scheme, recorded with its source; credited at the transfer rate. */
    TransferInView recordTransferIn(String policyNumber, java.math.BigDecimal amount, String sourceScheme,
                                    String documentRef, String recordedBy);

    List<TransferInView> listTransfersIn(String policyNumber);

    /** A person's correction: a signed amount and a reason, posted only once a second person approves. */
    AdjustmentView proposeAdjustment(String policyNumber, java.math.BigDecimal amount, String reason, String proposedBy);

    AdjustmentView approveAdjustment(java.util.UUID adjustmentId, String approvedBy);

    AdjustmentView rejectAdjustment(java.util.UUID adjustmentId, String rejectedBy);

    List<AdjustmentView> listAdjustments(String policyNumber);

    // ---- The same five, once per Idempotency-Key -----------------------------------------------
    //
    // What the REST layer calls. A repeat of the key answers with what the first request created and
    // creates nothing, so a client retrying after a timeout cannot collect, credit or propose twice.
    // A key reused for a different request is refused. The unkeyed forms above remain for in-process
    // callers, which carry their own source references; it is over HTTP that retries happen.

    WithdrawalView requestWithdrawal(String policyNumber, java.math.BigDecimal amount, String payeeRef, String requestedBy,
                                     String idempotencyKey);

    TopUpView requestTopUp(String policyNumber, java.math.BigDecimal amount, String payerRef, String requestedBy,
                           String idempotencyKey);

    TransferInView recordTransferIn(String policyNumber, java.math.BigDecimal amount, String sourceScheme,
                                    String documentRef, String recordedBy, String idempotencyKey);

    AdjustmentView proposeAdjustment(String policyNumber, java.math.BigDecimal amount, String reason, String proposedBy,
                                     String idempotencyKey);

    RateDeclarationView proposeRate(java.util.UUID productId, java.math.BigDecimal ratePercent,
                                    java.time.LocalDate effectiveFrom, String proposedBy, String idempotencyKey);

    // ---- Closing (task 7) ----------------------------------------------------------------------

    /** What a closing on {@code asOf} would move. The surrender approval screen shows it. */
    ClosingQuote quoteClosing(String policyNumber, java.time.LocalDate asOf);

    /** Read-only. What the account held at the death, before anything dated after it. */
    DeathValuation valueAtDeath(String policyNumber, java.time.LocalDate dateOfDeath);

    /**
     * Closes the account on the maturity date and returns what the MATURITY entry moved. Idempotent
     * on the instalment: asked again, it returns the same figure and posts nothing.
     */
    java.math.BigDecimal closeForMaturity(String policyNumber, java.util.UUID instalmentId, java.time.LocalDate dueDate);

    // ---- Statements (task 8) ---------------------------------------------------------------------

    /** Computed and reconciled, not filed. The console's Statement tab. */
    StatementView statement(String policyNumber, java.time.LocalDate from, java.time.LocalDate to);

    /**
     * Built, reconciled, rendered as a PDF and filed -- or the existing record when nothing new has
     * been posted since, so regenerating is provably the same statement.
     */
    StatementRecordView generateStatement(String policyNumber, java.time.LocalDate from, java.time.LocalDate to,
                                          String generatedBy);

    List<StatementRecordView> listStatements(String policyNumber);

    byte[] statementPdf(java.util.UUID statementId);
}
