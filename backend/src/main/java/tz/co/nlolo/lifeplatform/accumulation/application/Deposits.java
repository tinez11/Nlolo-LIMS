package tz.co.nlolo.lifeplatform.accumulation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.api.*;
import tz.co.nlolo.lifeplatform.accumulation.domain.Account;
import tz.co.nlolo.lifeplatform.accumulation.domain.DepositPeriod;
import tz.co.nlolo.lifeplatform.accumulation.domain.LedgerEntry;
import tz.co.nlolo.lifeplatform.accumulation.domain.MaturityInstruction;
import tz.co.nlolo.lifeplatform.accumulation.domain.Posting;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.*;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Fixed-term deposits (2026-10-02): the terms, their interest, the client's instruction and the
 * maturity run. The ledger stays LedgerService's -- every movement here is a posting with a source
 * reference -- so step 3's three rules hold unchanged: accumulation owns the balance, entries are
 * never edited, and one reference posts once.
 */
@Service("accumulationDeposits")
public class Deposits {

    private static final Logger log = LoggerFactory.getLogger(Deposits.class);

    private final AccountRepository accounts;
    private final DepositPeriodRepository periods;
    private final MaturityInstructionRepository instructions;
    private final PostingRepository postings;
    private final LedgerEntryRepository entries;
    private final LedgerService ledger;
    private final PolicyApi policyApi;
    private final ProductApi productApi;
    private final ApplicationEventPublisher events;

    public Deposits(AccountRepository accounts, DepositPeriodRepository periods, MaturityInstructionRepository instructions,
                    PostingRepository postings, LedgerEntryRepository entries, LedgerService ledger, PolicyApi policyApi,
                    ProductApi productApi, ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.periods = periods;
        this.instructions = instructions;
        this.postings = postings;
        this.entries = entries;
        this.ledger = ledger;
        this.policyApi = policyApi;
        this.productApi = productApi;
        this.events = events;
    }

    /**
     * Asked of product, not of deposit_period, so an ordinary account's path never reaches V3's
     * tables -- which keeps V3 out of every test class that only knows step 3.
     */
    boolean isDepositVersion(UUID productVersionId) {
        return productApi.resolveDepositPlan(productVersionId).isDeposit();
    }

    Optional<DepositPeriod> running(String policyNumber) {
        return periods.findByPolicyNumberAndStatus(policyNumber, DepositPeriodStatus.RUNNING.name());
    }

    static BigDecimal interestTo(DepositPeriod p, LocalDate day) {
        return DepositInterest.accrued(p.getPrincipal(), p.getRatePercent(), p.getStartDate(), p.getMaturityDate(), day);
    }

    /** D5: nothing in, nothing out during the term. Adjustments stay -- they are the two-person correction. */
    void refuseMovement(Account account) {
        if (!isDepositVersion(account.getProductVersionId())) {
            return;
        }
        String until = running(account.getPolicyNumber()).map(p -> p.getMaturityDate().toString()).orElse("the end of its term");
        throw new AccumulationStateException("This is a fixed-term deposit: nothing can be added or taken out until it matures on "
            + until);
    }

    /**
     * {@code billing.PremiumCollected} on a deposit. The deposit goes in as ONE posting keyed on the
     * invoice, with no allocation charge, and period 1 opens on the day it arrived, at the policy
     * version's rate for this amount and term. The policy's term moves to follow the money (§R16).
     */
    @Transactional
    public void credit(Account account, UUID invoiceId, BigDecimal amount, LocalDate on, String payerRef) {
        String policyNumber = account.getPolicyNumber();
        if (postings.findByTenantIdAndSourceTypeAndSourceRef(TenantContext.get(), "invoice", "invoice:" + invoiceId).isPresent()) {
            return; // a redelivery
        }
        if (!periods.findByPolicyNumberOrderBySeq(policyNumber).isEmpty()) {
            log.error("A second premium (invoice {}) arrived on fixed-term deposit {}; a deposit takes one payment. {} must be "
                + "refunded by hand", invoiceId, policyNumber, amount);
            return;
        }
        Integer term = policyApi.getPolicy(policyNumber).policyTermMonths();
        BigDecimal rate = (term == null ? Optional.<BigDecimal>empty()
                : productApi.resolveDepositPlan(account.getProductVersionId()).rateFor(amount, term))
            .orElseThrow(() -> new AccumulationStateException("Fixed-term deposit " + policyNumber + ": no rate for "
                + amount.toPlainString() + " over " + term + " months on its version; the deposit is NOT credited"));
        ledger.post(policyNumber, LedgerService.Source.invoice(invoiceId), List.of(LedgerService.Line.of(EntryType.CONTRIBUTION,
                amount, on, "Deposit for " + term + " months at " + rate.stripTrailingZeros().toPlainString() + "% for the term")),
            "system", null);
        LocalDate maturity = policyApi.restateDepositTerm(policyNumber, on, term);
        periods.save(new DepositPeriod(TenantContext.get(), policyNumber, 1, amount, term, rate,
            account.getProductVersionId(), on, maturity, blankToNull(payerRef)));
    }

    /** Unposted interest on the running term up to {@code day}; zero when no term runs (§R6). */
    BigDecimal interestTo(Account account, LocalDate day) {
        return running(account.getPolicyNumber()).map(p -> interestTo(p, day)).orElse(BigDecimal.ZERO.setScale(2));
    }

    /** An early exit: the closing posting carried the interest, and the term ends with it, in the same transaction. */
    void endRunning(Account account, DepositPeriodStatus how, BigDecimal interest, LocalDate on) {
        if (!isDepositVersion(account.getProductVersionId())) {
            return;
        }
        running(account.getPolicyNumber()).ifPresent(p -> {
            p.end(how, interest, on);
            periods.save(p);
        });
    }

    @Transactional(readOnly = true)
    public Optional<DepositView> find(String policyNumber) {
        Optional<Account> found = accounts.findById(policyNumber);
        if (found.isEmpty() || !isDepositVersion(found.get().getProductVersionId())) {
            return Optional.empty();
        }
        Account account = found.get();
        List<DepositPeriod> all = periods.findByPolicyNumberOrderBySeq(policyNumber);
        Optional<DepositPeriod> run = all.stream().filter(p -> p.status() == DepositPeriodStatus.RUNNING).findFirst();
        MaturityInstructionView instruction = run.flatMap(p -> instructions.findByPeriodIdAndSupersededAtIsNull(p.getPeriodId()))
            .map(Deposits::view).orElse(null);
        String defaultPayee = all.isEmpty() ? null : all.get(all.size() - 1).getDefaultPayeeRef();
        return Optional.of(new DepositView(policyNumber, account.getCurrency(), all.stream().map(Deposits::view).toList(),
            instruction, run.map(p -> interestTo(p, LocalDate.now())).orElse(BigDecimal.ZERO.setScale(2)), defaultPayee,
            awaitingPayee(account, all), termsOfferedToday(account.getProductId()),
            run.map(p -> schedule(p, account.getCurrency())).orElse(null)));
    }

    /**
     * The running term worked forward: its maturity figures, and the value at each monthly date if closed then --
     * interest to that day by {@link DepositInterest#accrued}, the same sum an early surrender pays (before any
     * surrender charge). A date past maturity is clamped to it, so the last row is the maturity figure.
     */
    static tz.co.nlolo.lifeplatform.accumulation.api.DepositScheduleView schedule(DepositPeriod p, String currency) {
        List<tz.co.nlolo.lifeplatform.accumulation.api.DepositScheduleView.Row> rows = new java.util.ArrayList<>();
        for (int month = 1; month <= p.getTermMonths(); month++) {
            LocalDate on = month == p.getTermMonths() ? p.getMaturityDate() : p.getStartDate().plusMonths(month);
            BigDecimal interest = DepositInterest.accrued(p.getPrincipal(), p.getRatePercent(), p.getStartDate(),
                p.getMaturityDate(), on);
            rows.add(new tz.co.nlolo.lifeplatform.accumulation.api.DepositScheduleView.Row(on, interest,
                p.getPrincipal().add(interest)));
        }
        BigDecimal atMaturity = DepositInterest.full(p.getPrincipal(), p.getRatePercent());
        return new tz.co.nlolo.lifeplatform.accumulation.api.DepositScheduleView(p.getPrincipal(), currency,
            p.getTermMonths(), p.getRatePercent(), p.getStartDate(), p.getMaturityDate(), atMaturity,
            p.getPrincipal().add(atMaturity), List.copyOf(rows));
    }

    /** §R5: matured, nothing running, money still on an open account. */
    static boolean awaitingPayee(Account account, List<DepositPeriod> all) {
        return account.status() == AccountStatus.OPEN && account.getBalance().signum() > 0 && !all.isEmpty()
            && all.stream().noneMatch(p -> p.status() == DepositPeriodStatus.RUNNING)
            && all.get(all.size() - 1).status() == DepositPeriodStatus.MATURED;
    }

    /** The terms a reinvestment may choose: those of the version active for new business (D8). */
    List<Integer> termsOfferedToday(UUID productId) {
        try {
            UUID active = productApi.getActiveSnapshot(productId, LocalDate.now()).productVersionId();
            return productApi.resolveDepositPlan(active).terms();
        } catch (RuntimeException e) {
            return List.of(); // the product no longer sells: a reinvestment would fall back to paying out
        }
    }

    // ---- The client's instruction (D7) ---------------------------------------------------------

    /** Any time before maturity, and changeable until then: a change supersedes, never edits. */
    @Transactional
    public MaturityInstructionView instruct(String policyNumber, MaturityAction action, Integer termMonths, String payeeRef,
                                            String by) {
        if (action == null) {
            throw new AccumulationStateException("Say whether the deposit is reinvested or paid out");
        }
        Account account = accounts.findById(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber));
        if (!isDepositVersion(account.getProductVersionId())) {
            throw new AccumulationStateException("Policy " + policyNumber + " is not a fixed-term deposit");
        }
        DepositPeriod period = running(policyNumber).orElseThrow(() -> new AccumulationStateException(
            "Policy " + policyNumber + " has no running deposit term to instruct"));
        if (action == MaturityAction.REINVEST) {
            List<Integer> offered = termsOfferedToday(account.getProductId());
            if (termMonths == null || !offered.contains(termMonths)) {
                throw new AccumulationStateException("This product now offers terms of " + offered + " months, not " + termMonths);
            }
        } else if (termMonths != null) {
            throw new AccumulationStateException("A pay-out instruction takes no term");
        }
        instructions.findByPeriodIdAndSupersededAtIsNull(period.getPeriodId()).ifPresent(previous -> {
            previous.supersede();
            instructions.saveAndFlush(previous); // free ux_maturity_instruction_current before the new row
        });
        MaturityInstruction saved = instructions.save(new MaturityInstruction(TenantContext.get(), policyNumber,
            period.getPeriodId(), action, action == MaturityAction.REINVEST ? termMonths : null,
            action == MaturityAction.PAY_OUT ? blankToNull(payeeRef) : null, by));
        return view(saved);
    }

    @Transactional(readOnly = true)
    public MaturityInstructionView instructionView(UUID instructionId) {
        return view(instructions.findById(instructionId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public DepositPeriodView periodView(UUID periodId) {
        return view(periods.findById(periodId).orElseThrow());
    }

    // ---- Maturity (spec §4.3) ------------------------------------------------------------------

    /**
     * One deposit's maturity, in one transaction: the term's interest (once, keyed on the period),
     * then the instruction. Idempotent: a term that no longer RUNS is left alone.
     */
    @Transactional
    public void mature(String policyNumber, LocalDate today) {
        Account account = accounts.lockForPosting(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber));
        DepositPeriod period = running(policyNumber).orElse(null);
        if (period == null || period.getMaturityDate().isAfter(today) || account.status() != AccountStatus.OPEN) {
            return;
        }
        LocalDate on = period.getMaturityDate();
        BigDecimal interest = DepositInterest.full(period.getPrincipal(), period.getRatePercent());
        ledger.post(policyNumber, new LedgerService.Source("deposit-interest", "deposit-interest:" + period.getPeriodId()),
            List.of(LedgerService.Line.of(EntryType.INTEREST, interest, on,
                "Interest for the term, " + period.getRatePercent().stripTrailingZeros().toPlainString() + "%")),
            "system", null);
        period.end(DepositPeriodStatus.MATURED, interest, on);
        periods.saveAndFlush(period); // free ux_deposit_period_running before a reinvested term is written

        MaturityInstruction instruction = instructions.findByPeriodIdAndSupersededAtIsNull(period.getPeriodId()).orElse(null);
        String note = null;
        if (instruction != null && instruction.action() == MaturityAction.REINVEST) {
            Optional<DepositPeriod> next = reinvest(account, period, instruction.getTermMonths(), today);
            if (next.isPresent()) {
                matured(policyNumber, period, "REINVESTED", interest, next.get().getPeriodId());
                return;
            }
            note = "Reinvestment for " + instruction.getTermMonths() + " months is no longer offered; paid out instead";
        }
        String payee = instruction != null && instruction.getPayeeRef() != null ? instruction.getPayeeRef()
            : period.getDefaultPayeeRef();
        if (payee == null) {
            log.warn("Fixed-term deposit {} matured with no number to pay it to; {} waits on the account for a payee",
                policyNumber, account.getBalance());
            matured(policyNumber, period, "AWAITING_PAYEE", interest, null);
            return;
        }
        payOut(account, period, payee, note, on, "system");
        matured(policyNumber, period, "PAID_OUT", interest, null);
    }

    /**
     * D6, D8: the whole balance, for the instructed term, at the version active for new business
     * today. The policy's term grows by the new months and the new period ends when the policy now
     * does (§R16).
     */
    private Optional<DepositPeriod> reinvest(Account account, DepositPeriod ended, int term, LocalDate today) {
        UUID active;
        try {
            active = productApi.getActiveSnapshot(account.getProductId(), today).productVersionId();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        BigDecimal balance = account.getBalance();
        Optional<BigDecimal> rate = productApi.resolveDepositPlan(active).rateFor(balance, term);
        if (rate.isEmpty()) {
            return Optional.empty();
        }
        PolicyView policy = policyApi.getPolicy(account.getPolicyNumber());
        LocalDate maturity = policyApi.restateDepositTerm(account.getPolicyNumber(), policy.commencementDate(),
            policy.policyTermMonths() + term);
        return Optional.of(periods.save(new DepositPeriod(TenantContext.get(), account.getPolicyNumber(), ended.getSeq() + 1,
            balance, term, rate.get(), active, ended.getMaturityDate(), maturity, ended.getDefaultPayeeRef())));
    }

    /**
     * The whole balance out, closing the account, then the payment requested through payment. The
     * attempt number makes each try its own source reference (§R7).
     */
    private void payOut(Account account, DepositPeriod period, String payee, String note, LocalDate on, String by) {
        int attempt = period.recordPayoutAttempt();
        periods.save(period);
        String ref = period.getPeriodId() + ":" + attempt;
        BigDecimal value = account.getBalance();
        ledger.post(account.getPolicyNumber(), new LedgerService.Source("deposit-maturity", "deposit-maturity:" + ref),
            List.of(LedgerService.Line.of(EntryType.MATURITY, value.negate(), on,
                note != null ? note : "Matured; paid to " + payee)), by, null);
        account.close("MATURED", on);
        accounts.save(account);
        events.publishEvent(DomainEventEnvelope.of("accumulation.PayoutRequested", TenantContext.get(), Map.of(
            "purpose", "DEPOSIT_MATURITY_PAYOUT",
            "sourceRef", ref,
            "idempotencyKey", "deposit-maturity:" + ref,
            "policyNumber", account.getPolicyNumber(),
            "payeeRef", payee,
            "amount", Map.of("amount", value.toPlainString(), "currencyCode", account.getCurrency()))));
        policyApi.markMatured(account.getPolicyNumber(), "system:accumulation");
    }

    private void matured(String policyNumber, DepositPeriod period, String outcome, BigDecimal interest, UUID nextPeriodId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("policyNumber", policyNumber);
        payload.put("periodId", period.getPeriodId().toString());
        payload.put("outcome", outcome);
        payload.put("interest", interest.toPlainString());
        if (nextPeriodId != null) payload.put("nextPeriodId", nextPeriodId.toString());
        events.publishEvent(DomainEventEnvelope.of("accumulation.DepositMatured", TenantContext.get(), payload));
    }

    /** Finance records the number a waiting deposit is paid to (§R5). */
    @Transactional
    public DepositPeriodView payOutAwaiting(String policyNumber, String payeeRef, String by) {
        if (payeeRef == null || payeeRef.isBlank()) {
            throw new AccumulationStateException("A payout needs a payee reference");
        }
        Account account = accounts.lockForPosting(policyNumber).orElseThrow(() -> new AccountNotFoundException(policyNumber));
        List<DepositPeriod> all = periods.findByPolicyNumberOrderBySeq(policyNumber);
        if (!awaitingPayee(account, all)) {
            throw new AccumulationStateException("Policy " + policyNumber + " has no matured deposit waiting for a payee");
        }
        DepositPeriod period = all.get(all.size() - 1);
        payOut(account, period, payeeRef, "Matured; paid to " + payeeRef + " (payee recorded by staff)", LocalDate.now(), by);
        return view(period);
    }

    /**
     * {@code payment.DisbursementFailed} for a deposit's maturity: the MATURITY entry is reversed,
     * never edited, and the account reopens to wait for a payee. A completed payment needs nothing:
     * the account closed and the policy matured when it was requested.
     */
    @Transactional
    public void settlePayout(String sourceRef, boolean paid) {
        if (paid) {
            // IFRS 17 I3b: the maturity left the bank; the ledger clears what the MATURITY entry made payable (2340).
            postings.findByTenantIdAndSourceTypeAndSourceRef(TenantContext.get(), "deposit-maturity",
                    "deposit-maturity:" + sourceRef)
                .map(p -> entries.findByPostingIdOrderBySeq(p.getPostingId()).get(0))
                .ifPresent(e -> events.publishEvent(DomainEventEnvelope.of("accumulation.PayoutPaid", TenantContext.get(),
                    Map.of("payoutRef", "deposit-maturity:" + sourceRef, "paymentRef", sourceRef,
                           "policyNumber", e.getPolicyNumber(),
                           "amount", Map.of("amount", e.getAmount().abs().toPlainString(),
                               "currencyCode", accounts.findById(e.getPolicyNumber()).orElseThrow().getCurrency())))));
            return;
        }
        Posting posting = postings.findByTenantIdAndSourceTypeAndSourceRef(TenantContext.get(), "deposit-maturity",
            "deposit-maturity:" + sourceRef).orElse(null);
        if (posting == null) {
            return;
        }
        LedgerEntry original = entries.findByPostingIdOrderBySeq(posting.getPostingId()).get(0);
        if (postings.findByTenantIdAndSourceTypeAndSourceRef(TenantContext.get(), "reversal",
                "reversal:" + original.getEntryId()).isPresent()) {
            return; // a redelivery
        }
        Account account = accounts.lockForPosting(original.getPolicyNumber()).orElseThrow();
        ledger.post(account.getPolicyNumber(), new LedgerService.Source("reversal", "reversal:" + original.getEntryId()),
            List.of(new LedgerService.Line(EntryType.REVERSAL, original.getAmount().negate(), LocalDate.now(),
                "Maturity payment failed; waiting for a payee", original.getEntryId())), "system", null);
        account.reopenAwaitingPayee();
        accounts.save(account);
        log.warn("The maturity payment of deposit {} failed; the money is back on the account and waits for a payee",
            account.getPolicyNumber());
    }

    @Transactional(readOnly = true)
    public List<AwaitingPayeeView> awaiting() {
        return periods.findByStatusOrderByClosedOn(DepositPeriodStatus.MATURED.name()).stream()
            .map(DepositPeriod::getPolicyNumber).distinct()
            .flatMap(n -> accounts.findById(n).stream())
            .flatMap(a -> {
                List<DepositPeriod> all = periods.findByPolicyNumberOrderBySeq(a.getPolicyNumber());
                return awaitingPayee(a, all)
                    ? java.util.stream.Stream.of(new AwaitingPayeeView(a.getPolicyNumber(), a.getBalance(), a.getCurrency(),
                        all.get(all.size() - 1).getClosedOn()))
                    : java.util.stream.Stream.empty();
            }).toList();
    }

    static DepositPeriodView view(DepositPeriod p) {
        return new DepositPeriodView(p.getPeriodId(), p.getPolicyNumber(), p.getSeq(), p.getPrincipal(), p.getTermMonths(),
            p.getRatePercent(), p.getRateVersionId(), p.getStartDate(), p.getMaturityDate(), p.status(), p.getInterestPosted(),
            p.getClosedOn());
    }

    static MaturityInstructionView view(MaturityInstruction i) {
        return new MaturityInstructionView(i.getInstructionId(), i.getPeriodId(), i.action(), i.getTermMonths(), i.getPayeeRef(),
            i.getRecordedBy(), i.getRecordedAt());
    }

    static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
