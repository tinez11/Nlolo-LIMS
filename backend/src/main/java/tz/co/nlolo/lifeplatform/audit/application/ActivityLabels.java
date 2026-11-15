package tz.co.nlolo.lifeplatform.audit.application;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * What each write action is called in the activity log (2026-11-14): one line per route in audit/activity-labels.tsv,
 * drafted from the API's own summaries and reviewed by the user. ActivityLabelsCoverageTest fails when a route the
 * application serves has no line, so a new action cannot ship unlabelled.
 */
@Component
public class ActivityLabels {

    public record Label(String kind, String text) {}

    private final Map<String, Label> labels = new HashMap<>();

    public ActivityLabels() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new ClassPathResource("audit/activity-labels.tsv").getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                if (f.length < 4) throw new IllegalStateException("audit/activity-labels.tsv needs four columns: " + line);
                labels.put(key(f[0], f[1]), new Label(f[2], f[3]));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The label for a route, or null when it has none. */
    public Label find(String method, String pattern) {
        return labels.get(key(method, pattern));
    }

    /** Variable names differ between the API description and the controllers ({id} vs {claimId}); the shape is what matters. */
    public static String key(String method, String pattern) {
        return method.toUpperCase(java.util.Locale.ROOT) + " " + pattern.replaceAll("\\{[^}]*}", "{}");
    }
}
