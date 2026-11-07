package tz.co.nlolo.lifeplatform.policyloan.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.api.CashValueView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policyloan.api.*;
import tz.co.nlolo.lifeplatform.policyloan.domain.LoanInterestTerm;
import tz.co.nlolo.lifeplatform.policyloan.domain.LoanPosition;
import tz.co.nlolo.lifeplatform.policyloan.domain.LoanTransaction;
import tz.co.nlolo.lifeplatform.policyloan.domain.PolicyLoan;
import tz.co.nlolo.lifeplatform.policyloan.infrastructure.LoanInterestTermRepository;
import tz.co.nlolo.lifeplatform.policyloan.infrastructure.LoanTransactionRepository;
import tz.co.nlolo.lifeplatform.policyloan.infrastructure.PolicyLoanRepository;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PolicyLoanApiImpl implements PolicyLoanApi, tz.co.nlolo.lifeplatform.policy.api.LoanNetting {

    private static final Logger log = LoggerFactory.getLogger(PolicyLoanApiImpl.class);

    /** Module-Architecture-B1's TTL -- the same value passed by every Task 8 test. Not
     * externalized as a refdata parameter in M3 -- it governs an internal protocol timing, not
     * a business/statutory value, unlike TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE. */
    private static final Duration RESERVATION_TTL = Duration.ofMinutes(15);

    private final PolicyLoanRepository policyLoanRepository;
    private final LoanInterestTermRepository loanInterestTermRepository;
    private final LoanTransactionRepository loanTransactionRepository;
    private final PolicyApi policyApi;
    private final ReferenceDataApi referenceDataApi;
    private final ApplicationEventPublisher eventPublisher;
    /** Whether the person releasing a payout may be the one who took the loan (money movement step 4). */
    private final tz.co.nlolo.lifeplatform.approvals.api.OwnWork ownWork;

    public PolicyLoanApiImpl(PolicyLoanRepository policyLoanRepository, LoanInterestTermRepository loanInterestTermRepository,
                              LoanTransactionRepository loanTransactionRepository, PolicyApi policyApi,
                              ReferenceDataApi referenceDataApi, ApplicationEventPublisher eventPublisher,
                              tz.co.nlolo.lifeplatform.approvals.api.OwnWork ownWork) {
        this.ownWork = ownWork;
        this.policyLoanRepository = policyLoanRepository;
        this.loanInterestTermRepository = loanInterestTermRepository;
        this.loanTransactionRepository = loanTransactionRepository;
        this.policyApi = policyApi;
        this.referenceDataApi = referenceDataApi;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public LoanView originateLoan(String policyNumber, BigDecimal requestedAmount, String currency, String payeeRef, String originatedBy) {
        UUID tenantId = TenantContext.get();
        if (!policyApi.isPolicyInForce(policyNumber, LocalDate.now())) {
            throw new LoanNotEligibleException("Policy " + policyNumber + " must be in force to originate a loan");
        }
        // Product step 6 (plan R9): a unit-linked policy has no cash value to lend against in U1 -- its value is its
        // units. Said in words here, rather than left to a zero cash value refusing it obscurely.
        // productCategoryOf, never getPolicy: the reservation below locks policy_account, which getPolicy's view would
        // already have loaded -- turning a racing loan's clean INSUFFICIENT_LOAN_VALUE into a version conflict.
        if ("UNIT_LINKED".equals(policyApi.productCategoryOf(policyNumber))) {
            throw new LoanNotEligibleException("A unit-linked policy has no paid-up or loan value in U1; its value is its units");
        }
        // The interest already owed counts against the limit too (2026-11-07). Read from this module's own loans only:
        // the policy's account must not be loaded before reserveLoanValue locks it (see productCategoryOf above), so
        // the product's maximum and the interest are both checked inside that lock.
        BigDecimal interestOwed = interestOwedOn(policyNumber, tenantId);

        // Module-Architecture-B1's reserve/persist/confirm sequence below runs in ONE physical
        // DB transaction spanning policy and policyloan, DELIBERATELY -- per this plan's own
        // Global Constraints: "policyloan.confirmReservation(reservationId) is already a direct
        // synchronous call from policyloan into policy's own transaction (that is the entire
        // point of the reserve/confirm/release protocol). This plan updates
        // loan_encumbrance_amount directly inside PolicyApiImpl.confirmReservation, in the same
        // transaction." This is a CONSCIOUS deviation from docs/02-module-architecture.md's
        // stated B1 rationale for preferring reserve/confirm/release over a raw pessimistic lock
        // in the first place ("a lock taken during getAvailableLoanValue()'s transaction can't
        // survive into policyloan's separate transaction without violating module transaction
        // autonomy"): this implementation achieves safety by collapsing policy's and
        // policyloan's transactions into one, rather than by running a true two-phase protocol
        // across independent transactions. Consequence, stated plainly: the PESSIMISTIC_WRITE
        // lock reserveLoanValue takes on policy_account below (and re-acquires inside
        // confirmReservation) is held for the FULL remainder of this method -- across the
        // ReferenceDataApi lookup, both policyloan-schema inserts, and confirmReservation's own
        // lock/update -- not released until this transaction commits, not just for policy's own
        // writes. The genuine two-phase protocol (independent transactions, real compensating
        // actions, idempotency across retries) is DEFERRED: it lands only when payment (M5)
        // forces it, it is not built here. InsufficientLoanValueException (policy.api)
        // propagates as-is -- policy.infrastructure.PolicyExceptionHandler maps it to 409
        // application-wide, so policyloan does not need its own duplicate mapping for a
        // policy-owned exception type.
        UUID reservationId = policyApi.reserveLoanValue(policyNumber, requestedAmount, currency, RESERVATION_TTL, interestOwed);
        BigDecimal rate = new BigDecimal(referenceDataApi.getValue("TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE", "TZ"));
        PolicyLoan loan = new PolicyLoan(tenantId, policyNumber, requestedAmount, currency, originatedBy);
        // M5: persisted for FORENSICS, not for any code path.
        //
        // Corrected in M5's final review -- this comment previously said the DisbursementFailed
        // compensation path "knows which reservation's encumbrance to release" from it. It does not:
        // markDisbursementFailed below releases by (policyNumber, principalAmount, currency) and
        // never reads this column; PolicyLoan.getReservationId() has no caller anywhere in the
        // application. What this records is the otherwise-unanswerable reconciliation question
        // "which reservation did this loan consume?" -- before M5 reservationId was a local
        // variable here and was persisted nowhere at all. Kept deliberately (see
        // db-migrations/policyloan/V4's header), but a reader must not conclude the compensation
        // depends on it.
        //
        // Recorded before the first save below so it is part of the same INSERT, not a separate
        // UPDATE.
        loan.recordReservation(reservationId);

        // No try/catch + compensating releaseReservation here (removed on review -- it was
        // inert). Reserve, persist, and confirm all share the single physical transaction
        // described above, so if either save below ever surfaced a persistence failure
        // synchronously, or confirmReservation further down throws, the whole transaction rolls
        // back atomically -- undoing these two inserts AND the reservation's own INSERT
        // together; no compensating action is needed. A local catch here would not even see the
        // realistic failure in the first place: PolicyLoan.loanId is an application-assigned
        // UUID (set in the constructor, no @GeneratedValue) and PolicyLoan carries a
        // non-primitive @Version field, so Spring Data's isNew() check treats a fresh instance
        // as new via the null-version check and calls entityManager.persist(), which only
        // enqueues the INSERT in Hibernate's flush action queue rather than executing it here.
        // (Verified against this codebase's own established precedent for the identical
        // situation: PartyApiImpl.registerCorporate and ProductApiImpl.createProduct both call
        // saveAndFlush instead of plain save specifically because save() on an
        // application/Hibernate-assigned UUID id defers the INSERT past any local catch.)
        // Nothing between here and confirmReservation issues a query that would force an early
        // flush, so a real constraint violation would only ever surface at confirmReservation's
        // own locking query below or at this transaction's final commit -- both already outside
        // where a catch placed here could run.
        policyLoanRepository.save(loan);
        loanInterestTermRepository.save(new LoanInterestTerm(tenantId, loan.getLoanId(), rate, LocalDate.now()));
        loan.markOriginated();
        policyLoanRepository.save(loan);

        // Deliberately unguarded: confirmReservation joins this same @Transactional method's
        // single physical transaction (see the comment above reserveLoanValue), so
        // reserveLoanValue's own opportunistic TTL sweep in a DIFFERENT, later call can never
        // observe (and flip to EXPIRED) a reservation this transaction hasn't committed yet. If
        // confirmReservation nonetheless throws InvalidPolicyStateException (policy.api) -- e.g.
        // the reservation was already terminal for some other reason -- it is NOT caught here:
        // letting it propagate relies on this method's own transaction rolling back atomically,
        // undoing the PolicyLoan/LoanInterestTerm rows just persisted above AND the
        // reservation's own INSERT together -- no compensating releaseReservation call is
        // needed, unlike a true two-phase protocol would require. InvalidPolicyStateException is
        // already mapped to 409 CONFLICT by policy.infrastructure.PolicyExceptionHandler
        // application-wide, so it does not need a policyloan-local translation.
        policyApi.confirmReservation(reservationId);

        eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanOriginated", tenantId,
            Map.of("loanId", loan.getLoanId(), "policyNumber", policyNumber,
                   "principalAmount", Map.of("amount", requestedAmount.toPlainString(), "currencyCode", currency),
                   "interestRate", rate, "originatedAt", loan.getOriginatedAt().toString())));

        // Money movement step 4 (2026-11-04): the payout waits for a second person (approvePayout) -- the one money-out
        // road that had nobody checking it. The reserved loan value stays reserved meanwhile.
        loan.markAwaitingPayoutApproval(payeeRef);
        policyLoanRepository.save(loan);
        return toView(loan);
    }

    /**
     * A second person releases a loan's payout (money movement step 4): payouts.approve, or the person who took the loan
     * holding payouts.approve-own, recorded against their name. Then payment is asked to pay it, as before.
     */
    @Override
    @Transactional
    public LoanView approvePayout(UUID loanId, String approvedBy) {
        UUID tenantId = TenantContext.get();
        PolicyLoan loan = findLoanOrThrow(loanId, tenantId);
        loan.approvePayout(approvedBy, ownWork.mayActAlone("payouts.approve-own"));
        policyLoanRepository.save(loan);
        if (loan.isActedAlone()) {
            ownWork.record("Loan payout released by the person who took the loan", loanId.toString(),
                loan.getPolicyNumber(), approvedBy);
        }
        requestDisbursement(tenantId, loan);
        return toView(loan);
    }

    /** A second person refuses a loan's payout: nothing is paid, and the reserved loan value is freed. */
    @Override
    @Transactional
    public LoanView refusePayout(UUID loanId, String refusedBy, String reason) {
        UUID tenantId = TenantContext.get();
        PolicyLoan loan = findLoanOrThrow(loanId, tenantId);
        loan.refusePayout(refusedBy, ownWork.mayActAlone("payouts.approve-own"), reason);
        policyLoanRepository.save(loan);
        loanTransactionRepository.save(new LoanTransaction(tenantId, loanId, "REVERSAL", loan.getPrincipalAmount(),
            loan.getPrincipalCurrency(), "payout refused: " + reason.trim()));
        policyApi.releaseEncumbrance(loan.getPolicyNumber(), loan.getPrincipalAmount(), loan.getPrincipalCurrency());
        eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanPayoutRefused", tenantId,
            Map.of("loanId", loanId, "policyNumber", loan.getPolicyNumber(), "reason", reason.trim(),
                   "refusedBy", refusedBy)));
        return toView(loan);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoanView> listLoansAwaitingPayoutApproval() {
        return policyLoanRepository.findByTenantIdAndStatus(TenantContext.get(), "AWAITING_PAYOUT_APPROVAL").stream()
            .map(this::toView).toList();
    }

    /** Ask payment to pay a loan whose payout was released. */
    private void requestDisbursement(UUID tenantId, PolicyLoan loan) {
        String payeeRef = loan.getPayeeRef();
        String currency = loan.getPrincipalCurrency();
        BigDecimal requestedAmount = loan.getPrincipalAmount();
        Map<String, Object> disbursementPayload = new LinkedHashMap<>();
        disbursementPayload.put("loanId", loan.getLoanId());
        disbursementPayload.put("payeeRef", payeeRef);
        disbursementPayload.put("amount", Map.of("amount", requestedAmount.toPlainString(), "currencyCode", currency));
        // M5: idempotencyKey is REQUIRED on every inbound payment request event
        // (docs/02-module-architecture.md:140; PaymentRequestListener.requireKey throws without
        // one). loanId is used rather than DomainEventEnvelope.eventId() because eventId is
        // regenerated per publication (so it cannot dedup a redelivery) while loanId is stable
        // across redelivery AND unique per loan -- exactly the dedup semantics payment's
        // idempotency registry needs.
        disbursementPayload.put("idempotencyKey", loan.getLoanId().toString());
        // LinkedHashMap used instead of Map.of above for a separate reason: payeeRef, unlike
        // every other field on this payload, is genuinely nullable (callers may originate a loan
        // before a disbursement payee is known) and Map.of throws NPE on a null value, whereas a
        // HashMap/LinkedHashMap accepts one.
        eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanDisbursementRequested", tenantId, disbursementPayload));
    }

    @Override
    public LoanView getLoan(UUID loanId) {
        return toView(findLoanOrThrow(loanId, TenantContext.get()));
    }

    @Override
    public List<LoanView> listLoansForPolicy(String policyNumber) {
        UUID tenantId = TenantContext.get();
        return policyLoanRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId).stream().map(this::toView).toList();
    }

    /** Statuses whose principal is pledged but not yet paid out: still borrowed, with no interest yet. */
    private static final java.util.Set<String> BEING_TAKEN = java.util.Set.of(
        "RESERVED_PENDING_ORIGINATION", "ORIGINATED", "AWAITING_PAYOUT_APPROVAL", "DISBURSEMENT_REQUESTED");

    @Override
    @Transactional(readOnly = true)
    public List<LoanHistoryLine> history(UUID loanId) {
        PolicyLoan loan = findLoanOrThrow(loanId, TenantContext.get());
        return loanTransactionRepository.findByLoanIdOrderByOccurredAt(loan.getLoanId()).stream()
            .map(t -> new LoanHistoryLine(t.getTransactionType(), t.getAmount(), t.getCurrency(), t.getOccurredAt(),
                t.getReference()))
            .toList();
    }

    /** The interest accrued and not yet repaid on the policy's loans being repaid. */
    private BigDecimal interestOwedOn(String policyNumber, UUID tenantId) {
        BigDecimal interest = BigDecimal.ZERO;
        for (PolicyLoan loan : policyLoanRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId)) {
            if ("DISBURSED".equals(loan.getStatus()) || "REPAYING".equals(loan.getStatus())) {
                interest = interest.add(position(loan).interestOutstanding().max(BigDecimal.ZERO));
            }
        }
        return interest;
    }

    @Override
    @Transactional(readOnly = true)
    public LoanLimitView loanLimit(String policyNumber) {
        UUID tenantId = TenantContext.get();
        tz.co.nlolo.lifeplatform.policy.api.LoanValueView value = policyApi.getLoanValue(policyNumber);
        BigDecimal borrowed = BigDecimal.ZERO;
        BigDecimal interest = BigDecimal.ZERO;
        for (PolicyLoan loan : policyLoanRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId)) {
            if ("DISBURSED".equals(loan.getStatus()) || "REPAYING".equals(loan.getStatus())) {
                LoanPosition p = position(loan);
                borrowed = borrowed.add(p.principalOutstanding().max(BigDecimal.ZERO));
                interest = interest.add(p.interestOutstanding().max(BigDecimal.ZERO));
            } else if (BEING_TAKEN.contains(loan.getStatus())) {
                borrowed = borrowed.add(loan.getPrincipalAmount());
            }
        }
        // The interest owed comes off what may still be borrowed (2026-11-07): the limit is on what the policy owes, not
        // only on what it was lent. The policy's own figure is the one reserveLoanValue checks, so the two never disagree.
        BigDecimal canBorrow = value.lends() ? value.available().subtract(interest).max(BigDecimal.ZERO) : BigDecimal.ZERO;
        return new LoanLimitView(policyNumber, value.currency(), value.lends(), value.value(),
            value.maxLoanToValuePercent(), value.limit(), borrowed, interest, canBorrow.setScale(2, java.math.RoundingMode.DOWN),
            new BigDecimal(referenceDataApi.getValue("TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE", "TZ")));
    }

    @Override
    @Transactional
    public LoanView applyConfirmedRepayment(UUID loanId, BigDecimal amount, String currency, String paymentReference,
                                            String receiptMethod, UUID paymentId, String recordedBy) {
        UUID tenantId = TenantContext.get();
        PolicyLoan loan = findLoanOrThrow(loanId, tenantId);
        if (!"DISBURSED".equals(loan.getStatus()) && !"REPAYING".equals(loan.getStatus())) {
            throw new LoanNotEligibleException("Loan " + loanId + " must be DISBURSED or REPAYING to accept a repayment (current: " + loan.getStatus() + ")");
        }
        // Money movement step 1 (2026-11-04): only CONFIRMED money reaches here -- a deposit a second person approved, or
        // a mobile-money request the rail confirmed. The loan takes at most what it owes; the rest is the payer's,
        // announced as LoanOverpaid so it is posted (2128) and, from step 2, held on the policy balance. Before this a
        // typed-in repayment counted at once, and an overpayment drove principal below zero and was never given back.
        BigDecimal owed = computeOutstandingBalance(loan).max(BigDecimal.ZERO);
        BigDecimal applied = amount.min(owed);
        BigDecimal surplus = amount.subtract(applied);
        String method = receiptMethod == null ? "MOBILE_MONEY" : receiptMethod;

        if (applied.signum() > 0) {
            // IFRS 17 I3b (guide E-03): interest accrued so far is paid first, then principal -- measured BEFORE this
            // repayment is written, so the split is this repayment's own.
            BigDecimal interestPart = position(loan).interestPartOf(applied);
            LoanTransaction repayment = loanTransactionRepository.save(
                new LoanTransaction(tenantId, loanId, "REPAYMENT", applied, currency, paymentReference));
            loan.markRepaying();
            BigDecimal outstanding = computeOutstandingBalance(loan);
            if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
                loan.markSettled();
            }
            policyLoanRepository.save(loan);
            // The principal this repays stops being pledged against the policy's value (2026-11-07). Before this only a
            // reversed or failed loan released it, so a loan repaid in full still blocked surrender, held back savings
            // withdrawals and refused the next loan.
            BigDecimal principalPart = applied.subtract(interestPart);
            if (principalPart.signum() > 0) {
                policyApi.releaseEncumbrance(loan.getPolicyNumber(), principalPart, currency);
            }
            // Published on every repayment, partial ones included: each is cash arriving. loanTransactionId is the
            // posting's idempotency key -- unique per repayment, stable across a redelivery (M9: keyed on loanId, the
            // second partial repayment was dropped as a false redelivery and the loan receivable never cleared).
            eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanRepaid", tenantId,
                Map.of("loanId", loanId, "loanTransactionId", repayment.getLoanTransactionId(),
                       "policyNumber", loan.getPolicyNumber(),
                       "interestAmount", interestPart.toPlainString(),
                       "principalAmount", applied.subtract(interestPart).toPlainString(),
                       "amount", Map.of("amount", applied.toPlainString(), "currencyCode", currency),
                       "repaidAt", Instant.now().toString(),
                       // How the money came in: the ledger posts it to the account it reached (was always 1140).
                       "receiptMethod", method,
                       "outstandingBalance", Map.of("amount", outstanding.max(BigDecimal.ZERO).toPlainString(),
                                                    "currencyCode", loan.getPrincipalCurrency()))));
        }
        if (surplus.signum() > 0) {
            eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanOverpaid", tenantId,
                Map.of("paymentId", paymentId, "loanId", loanId, "policyNumber", loan.getPolicyNumber(),
                       "amount", Map.of("amount", surplus.setScale(2).toPlainString(), "currencyCode", currency),
                       "receiptMethod", method, "paymentReference", paymentReference)));
        }
        return toView(loan);
    }

    @Override
    @Transactional
    public void requestRepayment(UUID loanId, String payerRef, BigDecimal amount, String currency, String idempotencyKey) {
        UUID tenantId = TenantContext.get();
        // Same rule as an invoice's request (BillingApiImpl.requestPaymentForInvoice): the same key is the same attempt,
        // a new key a new one, and there is deliberately no default.
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required: the same key is the same collection attempt");
        }
        PolicyLoan loan = findLoanOrThrow(loanId, tenantId);
        if (!"DISBURSED".equals(loan.getStatus()) && !"REPAYING".equals(loan.getStatus())) {
            throw new LoanNotEligibleException("Loan " + loanId + " has nothing to repay (" + loan.getStatus() + ")");
        }
        BigDecimal owed = computeOutstandingBalance(loan).max(BigDecimal.ZERO);
        if (amount.signum() <= 0 || amount.compareTo(owed) > 0) {
            throw new LoanNotEligibleException("Ask for between 0.01 and " + owed.toPlainString() + " " + currency
                + ", what the loan owes now");
        }
        eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.RepaymentRequested", tenantId,
            Map.of("loanId", loanId, "policyNumber", loan.getPolicyNumber(), "payerRef", payerRef,
                   "amount", Map.of("amount", amount.setScale(2).toPlainString(), "currencyCode", currency),
                   "idempotencyKey", idempotencyKey)));
    }

    @Override
    @Transactional
    public LoanView markDisbursed(UUID loanId, String gatewayReference, Instant disbursedAt) {
        UUID tenantId = TenantContext.get();
        PolicyLoan loan = findLoanOrThrow(loanId, tenantId);
        boolean alreadyDisbursed = "DISBURSED".equals(loan.getStatus());
        loan.markDisbursed();
        if (!alreadyDisbursed) {
            // Only on the real (non-idempotent-repeat) transition -- a redelivered
            // payment.DisbursementCompleted must not write a second DISBURSEMENT transaction or
            // publish a second LoanDisbursed.
            loanTransactionRepository.save(new LoanTransaction(tenantId, loanId, "DISBURSEMENT", loan.getPrincipalAmount(), loan.getPrincipalCurrency(), gatewayReference));
            policyLoanRepository.save(loan);
            // M9: amount added. finaccounting needs it to post the loan receivable -- only loanId
            // and disbursedAt were carried before, no amount. loan is already loaded above; this is
            // purely additive, and stays inside the !alreadyDisbursed guard, so a redelivered
            // payment.DisbursementCompleted still emits no second event.
            eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanDisbursed", tenantId,
                Map.of("loanId", loanId, "disbursedAt", disbursedAt.toString(),
                       "amount", Map.of("amount", loan.getPrincipalAmount().toPlainString(),
                                        "currencyCode", loan.getPrincipalCurrency()))));
        }
        return toView(loan);
    }

    /** M5: the DisbursementFailed leg -- see PaymentEventListener.handleFailed. Runs the
     * compensation (ledger REVERSAL entry plus releasing the policy-side encumbrance) in the
     * SAME transaction as the status transition: this is local-module work with no external
     * call (unlike payment's own listener, which must split its gateway call across separate
     * transactions), so a plain @Transactional here is correct and sufficient. */
    @Override
    @Transactional
    public LoanView markDisbursementFailed(UUID loanId, String reason) {
        UUID tenantId = TenantContext.get();
        PolicyLoan loan = findLoanOrThrow(loanId, tenantId);
        boolean alreadyFailed = "DISBURSEMENT_FAILED".equals(loan.getStatus());
        loan.markDisbursementFailed();
        if (!alreadyFailed) {
            // Ledger-style compensation (docs/03-aggregate-design.md:208: "never delete the
            // original") -- the original DISBURSEMENT_REQUESTED transition and any reservation
            // row stay exactly as they were; this REVERSAL entry and the encumbrance release
            // below are the record of the compensating action, not an edit to history.
            loanTransactionRepository.save(new LoanTransaction(tenantId, loanId, "REVERSAL", loan.getPrincipalAmount(), loan.getPrincipalCurrency(), reason));
            policyLoanRepository.save(loan);
            policyApi.releaseEncumbrance(loan.getPolicyNumber(), loan.getPrincipalAmount(), loan.getPrincipalCurrency());
            eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanDisbursementFailed", tenantId,
                Map.of("loanId", loanId, "policyNumber", loan.getPolicyNumber(), "reason", reason,
                       "failedAt", Instant.now().toString())));
        }
        return toView(loan);
    }

    /**
     * The automatic forced-lapse path, and the tenant-scoped half of the interest-accrual sweep.
     *
     * <p>{@code docs/01-domain-map.md:224}: Forced Lapse is "automatic policy termination when
     * loan balance plus interest exceeds cash value". Before this existed, nothing on the
     * platform evaluated that condition -- {@code triggerForcedLapse} was called only from a
     * test, so {@code FORCED_LAPSE_TRIGGERED} was unreachable in production and a loan could grow
     * past the policy's cash value indefinitely with no consequence.
     *
     * <p>Idempotent by construction: a loan already past DISBURSED/REPAYING has nothing to test,
     * and a healthy loan is only un-flagged. Clearing the flag on BOTH outcomes is deliberate --
     * leaving it set would re-queue the same healthy loan forever, and the next accrual re-flags
     * it anyway, so nothing is lost.
     */
    @Override
    @Transactional
    public LoanView evaluateForcedLapse(UUID loanId) {
        UUID tenantId = TenantContext.get();
        PolicyLoan loan = findLoanOrThrow(loanId, tenantId);

        // A loan that is SETTLED, already FORCED_LAPSE_TRIGGERED, DISBURSEMENT_FAILED, or not yet
        // disbursed has no shortfall to test. Checked here rather than letting
        // markForcedLapseTriggered throw, because being asked to re-check a terminal loan is a
        // normal consequence of draining a queue, not a caller error.
        if (!"DISBURSED".equals(loan.getStatus()) && !"REPAYING".equals(loan.getStatus())) {
            loan.clearForcedLapseReview();
            policyLoanRepository.save(loan);
            return toView(loan);
        }

        BigDecimal outstanding = computeOutstandingBalance(loan);
        CashValueView cashValue = policyApi.getCashValue(loan.getPolicyNumber());

        // Refuse to compare money in two currencies rather than guessing an FX rate: the wrong
        // answer here terminates a customer's policy. Both sides default to TZS and nothing on
        // this platform converts, so a mismatch is a data defect that must surface loudly.
        if (!loan.getPrincipalCurrency().equals(cashValue.cashValueCurrency())) {
            throw new IllegalStateException("Cannot evaluate forced lapse for loan " + loanId + ": loan is in "
                + loan.getPrincipalCurrency() + " but policy " + loan.getPolicyNumber() + "'s cash value is in "
                + cashValue.cashValueCurrency() + " -- no conversion is defined on this platform");
        }

        // Strictly greater than: a balance exactly equal to cash value is still fully covered.
        if (outstanding.compareTo(cashValue.cashValueAmount()) > 0) {
            return forceLapse(loan, tenantId, "Outstanding balance " + outstanding.toPlainString() + " "
                + loan.getPrincipalCurrency() + " exceeds cash value " + cashValue.cashValueAmount().toPlainString()
                + " " + cashValue.cashValueCurrency());
        }

        loan.clearForcedLapseReview();
        policyLoanRepository.save(loan);
        return toView(loan);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoanView> listLoansPendingForcedLapseReview() {
        return policyLoanRepository
            .findByTenantIdAndForcedLapseReviewDueAtIsNotNullOrderByForcedLapseReviewDueAtAsc(TenantContext.get())
            .stream().map(this::toView).toList();
    }

    @Override
    @Transactional
    public LoanView triggerForcedLapse(UUID loanId, String reason) {
        UUID tenantId = TenantContext.get();
        return forceLapse(findLoanOrThrow(loanId, tenantId), tenantId, reason);
    }

    /**
     * The one place a forced lapse actually happens, shared by the automatic
     * ({@link #evaluateForcedLapse}) and manual ({@link #triggerForcedLapse}) paths.
     *
     * <p><b>The policy lapse is the half that was missing.</b> This module published
     * {@code policyloan.LoanForcedLapseTriggered} and NOTHING consumed it -- not policy, not
     * anything -- so the event was inert and the policy stayed in force no matter how far the
     * loan outgrew its cash value. Terminating the policy is what "forced lapse" means
     * ({@code docs/01-domain-map.md:224}: "automatic policy termination when..."), so it is done
     * here as a synchronous call on {@code PolicyApi} rather than by adding a consumer for the
     * event: policyloan already calls policy synchronously for the reserve/confirm/release
     * protocol, and a lapse that is merely announced is exactly the failure being fixed.
     */
    private LoanView forceLapse(PolicyLoan loan, UUID tenantId, String reason) {
        loan.markForcedLapseTriggered();
        loan.clearForcedLapseReview();
        policyLoanRepository.save(loan);

        // ASK, do not attempt-and-catch. PolicyApi.lapsePolicy is @Transactional and joins this
        // transaction, so an InvalidPolicyStateException from it marks the WHOLE transaction
        // rollback-only before this method could ever catch it -- the commit then dies with
        // UnexpectedRollbackException regardless of how carefully the exception is handled. That
        // was not a theoretical concern: the attempt-and-catch version of this method failed
        // ForcedLapseEndToEndTest.theLoanTransitionStandsEvenWhenThePolicyCannotBeLapsed exactly
        // that way. isLapsable is backed by the same Policy.canLapse() predicate lapse() guards
        // on, so this cannot drift from what lapsePolicy will actually accept.
        //
        // Yes, this is check-then-act, and that is safe here rather than merely tolerated. Both
        // calls join THIS transaction and share one persistence context, so lapsePolicy re-reads
        // the very entity isLapsable just examined -- the status cannot move between them from
        // inside. A concurrent transaction committing a status change in the window makes
        // lapsePolicy throw, which rolls this whole transaction back and surfaces a 409: the
        // forced lapse is then neither half-applied nor silently skipped, which is the only
        // property that actually matters for a policy termination.
        boolean policyLapsed = policyApi.isLapsable(loan.getPolicyNumber());
        if (policyLapsed) {
            policyApi.lapsePolicy(loan.getPolicyNumber(), "policyloan:forced-lapse");
        } else {
            // The policy is not ACTIVE or SUSPENDED, so either the termination this lapse intends
            // is already true (LAPSED / SURRENDERED / MATURED / terminated-for-claim) or the
            // policy is REINSTATED -- which isPolicyInForce() treats as in force but
            // Policy.lapse() refuses, a PRE-EXISTING gap in policy's state machine that is not
            // this module's to close. Either way the loan-side transition and the event must
            // still stand: this module's record of a forced lapse cannot be held hostage to
            // policy's state, and dropping the outcome silently is what made the original
            // missing-consumer bug invisible for so long.
            log.warn("Forced lapse recorded on loan {} but policy {} is not in a lapsable state -- "
                + "cover was not terminated by this call", loan.getLoanId(), loan.getPolicyNumber());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanId", loan.getLoanId());
        payload.put("policyNumber", loan.getPolicyNumber());
        payload.put("reason", reason);
        payload.put("triggeredAt", Instant.now().toString());
        // Carried so a consumer (and any later reconciliation) can tell a forced lapse that
        // actually terminated cover from one that only recorded the loan-side transition.
        payload.put("policyLapsed", policyLapsed);
        // IFRS 17 I3b (guide E-06): what the surrender value repays -- the principal and the interest still owed.
        LoanPosition position = position(loan);
        payload.put("principalOutstanding", position.principalOutstanding().max(BigDecimal.ZERO).toPlainString());
        payload.put("interestOutstanding", position.interestOutstanding().max(BigDecimal.ZERO).toPlainString());
        payload.put("currencyCode", loan.getPrincipalCurrency());
        eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanForcedLapseTriggered", tenantId, payload));

        return toView(loan);
    }

    // Scope note (Global Constraints): policyloan publishes LoanDisbursementRequested above but
    // nothing consumes it -- payment (M5) does not exist yet, so loans legitimately rest at
    // DISBURSEMENT_REQUESTED in every test that doesn't explicitly call markDisbursed. That is
    // expected, not a bug. Likewise, LoanSettledForPayout (one of the 6 DEFERRED surrender/
    // maturity choreography events) is neither published nor consumed anywhere in this class --
    // that whole choreography is out of M3's scope.

    private BigDecimal computeOutstandingBalance(PolicyLoan loan) {
        BigDecimal balance = loan.getPrincipalAmount();
        for (LoanTransaction transaction : loanTransactionRepository.findByLoanIdOrderByOccurredAt(loan.getLoanId())) {
            switch (transaction.getTransactionType()) {
                case "REPAYMENT", "SETTLEMENT" -> balance = balance.subtract(transaction.getAmount());
                case "INTEREST_ACCRUAL" -> balance = balance.add(transaction.getAmount());
                default -> { /* DISBURSEMENT/REVERSAL: already reflected in the starting principalAmount above, or unused in M3's scope */ }
            }
        }
        return balance;
    }

    // ---- Loan netting (2026-11-07, decision E7): a payout repays the policy's loans before anything is paid out ----

    @Override
    @Transactional
    public Netted settleFromPayout(String policyNumber, BigDecimal gross, String currency, Payout payout,
                                   String payoutRef, String by) {
        UUID tenantId = TenantContext.get();
        String reference = payout.name() + ":" + payoutRef;
        // Asked again for the same payout (a retried approval, a redelivered event): what it took the first time.
        List<LoanTransaction> earlier = loanTransactionRepository.findByTenantIdAndTransactionTypeAndReference(
            tenantId, "SETTLEMENT", reference);
        if (!earlier.isEmpty()) {
            BigDecimal total = earlier.stream().map(LoanTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new Netted(total, BigDecimal.ZERO, total);
        }
        if (gross == null || gross.signum() <= 0) return Netted.NONE;

        BigDecimal remaining = gross;
        BigDecimal interestTotal = BigDecimal.ZERO;
        BigDecimal principalTotal = BigDecimal.ZERO;
        List<PolicyLoan> open = policyLoanRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId).stream()
            .filter(l -> "DISBURSED".equals(l.getStatus()) || "REPAYING".equals(l.getStatus()))
            .sorted(java.util.Comparator.comparing(PolicyLoan::getOriginatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
            .toList();
        for (PolicyLoan loan : open) {
            if (remaining.signum() <= 0) break;
            LoanPosition before = position(loan);
            BigDecimal owed = before.principalOutstanding().add(before.interestOutstanding()).max(BigDecimal.ZERO);
            BigDecimal take = owed.min(remaining);
            if (take.signum() <= 0) continue;
            BigDecimal interestPart = before.interestPartOf(take);
            BigDecimal principalPart = take.subtract(interestPart);
            // SETTLEMENT: counted as a repayment by LoanPosition and the outstanding balance alike.
            LoanTransaction settlement = loanTransactionRepository.save(
                new LoanTransaction(tenantId, loan.getLoanId(), "SETTLEMENT", take, currency, reference));
            loan.markRepaying();
            BigDecimal outstanding = computeOutstandingBalance(loan);
            if (outstanding.signum() <= 0) loan.markSettled();
            policyLoanRepository.save(loan);
            if (principalPart.signum() > 0) {
                policyApi.releaseEncumbrance(policyNumber, principalPart, currency);
            }
            // The same LoanRepaid every repayment publishes, its "method" naming the payout it came out of: the ledger
            // clears that payout's payable (2211 claim, 2212 maturity, 2213 surrender, 2340 account) rather than cash.
            eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanRepaid", tenantId,
                Map.of("loanId", loan.getLoanId(), "loanTransactionId", settlement.getLoanTransactionId(),
                       "policyNumber", policyNumber,
                       "interestAmount", interestPart.toPlainString(),
                       "principalAmount", principalPart.toPlainString(),
                       "amount", Map.of("amount", take.toPlainString(), "currencyCode", currency),
                       "repaidAt", Instant.now().toString(),
                       "receiptMethod", payout.name() + "_OFFSET",
                       "payoutRef", payoutRef,
                       "outstandingBalance", Map.of("amount", outstanding.max(BigDecimal.ZERO).toPlainString(),
                                                    "currencyCode", currency))));
            remaining = remaining.subtract(take);
            interestTotal = interestTotal.add(interestPart);
            principalTotal = principalTotal.add(principalPart);
        }
        log.info("Payout {} on {} repaid {} of the policy's loans (by {})", reference, policyNumber,
            gross.subtract(remaining), by);
        return new Netted(gross.subtract(remaining), interestTotal, principalTotal);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal nettedFor(Payout payout, String payoutRef) {
        return loanTransactionRepository.findByTenantIdAndTransactionTypeAndReference(TenantContext.get(), "SETTLEMENT",
                payout.name() + ":" + payoutRef).stream()
            .map(LoanTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal owedOn(String policyNumber) {
        UUID tenantId = TenantContext.get();
        return policyLoanRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId).stream()
            .filter(l -> "DISBURSED".equals(l.getStatus()) || "REPAYING".equals(l.getStatus()))
            .map(l -> computeOutstandingBalance(l).max(BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private LoanPosition position(PolicyLoan loan) {
        return LoanPosition.of(loan.getPrincipalAmount(), loanTransactionRepository.findByLoanIdOrderByOccurredAt(loan.getLoanId()));
    }

    private PolicyLoan findLoanOrThrow(UUID loanId, UUID tenantId) {
        return policyLoanRepository.findByLoanIdAndTenantId(loanId, tenantId).orElseThrow(() -> new LoanNotFoundException(loanId));
    }

    private LoanView toView(PolicyLoan loan) {
        BigDecimal outstanding = computeOutstandingBalance(loan).max(BigDecimal.ZERO);
        BigDecimal currentRate = loanInterestTermRepository.findByLoanIdOrderByEffectiveFromDesc(loan.getLoanId()).stream()
            .findFirst().map(LoanInterestTerm::getRate).orElse(BigDecimal.ZERO);
        // Only a loan paid out owes anything; before that its principal is borrowed but not yet lent.
        boolean lent = "DISBURSED".equals(loan.getStatus()) || "REPAYING".equals(loan.getStatus());
        LoanPosition position = lent ? position(loan) : null;
        return new LoanView(loan.getLoanId(), loan.getPolicyNumber(), loan.getPrincipalAmount(), loan.getPrincipalCurrency(),
            outstanding, loan.getPrincipalCurrency(), currentRate, LoanStatus.valueOf(loan.getStatus()), loan.getPayeeRef(),
            loan.getCreatedBy(), loan.getPayoutDecidedBy(), loan.getPayoutRefusalReason(),
            loan.getOriginatedAt(),
            position == null ? BigDecimal.ZERO : position.principalOutstanding().max(BigDecimal.ZERO),
            position == null ? BigDecimal.ZERO : position.interestOutstanding().max(BigDecimal.ZERO));
    }
}
