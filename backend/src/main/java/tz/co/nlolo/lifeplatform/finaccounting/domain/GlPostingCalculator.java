package tz.co.nlolo.lifeplatform.finaccounting.domain;

import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns one event's facts into one balanced {@link JournalEntry}. A pure function: no I/O, no
 * Spring, no database, safe to unit-test without a container.
 *
 * <p>That purity is load-bearing rather than stylistic. It makes every account mapping and every
 * balance rule testable directly, and it is what would make a future async listener a thin-wrapper
 * change rather than a rewrite (the design spec's decision 5 defers async until IFRS 17 measurement
 * lands, because the ordering hazard documented at docs/05-event-catalog.md:62 concerns CSM
 * roll-forward, not independent postings).
 *
 * <p>All account mappings live in {@link PostingRule} and are INVENTED PLACEHOLDERS pending Finance
 * sign-off -- see that class's javadoc.
 */
public final class GlPostingCalculator {

    private GlPostingCalculator() {}

    /**
     * @return a balanced two-legged entry, or empty when this event has no accounting consequence
     *         (most events on this platform), or when the amount is not a positive magnitude.
     *         Empty is a normal outcome, never an error -- the caller logs and moves on.
     */
    public static Optional<JournalEntry> calculate(UUID tenantId, String eventType, String sourceRef,
                                                    String policyNumber, BigDecimal amount, String currency,
                                                    String period, String createdBy) {
        if (amount == null || amount.signum() <= 0) {
            return Optional.empty();
        }
        Optional<PostingRule.AccountPair> maybeRule = PostingRule.forEvent(eventType);
        if (maybeRule.isEmpty()) {
            return Optional.empty();
        }
        PostingRule.AccountPair rule = maybeRule.get();

        JournalEntry entry = new JournalEntry(tenantId, eventType, sourceRef, period, policyNumber, createdBy);
        entry.addLeg(rule.debitAccount(), PostingDirection.DR, amount, currency);
        entry.addLeg(rule.creditAccount(), PostingDirection.CR, amount, currency);
        return Optional.of(entry);
    }
}
