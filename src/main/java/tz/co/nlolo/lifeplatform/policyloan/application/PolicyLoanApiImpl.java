package tz.co.nlolo.lifeplatform.policyloan.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policyloan.api.*;
import tz.co.nlolo.lifeplatform.policyloan.domain.LoanInterestTerm;
import tz.co.nlolo.lifeplatform.policyloan.domain.LoanTransaction;
import tz.co.nlolo.lifeplatform.policyloan.domain.PolicyLoan;
import tz.co.nlolo.lifeplatform.policyloan.infrastructure.LoanInterestTermRepository;
import tz.co.nlolo.lifeplatform.policyloan.infrastructure.LoanTransactionRepository;
import tz.co.nlolo.lifeplatform.policyloan.infrastructure.PolicyLoanRepository;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
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
public class PolicyLoanApiImpl implements PolicyLoanApi {

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

    public PolicyLoanApiImpl(PolicyLoanRepository policyLoanRepository, LoanInterestTermRepository loanInterestTermRepository,
                              LoanTransactionRepository loanTransactionRepository, PolicyApi policyApi,
                              ReferenceDataApi referenceDataApi, ApplicationEventPublisher eventPublisher) {
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
        UUID reservationId = policyApi.reserveLoanValue(policyNumber, requestedAmount, currency, RESERVATION_TTL);
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

        // Disbursement is requested (event published) but never actually executed in M3 --
        // payment (its consumer) is M5. Loans legitimately rest at DISBURSEMENT_REQUESTED here
        // (Global Constraints) unless a test calls markDisbursed as a stand-in.
        loan.markDisbursementRequested();
        policyLoanRepository.save(loan);

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

        return toView(loan);
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

    @Override
    @Transactional
    public LoanView recordRepayment(UUID loanId, BigDecimal amount, String currency, String paymentReference, String recordedBy) {
        UUID tenantId = TenantContext.get();
        PolicyLoan loan = findLoanOrThrow(loanId, tenantId);
        if (!"DISBURSED".equals(loan.getStatus()) && !"REPAYING".equals(loan.getStatus())) {
            throw new LoanNotEligibleException("Loan " + loanId + " must be DISBURSED or REPAYING to accept a repayment (current: " + loan.getStatus() + ")");
        }
        // M3 simplification (Global Constraints): every repayment is treated as immediately
        // confirmed. The real trigger per Module Architecture is consuming
        // payment.PaymentConfirmed (M5, not built) -- this makes the success path testable now.
        // The saved transaction is CAPTURED, not discarded: its loanTransactionId is the only
        // stable, per-repayment identity this event can carry, and finaccounting needs one (see
        // the publish below). LoanTransaction.loanTransactionId is application-assigned in the
        // constructor via UUID.randomUUID() -- NOT @GeneratedValue -- so it is already real and
        // non-null here, whether or not Hibernate has flushed the INSERT yet.
        LoanTransaction repayment = loanTransactionRepository.save(
            new LoanTransaction(tenantId, loanId, "REPAYMENT", amount, currency, paymentReference));
        loan.markRepaying();
        BigDecimal outstanding = computeOutstandingBalance(loan);
        if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
            loan.markSettled();
        }
        policyLoanRepository.save(loan);

        // loanTransactionId added by M9's final-review fix wave, and it is NOT cosmetic --
        // it closes a silent financial misstatement.
        //
        // This publish is deliberately UNGUARDED (unlike markDisbursed/markDisbursementFailed
        // above, which each publish only on a real transition) because the loan model genuinely
        // supports REPEATABLE PARTIAL repayments: PolicyLoanApiIntegrationTest
        // .repaymentReducesOutstandingBalanceAndSettlesAtZero pays 200,000 then 300,000 against a
        // 500,000 loan, and BOTH are real accounting events -- cash arrived twice. Every partial
        // repayment therefore must and does emit its own LoanRepaid.
        //
        // The bug that made this field necessary: finaccounting's PolicyLoanEventListener keyed its
        // journal-entry idempotency (FinaccountingApiImpl.postEntry's
        // existsByTenantIdAndSourceEventAndSourceRef fast-path) on loanId alone. loanId is stable
        // across every repayment of the same loan, so the SECOND partial repayment looked exactly
        // like a redelivery of the first and was silently dropped -- no exception, no failure
        // metric, no alert, and 1400 Policy Loan Receivable never cleared. loanTransactionId is
        // unique per repayment yet stable across a genuine redelivery of the same repayment, which
        // is precisely the idempotency key that path needs. LoanDisbursed keeps keying on loanId:
        // a loan is disbursed exactly once, so there that key is still correct.
        eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanRepaid", tenantId,
            Map.of("loanId", loanId, "loanTransactionId", repayment.getLoanTransactionId(),
                   "amount", Map.of("amount", amount.toPlainString(), "currencyCode", currency),
                   "repaidAt", Instant.now().toString(),
                   "outstandingBalance", Map.of("amount", outstanding.max(BigDecimal.ZERO).toPlainString(), "currencyCode", loan.getPrincipalCurrency()))));

        return toView(loan);
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

    @Override
    @Transactional
    public LoanView triggerForcedLapse(UUID loanId, String reason) {
        UUID tenantId = TenantContext.get();
        PolicyLoan loan = findLoanOrThrow(loanId, tenantId);
        loan.markForcedLapseTriggered();
        policyLoanRepository.save(loan);
        eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanForcedLapseTriggered", tenantId,
            Map.of("loanId", loanId, "policyNumber", loan.getPolicyNumber(), "triggeredAt", Instant.now().toString())));
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

    private PolicyLoan findLoanOrThrow(UUID loanId, UUID tenantId) {
        return policyLoanRepository.findByLoanIdAndTenantId(loanId, tenantId).orElseThrow(() -> new LoanNotFoundException(loanId));
    }

    private LoanView toView(PolicyLoan loan) {
        BigDecimal outstanding = computeOutstandingBalance(loan).max(BigDecimal.ZERO);
        BigDecimal currentRate = loanInterestTermRepository.findByLoanIdOrderByEffectiveFromDesc(loan.getLoanId()).stream()
            .findFirst().map(LoanInterestTerm::getRate).orElse(BigDecimal.ZERO);
        return new LoanView(loan.getLoanId(), loan.getPolicyNumber(), loan.getPrincipalAmount(), loan.getPrincipalCurrency(),
            outstanding, loan.getPrincipalCurrency(), currentRate, LoanStatus.valueOf(loan.getStatus()));
    }
}
