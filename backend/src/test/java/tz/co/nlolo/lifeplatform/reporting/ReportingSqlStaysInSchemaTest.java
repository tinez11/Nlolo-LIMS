package tz.co.nlolo.lifeplatform.reporting;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reporting module reads nothing but its own schema. NoCrossModuleJoinTest only inspects JPA
 * {@code @Query} annotations, and this module uses JdbcTemplate, so without this test a query
 * joining policy.policy would slip through every existing guard.
 */
@Disabled("enabled in Task 3 when ProjectionStore exists")
class ReportingSqlStaysInSchemaTest {

    private static final Path SOURCE = Path.of("src/main/java/tz/co/nlolo/lifeplatform/reporting");
    private static final Pattern SCHEMA_REF = Pattern.compile("\\b([a-z]+)\\.(?:[a-z_]+)\\b");
    private static final List<String> OTHER_SCHEMAS = List.of("party", "product", "underwriting", "policy",
        "policyloan", "billing", "claims", "distribution", "payment", "reinsurance", "finaccounting",
        "regreporting", "communication", "omnichannel", "audit", "iam", "document", "refdata",
        "benefitpayout", "accumulation", "bonus", "annuity", "unitlinked");

    @Test
    void everySqlStringNamesOnlyTheReportingSchema() throws IOException {
        assertThat(SOURCE).as("the module must exist for this test to mean anything").isDirectory();
        List<String> violations = new ArrayList<>();
        int sqlFiles = 0;
        try (Stream<Path> files = Files.walk(SOURCE)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                if (!text.contains("reporting.report_") && !text.contains("reporting.applied_event")) continue;
                sqlFiles++;
                for (String line : text.split("\\R")) {
                    // Only string literals can carry SQL. Event type names ("policy.PolicyLapsed") never match:
                    // the pattern wants a lower-case table name after the dot.
                    if (!line.contains("\"")) continue;
                    Matcher m = SCHEMA_REF.matcher(line);
                    while (m.find()) {
                        if (OTHER_SCHEMAS.contains(m.group(1))) {
                            violations.add(file.getFileName() + ": " + line.trim());
                        }
                    }
                }
            }
        }
        assertThat(sqlFiles).as("at least one class carries reporting SQL").isPositive();
        assertThat(violations).isEmpty();
    }
}
