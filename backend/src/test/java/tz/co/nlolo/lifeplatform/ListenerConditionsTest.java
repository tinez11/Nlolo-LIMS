package tz.co.nlolo.lifeplatform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each after-commit listener names the event types it acts on (2026-11-14, durable events). The event registry
 * records a delivery only for a listener whose condition matches, so a listener taking every event costs a row per
 * event -- about 57 per event before conditions, which made requests markedly slower. The danger is the opposite
 * mistake: a type the code handles but the condition leaves out is never delivered at all. So every event type a
 * listener's file mentions must be in its condition, and only the listeners that genuinely read every event may go
 * without one.
 */
class ListenerConditionsTest {

    private static final Path SOURCES = Path.of("src/main/java/tz/co/nlolo/lifeplatform");

    /** Read every event: the audit journal, the ledger's posting rules, and reporting's read model. */
    private static final Set<String> EVERY_EVENT = Set.of(
        "audit/infrastructure/DomainEventAuditListener.java",
        "finaccounting/application/LedgerEventListener.java",
        "reporting/application/ReportingEventListener.java");

    /** The annotation's arguments, up to the method it sits on; never across another '@', so a mention in a comment cannot swallow a real one. */
    private static final Pattern ANNOTATION =
        Pattern.compile("@TransactionalEventListener\\(([^@]*?)\\)\\s*\\n\\s*(?:public|void|protected)");
    private static final Pattern EVENT_TYPE = Pattern.compile("\"([a-z]+\\.[A-Z][A-Za-z]+)\"");
    private static final Pattern PREFIX = Pattern.compile("startsWith\\(\"([a-z]+\\.)\"\\)");

    @Test
    void everyEventTypeAListenerHandlesIsInItsCondition() throws IOException {
        List<String> problems = new ArrayList<>();
        int listeners = 0;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file).replace("\r\n", "\n");
                String name = SOURCES.relativize(file).toString().replace('\\', '/');
                Matcher annotation = ANNOTATION.matcher(text);
                while (annotation.find()) {
                    listeners++;
                    String args = annotation.group(1);
                    if (!args.contains("condition")) {
                        if (!EVERY_EVENT.contains(name)) problems.add(name + ": no condition, so it is recorded for every event");
                        continue;
                    }
                    Set<String> missing = new TreeSet<>();
                    Matcher type = EVENT_TYPE.matcher(text);
                    while (type.find()) {
                        if (!args.contains("'" + type.group(1) + "'")) missing.add(type.group(1));
                    }
                    Matcher prefix = PREFIX.matcher(text);
                    while (prefix.find()) {
                        if (!args.contains("startsWith('" + prefix.group(1) + "')")) missing.add(prefix.group(1) + "*");
                    }
                    // A type the file only publishes is harmless to leave out -- but it cannot be told apart here, so
                    // it is listed too: one more row per event is cheap; a handled type left out is lost.
                    if (!missing.isEmpty()) problems.add(name + ": condition leaves out " + missing);
                }
            }
        }
        assertThat(listeners).as("listeners found -- a pattern that matches none proves nothing").isGreaterThan(50);
        assertThat(problems).isEmpty();
    }
}
