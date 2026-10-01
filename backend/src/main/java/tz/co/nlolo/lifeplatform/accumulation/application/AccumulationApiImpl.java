package tz.co.nlolo.lifeplatform.accumulation.application;

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
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AccumulationApiImpl implements AccumulationApi {

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
}
