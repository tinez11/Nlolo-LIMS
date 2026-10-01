package tz.co.nlolo.lifeplatform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every module's migrations are applied by the two places that build a database from nothing.
 *
 * <p>{@code scripts/migrate.sh} and the CI workflow each carry a hand-written module list, and
 * benefitpayout was missing from both from the day product step 2 created it. Every integration test
 * still passed, because tests apply migrations through {@code MigrationTestSupport} with their own
 * lists -- so the gap was invisible until an environment was built from scratch with no payout
 * schema, and every payout query failed under {@code ddl-auto: none}. No database is needed to see
 * it: the folders and the lists are both just files.
 */
class MigrationScriptCoverageTest {

    /** pg_partman registrations and sweeps, installed by configure-db.sh rather than migrate.sh. */
    private static final String POST_MIGRATION = "_post-migration";

    private static Set<String> migrationFolders() throws IOException {
        try (Stream<Path> dirs = Files.list(Path.of("db-migrations"))) {
            return dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                .filter(name -> !name.equals(POST_MIGRATION)).collect(Collectors.toSet());
        }
    }

    private static List<String> modulesIn(String text, Pattern listPattern) {
        Matcher m = listPattern.matcher(text);
        assertThat(m.find()).as("the module list was not found -- has its shape changed?").isTrue();
        return Arrays.asList(m.group(1).trim().split("\\s+"));
    }

    @Test
    void migrateShAppliesEveryModulesMigrations() throws IOException {
        String script = Files.readString(Path.of("scripts/migrate.sh"));
        List<String> listed = modulesIn(script, Pattern.compile("^MODULES=\"([^\"]+)\"", Pattern.MULTILINE));
        assertThat(listed).containsAll(migrationFolders());
    }

    @Test
    void ciAppliesEveryModulesMigrations() throws IOException {
        String workflow = Files.readString(Path.of("../.github/workflows/ci-cd.yml"));
        List<String> listed = modulesIn(workflow, Pattern.compile("for mod in ([a-z ]+); do"));
        assertThat(listed).containsAll(migrationFolders());
    }
}
