package tz.co.nlolo.lifeplatform.finaccounting.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountInUseException;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.ChartOfAccountView;
import tz.co.nlolo.lifeplatform.finaccounting.api.DuplicateAccountCodeException;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.GlPostingView;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalEntryNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalEntryView;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccount;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPosting;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountRepository;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.GlPostingRepository;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.JournalEntryRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Implements {@link FinaccountingApi} (three read methods -- the module publishes no write
 * method at all, see that interface's own javadoc), and additionally exposes {@link
 * #postEntry(JournalEntry)}: the package-private, atomic, idempotent, balance-checked posting
 * primitive that Task 6's per-source-module listeners (injecting this concrete class rather than
 * the interface -- the same shape {@code reinsurance}'s and {@code payment}'s listeners use
 * against their own {@code *ApiImpl}) will call directly.
 */
@Service
public class FinaccountingApiImpl implements FinaccountingApi {

    private static final Pattern ACCOUNT_CODE_PATTERN = Pattern.compile("^[1-5]\\d{3}$");
    private static final int MAX_ACCOUNT_NAME_LENGTH = 200;

    private final JournalEntryRepository journalEntryRepository;
    private final GlPostingRepository glPostingRepository;
    private final ChartOfAccountRepository chartOfAccountRepository;
    private final ApplicationEventPublisher eventPublisher;

    public FinaccountingApiImpl(JournalEntryRepository journalEntryRepository,
                                 GlPostingRepository glPostingRepository,
                                 ChartOfAccountRepository chartOfAccountRepository,
                                 ApplicationEventPublisher eventPublisher) {
        this.journalEntryRepository = journalEntryRepository;
        this.glPostingRepository = glPostingRepository;
        this.chartOfAccountRepository = chartOfAccountRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Persists {@code entry} and one {@link GlPosting} row per leg, atomically, then publishes one
     * {@code finaccounting.GlPostingRecorded} event -- or does nothing and returns
     * {@link Optional#empty()} if this {@code (tenant, sourceEvent, sourceRef)} has already been
     * posted.
     *
     * <p>The {@code existsBy...} check below is a convenience early-return only, saving a round
     * trip and an avoidable {@link IllegalStateException}-shaped stack trace on the common
     * redelivery path; {@code ux_journal_entry_once} (finaccounting/V2 section 6) is the real
     * backstop that makes a genuine race between two threads processing the same redelivered event
     * safe, by failing the losing thread's INSERT outright rather than relying on this check
     * (which has an unavoidable TOCTOU gap under concurrency).
     *
     * <p>Save order is load-bearing, not stylistic: {@code entry} first, so its
     * {@code @GeneratedValue journalEntryId} becomes real, and each {@link GlPosting} only after --
     * {@code gl_posting.journal_entry_id} is {@code NOT NULL} and there is deliberately no setter on
     * {@link GlPosting} to patch it in after the fact (it is an append-only entity).
     *
     * @throws IllegalStateException if {@code !entry.isBalanced()} -- an unbalanced entry is a bug
     *         in the calculator or a caller, and persisting one would silently corrupt the ledger
     */
    @Transactional
    Optional<JournalEntry> postEntry(JournalEntry entry) {
        if (journalEntryRepository.existsByTenantIdAndSourceEventAndSourceRef(
                entry.getTenantId(), entry.getSourceEvent(), entry.getSourceRef())) {
            return Optional.empty();
        }
        if (!entry.isBalanced()) {
            throw new IllegalStateException("Refusing to post an unbalanced journal entry: sourceEvent="
                + entry.getSourceEvent() + " sourceRef=" + entry.getSourceRef());
        }

        journalEntryRepository.save(entry);

        BigDecimal drTotal = BigDecimal.ZERO;
        String currency = null;
        for (JournalEntry.Leg leg : entry.getLegs()) {
            glPostingRepository.save(new GlPosting(entry.getTenantId(), entry.getJournalEntryId(),
                leg.accountCode(), leg.direction(), leg.amount(), leg.currency(),
                entry.getPeriod(), entry.getPolicyNumber(), entry.getSourceEvent(), entry.getSourceRef()));
            if (leg.direction() == PostingDirection.DR) {
                drTotal = drTotal.add(leg.amount());
            }
            currency = leg.currency();
        }

        // GlPostingRecordedPayload (api/asyncapi-events.yaml) was declared per-POSTING
        // (postingId, a singular amount) before M9 existed; this module's real unit of work is a
        // journal ENTRY with two legs. Publishing one event per entry, keyed by the entry's own id
        // and its DR total, is the closest honest fit to that inherited shape -- reconciling the
        // schema itself is Task 7's job, not this one's. groupId is a LinkedHashMap entry (never
        // Map.of, which throws NullPointerException on a null value) because a "group of insurance
        // contracts" is the C1-governed IFRS 17 unit of account and stays null throughout M9.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("postingId", entry.getJournalEntryId());
        payload.put("groupId", null);
        payload.put("period", entry.getPeriod());
        payload.put("amount", Map.of("amount", drTotal.toPlainString(), "currencyCode", currency));
        payload.put("postingType", entry.getSourceEvent());
        eventPublisher.publishEvent(
            DomainEventEnvelope.of("finaccounting.GlPostingRecorded", entry.getTenantId(), payload));

        return Optional.of(entry);
    }

    /**
     * See {@link FinaccountingApi#listJournalEntries} for why this is paged and why both filters are
     * applied in the database.
     *
     * <p><b>Legs are fetched for the whole page in ONE query, not one query per entry.</b> The
     * obvious implementation -- mapping each entry through {@link #toView(JournalEntry)}, which
     * loads its own legs -- is a textbook N+1: a 100-entry page cost 101 queries. Since every entry
     * on a page needs exactly the same shape of child rows, they are batch-loaded by entry id and
     * grouped in memory. Grouping in memory is fine here in a way that FILTERING in memory was not:
     * it happens strictly after the page has been selected, so it cannot change which entries the
     * page contains.
     */
    @Override
    public Page<JournalEntryView> listJournalEntries(String period, String policyNumber, Pageable pageable) {
        UUID tenantId = TenantContext.get();
        Page<JournalEntry> entries;
        if (period != null && policyNumber != null) {
            entries = journalEntryRepository.findByTenantIdAndPeriodAndPolicyNumberOrderByPostedAtDesc(
                tenantId, period, policyNumber, pageable);
        } else if (period != null) {
            entries = journalEntryRepository.findByTenantIdAndPeriodOrderByPostedAtDesc(tenantId, period, pageable);
        } else if (policyNumber != null) {
            entries = journalEntryRepository.findByTenantIdAndPolicyNumberOrderByPostedAtDesc(
                tenantId, policyNumber, pageable);
        } else {
            entries = journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId, pageable);
        }
        if (entries.isEmpty()) {
            // Short-circuited on purpose: an `IN ()` with an empty collection is a needless round
            // trip, and some dialects reject it outright.
            return entries.map(entry -> toView(entry, List.of()));
        }
        Map<UUID, List<GlPosting>> legsByEntry = glPostingRepository
            .findByTenantIdAndJournalEntryIdInOrderByJournalEntryIdAscDirectionAsc(tenantId,
                entries.map(JournalEntry::getJournalEntryId).toList())
            .stream()
            .collect(Collectors.groupingBy(GlPosting::getJournalEntryId, LinkedHashMap::new, Collectors.toList()));
        return entries.map(entry -> toView(entry,
            legsByEntry.getOrDefault(entry.getJournalEntryId(), List.of())));
    }

    @Override
    public JournalEntryView getJournalEntry(UUID journalEntryId) {
        UUID tenantId = TenantContext.get();
        JournalEntry entry = journalEntryRepository.findByJournalEntryIdAndTenantId(journalEntryId, tenantId)
            .orElseThrow(() -> new JournalEntryNotFoundException("Journal entry " + journalEntryId + " not found"));
        return toView(entry);
    }

    @Override
    public List<ChartOfAccountView> listChartOfAccounts() {
        UUID tenantId = TenantContext.get();
        return chartOfAccountRepository.findByTenantIdOrderByAccountCodeAsc(tenantId).stream()
            .map(this::toView).toList();
    }

    /**
     * Defense in depth, same convention as {@code DistributionApiImpl.onboardAgent}'s own
     * re-validation of a field the DTO's bean validation already checks: this method is
     * {@link FinaccountingApi}'s own published contract, reachable by a future non-HTTP caller
     * that bypasses {@code ChartOfAccountController}'s {@code @Valid} entirely.
     */
    @Override
    @Transactional
    public ChartOfAccountView createAccount(String accountCode, String name, String createdBy) {
        UUID tenantId = TenantContext.get();

        if (accountCode == null || !ACCOUNT_CODE_PATTERN.matcher(accountCode).matches()) {
            throw new FinaccountingValidationException(
                "Account code must be 4 digits with a leading 1-5 block (1=ASSET, 2=LIABILITY, "
                    + "3=EQUITY, 4=INCOME, 5=EXPENSE), got: " + accountCode);
        }
        if (name == null || name.isBlank()) {
            throw new FinaccountingValidationException("An account name is required");
        }
        if (name.length() > MAX_ACCOUNT_NAME_LENGTH) {
            throw new FinaccountingValidationException(
                "Account name is " + name.length() + " characters; the maximum is " + MAX_ACCOUNT_NAME_LENGTH);
        }

        // Fast-path check, not the real backstop -- (tenant_id, account_code) is the primary key,
        // so a genuine race is caught by the saveAndFlush/catch below instead, same shape as
        // AgentProfile onboarding's own duplicate-license check.
        if (chartOfAccountRepository.existsByTenantIdAndAccountCode(tenantId, accountCode)) {
            throw new DuplicateAccountCodeException(
                "Account code '" + accountCode + "' already exists in this tenant");
        }

        ChartOfAccount account = ChartOfAccount.root(tenantId, accountCode, name.trim(),
            true, ChartOfAccountBlueprint.SEED_CURRENCY, createdBy);
        try {
            chartOfAccountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateAccountCodeException(
                "Account code '" + accountCode + "' already exists in this tenant");
        }
        return toView(account);
    }

    @Override
    @Transactional
    public ChartOfAccountView renameAccount(String accountCode, String newName, String updatedBy) {
        UUID tenantId = TenantContext.get();
        if (newName == null || newName.isBlank()) {
            throw new FinaccountingValidationException("An account name is required");
        }
        if (newName.length() > MAX_ACCOUNT_NAME_LENGTH) {
            throw new FinaccountingValidationException(
                "Account name is " + newName.length() + " characters; the maximum is " + MAX_ACCOUNT_NAME_LENGTH);
        }
        ChartOfAccount account = findAccountOrThrow(accountCode, tenantId);
        account.rename(newName.trim(), updatedBy);
        chartOfAccountRepository.save(account);
        return toView(account);
    }

    @Override
    @Transactional
    public void deleteAccount(String accountCode) {
        UUID tenantId = TenantContext.get();
        findAccountOrThrow(accountCode, tenantId);
        if (glPostingRepository.existsByTenantIdAndAccountCode(tenantId, accountCode)) {
            throw new AccountInUseException("Account '" + accountCode
                + "' has real postings against it and cannot be deleted; retiring an in-use "
                + "account is a separate, not-yet-built concern");
        }
        chartOfAccountRepository.deleteByTenantIdAndAccountCode(tenantId, accountCode);
    }

    private ChartOfAccount findAccountOrThrow(String accountCode, UUID tenantId) {
        return chartOfAccountRepository.findByTenantIdAndAccountCode(tenantId, accountCode)
            .orElseThrow(() -> new AccountNotFoundException("Account '" + accountCode + "' not found"));
    }

    /** Single-entry form: loads this entry's own legs. Used by {@link #getJournalEntry}, where one
     * extra query IS the whole query -- never by the list path, which batches instead. */
    private JournalEntryView toView(JournalEntry entry) {
        return toView(entry, glPostingRepository
            .findByTenantIdAndJournalEntryIdOrderByDirectionAsc(entry.getTenantId(), entry.getJournalEntryId()));
    }

    private JournalEntryView toView(JournalEntry entry, List<GlPosting> legs) {
        return new JournalEntryView(entry.getJournalEntryId(), entry.getSourceEvent(), entry.getSourceRef(),
            entry.getPeriod(), entry.getPolicyNumber(), entry.getPostedAt(),
            legs.stream().map(this::toView).toList());
    }

    private GlPostingView toView(GlPosting posting) {
        return new GlPostingView(posting.getPostingId(), posting.getJournalEntryId(), posting.getAccountCode(),
            posting.getDirection(), posting.getAmount(), posting.getCurrency(), posting.getPeriod(),
            posting.getPolicyNumber(), posting.getSourceEvent(), posting.getSourceRef());
    }

    private ChartOfAccountView toView(ChartOfAccount account) {
        return new ChartOfAccountView(account.getAccountCode(), account.getName(),
            account.getAccountType(), account.getNormalBalance());
    }
}
