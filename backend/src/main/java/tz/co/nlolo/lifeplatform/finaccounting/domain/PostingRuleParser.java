package tz.co.nlolo.lifeplatform.finaccounting.domain;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads {@code posting-rules.yaml} into a {@link PostingRuleSet}. Shape only: whether the rules make sense against the
 * chart and the events is {@link PostingRuleValidator}'s question. A file that cannot be read as rules fails here with
 * the rule's position named.
 *
 * <pre>
 * version: 1
 * rules:
 *   - id: A-06
 *     event: billing.PremiumInvoiceGenerated
 *     models: [GMM, VFA]
 *     when: {}                      # optional
 *     effectiveFrom: "2020-01-01"   # optional; effectiveTo optional, exclusive
 *     description: Renewal premium falls due
 *     lines:
 *       - {dr: "2122", amount: amount, movement: PRM_REN}
 *       - {cr: "2121", amount: amount, movement: PRM_REN}
 * </pre>
 */
public final class PostingRuleParser {

    /** A rule with no {@code effectiveFrom} has always applied. */
    static final LocalDate BEGINNING = LocalDate.of(2000, 1, 1);

    private PostingRuleParser() {}

    public static PostingRuleSet parse(InputStream in) {
        try (in) {
            Object doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            if (!(doc instanceof Map<?, ?> root)) {
                throw new IllegalStateException("posting rules: the file is not a map with version and rules");
            }
            Object version = root.get("version");
            if (!(version instanceof Integer v)) {
                throw new IllegalStateException("posting rules: 'version' must be a whole number");
            }
            if (!(root.get("rules") instanceof List<?> list)) {
                throw new IllegalStateException("posting rules: 'rules' must be a list");
            }
            List<PostingRuleSet.Rule> rules = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                rules.add(rule(list.get(i), i + 1));
            }
            return new PostingRuleSet(v, rules);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PostingRuleSet.Rule rule(Object raw, int position) {
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalStateException("posting rules: rule #" + position + " is not a map");
        }
        String id = text(m.get("id"));
        String where = "posting rules: rule " + (id != null ? id : "#" + position);
        Set<String> models = new LinkedHashSet<>();
        if (m.get("models") instanceof List<?> ms) {
            ms.forEach(x -> models.add(String.valueOf(x)));
        }
        Map<String, String> when = new LinkedHashMap<>();
        if (m.get("when") instanceof Map<?, ?> w) {
            w.forEach((k, val) -> when.put(String.valueOf(k), String.valueOf(val)));
        } else if (m.get("when") != null) {
            throw new IllegalStateException(where + ": 'when' must be a map of attribute to value");
        }
        List<PostingRuleSet.Line> lines = new ArrayList<>();
        if (m.get("lines") instanceof List<?> ls) {
            for (Object l : ls) {
                lines.add(line(l, where));
            }
        }
        LocalDate from = date(m.get("effectiveFrom"), where);
        return new PostingRuleSet.Rule(id, text(m.get("event")), models, when, from != null ? from : BEGINNING,
            date(m.get("effectiveTo"), where), text(m.get("description")), lines);
    }

    private static PostingRuleSet.Line line(Object raw, String where) {
        if (!(raw instanceof Map<?, ?> l)) {
            throw new IllegalStateException(where + ": a line is not a map");
        }
        boolean dr = l.containsKey("dr");
        boolean cr = l.containsKey("cr");
        if (dr == cr) {
            throw new IllegalStateException(where + ": a line names exactly one of dr or cr");
        }
        return new PostingRuleSet.Line(dr ? PostingDirection.DR : PostingDirection.CR, text(l.get(dr ? "dr" : "cr")),
            text(l.get("amount")), text(l.get("movement")));
    }

    private static LocalDate date(Object value, String where) {
        if (value == null) {
            return null;
        }
        if (value instanceof Date d) {   // an unquoted YAML date
            return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        try {
            return LocalDate.parse(value.toString());
        } catch (RuntimeException e) {
            throw new IllegalStateException(where + ": '" + value + "' is not a date (YYYY-MM-DD)");
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
