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

        // Module-Architecture-B1's reserve leg -- created/checked inside policy's OWN
        // transaction (policy.PolicyApiImpl.reserveLoanValue), never a lock held across this
        // module boundary. InsufficientLoanValueException (policy.api) propagates as-is --
        // policy.infrastructure.PolicyExceptionHandler maps it to 409 application-wide, so
        // policyloan does not need its own duplicate mapping for a policy-owned exception type.
        UUID reservationId = policyApi.reserveLoanValue(policyNumber, requestedAmount, currency, RESERVATION_TTL);
        BigDecimal rate = new BigDecimal(referenceDataApi.getValue("TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE", "TZ"));
        PolicyLoan loan = new PolicyLoan(tenantId, policyNumber, requestedAmount, currency, originatedBy);
        try {
            policyLoanRepository.save(loan);
            loanInterestTermRepository.save(new LoanInterestTerm(tenantId, loan.getLoanId(), rate, LocalDate.now()));
            loan.markOriginated();
            policyLoanRepository.save(loan);
        } catch (RuntimeException e) {
            // Module-Architecture-B1's release leg -- persistence failed in policyloan's OWN
            // transaction/schema after the reservation already succeeded in policy's; releasing
            // immediately here (rather than leaving it to the TTL sweep) frees the hold right
            // away instead of making a legitimate concurrent borrower wait out the full TTL.
            policyApi.releaseReservation(reservationId);
            throw e;
        }

        // Deliberately unguarded: policyApi.confirmReservation is called by default REQUIRED
        // propagation, so it joins this same @Transactional method's single physical DB
        // transaction rather than committing independently -- reserveLoanValue's own
        // opportunistic TTL sweep in a DIFFERENT, later call can therefore never observe (and
        // flip to EXPIRED) a reservation this transaction hasn't committed yet. If confirmReservation
        // nonetheless throws InvalidPolicyStateException (policy.api) -- e.g. the reservation was
        // already terminal for some other reason -- it is NOT caught here: letting it propagate
        // (a) relies on this method's own transaction rolling back atomically, undoing the
        // PolicyLoan/LoanInterestTerm rows just persisted above AND the reservation's own INSERT
        // together, so no compensating releaseReservation call is needed (unlike the persistence-
        // failure catch above, which runs BEFORE any attempt to confirm), and (b) InvalidPolicyStateException
        // is already mapped to 409 CONFLICT by policy.infrastructure.PolicyExceptionHandler
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
        // idempotencyKey isn't a parameter on this method (the header is accepted, not
        // enforced, at the controller -- Global Constraints) -- omitted entirely here rather
        // than put as a null value. LinkedHashMap used instead of Map.of above for the same
        // reason: payeeRef, unlike every other field on this payload, is genuinely nullable
        // (callers may originate a loan before a disbursement payee is known) and Map.of throws
        // NPE on a null value, whereas a HashMap/LinkedHashMap accepts one.
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
        loanTransactionRepository.save(new LoanTransaction(tenantId, loanId, "REPAYMENT", amount, currency, paymentReference));
        loan.markRepaying();
        BigDecimal outstanding = computeOutstandingBalance(loan);
        if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
            loan.markSettled();
        }
        policyLoanRepository.save(loan);

        eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanRepaid", tenantId,
            Map.of("loanId", loanId, "amount", Map.of("amount", amount.toPlainString(), "currencyCode", currency),
                   "repaidAt", Instant.now().toString(),
                   "outstandingBalance", Map.of("amount", outstanding.max(BigDecimal.ZERO).toPlainString(), "currencyCode", loan.getPrincipalCurrency()))));

        return toView(loan);
    }

    @Override
    @Transactional
    public LoanView markDisbursed(UUID loanId) {
        UUID tenantId = TenantContext.get();
        PolicyLoan loan = findLoanOrThrow(loanId, tenantId);
        loan.markDisbursed();
        loanTransactionRepository.save(new LoanTransaction(tenantId, loanId, "DISBURSEMENT", loan.getPrincipalAmount(), loan.getPrincipalCurrency(), "test-seam-disbursement"));
        policyLoanRepository.save(loan);
        eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanDisbursed", tenantId,
            Map.of("loanId", loanId, "disbursedAt", Instant.now().toString())));
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
