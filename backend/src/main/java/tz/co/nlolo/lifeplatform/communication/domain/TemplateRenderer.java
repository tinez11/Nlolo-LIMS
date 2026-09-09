package tz.co.nlolo.lifeplatform.communication.domain;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills the holes in a message template.
 *
 * <p>Fifteen lines instead of a templating library, and that is the whole justification for not
 * adding one: these templates are four short operational sentences with named placeholders. A
 * template engine would bring conditionals, loops and an expression language into the path that
 * writes to customers, and none of the four messages needs any of it.
 *
 * <p>Static and pure. No Spring, no database, no clock — which is what makes the behaviour below
 * testable without booting anything, and why the rule about unfilled holes can be pinned properly
 * rather than discovered in production.
 */
public final class TemplateRenderer {

    /** {@code {{name}}}, where a name is letters and digits. Deliberately narrow. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private TemplateRenderer() {
    }

    /**
     * @throws IllegalArgumentException if the template contains a placeholder {@code values} does
     *     not supply. Refusing is the point: a message reading "Pay {{premium}} by" is worse than
     *     no message — it is unreadable, it looks broken, and it teaches people to ignore what
     *     this platform sends them. The caller records the dispatch FAILED with this reason, which
     *     is something an operator can act on.
     */
    public static String render(String bodyTemplate, Map<String, String> values) {
        Set<String> missing = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(bodyTemplate);
        StringBuilder rendered = new StringBuilder();

        while (matcher.find()) {
            String name = matcher.group(1);
            String value = values.get(name);
            if (value == null) {
                missing.add(name);
                // Left as-is for now; the throw below is what actually stops this reaching anyone.
                // Collecting the whole set first means somebody fixing a template gets the full
                // list rather than one name per attempt.
                value = matcher.group();
            }
            // quoteReplacement, so a value containing $ or a backslash is inserted literally.
            // Values are policy numbers, money and dates today, but they arrive from event
            // payloads and one of them will eventually be free text somebody typed.
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                "Template has placeholders nothing supplied a value for: " + String.join(", ", missing));
        }
        // One pass, over the template only. A value that happens to contain {{...}} is data and
        // stays data -- otherwise a free-text designee could smuggle a token into a message.
        return rendered.toString();
    }

    /**
     * Every placeholder a template declares, in the order it first mentions them.
     *
     * <p>For the console's template editor: somebody rewriting wording needs to see which tokens
     * are real, and the endpoint that accepts an edit uses this to refuse one that introduces a
     * placeholder the sender will never supply — catching at edit time what would otherwise
     * surface as a FAILED dispatch weeks later.
     */
    public static Set<String> placeholdersIn(String bodyTemplate) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(bodyTemplate);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
