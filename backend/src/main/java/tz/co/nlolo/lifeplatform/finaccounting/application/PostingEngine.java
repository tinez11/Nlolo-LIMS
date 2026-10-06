package tz.co.nlolo.lifeplatform.finaccounting.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalSource;
import tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingFacts;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRuleSet;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountSeeder;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.JournalEntryRepository;

import tz.co.nlolo.lifeplatform.finaccounting.api.UnpostedEventNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.UnpostedEventResolvedException;
import tz.co.nlolo.lifeplatform.finaccounting.api.UnpostedEventView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Posts an event's facts through the posting rules (IFRS 17 I3a).
 * <ol>
 *   <li>The contract's classification in force on the event date gives the measurement model and the line
 *       dimensions; an event with no policy is model NONE, a policy with no classification is UNCLASSIFIED (which no
 *       rule names).</li>
 *   <li>The rule set chooses the one rule for the event, model, attributes and date.</li>
 *   <li>The journal is built from the rule's lines (a zero line left out), stamped with the rule set's version, and
 *       posted through {@link FinaccountingApiImpl#postEntry}; what follows a PAA posting (its earning schedule) is
 *       written in the same transaction.</li>
 * </ol>
 * No rule is UNMAPPED and a journal the ledger refuses (a locked or closing period, a heading, a mode) is REFUSED;
 * both go to the unposted-event queue with the facts kept, never dropped. Idempotent on (event, source ref), as
 * posting always was: a redelivered event posts nothing, and a success resolves the event's open queue row.
 *
 * <p>Each posting runs in its own transaction, so a refusal -- which the ledger's guards raise inside the INSERT or,
 * for balance, at COMMIT -- rolls back only that journal, and the queue row is written in a transaction of its own.
 */
@Component
class PostingEngine {

    private static final Logger log = LoggerFactory.getLogger(PostingEngine.class);
    private static final String QUEUED_COUNTER = "lifeplatform_finaccounting_event_unposted_total";

    enum Outcome { POSTED, ALREADY_POSTED, NOTHING_TO_POST, UNMAPPED, REFUSED, ERROR }

    record Result(Outcome outcome, UUID journalEntryId, String detail) {}

    private final PostingRules rules;
    private final PolicyClassifier classifier;
    private final FinaccountingApiImpl ledger;
    private final JournalEntryRepository journals;
    private final ChartOfAccountSeeder seeder;
    private final UnpostedEvents queue;
    private final PaaEarningSchedule paa;
    private final MeterRegistry meters;
    private final TransactionTemplate requiresNew;

    PostingEngine(PostingRules rules, PolicyClassifier classifier, FinaccountingApiImpl ledger,
                  JournalEntryRepository journals, ChartOfAccountSeeder seeder, UnpostedEvents queue,
                  PaaEarningSchedule paa, MeterRegistry meters, PlatformTransactionManager transactionManager) {
        this.rules = rules;
        this.classifier = classifier;
        this.ledger = ledger;
        this.journals = journals;
        this.seeder = seeder;
        this.queue = queue;
        this.paa = paa;
        this.meters = meters;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /** Posts one event's facts under the tenant's context, which the caller has set. */
    Result post(UUID tenantId, PostingFacts facts, String by) {
        return post(tenantId, facts, by, JournalSource.EVENT);
    }

    /** {@code source} SYSTEM for the platform's own runs (PAA earning), which a closing period still takes. */
    Result post(UUID tenantId, PostingFacts facts, String by, JournalSource source) {
        PostingRuleSet set = rules.ruleSet();
        String period = YearMonth.from(facts.eventDate()).toString();
        Result result;
        try {
            result = requiresNew.execute(status -> postInTransaction(tenantId, facts, period, set, by, source));
        } catch (RuntimeException e) {
            // Two deliveries racing (a redelivery, a classification's retry): the loser's journal hits
            // ux_journal_entry_once. The event IS posted, so it is not queued.
            Optional<JournalEntry> won = requiresNew.execute(status ->
                journals.findByTenantIdAndSourceEventAndSourceRef(tenantId, facts.eventType(), facts.sourceRef()));
            if (won != null && won.isPresent()) {
                requiresNew.executeWithoutResult(status -> queue.resolvePosted(tenantId, facts.eventType(),
                    facts.sourceRef(), won.get().getJournalEntryId(), by));
                return new Result(Outcome.ALREADY_POSTED, won.get().getJournalEntryId(), null);
            }
            String message = rootMessage(e);
            boolean refused = message != null && (message.contains("LEDGER_") || message.startsWith("Refusing to post"));
            result = new Result(refused ? Outcome.REFUSED : Outcome.ERROR, null, message);
            if (!refused) {
                log.error("finaccounting could not post {} {}", facts.eventType(), facts.sourceRef(), e);
            }
        }
        if (result.outcome() == Outcome.UNMAPPED || result.outcome() == Outcome.REFUSED
                || result.outcome() == Outcome.ERROR) {
            Result failed = result;
            meters.counter(QUEUED_COUNTER, "eventType", facts.eventType(), "reason", failed.outcome().name()).increment();
            log.warn("{} {} was not posted ({}): {}", facts.eventType(), facts.sourceRef(), failed.outcome(), failed.detail());
            try {
                requiresNew.executeWithoutResult(status -> queue.record(tenantId, facts, period,
                    UnpostedEvents.Reason.valueOf(failed.outcome().name()), failed.detail(), set.versionLabel()));
            } catch (RuntimeException e) {
                log.error("finaccounting could not even queue {} {}; the ledger is missing it", facts.eventType(),
                    facts.sourceRef(), e);
            }
        }
        return result;
    }

    /**
     * Finance retries a queued event with the rules in force now. It posts in the period it is retried in -- the
     * period it first failed in may be closing by then -- with the classification in force today.
     *
     * @throws UnpostedEventNotFoundException if no such event is queued for this tenant
     * @throws UnpostedEventResolvedException if it was already posted or dismissed
     */
    Result retry(UUID tenantId, UUID unpostedEventId, String by) {
        UnpostedEventView row = queue.find(tenantId, unpostedEventId)
            .orElseThrow(() -> new UnpostedEventNotFoundException("Unposted event " + unpostedEventId + " not found"));
        if (row.resolution() != null) {
            throw new UnpostedEventResolvedException("This event was already " + row.resolution().toLowerCase()
                + " by " + row.resolvedBy());
        }
        PostingFacts kept = queue.facts(tenantId, unpostedEventId);
        PostingFacts today = new PostingFacts(kept.eventType(), kept.sourceRef(), kept.policyNumber(), kept.currency(),
            LocalDate.now(LedgerEventListener.CIVIL), kept.amounts(), kept.attributes());
        JournalSource source = PaaEarningJob.EVENT.equals(kept.eventType()) ? JournalSource.SYSTEM : JournalSource.EVENT;
        Result result = post(tenantId, today, by, source);
        if (result.outcome() == Outcome.NOTHING_TO_POST) {
            requiresNew.executeWithoutResult(status -> queue.dismiss(tenantId, unpostedEventId,
                "Retried: the rules in force post nothing for these facts", by));
        }
        return result;
    }

    /** A policy has just been classified: what was queued for want of its classification posts now. */
    void retryUnmapped(UUID tenantId, String policyNumber, String by) {
        List<UUID> waiting = requiresNew.execute(status -> queue.openUnmappedFor(tenantId, policyNumber));
        for (UUID id : waiting) {
            retry(tenantId, id, by);
        }
    }

    private Result postInTransaction(UUID tenantId, PostingFacts facts, String period, PostingRuleSet set, String by,
                                     JournalSource source) {
        seeder.seedIfAbsent(tenantId, by);
        Optional<JournalEntry> existing = journals.findByTenantIdAndSourceEventAndSourceRef(tenantId, facts.eventType(),
            facts.sourceRef());
        if (existing.isPresent()) {
            queue.resolvePosted(tenantId, facts.eventType(), facts.sourceRef(), existing.get().getJournalEntryId(), by);
            return new Result(Outcome.ALREADY_POSTED, existing.get().getJournalEntryId(), null);
        }
        Optional<PolicyClassifier.InForce> classification = facts.policyNumber() == null ? Optional.empty()
            : classifier.inForce(tenantId, facts.policyNumber(), facts.eventDate());
        String model = facts.policyNumber() == null ? PostingRuleSet.NONE
            : classification.map(PolicyClassifier.InForce::model).orElse(PostingRuleSet.UNCLASSIFIED);
        Optional<PostingRuleSet.Rule> rule = set.select(facts.eventType(), model, facts.attributes(), facts.eventDate());
        if (rule.isEmpty()) {
            return new Result(Outcome.UNMAPPED, null, "No rule in " + set.versionLabel() + " posts " + facts.eventType()
                + " for model " + model + ruleAttributes(facts)
                + (PostingRuleSet.UNCLASSIFIED.equals(model)
                    ? "; policy " + facts.policyNumber() + " has no IFRS 17 classification" : ""));
        }

        JournalEntry entry = new JournalEntry(tenantId, facts.eventType(), facts.sourceRef(), period,
            facts.policyNumber(), by).underRuleVersion(set.versionLabel()).withSource(source);
        for (PostingRuleSet.Line line : rule.get().lines()) {
            BigDecimal amount = facts.amount(line.amount());
            if (amount.signum() == 0) {
                continue;
            }
            if (amount.signum() < 0) {
                throw new IllegalStateException("Refusing to post " + facts.eventType() + " " + facts.sourceRef()
                    + ": fact '" + line.amount() + "' is negative (" + amount.toPlainString() + "); rule "
                    + rule.get().id() + " expects a magnitude");
            }
            entry.addLeg(line.account(), line.side(), amount, facts.currency(),
                dimensions(facts, line, classification.orElse(null)));
        }
        if (entry.getLegs().isEmpty()) {
            return new Result(Outcome.NOTHING_TO_POST, null, null);
        }
        if (!entry.isBalanced()) {
            throw new IllegalStateException("Refusing to post " + facts.eventType() + " " + facts.sourceRef()
                + ": rule " + rule.get().id() + " gives an unbalanced journal");
        }
        JournalEntry posted = ledger.postEntry(entry).orElse(entry);
        if ("PAA".equals(model)) {
            paa.afterPosting(tenantId, facts, classification.get(), posted.getJournalEntryId());
        }
        queue.resolvePosted(tenantId, facts.eventType(), facts.sourceRef(), posted.getJournalEntryId(), by);
        return new Result(Outcome.POSTED, posted.getJournalEntryId(), rule.get().id());
    }

    private static LineDimensions dimensions(PostingFacts facts, PostingRuleSet.Line line,
                                             PolicyClassifier.InForce classification) {
        String movement = line.movement();
        if (movement != null && movement.startsWith("attr:")) {
            movement = facts.attribute(movement.substring(5));
        }
        String reference = facts.sourceRef().length() > 100 ? facts.sourceRef().substring(0, 100) : facts.sourceRef();
        if (classification == null) {
            return new LineDimensions(null, null, movement, null, null, null, null, facts.attribute("fund"),
                facts.attribute("refType"), reference);
        }
        return new LineDimensions(classification.groupKey(), classification.model(), movement,
            classification.productId(), classification.portfolio(), classification.channel(), classification.branch(),
            facts.attribute("fund"), facts.attribute("refType"), reference);
    }

    /** The attributes a rule could have tested, not the engine's own (reference type, covers, fund). */
    private static String ruleAttributes(PostingFacts facts) {
        var shape = PostingFactsExtractor.SHAPES.get(facts.eventType());
        var tested = new java.util.TreeMap<String, String>();
        facts.attributes().forEach((k, v) -> {
            if (shape != null && shape.attributes().contains(k)) {
                tested.put(k, v);
            }
        });
        return tested.isEmpty() ? "" : " with " + tested;
    }

    private static String rootMessage(Throwable e) {
        String message = e.getMessage();
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c.getMessage() != null) {
                message = c.getMessage();
            }
        }
        return message;
    }
}
