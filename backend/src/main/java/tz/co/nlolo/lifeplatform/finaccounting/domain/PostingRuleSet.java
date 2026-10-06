package tz.co.nlolo.lifeplatform.finaccounting.domain;

import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The posting rules (IFRS 17 I3a), loaded once from {@code finaccounting/posting-rules.yaml} and never changed while the
 * application runs. Each rule says which journal one event posts for contracts measured under given models: its lines,
 * each an account, a side, the fact that gives the amount and the guide's movement type.
 *
 * <p><b>Choosing a rule.</b> A rule applies to an event when its event matches, its models include the contract's
 * model, every attribute in its {@code when} has the event's value, and the event's date falls in its effective range.
 * Of those, the one with the most {@code when} attributes wins -- a refunded top-up's rule beats the unit-linked
 * payout's general one. Two rules that could both win are refused at startup ({@link PostingRuleValidator}), so the
 * choice never depends on file order.
 *
 * <p><b>Models.</b> GMM, VFA, PAA and IFRS9 are the contract's measurement model from its classification (I2). NONE is
 * an event with no policy behind it. ANY is any classified contract -- never one that is not classified, which matches
 * no rule and is queued, so a contract is never posted to accounts its model was not decided for.
 */
public record PostingRuleSet(int version, List<Rule> rules) {

    public static final String ANY = "ANY";
    public static final String NONE = "NONE";
    /** The model of a policy event whose policy has no classification: no rule names it. */
    public static final String UNCLASSIFIED = "UNCLASSIFIED";
    public static final Set<String> CLASSIFIED = Set.of("GMM", "VFA", "PAA", "IFRS9");
    public static final Set<String> MODELS = Set.of("GMM", "VFA", "PAA", "IFRS9", NONE, ANY);

    /**
     * IFRS 17 I3b: a line's account may be BANK -- the account of the rail that moved the money: 1130 Claims and
     * benefits payment bank account for a bank transfer (EFT), 1140 Mobile money wallets otherwise (user answer Q3).
     */
    public static final String BANK = "BANK";
    public static final String BANK_EFT = "1130";
    public static final String BANK_MOBILE_MONEY = "1140";

    public PostingRuleSet {
        rules = List.copyOf(rules);
    }

    /** One line of a rule's journal. {@code movement} is a guide code, {@code attr:<name>} for an attribute's value, or null. */
    public record Line(PostingDirection side, String account, String amount, String movement) {}

    /**
     * @param post false for a rule that deliberately posts nothing (IFRS 17 I3b) -- an IFRS 9 contract's invoice, whose
     *             money its own account ledger posts. Without such a rule the event would queue as UNMAPPED.
     * @param system true for a rule whose journal is the platform's own ({@code source: SYSTEM}, IFRS 17 I3d): it may
     *             post to MAN accounts -- the ledger guard refuses only an EVENT journal there -- and a closing period
     *             still takes it. PAA earning (I-03) and the reinsurance statement are.
     */
    public record Rule(String id, String event, Set<String> models, Map<String, String> when, LocalDate effectiveFrom,
                       LocalDate effectiveTo, String description, List<Line> lines, boolean post, boolean system) {

        public Rule {
            models = Set.copyOf(models);
            when = Map.copyOf(when);
            lines = List.copyOf(lines);
        }

        public boolean appliesToModel(String model) {
            return models.contains(model) || (models.contains(ANY) && CLASSIFIED.contains(model));
        }

        /** The models this rule matches, ANY spelled out. */
        public Set<String> expandedModels() {
            if (!models.contains(ANY)) {
                return models;
            }
            var all = new java.util.HashSet<>(models);
            all.remove(ANY);
            all.addAll(CLASSIFIED);
            return all;
        }

        public boolean inForceOn(LocalDate date) {
            return !date.isBefore(effectiveFrom) && (effectiveTo == null || date.isBefore(effectiveTo));
        }

        boolean matches(String eventType, String model, Map<String, String> attributes, LocalDate on) {
            return event.equals(eventType) && appliesToModel(model) && inForceOn(on)
                && when.entrySet().stream().allMatch(w -> w.getValue().equals(attributes.get(w.getKey())));
        }
    }

    /** The rule that posts this event, if any. */
    public Optional<Rule> select(String eventType, String model, Map<String, String> attributes, LocalDate on) {
        return rules.stream()
            .filter(r -> r.matches(eventType, model, attributes, on))
            .max(Comparator.comparingInt(r -> r.when().size()));
    }

    /** What every journal posted under these rules records as its {@code rule_version}. */
    public String versionLabel() {
        return "posting-rules v" + version;
    }
}
