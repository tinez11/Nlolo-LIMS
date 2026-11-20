package tz.co.nlolo.lifeplatform.iam.application;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code deploy/server/add-user.sh --roles ALL} gives the first administrator every built-in job role (ticket 10 of
 * .scratch/authentik-cutover). The script runs before the backend's first request, which is what seeds a new tenant's
 * roles, so it cannot read them from the database and carries its own list. This keeps that list the backend's: a role
 * added to {@link BuiltInRoles} and forgotten there would leave the first administrator without it.
 */
class AddUserScriptRolesTest {

    @Test
    void addUserShGivesAllBuiltInJobRoles() throws IOException {
        String script = Files.readString(Path.of("../deploy/server/add-user.sh"));
        Matcher m = Pattern.compile("^ALL_JOB_ROLES=\"([^\"]+)\"", Pattern.MULTILINE).matcher(script);
        assertThat(m.find()).as("ALL_JOB_ROLES was not found in add-user.sh -- has its shape changed?").isTrue();
        List<String> listed = Arrays.asList(m.group(1).trim().split(","));
        List<String> builtIn = BuiltInRoles.ALL.stream().map(BuiltInRoles.Definition::code).toList();
        assertThat(listed).containsExactlyInAnyOrderElementsOf(builtIn);
    }
}
