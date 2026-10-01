package tz.co.nlolo.lifeplatform.accumulation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.api.*;
import tz.co.nlolo.lifeplatform.accumulation.domain.Account;
import tz.co.nlolo.lifeplatform.accumulation.domain.Posting;
import tz.co.nlolo.lifeplatform.accumulation.domain.RateDeclaration;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.AccountRepository;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.LedgerEntryRepository;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.PostingRepository;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.RateDeclarationRepository;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.AccumulationChargeRow;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

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

    public AccumulationApiImpl(AccountRepository accounts, PostingRepository postings, LedgerEntryRepository entries,
                               LedgerService ledger, PolicyApi policyApi, ProductApi productApi,
                               AccountValuer valuer, RateDeclarationRepository rates) {
        this.accounts = accounts;
        this.postings = postings;
        this.entries = entries;
        this.ledger = ledger;
        this.policyApi = policyApi;
        this.productApi = productApi;
        this.valuer = valuer;
        this.rates = rates;
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
