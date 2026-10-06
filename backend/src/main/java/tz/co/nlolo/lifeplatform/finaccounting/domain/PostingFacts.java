package tz.co.nlolo.lifeplatform.finaccounting.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one event tells the posting rules (IFRS 17 I3a): named amounts, named attributes, and the source it is posted
 * against. A rule's line names an amount; a rule's {@code when} matches attributes. The facts know nothing of accounts --
 * which accounts an event posts to is the rules file's business alone.
 *
 * <p>{@code eventType} and {@code sourceRef} are the journal's idempotency key, exactly as before the rules engine:
 * one event that posts twice (a withdrawal's proceeds and its surrender charge) yields two facts with two refs.
 *
 * @param policyNumber null for an event with no policy behind it (a commission statement, a fund revaluation); such
 *                     an event is matched as model NONE
 */
public record PostingFacts(String eventType, String sourceRef, String policyNumber, String currency, LocalDate eventDate,
                           Map<String, BigDecimal> amounts, Map<String, String> attributes) {

    public PostingFacts {
        amounts = amounts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(amounts));
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /** The named amount, zero when the event did not state it. */
    public BigDecimal amount(String name) {
        BigDecimal value = amounts.get(name);
        return value == null ? BigDecimal.ZERO : value;
    }

    public String attribute(String name) {
        return attributes.get(name);
    }
}
