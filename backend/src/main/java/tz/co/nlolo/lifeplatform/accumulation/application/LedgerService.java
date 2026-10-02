package tz.co.nlolo.lifeplatform.accumulation.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.api.AccountNotFoundException;
import tz.co.nlolo.lifeplatform.accumulation.api.EntryType;
import tz.co.nlolo.lifeplatform.accumulation.api.LedgerEntryView;
import tz.co.nlolo.lifeplatform.accumulation.domain.Account;
import tz.co.nlolo.lifeplatform.accumulation.domain.LedgerEntry;
import tz.co.nlolo.lifeplatform.accumulation.domain.Posting;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.AccountRepository;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.LedgerEntryRepository;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.PostingRepository;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The ONE place a posting is written. Everything that changes a balance comes through {@link #post}.
 *
 * <p>Exactly-once has two layers. A redelivery -- the common case -- finds its posting already there
 * and returns empty without touching anything. A RACE -- two deliveries at once -- is stopped by
 * {@code ux_posting_source}: the loser's insert fails, its whole transaction rolls back, and nothing
 * half-posted survives. The check is the fast path; the index is the guarantee.
 */
@Service
public class LedgerService {

    /** Where a posting came from. {@code ref} is unique per {@code type} within a tenant. */
    public record Source(String type, String ref) {
        public static Source invoice(UUID invoiceId) { return new Source("invoice", "invoice:" + invoiceId); }
    }

    /** One entry to write. The service assigns seq and balance_after; the caller never does. */
    public record Line(EntryType type, BigDecimal amount, LocalDate effectiveDate, String reason, UUID reversesEntryId) {
        public static Line of(EntryType type, BigDecimal amount, LocalDate effectiveDate, String reason) {
            return new Line(type, amount, effectiveDate, reason, null);
        }
    }

    private final AccountRepository accounts;
    private final PostingRepository postings;
    private final LedgerEntryRepository entries;
    private final PolicyApi policyApi;
    private final ApplicationEventPublisher events;

    public LedgerService(AccountRepository accounts, PostingRepository postings, LedgerEntryRepository entries,
                         PolicyApi policyApi, ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.postings = postings;
        this.entries = entries;
        this.policyApi = policyApi;
        this.events = events;
    }

    /**
     * Write one source's entries, in order, or nothing if that source is already posted.
     *
     * <p>Zero-amount lines are dropped rather than written: an allocation charge of 0% is not a
     * transaction, and an entry that moves nothing only clutters the statement.
     */
    @Transactional
    public Optional<List<LedgerEntryView>> post(String policyNumber, Source source, List<Line> lines,
                                                String createdBy, String approvedBy) {
        UUID tenantId = TenantContext.get();
        if (postings.existsByTenantIdAndSourceTypeAndSourceRef(tenantId, source.type(), source.ref())) {
            return Optional.empty();
        }
        Account account = accounts.lockForPosting(policyNumber)
            .orElseThrow(() -> new AccountNotFoundException(policyNumber));

        // saveAndFlush, not save: the unique index must fire HERE, before any entry is written, so a
        // lost race fails on the posting rather than half-way through its lines.
        Posting posting = postings.saveAndFlush(new Posting(tenantId, policyNumber, source.type(), source.ref(),
            createdBy, approvedBy));

        List<LedgerEntryView> written = new ArrayList<>();
        for (Line line : lines) {
            if (line.amount().signum() == 0) {
                continue;
            }
            int seq = account.advance(line.amount());
            LedgerEntry entry = entries.saveAndFlush(new LedgerEntry(tenantId, posting.getPostingId(), policyNumber,
                seq, line.type(), line.amount(), account.getBalance(), line.effectiveDate(),
                line.reversesEntryId(), line.reason(), createdBy, approvedBy));
            written.add(Views.of(entry, posting));
        }
        accounts.save(account);
        // The projection follows the ledger in the same transaction, so a reader of the policy
        // never sees a cash value the ledger does not hold.
        policyApi.restateAccountValue(policyNumber, account.getBalance());
        // One event per posting (spec §8). Audit records every domain event generically, so this is
        // the ledger's audit trail; and a later GL change can subscribe without touching this module.
        events.publishEvent(DomainEventEnvelope.of("accumulation.PostingRecorded", tenantId, Map.of(
            "postingId", posting.getPostingId().toString(),
            "policyNumber", policyNumber,
            "sourceType", source.type(),
            "sourceRef", source.ref(),
            "createdBy", createdBy,
            // Map.of refuses a null; "" is "nobody", and every reader treats it so.
            "approvedBy", approvedBy != null ? approvedBy : "",
            "entries", written.stream().map(e -> Map.of(
                "seq", e.seq(), "type", e.type().name(), "amount", e.amount().toPlainString(),
                "balanceAfter", e.balanceAfter().toPlainString(), "effectiveDate", e.effectiveDate().toString())).toList())));
        return Optional.of(written);
    }
}
