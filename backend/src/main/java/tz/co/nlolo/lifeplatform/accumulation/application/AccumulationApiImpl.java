package tz.co.nlolo.lifeplatform.accumulation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.api.*;
import tz.co.nlolo.lifeplatform.accumulation.domain.*;
import tz.co.nlolo.lifeplatform.accumulation.domain.Posting;
import tz.co.nlolo.lifeplatform.accumulation.domain.RateDeclaration;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.*;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.LedgerEntryRepository;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.PostingRepository;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.RateDeclarationRepository;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.AccumulationChargeRow;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.VestingTerms;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AccumulationApiImpl implements AccumulationApi {

    private static final Logger log = LoggerFactory.getLogger(AccumulationApiImpl.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    final AccountRepository accounts;
    final PostingRepository postings;
    final LedgerEntryRepository entries;
    final LedgerService ledger;
    final PolicyApi policyApi;
    final ProductApi productApi;
    final AccountValuer valuer;
    final RateDeclarationRepository rates;
    final WithdrawalRequestRepository withdrawals;
    final TopUpRequestRepository topUps;
    final TransferInRepository transfers;
    final AdjustmentRequestRepository adjustments;
    final ApplicationEventPublisher events;
    final StatementRepository statementRepository;
    final DocumentApi documentApi;
    final IdempotentRequests keyed;
    final Deposits deposits;

    public AccumulationApiImpl(AccountRepository accounts, PostingRepository postings, LedgerEntryRepository entries,
                               LedgerService ledger, PolicyApi policyApi, ProductApi productApi,
                               AccountValuer valuer, RateDeclarationRepository rates,
                               WithdrawalRequestRepository withdrawals, TopUpRequestRepository topUps,
                               TransferInRepository transfers, AdjustmentRequestRepository adjustments,
                               ApplicationEventPublisher events, StatementRepository statementRepository,
                               DocumentApi documentApi, IdempotentRequests keyed, Deposits deposits) {
        this.keyed = keyed;
        this.deposits = deposits;
        this.statementRepository = statementRepository;
        this.documentApi = documentApi;
        this.accounts = accounts;
        this.postings = postings;
        this.entries = entries;
        this.ledger = ledger;
        this.policyApi = policyApi;
        this.productApi = productApi;
        this.valuer = valuer;
        this.rates = rates;
        this.withdrawals = withdrawals;
        this.topUps = topUps;
        this.transfers = transfers;
        this.adjustments = adjustments;
        this.events = events;
    }

    // ---- Withdrawals: two people, valued at approval, reversed on a failed payment (task 6) ------

    @Override
    @Transactional
    public WithdrawalView requestWithdrawal(String policyNumber, BigDecimal amount, String payeeRef, String requestedBy) {
        if (payeeRef == null || payeeRef.isBlank()) {
            throw new AccumulationStateException("A withdrawal needs a payee reference");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new AccumulationStateException("A withdrawal must be for more than zero");
        }
        Account account = loadOpen(policyNumber);
        deposits.refuseMovement(account);
        refuseIfLockedPension(account);
        if (withdrawals.existsByPolicyNumberAndStatusIn(policyNumber, List.of("REQUESTED", "APPROVED"))) {
            throw new AccumulationStateException("A withdrawal is already in flight on policy " + policyNumber);
        }
        refuseBelowMinimum(account, amount);
        WithdrawalRequest request = withdrawals.save(new WithdrawalRequest(TenantContext.get(), policyNumber,
            amount.setScale(2, RoundingMode.UNNECESSARY), account.getCurrency(), payeeRef, requestedBy));
        return toView(request);
    }

    /**
     * The money leaves the account HERE, at approval -- not when the payment lands. That is what stops
     * two withdrawals, or a withdrawal and a surrender, from both spending the same balance.
     */
    @Override
    @Transactional
    public WithdrawalView approveWithdrawal(UUID withdrawalId, String approvedBy) {
        WithdrawalRequest request = withdrawals.findById(withdrawalId)
            .orElseThrow(() -> new AccumulationStateException("No withdrawal " + withdrawalId));
        Account account = loadOpen(request.getPolicyNumber());
        request.approve(approvedBy);
        // Again at approval: a month-end fee may have landed since the request.
        refuseBelowMinimum(account, request.getAmount());
        ledger.post(request.getPolicyNumber(), new LedgerService.Source("withdrawal", "withdrawal:" + withdrawalId),
            List.of(LedgerService.Line.of(EntryType.WITHDRAWAL, request.getAmount().negate(), LocalDate.now(),
                "Partial withdrawal to " + request.getPayeeRef())),
            request.getRequestedBy(), approvedBy);
        withdrawals.save(request);
        events.publishEvent(DomainEventEnvelope.of("accumulation.PayoutRequested", TenantContext.get(), Map.of(
            "purpose", "WITHDRAWAL_PAYOUT",
            "sourceRef", withdrawalId.toString(),
            "idempotencyKey", "withdrawal:" + withdrawalId,
            "policyNumber", request.getPolicyNumber(),
            "payeeRef", request.getPayeeRef(),
            "amount", Map.of("amount", request.getAmount().toPlainString(), "currencyCode", request.getCurrency()))));
        return toView(request);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WithdrawalView> listWithdrawals(String policyNumber) {
        return withdrawals.findByPolicyNumberOrderByRequestedAtDesc(policyNumber).stream().map(this::toView).toList();
    }

    /** What may leave is the balance less the loan lien, down to the version's minimum balance. */
    private void refuseBelowMinimum(Account account, BigDecimal amount) {
        BigDecimal lien = policyApi.getCashValue(account.getPolicyNumber()).loanEncumbranceAmount();
        BigDecimal minimum = productApi.resolveAccumulationPlan(account.getProductVersionId()).minimumBalance();
        BigDecimal available = account.getBalance().subtract(lien);
        BigDecimal left = available.subtract(amount);
        if (left.compareTo(minimum) < 0) {
            BigDecimal most = available.subtract(minimum).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_EVEN);
            throw new AccumulationStateException("A withdrawal of " + amount.setScale(2, RoundingMode.HALF_EVEN)
                + " would leave " + left.setScale(2, RoundingMode.HALF_EVEN) + ", below this product's minimum balance of "
                + minimum.setScale(2, RoundingMode.HALF_EVEN) + ". The most that can be withdrawn is " + most + ".");
        }
    }

    /**
     * {@code payment.DisbursementCompleted} / {@code DisbursementFailed} with purpose
     * WITHDRAWAL_PAYOUT. A failure puts the money back by REVERSING the withdrawal entry -- the
     * original stays, unedited, and the statement shows both.
     */
    @Transactional
    public void settleWithdrawal(UUID withdrawalId, UUID disbursementId, boolean paid) {
        WithdrawalRequest request = withdrawals.findById(withdrawalId).orElse(null);
        if (request == null) {
            return;
        }
        if (paid) {
            if (request.markPaid(disbursementId)) {
                withdrawals.save(request);
                // IFRS 17 I3b (guide G-04): the withdrawal left the bank; the ledger clears what the WITHDRAWAL entry
                // made payable (2340). On the real transition only.
                events.publishEvent(DomainEventEnvelope.of("accumulation.PayoutPaid", TenantContext.get(), Map.of(
                    "payoutRef", "withdrawal:" + withdrawalId, "paymentRef", withdrawalId.toString(),
                    "policyNumber", request.getPolicyNumber(),
                    "amount", Map.of("amount", request.getAmount().toPlainString(), "currencyCode", request.getCurrency()))));
            }
            return;
        }
        if (!request.markFailed(disbursementId)) {
            return; // already settled: a redelivery
        }
        withdrawals.save(request);
        Posting posting = postings.findByTenantIdAndSourceTypeAndSourceRef(TenantContext.get(), "withdrawal",
            "withdrawal:" + withdrawalId).orElseThrow();
        LedgerEntry original = entries.findByPostingIdOrderBySeq(posting.getPostingId()).get(0);
        // Posted even onto a CLOSED account: LedgerService has no requireOpen, deliberately -- the
        // money is real and the ledger must say where it is.
        ledger.post(request.getPolicyNumber(), new LedgerService.Source("reversal", "reversal:" + original.getEntryId()),
            List.of(new LedgerService.Line(EntryType.REVERSAL, original.getAmount().negate(), LocalDate.now(),
                "Withdrawal payment failed", original.getEntryId())),
            "system", null);
        accounts.findById(request.getPolicyNumber()).filter(a -> a.status() == AccountStatus.CLOSED).ifPresent(a ->
            log.error("Withdrawal {} on policy {} failed after its account closed ({}); {} is back on the closed account "
                + "and must be refunded by hand", withdrawalId, a.getPolicyNumber(), a.getClosedReason(), request.getAmount()));
    }

    private WithdrawalView toView(WithdrawalRequest w) {
        return new WithdrawalView(w.getWithdrawalId(), w.getPolicyNumber(), w.getAmount(), w.getCurrency(), w.getPayeeRef(),
            w.getStatus(), w.getRequestedBy(), w.getRequestedAt(), w.getApprovedBy(), w.getApprovedAt());
    }

    // ---- Top-ups and transfers in (task 6) -----------------------------------------------------

    @Override
    @Transactional
    public TopUpView requestTopUp(String policyNumber, BigDecimal amount, String payerRef, String requestedBy) {
        if (payerRef == null || payerRef.isBlank()) {
            throw new AccumulationStateException("A top-up needs a payer reference");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new AccumulationStateException("A top-up must be for more than zero");
        }
        Account account = loadOpen(policyNumber);
        deposits.refuseMovement(account);
        TopUpRequest request = topUps.save(new TopUpRequest(TenantContext.get(), policyNumber,
            amount.setScale(2, RoundingMode.UNNECESSARY), account.getCurrency(), payerRef, requestedBy));
        events.publishEvent(DomainEventEnvelope.of("accumulation.TopUpRequested", TenantContext.get(), Map.of(
            "topUpId", request.getTopUpId().toString(),
            "idempotencyKey", "topup:" + request.getTopUpId(),
            "policyNumber", policyNumber,
            "payerRef", payerRef,
            "amount", Map.of("amount", request.getAmount().toPlainString(), "currencyCode", request.getCurrency()))));
        return toView(request);
    }

    /** The money is in. Credited at the CONTRIBUTION rate: a top-up is a contribution the customer chose. */
    @Transactional
    public void creditTopUp(UUID topUpId, BigDecimal amount, LocalDate confirmedOn) {
        TopUpRequest request = topUps.findById(topUpId).orElse(null);
        if (request == null || !request.markCollected()) {
            return;
        }
        topUps.save(request);
        Account account = loadOpen(request.getPolicyNumber());
        LocalDate effective = confirmedOn.isBefore(account.getOpenedOn()) ? account.getOpenedOn() : confirmedOn;
        int year = PolicyYears.of(account.getOpenedOn(), effective);
        BigDecimal pct = productApi.resolveAccumulationPlan(account.getProductVersionId()).chargesFor(year)
            .contributionAllocationPercent();
        BigDecimal charge = amount.multiply(pct).divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
        ledger.post(request.getPolicyNumber(), new LedgerService.Source("topup", "topup:" + topUpId), List.of(
            LedgerService.Line.of(EntryType.TOP_UP, amount, effective, "Top-up from " + request.getPayerRef()),
            LedgerService.Line.of(EntryType.ALLOCATION_CHARGE, charge.negate(), effective,
                "Allocation charge " + pct.stripTrailingZeros().toPlainString() + "% (policy year " + year + ")")),
            request.getRequestedBy(), null);
    }

    @Transactional
    public void failTopUp(UUID topUpId) {
        topUps.findById(topUpId).filter(TopUpRequest::markFailed).ifPresent(topUps::save);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TopUpView> listTopUps(String policyNumber) {
        return topUps.findByPolicyNumberOrderByRequestedAtDesc(policyNumber).stream().map(this::toView).toList();
    }

    /**
     * One person, unlike a withdrawal: it records money that has ALREADY arrived, with its document,
     * and moves nothing out. If two-person recording is wanted later, it takes the withdrawal's shape.
     */
    @Override
    @Transactional
    public TransferInView recordTransferIn(String policyNumber, BigDecimal amount, String sourceScheme,
                                           String documentRef, String recordedBy) {
        if (sourceScheme == null || sourceScheme.isBlank()) {
            throw new AccumulationStateException("A transfer in must name the scheme it came from");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new AccumulationStateException("A transfer in must be for more than zero");
        }
        Account account = loadOpen(policyNumber);
        deposits.refuseMovement(account);
        TransferIn transfer = transfers.save(new TransferIn(TenantContext.get(), policyNumber,
            amount.setScale(2, RoundingMode.UNNECESSARY), account.getCurrency(), sourceScheme, documentRef, recordedBy));
        LocalDate today = LocalDate.now();
        LocalDate effective = today.isBefore(account.getOpenedOn()) ? account.getOpenedOn() : today;
        int year = PolicyYears.of(account.getOpenedOn(), effective);
        BigDecimal pct = productApi.resolveAccumulationPlan(account.getProductVersionId()).chargesFor(year)
            .transferAllocationPercent();
        BigDecimal charge = transfer.getAmount().multiply(pct).divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
        ledger.post(policyNumber, new LedgerService.Source("transfer", "transfer:" + transfer.getTransferId()), List.of(
            LedgerService.Line.of(EntryType.TRANSFER_IN, transfer.getAmount(), effective, "Transfer in from " + sourceScheme),
            LedgerService.Line.of(EntryType.ALLOCATION_CHARGE, charge.negate(), effective,
                "Transfer allocation charge " + pct.stripTrailingZeros().toPlainString() + "% (policy year " + year + ")")),
            recordedBy, null);
        return toView(transfer);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransferInView> listTransfersIn(String policyNumber) {
        return transfers.findByPolicyNumberOrderByRecordedAtDesc(policyNumber).stream().map(this::toView).toList();
    }

    private TopUpView toView(TopUpRequest t) {
        return new TopUpView(t.getTopUpId(), t.getPolicyNumber(), t.getAmount(), t.getCurrency(), t.getPayerRef(),
            t.getStatus(), t.getRequestedBy(), t.getRequestedAt());
    }

    private TransferInView toView(TransferIn t) {
        return new TransferInView(t.getTransferId(), t.getPolicyNumber(), t.getAmount(), t.getCurrency(),
            t.getSourceScheme(), t.getDocumentRef(), t.getRecordedBy(), t.getRecordedAt());
    }

    // ---- Adjustments: a person's correction, two people (task 6) -------------------------------

    @Override
    @Transactional
    public AdjustmentView proposeAdjustment(String policyNumber, BigDecimal amount, String reason, String proposedBy) {
        if (amount == null || amount.signum() == 0) {
            throw new AccumulationStateException("An adjustment must move the balance by something");
        }
        if (reason == null || reason.isBlank()) {
            throw new AccumulationStateException("An adjustment needs a reason");
        }
        loadOpen(policyNumber);
        return toView(adjustments.save(new AdjustmentRequest(TenantContext.get(), policyNumber,
            amount.setScale(2, RoundingMode.UNNECESSARY), reason, proposedBy)));
    }

    /**
     * Posts the ADJUSTMENT entry with created_by = the proposer and approved_by = the approver -- the
     * exact pair ledger_entry_adjustment_approved compares, so the database refuses a same-person
     * adjustment even if this aggregate somehow did not.
     */
    @Override
    @Transactional
    public AdjustmentView approveAdjustment(UUID adjustmentId, String approvedBy) {
        AdjustmentRequest request = adjustments.findById(adjustmentId)
            .orElseThrow(() -> new AccumulationStateException("No adjustment " + adjustmentId));
        loadOpen(request.getPolicyNumber());
        request.approve(approvedBy);
        ledger.post(request.getPolicyNumber(), new LedgerService.Source("adjustment", "adjustment:" + adjustmentId),
            List.of(LedgerService.Line.of(EntryType.ADJUSTMENT, request.getAmount(), LocalDate.now(), request.getReason())),
            request.getProposedBy(), approvedBy);
        return toView(adjustments.save(request));
    }

    @Override
    @Transactional
    public AdjustmentView rejectAdjustment(UUID adjustmentId, String rejectedBy) {
        AdjustmentRequest request = adjustments.findById(adjustmentId)
            .orElseThrow(() -> new AccumulationStateException("No adjustment " + adjustmentId));
        request.reject(rejectedBy);
        return toView(adjustments.save(request));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdjustmentView> listAdjustments(String policyNumber) {
        return adjustments.findByPolicyNumberOrderByProposedAtDesc(policyNumber).stream().map(this::toView).toList();
    }

    private AdjustmentView toView(AdjustmentRequest a) {
        return new AdjustmentView(a.getAdjustmentId(), a.getPolicyNumber(), a.getAmount(), a.getReason(), a.getStatus(),
            a.getProposedBy(), a.getProposedAt(), a.getDecidedBy(), a.getDecidedAt());
    }

    // ---- Once per Idempotency-Key: what the REST layer calls ---------------------------------------
    //
    // Not @Transactional: IdempotentRequests opens the one transaction the create and its key share,
    // and the unkeyed call inside it joins that transaction (a self-call, so its own annotation is not
    // what applies -- the template's transaction is).

    @Override
    public WithdrawalView requestWithdrawal(String policyNumber, BigDecimal amount, String payeeRef, String requestedBy,
                                            String idempotencyKey) {
        return keyed.once(idempotencyKey, "WITHDRAWAL", policyNumber, requestedBy,
            () -> requestWithdrawal(policyNumber, amount, payeeRef, requestedBy), WithdrawalView::withdrawalId,
            id -> toView(withdrawals.findById(id).orElseThrow()));
    }

    @Override
    public TopUpView requestTopUp(String policyNumber, BigDecimal amount, String payerRef, String requestedBy,
                                  String idempotencyKey) {
        return keyed.once(idempotencyKey, "TOP_UP", policyNumber, requestedBy,
            () -> requestTopUp(policyNumber, amount, payerRef, requestedBy), TopUpView::topUpId,
            id -> toView(topUps.findById(id).orElseThrow()));
    }

    @Override
    public TransferInView recordTransferIn(String policyNumber, BigDecimal amount, String sourceScheme, String documentRef,
                                           String recordedBy, String idempotencyKey) {
        return keyed.once(idempotencyKey, "TRANSFER_IN", policyNumber, recordedBy,
            () -> recordTransferIn(policyNumber, amount, sourceScheme, documentRef, recordedBy), TransferInView::transferId,
            id -> toView(transfers.findById(id).orElseThrow()));
    }

    @Override
    public AdjustmentView proposeAdjustment(String policyNumber, BigDecimal amount, String reason, String proposedBy,
                                            String idempotencyKey) {
        return keyed.once(idempotencyKey, "ADJUSTMENT", policyNumber, proposedBy,
            () -> proposeAdjustment(policyNumber, amount, reason, proposedBy), AdjustmentView::adjustmentId,
            id -> toView(adjustments.findById(id).orElseThrow()));
    }

    @Override
    public RateDeclarationView proposeRate(UUID productId, BigDecimal ratePercent, LocalDate effectiveFrom, String proposedBy,
                                           String idempotencyKey) {
        return keyed.once(idempotencyKey, "RATE_DECLARATION", productId.toString(), proposedBy,
            () -> proposeRate(productId, ratePercent, effectiveFrom, proposedBy), RateDeclarationView::declarationId,
            id -> toView(rates.findById(id).orElseThrow()));
    }

    // ---- Fixed-term deposits (2026-10-02): the rules live in Deposits --------------------------

    @Override
    @Transactional(readOnly = true)
    public boolean isDeposit(String policyNumber) {
        return accounts.findById(policyNumber).map(a -> deposits.isDepositVersion(a.getProductVersionId())).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DepositView> findDeposit(String policyNumber) {
        return deposits.find(policyNumber);
    }

    @Override
    public MaturityInstructionView recordMaturityInstruction(String policyNumber, MaturityAction action, Integer termMonths,
                                                             String payeeRef, String recordedBy, String idempotencyKey) {
        return keyed.once(idempotencyKey, "MATURITY_INSTRUCTION", policyNumber, recordedBy,
            () -> deposits.instruct(policyNumber, action, termMonths, payeeRef, recordedBy),
            MaturityInstructionView::instructionId, deposits::instructionView);
    }

    @Override
    public DepositPeriodView payOutMaturedDeposit(String policyNumber, String payeeRef, String requestedBy,
                                                  String idempotencyKey) {
        return keyed.once(idempotencyKey, "DEPOSIT_PAYOUT", policyNumber, requestedBy,
            () -> deposits.payOutAwaiting(policyNumber, payeeRef, requestedBy),
            DepositPeriodView::periodId, deposits::periodView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AwaitingPayeeView> listAwaitingPayee() {
        return deposits.awaiting();
    }

    // ---- Statements (task 8) ----------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public StatementView statement(String policyNumber, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new AccumulationStateException("A statement's period must end on or after it starts");
        }
        Account account = accounts.findById(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber));
        StatementView view = StatementBuilder.build(policyNumber, account.getCurrency(), entries(policyNumber), from, to,
            account.getLastSeq());
        reconcile(view);
        return view;
    }

    /**
     * The statement against an independent sum, not against itself. A statement that does not add up
     * must never reach a customer, so a mismatch refuses to file at all.
     */
    private void reconcile(StatementView view) {
        BigDecimal ledgerTotal = entries.sumThrough(view.policyNumber(), view.periodTo(), view.lastSeq());
        if (view.closingBalance().compareTo(ledgerTotal) != 0) {
            throw new IllegalStateException("Statement for " + view.policyNumber() + " to " + view.periodTo()
                + " closes at " + view.closingBalance() + " but the ledger holds " + ledgerTotal + " -- not filed");
        }
    }

    @Override
    @Transactional
    public StatementRecordView generateStatement(String policyNumber, LocalDate from, LocalDate to, String generatedBy) {
        return toView(fileStatement(policyNumber, from, to, generatedBy, false));
    }

    /**
     * Shared by on-demand and the annual run; only the annual run sets {@code annual}, and only then
     * does communication send the SMS. Filed once per (policy, period, last seq): asked again with
     * nothing new posted it returns the existing record, and a later correction -- a new entry with
     * a higher seq -- makes a NEW statement rather than changing an old one.
     */
    Statement fileStatement(String policyNumber, LocalDate from, LocalDate to, String generatedBy, boolean annual) {
        StatementView view = statement(policyNumber, from, to);
        Optional<Statement> existing = statementRepository.findByPolicyNumberAndPeriodFromAndPeriodToAndLastSeq(
            policyNumber, from, to, view.lastSeq());
        if (existing.isPresent()) {
            return existing.get();
        }
        // The upload is the one side effect a rollback cannot undo; a failed save after it leaves an
        // orphan object. The same trade every DocumentApi caller on this platform makes.
        byte[] pdf = StatementPdf.render(view, "Nlolo Life");
        String ref = documentApi.upload("policy:" + policyNumber, DocumentType.ACCOUNT_STATEMENT, generatedBy,
            new java.io.ByteArrayInputStream(pdf), pdf.length, "application/pdf",
            "statement-" + policyNumber + "-" + from + "-" + to + ".pdf");
        Statement saved = statementRepository.save(new Statement(TenantContext.get(), policyNumber, from, to,
            view.lastSeq(), ref, generatedBy));
        Account account = accounts.findById(policyNumber).orElseThrow();
        events.publishEvent(DomainEventEnvelope.of("accumulation.StatementIssued", TenantContext.get(), Map.of(
            "statementId", saved.getStatementId().toString(),
            "policyNumber", policyNumber,
            "policyholderPartyId", account.getPolicyholderPartyId(),
            "periodFrom", from.toString(),
            "periodTo", to.toString(),
            "closingBalance", Map.of("amount", view.closingBalance().toPlainString(), "currencyCode", view.currency()),
            "interestCredited", Map.of("amount", view.interestCredited().toPlainString(), "currencyCode", view.currency()),
            "annual", annual)));
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StatementRecordView> listStatements(String policyNumber) {
        return statementRepository.findByPolicyNumberOrderByPeriodToDescGeneratedAtDesc(policyNumber).stream()
            .map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] statementPdf(UUID statementId) {
        Statement s = statementRepository.findById(statementId)
            .orElseThrow(() -> new AccumulationStateException("No statement " + statementId));
        return documentApi.download(s.getDocumentRef());
    }

    private StatementRecordView toView(Statement s) {
        return new StatementRecordView(s.getStatementId(), s.getPolicyNumber(), s.getPeriodFrom(), s.getPeriodTo(),
            s.getLastSeq(), s.getDocumentRef(), s.getGeneratedBy(), s.getGeneratedAt());
    }

    // ---- Closing: surrender, maturity, death, free-look (task 7) --------------------------------
    //
    // Every closing is ONE posting: interest from the day after the last month-end up to the closing
    // date, then the closing entry for the whole balance, with the account closed in the same
    // transaction. Each caller takes the row lock first, so a contribution landing concurrently is
    // either in the interest or waits behind the close -- never missed by one and refused by the other.

    /** The day after the last month-end on or before {@code date}, or the opening day. */
    private LocalDate interestFrom(Account account, LocalDate date) {
        return entries.findByPolicyNumberOrderBySeq(account.getPolicyNumber()).stream()
            .filter(e -> e.type() == EntryType.INTEREST && "month-end".equals(sourceTypeOf(e)) && !e.getEffectiveDate().isAfter(date))
            .map(LedgerEntry::getEffectiveDate).max(LocalDate::compareTo)
            .map(d -> d.plusDays(1)).orElse(account.getOpenedOn());
    }

    private String sourceTypeOf(LedgerEntry e) {
        return postings.findById(e.getPostingId()).map(Posting::getSourceType).orElse("");
    }

    @Override
    @Transactional(readOnly = true)
    public ClosingQuote quoteClosing(String policyNumber, LocalDate asOf) {
        Account account = loadOpen(policyNumber);
        BigDecimal interest = valuer.interestBetween(account, interestFrom(account, asOf), asOf);
        return new ClosingQuote(policyNumber, asOf, account.getBalance(), interest, account.getBalance().add(interest),
            account.getCurrency());
    }

    /** Interest to the day, then the whole balance out, in one posting; closes the account. */
    private BigDecimal close(Account account, LedgerService.Source source, EntryType type, LocalDate on, String reason,
                             String closedReason, String createdBy, String approvedBy) {
        BigDecimal interest = valuer.interestBetween(account, interestFrom(account, on), on);
        BigDecimal value = account.getBalance().add(interest);
        ledger.post(account.getPolicyNumber(), source, List.of(
            LedgerService.Line.of(EntryType.INTEREST, interest, on, "Interest to " + on),
            LedgerService.Line.of(type, value.negate(), on, reason)), createdBy, approvedBy);
        account.close(closedReason, on);
        deposits.endRunning(account, DepositPeriodStatus.TERMINATED, interest, on);
        accounts.save(account);
        return value;
    }

    /**
     * {@code policy.AccountSurrenderApproved}: close on the approval day and pay the value less the
     * surrender charge, through payment, under SURRENDER_PAYOUT with the surrender request id as the
     * source -- so policy's SurrenderPaymentListener marks the request PAID unchanged. The charge is
     * kept by the insurer and is not a ledger entry: the account is emptied either way. A failed
     * payment leaves the SURRENDER entry standing -- cover has already stopped -- and finance retries
     * on step 1's existing path.
     */
    @Transactional
    public void closeForSurrender(String policyNumber, UUID surrenderRequestId, String payeeRef,
                                  BigDecimal chargePercent, String approvedBy, LocalDate on) {
        Account account = accounts.lockForPosting(policyNumber).orElse(null);
        if (account == null || account.status() != AccountStatus.OPEN) {
            return; // redelivered after the close, or not an account policy
        }
        BigDecimal value = close(account, new LedgerService.Source("surrender", "surrender:" + surrenderRequestId),
            EntryType.SURRENDER, on, "Surrendered", "SURRENDERED", "system", approvedBy);
        BigDecimal paid = value.multiply(HUNDRED.subtract(chargePercent)).divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
        if (paid.signum() <= 0) {
            log.error("Surrender {} of policy {} values to nothing after a {}% charge; no payment requested",
                surrenderRequestId, policyNumber, chargePercent);
            return;
        }
        events.publishEvent(DomainEventEnvelope.of("accumulation.PayoutRequested", TenantContext.get(), Map.of(
            "purpose", "SURRENDER_PAYOUT",
            "sourceRef", surrenderRequestId.toString(),
            // The bare request id, as step 1's own surrender payout uses -- payment's key registry
            // pays one request once whichever path published it.
            "idempotencyKey", surrenderRequestId.toString(),
            "policyNumber", policyNumber,
            "payeeRef", payeeRef,
            "amount", Map.of("amount", paid.toPlainString(), "currencyCode", account.getCurrency()),
            // IFRS 17 I3b: the value before the surrender charge, so the ledger can earn the charge (7310).
            "grossAmount", Map.of("amount", value.toPlainString(), "currencyCode", account.getCurrency()))));
    }

    /**
     * {@code policy.PolicyCancelledFreeLook}: the account is emptied. No interest -- a free-look
     * cancels the contract from inception -- and the customer is refunded step 2's premiums less
     * deductions; this entry only records that the account no longer holds anything. Keyed on the
     * policy, because the event carries no cancellation id and a policy is cancelled once.
     */
    @Transactional
    public void closeForFreeLook(String policyNumber, String cancelledBy) {
        Account account = accounts.lockForPosting(policyNumber).orElse(null);
        if (account == null || account.status() != AccountStatus.OPEN) {
            return;
        }
        ledger.post(policyNumber, new LedgerService.Source("freelook", "freelook:" + policyNumber), List.of(
            LedgerService.Line.of(EntryType.FREE_LOOK_REFUND, account.getBalance().negate(), LocalDate.now(),
                "Cancelled in the free-look period; refunded as premiums less deductions")),
            "system", cancelledBy);
        account.close("FREE_LOOK", LocalDate.now());
        deposits.endRunning(account, DepositPeriodStatus.CANCELLED, BigDecimal.ZERO.setScale(2), LocalDate.now());
        accounts.save(account);
    }

    /**
     * Called by benefitpayout as an ACCOUNT_VALUE maturity falls due, inside its transaction -- so the
     * closing entry and the DUE instalment commit together or not at all.
     */
    @Override
    @Transactional
    public BigDecimal closeForMaturity(String policyNumber, UUID instalmentId, LocalDate dueDate) {
        LedgerService.Source source = new LedgerService.Source("instalment", "instalment:" + instalmentId);
        Optional<Posting> done = postings.findByTenantIdAndSourceTypeAndSourceRef(TenantContext.get(), source.type(), source.ref());
        if (done.isPresent()) {
            return entries.findByPostingIdOrderBySeq(done.get().getPostingId()).stream()
                .filter(e -> e.type() == EntryType.MATURITY).map(e -> e.getAmount().negate())
                .findFirst().orElse(BigDecimal.ZERO.setScale(2));
        }
        Account account = accounts.lockForPosting(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber));
        if (account.status() != AccountStatus.OPEN) {
            return BigDecimal.ZERO.setScale(2);
        }
        return close(account, source, EntryType.MATURITY, dueDate, "Matured", "MATURED", "system", null);
    }

    /**
     * Called by the annuity module as a pension vests, inside its transaction -- so the closing entry,
     * the lump sum and the annuity it buys commit together or not at all. Keyed on the policy: a
     * pension vests once.
     */
    @Override
    @Transactional
    public BigDecimal closeForVesting(String policyNumber, LocalDate vestingDate) {
        LedgerService.Source source = new LedgerService.Source("vesting", "vesting:" + policyNumber);
        Optional<Posting> done = postings.findByTenantIdAndSourceTypeAndSourceRef(TenantContext.get(), source.type(), source.ref());
        if (done.isPresent()) {
            return entries.findByPostingIdOrderBySeq(done.get().getPostingId()).stream()
                .filter(e -> e.type() == EntryType.VESTING).map(e -> e.getAmount().negate())
                .findFirst().orElse(BigDecimal.ZERO.setScale(2));
        }
        Account account = accounts.lockForPosting(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber));
        if (account.status() != AccountStatus.OPEN) {
            return BigDecimal.ZERO.setScale(2);
        }
        return close(account, source, EntryType.VESTING, vestingDate, "Vested", "VESTED", "system", null);
    }

    /** A locked pension (spec Q9): nothing leaves before it vests. Product first, as everywhere. */
    private void refuseIfLockedPension(Account account) {
        VestingTerms vesting = productApi.resolveAnnuityPlan(account.getProductVersionId()).vesting();
        if (vesting != null && !Boolean.TRUE.equals(vesting.surrenderBeforeVesting())) {
            throw new AccumulationStateException("This pension cannot be surrendered or withdrawn from before it vests");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public DeathValuation valueAtDeath(String policyNumber, LocalDate dateOfDeath) {
        Account account = accounts.findById(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber));
        List<LedgerEntry> all = entries.findByPolicyNumberOrderBySeq(policyNumber);
        BigDecimal atDeath = keptAtDeath(all, dateOfDeath).stream()
            .map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal interest = account.status() == AccountStatus.OPEN
            ? valuer.interestBetween(account, interestFrom(account, dateOfDeath), dateOfDeath) : BigDecimal.ZERO;
        BigDecimal premiumsBefore = sumOfPremiums(all, e -> !e.getEffectiveDate().isAfter(dateOfDeath));
        BigDecimal contributionsAfter = sumOfPremiums(all, e -> e.getEffectiveDate().isAfter(dateOfDeath));
        return new DeathValuation(atDeath.add(interest).setScale(2, RoundingMode.HALF_EVEN),
            premiumsBefore.setScale(2, RoundingMode.HALF_EVEN), contributionsAfter.setScale(2, RoundingMode.HALF_EVEN),
            account.getCurrency());
    }

    /** "Premiums" for the percentage floor: what the customer paid in -- a transfer in is not a premium. */
    private static BigDecimal sumOfPremiums(List<LedgerEntry> all, java.util.function.Predicate<LedgerEntry> when) {
        return all.stream().filter(e -> (e.type() == EntryType.CONTRIBUTION || e.type() == EntryType.TOP_UP) && when.test(e))
            .map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The entries a death leaves standing: everything effective on or before it, plus a REVERSAL
     * dated later whose original was on or before it -- that reversal restores money the life
     * assured owned when they died. Shared by valueAtDeath and closeForDeath, so the figure a claim is
     * approved against and the figure the account pays out are one calculation.
     */
    private static List<LedgerEntry> keptAtDeath(List<LedgerEntry> all, LocalDate dateOfDeath) {
        Map<UUID, LedgerEntry> byId = all.stream().collect(Collectors.toMap(LedgerEntry::getEntryId, e -> e));
        return all.stream().filter(e -> !e.getEffectiveDate().isAfter(dateOfDeath)
            || (e.type() == EntryType.REVERSAL && byId.containsKey(e.getReversesEntryId())
                && !byId.get(e.getReversesEntryId()).getEffectiveDate().isAfter(dateOfDeath))).toList();
    }

    /**
     * {@code claims.ClaimApproved} with claimType DEATH. Every entry effective after the death is
     * reversed -- newest first, so the running balance passes back through states that already existed
     * and never dips below zero -- then interest to the death, then DEATH_CLAIM takes the balance.
     * Contributions reversed this way are owed to the estate; the death ceiling adds them to the
     * claim, so they reach the claimant with the death benefit.
     */
    @Transactional
    public void closeForDeath(String policyNumber, UUID claimId, LocalDate dateOfDeath, String approvedBy) {
        Account account = accounts.lockForPosting(policyNumber).orElse(null);
        if (account == null || account.status() != AccountStatus.OPEN) {
            return;
        }
        List<LedgerEntry> all = entries.findByPolicyNumberOrderBySeq(policyNumber);
        Set<UUID> kept = keptAtDeath(all, dateOfDeath).stream().map(LedgerEntry::getEntryId).collect(Collectors.toSet());
        Set<UUID> alreadyReversed = all.stream().map(LedgerEntry::getReversesEntryId).filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
        List<LedgerService.Line> lines = new java.util.ArrayList<>();
        for (LedgerEntry e : all.reversed()) {
            if (!kept.contains(e.getEntryId()) && e.type() != EntryType.REVERSAL && !alreadyReversed.contains(e.getEntryId())) {
                lines.add(new LedgerService.Line(EntryType.REVERSAL, e.getAmount().negate(), dateOfDeath,
                    "Dated after the death on " + dateOfDeath, e.getEntryId()));
            }
        }
        BigDecimal reversedTotal = lines.stream().map(LedgerService.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal interest = valuer.interestBetween(account, interestFrom(account, dateOfDeath), dateOfDeath);
        BigDecimal value = account.getBalance().add(reversedTotal).add(interest);
        lines.add(LedgerService.Line.of(EntryType.INTEREST, interest, dateOfDeath, "Interest to the date of death"));
        lines.add(LedgerService.Line.of(EntryType.DEATH_CLAIM, value.negate(), dateOfDeath, "Death claim " + claimId));
        ledger.post(policyNumber, new LedgerService.Source("claim", "claim:" + claimId), lines, "system", approvedBy);
        account.close("DEATH", dateOfDeath);
        deposits.endRunning(account, DepositPeriodStatus.TERMINATED, interest, dateOfDeath);
        accounts.save(account);
    }

    // ---- Month-end: interest, the policy fee, exhaustion (task 4) --------------------------------

    /** Policy states that are on cover, and so owe the month's fee. No cover, no fee. */
    private static final Set<PolicyStatus> ON_COVER =
        EnumSet.of(PolicyStatus.ACTIVE, PolicyStatus.REINSTATED, PolicyStatus.PAID_UP, PolicyStatus.SUSPENDED);

    /**
     * Every month that has COMPLETED since the account's last month-end, oldest first, each its own
     * posting -- so a drain that was down for a quarter catches up exactly, and a drain that runs
     * twice posts nothing the second time (the source ref names the policy and the month).
     */
    @Transactional
    public void postMonthEnds(String policyNumber, LocalDate today) {
        Account account = accounts.lockForPosting(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber));
        LocalDate lastCompleted = today.withDayOfMonth(1).minusDays(1);
        LocalDate previous = account.getLastMonthEnd() != null ? account.getLastMonthEnd()
            : account.getOpenedOn().withDayOfMonth(1).minusDays(1);
        for (LocalDate monthEnd = endOfMonthAfter(previous); !monthEnd.isAfter(lastCompleted);
             monthEnd = endOfMonthAfter(monthEnd)) {
            if (account.status() != AccountStatus.OPEN) {
                return;
            }
            postMonthEnd(account, previous.plusDays(1), monthEnd);
            previous = monthEnd;
        }
    }

    private static LocalDate endOfMonthAfter(LocalDate monthEnd) {
        LocalDate next = monthEnd.plusDays(1);
        return next.withDayOfMonth(next.lengthOfMonth());
    }

    /**
     * One month. {@code account} is the managed entity {@code lockForPosting} returned;
     * {@code LedgerService.post} locks the same row in the same transaction and gets the same
     * instance, so the head it advances is the one this method reads next.
     */
    private void postMonthEnd(Account account, LocalDate from, LocalDate monthEnd) {
        if (deposits.isDepositVersion(account.getProductVersionId())) {
            // A deposit earns for its term, posted once at the end (D4): no monthly interest, no fee.
            account.monthEndPostedThrough(monthEnd);
            accounts.save(account);
            return;
        }
        String policyNumber = account.getPolicyNumber();
        if (account.getLastSeq() == 0) {
            // Never paid into: nothing to credit, nothing to charge, and nothing to lapse. A policy
            // whose first premium never arrives is not-taken-up's business, not exhaustion's.
            account.monthEndPostedThrough(monthEnd);
            accounts.save(account);
            return;
        }
        BigDecimal interest = valuer.interestBetween(account, from, monthEnd);
        PolicyStatus status = policyApi.getPolicy(policyNumber).status();
        // A part first month is free: the fee is for a month of cover the account had all of.
        boolean wholeMonth = !account.getOpenedOn().isAfter(monthEnd.withDayOfMonth(1));
        BigDecimal fee = wholeMonth && ON_COVER.contains(status)
            ? productApi.resolveAccumulationPlan(account.getProductVersionId())
                .chargesFor(PolicyYears.of(account.getOpenedOn(), monthEnd)).monthlyPolicyFee()
            : BigDecimal.ZERO;
        BigDecimal available = account.getBalance().add(interest);
        BigDecimal taken = fee.min(available);
        boolean exhausted = fee.signum() > 0 && taken.compareTo(available) == 0;

        ledger.post(policyNumber,
            // The policy number is in the ref because ux_posting_source is unique per TENANT: without
            // it the second policy's month would be refused as a duplicate of the first's.
            new LedgerService.Source("month-end", "month-end:" + policyNumber + ":" + YearMonth.from(monthEnd)),
            List.of(
                LedgerService.Line.of(EntryType.INTEREST, interest, monthEnd, "Interest " + YearMonth.from(monthEnd)),
                LedgerService.Line.of(EntryType.POLICY_FEE, taken.negate(), monthEnd,
                    exhausted && taken.compareTo(fee) < 0
                        ? "Policy fee " + YearMonth.from(monthEnd) + " (" + taken + " of " + fee + " -- the account is exhausted)"
                        : "Policy fee " + YearMonth.from(monthEnd))),
            "system", null);
        account.monthEndPostedThrough(monthEnd);
        if (exhausted) {
            account.close("EXHAUSTED", monthEnd);
            // False means the policy was already off cover by another route; the account closes
            // either way, because it holds nothing.
            policyApi.lapseExhaustedAccount(policyNumber, monthEnd);
        }
        accounts.save(account);
    }

    /** {@code policy.PolicyReinstated}: an EXHAUSTED account comes back, at the zero it closed on. */
    @Transactional
    public void reopenOnReinstatement(String policyNumber) {
        accounts.findById(policyNumber)
            .filter(a -> a.status() == AccountStatus.CLOSED && "EXHAUSTED".equals(a.getClosedReason()))
            .ifPresent(a -> { a.reopen(); accounts.save(a); });
    }

    // ---- Declared rates, two people (task 4) ------------------------------------------------------

    @Override
    @Transactional
    public RateDeclarationView proposeRate(UUID productId, BigDecimal ratePercent, LocalDate effectiveFrom, String proposedBy) {
        if (ratePercent.signum() < 0 || ratePercent.compareTo(HUNDRED) > 0) {
            throw new AccumulationStateException("A declared rate must be between 0% and 100%");
        }
        refuseIfReachingBack(productId, effectiveFrom);
        return toView(rates.save(new RateDeclaration(TenantContext.get(), productId, ratePercent, effectiveFrom, proposedBy)));
    }

    @Override
    @Transactional
    public RateDeclarationView approveRate(UUID declarationId, String approvedBy) {
        RateDeclaration declaration = rates.findById(declarationId)
            .orElseThrow(() -> new AccumulationStateException("No rate declaration " + declarationId));
        // Again at approval: the month-end run may have passed the date since it was proposed.
        refuseIfReachingBack(declaration.getProductId(), declaration.getEffectiveFrom());
        declaration.approve(approvedBy);
        try {
            // saveAndFlush so ux_rate_declaration_effective fires HERE, inside the catch -- a plain
            // save would flush at commit, outside it, and the caller would meet a 500.
            return toView(rates.saveAndFlush(declaration));
        } catch (DataIntegrityViolationException e) {
            throw new AccumulationStateException("A rate is already approved for this product from "
                + declaration.getEffectiveFrom() + ". Withdrawing an approved rate is not possible; declare a new one "
                + "from a later date.");
        }
    }

    @Override
    @Transactional
    public RateDeclarationView withdrawRate(UUID declarationId, String withdrawnBy) {
        RateDeclaration declaration = rates.findById(declarationId)
            .orElseThrow(() -> new AccumulationStateException("No rate declaration " + declarationId));
        declaration.withdraw();
        return toView(rates.save(declaration));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RateDeclarationView> listRates(UUID productId) {
        return rates.findByProductIdOrderByEffectiveFromDescProposedAtDesc(productId).stream().map(this::toView).toList();
    }

    /** A rate may not rewrite interest a customer has already been credited. */
    private void refuseIfReachingBack(UUID productId, LocalDate effectiveFrom) {
        LocalDate postedThrough = accounts.latestMonthEndForProduct(productId);
        if (postedThrough != null && !effectiveFrom.isAfter(postedThrough)) {
            throw new AccumulationStateException("Interest on this product is already credited up to "
                + postedThrough + ". A rate effective from " + effectiveFrom + " would rewrite it; declare it from "
                + postedThrough.plusDays(1) + " or later.");
        }
    }

    private RateDeclarationView toView(RateDeclaration r) {
        return new RateDeclarationView(r.getDeclarationId(), r.getProductId(), r.getRatePercent(), r.getEffectiveFrom(),
            r.status(), r.getProposedBy(), r.getProposedAt(), r.getApprovedBy(), r.getApprovedAt());
    }

    /**
     * {@code policy.PolicyIssued}: open an account if, and only if, the version is ACCOUNT-basis.
     * Idempotent on the account's presence -- an event may be redelivered.
     */
    @Transactional
    public void openIfAccountVersion(String policyNumber, UUID productVersionId, LocalDate issueDate) {
        if (accounts.existsById(policyNumber) || !productApi.resolveAccumulationPlan(productVersionId).isAccount()) {
            return;
        }
        PolicyView policy = policyApi.getPolicy(policyNumber);
        // Cover's start, not the issue date: a policy year -- and so the charge row -- counts from
        // when cover began, the arrangement benefitpayout's schedule already uses.
        LocalDate opened = policy.commencementDate() != null ? policy.commencementDate() : issueDate;
        accounts.save(new Account(TenantContext.get(), policyNumber, policy.productId(), productVersionId,
            policy.policyholderPartyId(), policy.premiumCurrency(), opened));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountView> findAccount(String policyNumber) {
        return accounts.findById(policyNumber).map(Views::of);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAccount(String policyNumber) {
        return accounts.existsById(policyNumber);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerEntryView> entries(String policyNumber) {
        var list = entries.findByPolicyNumberOrderBySeq(policyNumber);
        Map<UUID, Posting> byId = postings.findAllById(list.stream().map(e -> e.getPostingId()).distinct().toList())
            .stream().collect(Collectors.toMap(Posting::getPostingId, Function.identity()));
        return list.stream().map(e -> Views.of(e, byId.get(e.getPostingId()))).toList();
    }

    Account loadOpen(String policyNumber) {
        Account account = accounts.findById(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber));
        account.requireOpen();
        return account;
    }

    /**
     * {@code billing.PremiumCollected}: the gross premium in, then the year's allocation charge out,
     * as ONE posting keyed on the invoice -- so a redelivery, however many, posts nothing more.
     *
     * <p>The policy year is the year the money ARRIVED in, not the year the invoice was due: a
     * premium paid late in year 2 for a year-1 instalment is charged at year 2's rate. That is what
     * the customer is told on the charge schedule ("charges taken from each payment, by policy
     * year"), and it needs no knowledge of which period an invoice covered.
     */
    @Transactional
    public void creditContribution(String policyNumber, UUID invoiceId, BigDecimal amount, LocalDate collectedOn) {
        creditContribution(policyNumber, invoiceId, amount, collectedOn, null);
    }

    /** {@code payerRef}: the number the money came from, when billing knows it -- a deposit's default payee. */
    @Transactional
    public void creditContribution(String policyNumber, UUID invoiceId, BigDecimal amount, LocalDate collectedOn,
                                   String payerRef) {
        Optional<Account> found = accounts.findById(policyNumber);
        if (found.isEmpty()) {
            return; // a scale policy -- not ours
        }
        Account account = found.get();
        if (account.status() != AccountStatus.OPEN) {
            // Never credited to a closed account -- and not thrown either: throwing inside a
            // listener only adds a stack trace. The money is real and somebody must give it back.
            log.error("Premium for invoice {} on policy {} arrived after its account closed ({}); {} must be refunded "
                + "by hand", invoiceId, policyNumber, account.getClosedReason(), amount);
            return;
        }
        // A first premium can arrive BEFORE a future-dated commencement. It is dated to the day
        // cover starts: it cannot earn interest before the account exists, and PolicyYears refuses
        // a date before cover -- which inside this listener would drop the customer's money with
        // nothing but a log line.
        LocalDate effective = collectedOn.isBefore(account.getOpenedOn()) ? account.getOpenedOn() : collectedOn;
        if (deposits.isDepositVersion(account.getProductVersionId())) {
            deposits.credit(account, invoiceId, amount, effective, payerRef);
            return;
        }
        int policyYear = PolicyYears.of(account.getOpenedOn(), effective);
        AccumulationChargeRow charges = productApi.resolveAccumulationPlan(account.getProductVersionId())
            .chargesFor(policyYear);
        BigDecimal charge = amount.multiply(charges.contributionAllocationPercent())
            .divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
        ledger.post(policyNumber, LedgerService.Source.invoice(invoiceId), List.of(
            LedgerService.Line.of(EntryType.CONTRIBUTION, amount, effective, "Premium collected"),
            LedgerService.Line.of(EntryType.ALLOCATION_CHARGE, charge.negate(), effective,
                "Allocation charge " + charges.contributionAllocationPercent().stripTrailingZeros().toPlainString()
                    + "% (policy year " + policyYear + ")")),
            "system", null);
    }
}
