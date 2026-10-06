package tz.co.nlolo.lifeplatform.finaccounting.domain;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingMode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The guide's manual and period-end entries (Part 4: M, N, O, Q, R) as manual journal templates (IFRS 17 I4), read
 * from {@code finaccounting/manual-journal-templates.yaml}. An entry the guide puts on an AUTO account carries
 * {@code postedBy} and is shown, never offered. Validated when loaded: every account of an offered template is a
 * MAN or BOTH posting account of the chart -- a template that could only fail at approval is refused at startup.
 */
public final class GuideTemplates {

    public record Template(String id, String title, String when, String postedBy, List<Line> lines) {}

    public record Line(PostingDirection side, String account) {}

    private GuideTemplates() {}

    public static List<Template> load(InputStream in, List<ChartOfAccountBlueprint.Seed> chart) {
        List<Template> templates = new ArrayList<>();
        try (in) {
            Object doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            if (!(doc instanceof Map<?, ?> root) || !(root.get("templates") instanceof List<?> list)) {
                throw new IllegalStateException("manual-journal-templates.yaml: no 'templates' list");
            }
            for (Object o : list) {
                Map<?, ?> m = (Map<?, ?>) o;
                List<Line> lines = new ArrayList<>();
                for (Object l : (List<?>) m.get("lines")) {
                    Map<?, ?> line = (Map<?, ?>) l;
                    boolean dr = line.containsKey("dr");
                    lines.add(new Line(dr ? PostingDirection.DR : PostingDirection.CR,
                        String.valueOf(line.get(dr ? "dr" : "cr"))));
                }
                templates.add(new Template(String.valueOf(m.get("id")), String.valueOf(m.get("title")),
                    m.get("when") == null ? null : String.valueOf(m.get("when")),
                    m.get("postedBy") == null ? null : String.valueOf(m.get("postedBy")), lines));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> problems = new ArrayList<>();
        for (Template t : templates) {
            if (t.postedBy() != null) {
                continue;
            }
            for (Line line : t.lines()) {
                ChartOfAccountBlueprint.Seed account = chart.stream().filter(s -> s.code().equals(line.account()))
                    .findFirst().orElse(null);
                if (account == null || !account.postingAllowed()) {
                    problems.add(t.id() + ": " + line.account() + " is not a posting account of the chart");
                } else if (account.mode() == PostingMode.AUTO) {
                    problems.add(t.id() + ": " + line.account() + " is AUTO -- give the entry a postedBy");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("manual-journal-templates.yaml is invalid:\n  " + String.join("\n  ", problems));
        }
        return List.copyOf(templates);
    }
}
