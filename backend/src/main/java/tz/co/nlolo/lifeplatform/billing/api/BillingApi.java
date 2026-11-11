package tz.co.nlolo.lifeplatform.billing.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface BillingApi {

    /**
     * The collections queue: this tenant's arrears cases, paged, worst escalation first.
     *
     * <p>The only tenant-wide read this module has. Every other billing read is keyed by a
     * policy number or an invoice id you must already know, which meant "which policies are in
     * arrears" could not be asked at all -- dunning was visible only as a badge on one invoice
     * row of one policy record, so collections was not an operable job.
     *
     * @param minDunningLevel null for every level; otherwise a FLOOR, because the real question
     *     is "what is at this level or worse"
     * @param resolved null for both, false for the live queue, true for settled history
     */
    Page<ArrearsCaseView> searchArrears(Integer minDunningLevel, Boolean resolved, Pageable pageable);

    /**
     * What is owed as at {@code asOf}, aged into five bands, one entry per currency (2026-10-15). Currencies are
     * never added together, and every band is present even when empty -- "nothing is this overdue" is an answer.
     *
     * @param asOf the civil day the age is measured from, in {@code Africa/Dar_es_Salaam}
     */
    List<ReceivablesAging> receivablesAging(LocalDate asOf);

    /**
     * The reconciliation queue: this tenant's field-captured premium receipts, oldest first.
     *
     * <p>The read this entity never had. {@code captureFieldReceipt} was its only endpoint, so
     * cash an agent recorded in the field could sit unmatched past its SLA, raise a
     * medium-severity Prometheus alert, and still be invisible to everyone -- the alert names a
     * count and no receipt.
     *
     * @param status null for every state; otherwise PENDING_RECONCILIATION, RECONCILED or
     *     RECONCILIATION_OVERDUE
     */
    Page<FieldReceiptView> searchFieldReceipts(String status, Pageable pageable);

    /**
     * Match field-captured cash to money actually received, and close the receipt.
     *
     * <p><b>The transition this closes had no caller at all.</b> {@code FieldReceipt.reconcile()}
     * has existed since M5 with a javadoc claiming it "closes the offline-receipt SLA loop", and
     * nothing in the platform ever invoked it -- no endpoint, no event listener, no sweep step.
     * So a receipt could only ever ratchet PENDING_RECONCILIATION -> RECONCILIATION_OVERDUE via
     * {@code billing.sweep_billing_state()} and stay there: the reconciliation queue could fill
     * and never drain, and the {@code FieldReceiptReconciliationOverdue} alert, once firing,
     * would never clear.
     *
     * <p><b>Manual, by a finance officer, rather than automatic on {@code PaymentConfirmed}.</b>
     * Matching cash to a receipt automatically needs a rule nobody has written down -- which
     * payment clears which receipt, what a partial amount means, what happens when one policy has
     * several open receipts -- and inventing one would put a guess between an agent's collection
     * and the ledger. Reconciling is what a person does against a bank statement; this records
     * that they did it, and who.
     *
     * <p>Idempotent: reconciling an already-RECONCILED receipt returns it unchanged rather than
     * throwing, matching {@code FieldReceipt.reconcile()}'s own early return. A receipt is
     * reconcilable from PENDING_RECONCILIATION or RECONCILIATION_OVERDUE alike -- being past SLA
     * is exactly when this is most needed, so overdue is not a state that blocks it.
     *
     * @param reconciledBy the acting user, carried on the published event and therefore into
     *     {@code audit_log} -- the same way {@code waiveInvoice} records who waived, since
     *     {@code field_receipt} has no actor column.
     */
    FieldReceiptView reconcileFieldReceipt(UUID receiptId, String reconciledBy);

    /**
     * The cash this agent has collected and the company has not yet received, oldest first (2026-11-10): what the
     * agent is answerable for. Matched collections drop off.
     */
    java.util.List<FieldReceiptView> agentCash(UUID agentId);

    /**
     * The agent says they paid these collections in, under one bank slip or mobile-money reference (2026-11-10). Their
     * word, for finance to match against -- each stays open until finance records the money and a second person
     * confirms it. Only the agent's own collections; one already matched is refused.
     */
    java.util.List<FieldReceiptView> markAgentCashBanked(UUID agentId, java.util.List<UUID> receiptIds, String reference);

    InvoiceView getNextDueInvoice(String policyNumber);
    List<InvoiceView> listInvoices(String policyNumber, InvoiceStatus status);

    /** Every payment applied to the policy's invoices, oldest first (billing V10). */
    List<ReceiptView> listReceipts(String policyNumber);

    /**
     * Every premium credit on a policy, oldest first -- what was given back, for whom, and why.
     *
     * <p>Credits were recorded and never readable: the policy page showed a 13,800 invoice DUE
     * when 4,200 of it had been refunded to a borrower who repaid early, and the lender actually
     * owed 9,600.
     */
    List<PremiumCreditView> listCredits(String policyNumber);

    /** A second person (payouts.approve) releases a lender's premium refund to the bank-transfer queue (money movement step 4). */
    LenderRefundView approveLenderRefund(java.util.UUID creditId, String approvedBy);

    /** A second person refuses a lender's premium refund, saying why; it stays owed to the lender on the books. */
    LenderRefundView refuseLenderRefund(java.util.UUID creditId, String refusedBy, String reason);

    /** Lender refunds raised and waiting for a second person, oldest first. */
    List<LenderRefundView> listLenderRefundsAwaitingApproval();

    /**
     * Single-invoice read by id, needed for object-level authorization on endpoints that identify
     * an invoice without naming its policy (BillingController.requestPaymentForInvoice). Tenant
     * scoping is applied inside the implementation, never taken from the caller.
     */
    InvoiceView getInvoice(UUID invoiceId);

    /**
     * A staff waiver. Refused (WaiverRefusedException) when the invoice is already waived or paid, or when its
     * policy is still proposed -- that premium starts the cover and is paid, not waived (2026-11-08).
     */
    InvoiceView waiveInvoice(UUID invoiceId, String reason, String waivedBy);

    /** A waiver taken back: the invoice is owed again and the ledger carries its receivable again (2026-11-08). */
    InvoiceView undoWaiver(UUID invoiceId, String reason, String undoneBy);

    /**
     * Requests collection of an invoice's premium through payment (M5). Publishes
     * billing.PaymentRequested, whose declared payload shape is api/asyncapi-events.yaml.
     *
     * <p><b>Review fix (I1): the idempotency key is now a CALLER-SUPPLIED value, not
     * {@code invoiceId.toString()}.</b> The invoice id looked like the perfect key -- stable across
     * redelivery, unique per invoice -- and it is exactly wrong: {@code payment} drops any event
     * whose {@code (tenant_id, idempotency_key)} pair is already claimed, so the FIRST attempt
     * permanently consumed the invoice's ONLY key. After an ordinary {@code INSUFFICIENT_FUNDS}
     * decline, an operator retry reached the rail ZERO times (empirically proven) while still
     * returning HTTP 202 with nothing but an INFO log, and the invoice became uncollectable through
     * this platform forever. A {@code PARTIALLY_PAID} top-up -- a state this milestone newly made
     * reachable -- was equally unresolvable.
     *
     * <p>Semantics, which the caller is responsible for honouring and the HTTP layer enforces via a
     * required {@code Idempotency-Key} header: the same key means the same INTENT and is deduped by
     * {@code payment} (so a double-click or a redelivered request is still safe), while a NEW key
     * means a NEW attempt and is allowed to reach the rail. This is the platform's existing
     * {@code Idempotency-Key} convention (openapi-common.yaml's shared parameter), promoted from
     * "accepted but not enforced" to genuinely load-bearing for this one endpoint.
     *
     * @param idempotencyKey required, non-blank. Deliberately has NO default: any default
     *        (the invoice id, a timestamp, a random UUID) reintroduces either the original
     *        single-shot bug or the loss of duplicate-submission protection. A missing or blank key
     *        is rejected rather than substituted.
     */
    void requestPaymentForInvoice(UUID invoiceId, String payerRef, String idempotencyKey);

    /** The same, recording who asked as a collection action (premiums to follow up, 2026-11-11). */
    void requestPaymentForInvoice(UUID invoiceId, String payerRef, String idempotencyKey, String requestedBy);

    /**
     * The premiums still owed, or falling due by this day, oldest first, with what was done to collect each
     * (2026-11-11, premiums to follow up). Leaves out what the policy balance will pay on its due date and what open
     * agent cash already covers.
     */
    List<OpenPremiumView> openPremiums(LocalDate dueOnOrBefore);

    /** Records a call about an owed premium; refused (CollectionActionRefusedException) without an outcome. */
    OpenPremiumView.ActionView recordCall(UUID invoiceId, String outcome, LocalDate promisedOn, String note, String by);

    /** Sends the client a reminder (billing.PremiumReminderSent); at most one per premium per day. */
    OpenPremiumView.ActionView sendPremiumReminder(UUID invoiceId, String by);

    /**
     * Every premium falling due from {@code from} to {@code to} inclusive, paid or not, for the collection rate
     * (2026-11-11). Waived premiums are left out (nothing was asked for), and so are savings-type policies
     * ({@link #isSavingsType}): their deposits are the customer's choice, not a premium the company is owed.
     */
    List<InvoiceDueView> invoicesDueBetween(LocalDate from, LocalDate to);

    /** Applies a payment.PaymentConfirmed collection to the invoice it was requested for (M5) --
     * see PremiumInvoice.applyPayment for the PAID/PARTIALLY_PAID decision and WAIVED's
     * no-overwrite rule. Also resolves any open ArrearsCase for the invoice, reusing the exact
     * ArrearsCase::resolve wiring waiveInvoice already uses. currency/paymentReference are
     * accepted for parity with the PaymentConfirmed payload and for future reconciliation use;
     * premium_invoice has no column for either today, so neither is persisted or compared here. */
    InvoiceView applyConfirmedPayment(UUID invoiceId, BigDecimal amount, String currency, String paymentReference);

    /** As above, with the number the money came from; carried onto billing.PremiumCollected as payerRef. */
    InvoiceView applyConfirmedPayment(UUID invoiceId, BigDecimal amount, String currency, String paymentReference, String payerRef);

    /**
     * As above, with how the money came in (2026-10-27): MOBILE_MONEY from the gateway, or a recorded receipt's CASH,
     * BANK_DEPOSIT, CHEQUE, LIPA_NUMBER or TANQR. Carried onto billing.PremiumCollected as receiptMethod, so the ledger
     * posts the premium to the account the money reached.
     */
    InvoiceView applyConfirmedPayment(UUID invoiceId, BigDecimal amount, String currency, String paymentReference,
                                      String payerRef, String method);

    /**
     * As above, keyed on the confirmed payment's own id (money movement step 2, 2026-11-04). On an insurance policy the
     * money is a deposit on the policy's balance -- posted at once for what arrived -- and the balance pays the policy's
     * premiums that have fallen due, oldest first, and the named invoice even if it is not yet due; what is left stays.
     * A savings-type policy keeps the old road: the money pays the named invoice.
     */
    InvoiceView applyConfirmedPayment(UUID invoiceId, BigDecimal amount, String currency, String paymentReference,
                                      String payerRef, String method, UUID paymentId);

    /** What is on a policy's balance now: every deposit less every premium paid from it. Zero for none. */
    BigDecimal balanceOf(String policyNumber);

    /**
     * A customer asks for money back off their policy's balance, to their mobile-money number (money movement step 5).
     * It waits for staff (payouts.approve) like any withdrawal. Returns the new withdrawal's id.
     */
    java.util.UUID requestWithdrawalToMobileMoney(String policyNumber, BigDecimal amount, String phone, String requestedBy);

    /** Every policy balance in the tenant, summed: the sub-ledger behind account 2128 (money movement step 7). */
    BigDecimal policyBalancesTotal();

    /**
     * What each policy's invoices still owe, in TZS -- charged less paid less credited, never below zero, a waived one
     * owing nothing (money movement step 7): the sub-ledger behind the premium receivables.
     */
    java.util.Map<String, BigDecimal> owedByPolicy();

    /** The balance and how far ahead it pays premiums (money movement step 5). */
    PolicyBalanceView balance(String policyNumber);

    /** One page of the balance's statement, newest first, each line with the balance after it. */
    BalanceStatementView balanceStatement(String policyNumber, int page, int size);

    /**
     * Whether a policy's premiums keep their own road (its product version's account basis is ACCOUNT): no policy balance,
     * and a receipt still at most what its invoice owes (money movement D12, until "For: Savings").
     */
    boolean isSavingsType(String policyNumber);

    record FieldReceiptResult(UUID receiptId, String status) {}
    FieldReceiptResult captureFieldReceipt(UUID agentId, String policyNumber, BigDecimal amount, String currency,
                                            String clientIdempotencyKey, Instant capturedAtClient);

    /**
     * Cash an agent collected (2026-11-11): on an insurance policy it pays the customer's premium at once -- a deposit
     * onto the balance posted to 1320 Due from agents -- and the customer is told by name who took it.
     *
     * @param agentPartyId the agent's own party, for the customer's SMS ("Your agent Juma Senior has collected ...");
     *                     null only leaves the name out
     */
    FieldReceiptResult captureFieldReceipt(UUID agentId, UUID agentPartyId, String policyNumber, BigDecimal amount,
                                            String currency, String clientIdempotencyKey, Instant capturedAtClient);

    /**
     * Whether this agent's cash already paid onto the policy's balance when it was recorded, and has not been undone:
     * then the money arriving moves it from 1320 to the bank, and pays nothing a second time (2026-11-11).
     */
    boolean agentCashOnBalance(UUID fieldReceiptId);

    /** One agent's cash by its id, in the caller's tenant. */
    java.util.Optional<FieldReceiptView> fieldReceipt(UUID fieldReceiptId);

    /** What one agent still holds, in one currency: how many collections, how much, and since when (2026-11-11). */
    record AgentCashTotal(UUID agentId, long collections, BigDecimal total, String currency, Instant oldestCapturedAt) {}

    /** Every agent still holding cash, longest-held first (findings 14: "Juma Senior holds TZS 545,000, oldest 10 days"). */
    java.util.List<AgentCashTotal> agentCashTotals();
}
