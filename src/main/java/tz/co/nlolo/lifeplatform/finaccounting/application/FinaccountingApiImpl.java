package tz.co.nlolo.lifeplatform.finaccounting.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.ChartOfAccountView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.GlPostingView;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalEntryNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalEntryView;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccount;
import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPosting;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountRepository;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.GlPostingRepository;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.JournalEntryRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    @Override
    public List<JournalEntryView> listJournalEntries(String period, String policyNumber) {
        UUID tenantId = TenantContext.get();
        List<JournalEntry> entries;
        if (period != null) {
            entries = journalEntryRepository.findByTenantIdAndPeriodOrderByPostedAtDesc(tenantId, period);
            if (policyNumber != null) {
                entries = entries.stream().filter(e -> policyNumber.equals(e.getPolicyNumber())).toList();
            }
        } else if (policyNumber != null) {
            entries = journalEntryRepository.findByTenantIdAndPolicyNumberOrderByPostedAtDesc(tenantId, policyNumber);
        } else {
            entries = journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId);
        }
        return entries.stream().map(this::toView).toList();
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

    private JournalEntryView toView(JournalEntry entry) {
        List<GlPostingView> postings = glPostingRepository
            .findByTenantIdAndJournalEntryIdOrderByDirectionAsc(entry.getTenantId(), entry.getJournalEntryId())
            .stream().map(this::toView).toList();
        return new JournalEntryView(entry.getJournalEntryId(), entry.getSourceEvent(), entry.getSourceRef(),
            entry.getPeriod(), entry.getPolicyNumber(), entry.getPostedAt(), postings);
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
