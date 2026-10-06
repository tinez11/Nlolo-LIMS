package tz.co.nlolo.lifeplatform.finaccounting.domain;

import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingMode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refuses a rules file that would post wrongly (IFRS 17 I3a), at startup, naming each rule at fault -- so a mistake
 * in the file stops the application instead of reaching the ledger. Checks, per rule:
 * <ul>
 *   <li>its event is one the platform extracts facts for, and every {@code when} attribute is one that event has;</li>
 *   <li>its models are known, and it has at least one Dr and one Cr line;</li>
 *   <li>every line's account is a posting account of the guide's chart (2.5) whose mode is AUTO or BOTH -- never MAN,
 *       which the database refuses an event journal on, unless the rule is {@code source: SYSTEM} (the guard lets the
 *       platform's own journals post there);</li>
 *   <li>every line's amount is a fact the event states, and its movement is one of the guide's (2.4);</li>
 *   <li>no other rule could be chosen for the same event, model, attributes and date: two rules for one event with an
 *       identical {@code when}, a model in common and overlapping dates leave the choice to file order.</li>
 * </ul>
 * Balance is not checkable here (lines name facts, not amounts); the ledger's own guard checks each journal.
 */
public final class PostingRuleValidator {

    /** What an event offers the rules: the amounts its lines may name and the attributes its {@code when} may test. */
    public record EventShape(Set<String> amounts, Set<String> attributes) {}

    private PostingRuleValidator() {}

    /** @throws IllegalStateException listing every problem, each prefixed with the rule's id */
    public static void validate(PostingRuleSet set, List<ChartOfAccountBlueprint.Seed> chart,
                                Map<String, EventShape> events) {
        List<String> problems = new ArrayList<>();
        if (set.version() < 1) {
            problems.add("version must be 1 or more");
        }
        Set<String> ids = new HashSet<>();
        for (PostingRuleSet.Rule rule : set.rules()) {
            String id = rule.id() == null ? "(no id)" : rule.id();
            if (rule.id() == null || rule.id().isBlank()) {
                problems.add(id + ": every rule needs an id");
            } else if (!ids.add(rule.id())) {
                problems.add(id + ": the id is used twice");
            }
            checkRule(rule, id, chart, events, problems);
        }
        List<PostingRuleSet.Rule> rules = set.rules();
        for (int i = 0; i < rules.size(); i++) {
            for (int j = i + 1; j < rules.size(); j++) {
                PostingRuleSet.Rule a = rules.get(i);
                PostingRuleSet.Rule b = rules.get(j);
                if (a.event() != null && a.event().equals(b.event()) && a.when().equals(b.when())
                        && intersects(a.expandedModels(), b.expandedModels()) && overlaps(a, b)) {
                    problems.add(b.id() + ": overlaps " + a.id() + " -- same event, same 'when', a model in common and"
                        + " overlapping dates; give one a narrower 'when', other models or other dates");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("posting-rules.yaml is invalid:\n  " + String.join("\n  ", problems));
        }
    }

    private static void checkRule(PostingRuleSet.Rule rule, String id, List<ChartOfAccountBlueprint.Seed> chart,
                                  Map<String, EventShape> events, List<String> problems) {
        EventShape shape = rule.event() == null ? null : events.get(rule.event());
        if (shape == null) {
            problems.add(id + ": event '" + rule.event() + "' is not one the platform posts");
        }
        if (rule.models().isEmpty()) {
            problems.add(id + ": models is empty");
        }
        for (String model : rule.models()) {
            if (!PostingRuleSet.MODELS.contains(model)) {
                problems.add(id + ": model '" + model + "' is not one of " + PostingRuleSet.MODELS);
            }
        }
        if (rule.effectiveTo() != null && !rule.effectiveTo().isAfter(rule.effectiveFrom())) {
            problems.add(id + ": effectiveTo must be after effectiveFrom");
        }
        if (shape != null) {
            for (String attribute : rule.when().keySet()) {
                if (!shape.attributes().contains(attribute)) {
                    problems.add(id + ": 'when' tests '" + attribute + "', which " + rule.event() + " does not have");
                }
            }
        }
        if (!rule.post()) {
            if (!rule.lines().isEmpty()) {
                problems.add(id + ": a rule that posts nothing (post: false) has no lines");
            }
            return;
        }
        boolean dr = false;
        boolean cr = false;
        for (PostingRuleSet.Line line : rule.lines()) {
            dr |= line.side() == PostingDirection.DR;
            cr |= line.side() == PostingDirection.CR;
            if (PostingRuleSet.BANK.equals(line.account())) {
                checkAmountAndMovement(rule, id, line, shape, problems);
                continue;
            }
            ChartOfAccountBlueprint.Seed account = chart.stream()
                .filter(s -> s.code().equals(line.account())).findFirst().orElse(null);
            if (account == null) {
                problems.add(id + ": account " + line.account() + " is not in the chart");
            } else if (!account.postingAllowed()) {
                problems.add(id + ": account " + line.account() + " is a heading and takes no postings");
            } else if (account.mode() == PostingMode.MAN && !rule.system()) {
                problems.add(id + ": account " + line.account() + " is MAN (manual journals only)");
            }
            checkAmountAndMovement(rule, id, line, shape, problems);
        }
        if (!dr || !cr) {
            problems.add(id + ": a rule needs at least one Dr and one Cr line");
        }
    }

    private static void checkAmountAndMovement(PostingRuleSet.Rule rule, String id, PostingRuleSet.Line line,
                                               EventShape shape, List<String> problems) {
        if (shape != null && (line.amount() == null || !shape.amounts().contains(line.amount()))) {
            problems.add(id + ": amount '" + line.amount() + "' is not a fact " + rule.event() + " states "
                + shape.amounts());
        }
        String movement = line.movement();
        if (movement != null && !movement.startsWith("attr:") && !MovementTypes.CODES.contains(movement)) {
            problems.add(id + ": movement '" + movement + "' is not one of the guide's movement types");
        }
        if (movement != null && movement.startsWith("attr:") && shape != null
                && !shape.attributes().contains(movement.substring(5))) {
            problems.add(id + ": movement names attribute '" + movement.substring(5) + "', which " + rule.event()
                + " does not have");
        }
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        return a.stream().anyMatch(b::contains);
    }

    private static boolean overlaps(PostingRuleSet.Rule a, PostingRuleSet.Rule b) {
        LocalDate aEnd = a.effectiveTo() == null ? LocalDate.MAX : a.effectiveTo();
        LocalDate bEnd = b.effectiveTo() == null ? LocalDate.MAX : b.effectiveTo();
        return a.effectiveFrom().isBefore(bEnd) && b.effectiveFrom().isBefore(aEnd);
    }
}
