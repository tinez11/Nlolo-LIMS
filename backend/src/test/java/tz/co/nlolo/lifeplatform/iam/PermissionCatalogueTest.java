package tz.co.nlolo.lifeplatform.iam;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.iam.api.Permission;
import tz.co.nlolo.lifeplatform.iam.application.BuiltInRoles;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The catalogue and the eight roles every tenant starts with. */
class PermissionCatalogueTest {

    @Test
    void everyCodeIsUniqueAndShapedAreaDotAct() {
        Set<String> codes = Arrays.stream(Permission.values()).map(Permission::code).collect(Collectors.toSet());
        assertThat(codes).hasSize(Permission.values().length);
        assertThat(codes).allMatch(c -> c.matches("^[a-z0-9]+\\.[a-z0-9-]+$"));
    }

    @Test
    void everyAreaHasExactlyOneView() {
        for (Permission.Area area : Permission.Area.values()) {
            assertThat(Arrays.stream(Permission.values()).filter(p -> p.area() == area && p.code().endsWith(".view")))
                .as(area.label()).hasSize(1);
        }
    }

    /** A role that may act in an area must be able to see it -- or its buttons would sit on a page it cannot open. */
    @Test
    void aBuiltInRoleThatActsInAnAreaCanSeeIt() {
        for (BuiltInRoles.Definition role : BuiltInRoles.ALL) {
            for (Permission p : role.permissions()) {
                Permission view = Arrays.stream(Permission.values())
                    .filter(v -> v.area() == p.area() && v.code().endsWith(".view")).findFirst().orElseThrow();
                assertThat(role.permissions()).as(role.code() + " holds " + p.code()).contains(view);
            }
        }
    }

    @Test
    void theEightKeycloakRolesCarryOverByName() {
        assertThat(BuiltInRoles.ALL).extracting(BuiltInRoles.Definition::code).containsExactly(
            "ADMIN", "SENIOR_UNDERWRITER", "UNDERWRITER", "CLAIMS_MANAGER", "CLAIMS_ASSESSOR", "FINANCE_APPROVER",
            "FINANCE_OFFICER", "CUSTOMER_SERVICE_REP");
    }

    @Test
    void anAdministratorRunsThePlatformButNeitherUnderwritesNorGivesTheApproversSignature() {
        Set<Permission> admin = BuiltInRoles.byCode("ADMIN").permissions();
        assertThat(admin).contains(Permission.USERS_MANAGE, Permission.ROLES_MANAGE, Permission.PRODUCTS_MANAGE,
            Permission.LEDGER_MANAGE);
        assertThat(admin).doesNotContain(Permission.UNDERWRITING_DECIDE, Permission.CLAIMS_SETTLE,
            Permission.LEDGER_APPROVE, Permission.IFRS17_APPROVE, Permission.REINSURANCE_APPROVE);
    }

    /** The user's example: finance holds nothing in underwriting, not even the view. */
    @Test
    void financeHasNoUnderwritingAtAll() {
        assertThat(BuiltInRoles.byCode("FINANCE_OFFICER").permissions())
            .noneMatch(p -> p.area() == Permission.Area.UNDERWRITING);
    }

    /**
     * Every permission an endpoint names is in the catalogue -- @access.can refuses an unknown code, so a typo would
     * lock everyone out of that endpoint -- and no endpoint asks Keycloak for a staff role any more: the platform holds
     * them now, and a role read from the token would ignore every change an administrator makes.
     */
    @Test
    void everyEndpointChecksAPermissionThatExistsAndNoneReadsAKeycloakStaffRole() throws Exception {
        java.util.regex.Pattern code = java.util.regex.Pattern.compile("'([a-z0-9]+\\.[a-z0-9-]+)'");
        java.util.List<String> unknown = new java.util.ArrayList<>();
        java.util.List<String> keycloakRoles = new java.util.ArrayList<>();
        int checked = 0;
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            for (java.nio.file.Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                for (String line : java.nio.file.Files.readAllLines(file)) {
                    if (!line.contains("@PreAuthorize(\"")) continue;
                    checked++;
                    java.util.regex.Matcher m = code.matcher(line);
                    while (m.find()) {
                        if (Permission.fromCode(m.group(1)).isEmpty()) unknown.add(file.getFileName() + ": " + m.group(1));
                    }
                    if (line.matches(".*hasRole\\('(?!REALM_)[A-Z_]+'\\).*")) keycloakRoles.add(file.getFileName() + ": " + line.trim());
                }
            }
        }
        assertThat(checked).as("annotations found").isGreaterThan(350);
        assertThat(unknown).isEmpty();
        assertThat(keycloakRoles).isEmpty();
    }

    @Test
    void onlyAdministratorsManageAccess() {
        for (BuiltInRoles.Definition role : BuiltInRoles.ALL) {
            if (role.code().equals("ADMIN")) continue;
            assertThat(role.permissions()).as(role.code())
                .noneMatch(p -> p.area() == Permission.Area.ADMINISTRATION);
        }
    }
}
