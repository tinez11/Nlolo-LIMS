package tz.co.nlolo.lifeplatform.accumulation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.api.*;
import tz.co.nlolo.lifeplatform.accumulation.domain.Account;
import tz.co.nlolo.lifeplatform.accumulation.domain.Posting;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.AccountRepository;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.LedgerEntryRepository;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.PostingRepository;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.AccumulationChargeRow;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
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

    public AccumulationApiImpl(AccountRepository accounts, PostingRepository postings, LedgerEntryRepository entries,
                               LedgerService ledger, PolicyApi policyApi, ProductApi productApi) {
        this.accounts = accounts;
        this.postings = postings;
        this.entries = entries;
        this.ledger = ledger;
        this.policyApi = policyApi;
        this.productApi = productApi;
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
