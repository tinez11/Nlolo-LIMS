package tz.co.nlolo.lifeplatform.iam.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.iam.api.AccessApi.RoleRef;
import tz.co.nlolo.lifeplatform.iam.api.AccessChangeRefusedException;
import tz.co.nlolo.lifeplatform.iam.api.Permission;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.iam.api.AccessChangeRefusedException.Reason.*;

/**
 * The Users and Roles screens (2026-10-26): who works here, what roles they hold, what single permissions they have
 * on top, and what each role carries.
 *
 * <p>Four rules, the user's:
 * <ol>
 *   <li>No one changes their own access -- not their roles, not a role they hold, not their own login.
 *   <li>Anything that gives a person an Administration permission (managing users or roles) waits for a second
 *       administrator, who is neither the one who asked nor the one it is for.
 *   <li>The last active person who can manage users cannot lose that, or be deactivated: the platform would have no
 *       one left to fix it.
 *   <li>Single permissions add to roles; there is no "deny".
 * </ol>
 * Every change is written to {@code iam.access_change}, which is the history each user's page shows.
 */
@Service
public class AccessAdministration {

    public record Actor(UUID userId, String name) {}

    public record PermissionInfo(String code, String area, String areaLabel, String label, String description) {}

    public record RoleView(UUID roleId, String code, String name, String description, boolean builtIn, boolean active,
                           List<String> permissions, int holders) {}

    public record ChangeView(UUID changeId, UUID userId, String userName, UUID roleId, String roleName, String changeType,
                             Map<String, Object> detail, String status, String requestedByName, Instant requestedAt,
                             String decidedByName, Instant decidedAt) {}

    public record UserView(UUID userId, String username, String displayName, String email, String phone,
                           String homeBranch, boolean active, Instant lastSeenAt, List<RoleRef> roles,
                           List<String> extraPermissions, List<String> effectivePermissions, boolean pendingApproval) {}

    public record UserDetail(UserView user, List<ChangeView> history) {}

    public record NewUser(String username, String firstName, String lastName, String email, String phone,
                          String homeBranch, List<String> roleCodes, List<String> permissions) {}

    /**
     * How a first or new password reaches a staff member -- the portal invite's rule (PortalAccessService.deliveryFor)
     * less the SMS, which needs a client record to log against and staff have none: the identity provider's own email
     * link where mail is on and they have an address, otherwise a one-time password shown to the administrator once
     * and kept nowhere, as deploy/server/add-user.sh has always done.
     */
    public enum Delivery { EMAIL_LINK, TEMPORARY_PASSWORD }

    public record PasswordIssued(Delivery delivery, String temporaryPassword) {}

    public record CreatedUser(UserView user, Delivery delivery, String temporaryPassword, boolean awaitingApproval) {}

    public record AccessChange(List<String> roleCodes, List<String> permissions) {}

    public record RoleDraft(String code, String name, String description, List<String> permissions, Boolean active) {}

    /** What a change did: applied at once, or waiting for a second administrator. */
    public record Outcome(boolean applied, UUID changeId) {}

    /** No 0/O, 1/l/I: read aloud over a counter or a phone -- PortalAccessService's alphabet. */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final Set<String> ADMINISTRATION = Set.of(Permission.USERS_VIEW.code(), Permission.USERS_MANAGE.code(),
        Permission.ROLES_MANAGE.code());

    private final NamedParameterJdbcTemplate jdbc;
    private final AccessService access;
    private final StaffLogins logins;
    private final ObjectMapper json;
    private final boolean emailEnabled;
    private final SecureRandom random = new SecureRandom();

    public AccessAdministration(NamedParameterJdbcTemplate jdbc, AccessService access, StaffLogins logins,
                                ObjectMapper json,
                                @org.springframework.beans.factory.annotation.Value("${communication.email.enabled:true}")
                                boolean emailEnabled) {
        this.emailEnabled = emailEnabled;
        this.jdbc = jdbc;
        this.access = access;
        this.logins = logins;
        this.json = json;
    }

    // ---- the catalogue and roles ---------------------------------------------------------------------------------

    public List<PermissionInfo> catalogue() {
        return java.util.Arrays.stream(Permission.values())
            .map(p -> new PermissionInfo(p.code(), p.area().name(), p.area().label(), p.label(), p.description()))
            .toList();
    }

    @Transactional
    public List<RoleView> roles() {
        UUID tenant = TenantContext.get();
        access.seedBuiltInRolesFor(tenant);
        MapSqlParameterSource t = new MapSqlParameterSource("tenant", tenant);
        Map<UUID, List<String>> permissions = new LinkedHashMap<>();
        jdbc.query("SELECT role_id, permission FROM iam.role_permission WHERE tenant_id = :tenant ORDER BY permission", t,
            rs -> { permissions.computeIfAbsent(rs.getObject("role_id", UUID.class), k -> new ArrayList<>())
                .add(rs.getString("permission")); });
        List<RoleView> roles = jdbc.query(
            "SELECT r.role_id, r.code, r.name, r.description, r.built_in, r.active, "
                + "(SELECT count(*) FROM iam.user_role ur JOIN iam.staff_user u ON u.tenant_id = ur.tenant_id "
                + "AND u.user_id = ur.user_id WHERE ur.role_id = r.role_id AND u.active) AS holders "
                + "FROM iam.role r WHERE r.tenant_id = :tenant", t,
            (rs, i) -> new RoleView(rs.getObject("role_id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getBoolean("built_in"), rs.getBoolean("active"),
                permissions.getOrDefault(rs.getObject("role_id", UUID.class), List.of()), rs.getInt("holders")));
        return roles.stream().sorted(Comparator.comparing((RoleView r) -> !r.builtIn())
            .thenComparingInt(r -> BuiltInRoles.precedence(r.code())).thenComparing(RoleView::name)).toList();
    }

    @Transactional
    public RoleView createRole(Actor actor, RoleDraft draft) {
        UUID tenant = TenantContext.get();
        access.seedBuiltInRolesFor(tenant);
        String code = draft.code() == null ? "" : draft.code().trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        if (code.isEmpty() || !Character.isLetter(code.charAt(0))) {
            throw new AccessChangeRefusedException(UNKNOWN_ROLE, "A role's code starts with a letter, e.g. BRANCH_MANAGER.");
        }
        Set<String> permissions = validPermissions(draft.permissions());
        // A new role is held by nobody, so even one carrying Administration grants nothing until it is given -- and
        // giving it is what waits for the second administrator.
        List<UUID> created = jdbc.queryForList("INSERT INTO iam.role (tenant_id, code, name, description, created_by) "
                + "VALUES (:tenant, :code, :name, :description, :actor) ON CONFLICT (tenant_id, code) DO NOTHING RETURNING role_id",
            new MapSqlParameterSource("tenant", tenant).addValue("code", code).addValue("name", required(draft.name(), "name"))
                .addValue("description", blankToNull(draft.description())).addValue("actor", actor.userId().toString()),
            UUID.class);
        if (created.isEmpty()) throw new AccessChangeRefusedException(ROLE_CODE_TAKEN, "A role with the code " + code + " already exists.");
        UUID roleId = created.get(0);
        writeRolePermissions(tenant, roleId, permissions);
        record(tenant, null, roleId, "ROLE_CREATED", Map.of("code", code, "permissions", permissions), "APPLIED", actor);
        return role(roleId);
    }

    @Transactional
    public Outcome updateRole(Actor actor, UUID roleId, RoleDraft draft) {
        UUID tenant = TenantContext.get();
        RoleView current = role(roleId);
        if (holds(tenant, actor.userId(), roleId)) {
            throw new AccessChangeRefusedException(SELF_CHANGE, "You hold " + current.name()
                + ", so another administrator has to change what it carries.");
        }
        Set<String> wanted = draft.permissions() == null ? new TreeSet<>(current.permissions()) : validPermissions(draft.permissions());
        boolean active = draft.active() == null ? current.active() : draft.active();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", draft.name() == null ? current.name() : draft.name().trim());
        detail.put("description", draft.description() == null ? current.description() : blankToNull(draft.description()));
        detail.put("permissions", wanted);
        detail.put("active", active);
        detail.put("added", difference(wanted, current.permissions()));
        detail.put("removed", difference(current.permissions(), wanted));

        boolean grantsAdministration = active && current.holders() > 0
            && wanted.stream().anyMatch(p -> ADMINISTRATION.contains(p) && !current.permissions().contains(p));
        if (grantsAdministration || (!current.active() && active && wanted.stream().anyMatch(ADMINISTRATION::contains))) {
            return new Outcome(false, record(tenant, null, roleId, "ROLE_CHANGED", detail, "PENDING", actor));
        }
        applyRole(tenant, roleId, detail);
        ensureSomeoneCanManageUsers(tenant);
        return new Outcome(true, record(tenant, null, roleId, "ROLE_CHANGED", detail, "APPLIED", actor));
    }

    // ---- users ---------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<UserView> users(String q) {
        UUID tenant = TenantContext.get();
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        List<UUID> ids = jdbc.queryForList("SELECT user_id FROM iam.staff_user WHERE tenant_id = :tenant "
                + "AND (CAST(:q AS text) IS NULL OR lower(username) LIKE :q OR lower(coalesce(display_name, '')) LIKE :q "
                + "OR lower(coalesce(email, '')) LIKE :q) ORDER BY active DESC, lower(coalesce(display_name, username))",
            new MapSqlParameterSource("tenant", tenant).addValue("q", like), UUID.class);
        return ids.stream().map(id -> userView(tenant, id)).toList();
    }

    @Transactional(readOnly = true)
    public UserDetail user(UUID userId) {
        UUID tenant = TenantContext.get();
        return new UserDetail(userView(tenant, userId), history(tenant, userId));
    }

    @Transactional
    public CreatedUser createUser(Actor actor, NewUser request) {
        UUID tenant = TenantContext.get();
        access.seedBuiltInRolesFor(tenant);
        String username = required(request.username(), "username").toLowerCase();
        String firstName = required(request.firstName(), "first name");
        String lastName = required(request.lastName(), "last name");
        List<UUID> roleIds = roleIds(tenant, request.roleCodes());
        Set<String> permissions = validPermissions(request.permissions());
        UUID userId = logins.create(new StaffLogins.NewLogin(username, firstName, lastName, blankToNull(request.email()),
            tenant, blankToNull(request.homeBranch())));
        jdbc.update("INSERT INTO iam.staff_user (tenant_id, user_id, username, display_name, email, phone, home_branch, "
                + "created_by) VALUES (:tenant, :user, :username, :name, :email, :phone, :branch, :actor)",
            new MapSqlParameterSource("tenant", tenant).addValue("user", userId).addValue("username", username)
                .addValue("name", firstName + " " + lastName).addValue("email", blankToNull(request.email()))
                .addValue("phone", blankToNull(request.phone())).addValue("branch", blankToNull(request.homeBranch()))
                .addValue("actor", actor.userId().toString()));
        record(tenant, userId, null, "USER_CREATED", Map.of("username", username), "APPLIED", actor);
        Outcome outcome = changeAccess(actor, tenant, userId, roleIds, permissions);
        PasswordIssued first = issuePassword(userId, blankToNull(request.email()));
        return new CreatedUser(userView(tenant, userId), first.delivery(), first.temporaryPassword(), !outcome.applied());
    }

    @Transactional
    public Outcome setAccess(Actor actor, UUID userId, AccessChange change) {
        UUID tenant = TenantContext.get();
        refuseSelf(actor, userId);
        userView(tenant, userId);
        return changeAccess(actor, tenant, userId, roleIds(tenant, change.roleCodes()), validPermissions(change.permissions()));
    }

    @Transactional
    public void setActive(Actor actor, UUID userId, boolean active) {
        UUID tenant = TenantContext.get();
        refuseSelf(actor, userId);
        UserView user = userView(tenant, userId);
        if (user.active() == active) return;
        jdbc.update("UPDATE iam.staff_user SET active = :active WHERE tenant_id = :tenant AND user_id = :user",
            new MapSqlParameterSource("tenant", tenant).addValue("user", userId).addValue("active", active));
        if (!active) ensureSomeoneCanManageUsers(tenant);
        logins.setEnabled(userId, active);
        record(tenant, userId, null, active ? "USER_ACTIVATED" : "USER_DEACTIVATED", Map.of(), "APPLIED", actor);
        access.evict(tenant, userId);
    }

    @Transactional
    public PasswordIssued resetPassword(Actor actor, UUID userId) {
        UUID tenant = TenantContext.get();
        refuseSelf(actor, userId);
        UserView user = userView(tenant, userId);
        PasswordIssued issued = issuePassword(userId, user.email());
        record(tenant, userId, null, "PASSWORD_RESET", Map.of("delivery", issued.delivery().name()), "APPLIED", actor);
        return issued;
    }

    private PasswordIssued issuePassword(UUID userId, String email) {
        if (email != null && emailEnabled) {
            logins.sendSetPasswordLink(userId);
            return new PasswordIssued(Delivery.EMAIL_LINK, null);
        }
        String password = temporaryPassword();
        logins.setTemporaryPassword(userId, password);
        return new PasswordIssued(Delivery.TEMPORARY_PASSWORD, password);
    }

    // ---- the second administrator ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ChangeView> pending() {
        UUID tenant = TenantContext.get();
        return changes(tenant, "c.tenant_id = :tenant AND c.status = 'PENDING'", new MapSqlParameterSource("tenant", tenant));
    }

    @Transactional
    public void decide(Actor actor, UUID changeId, boolean approve) {
        UUID tenant = TenantContext.get();
        List<ChangeView> found = changes(tenant, "c.tenant_id = :tenant AND c.change_id = :change",
            new MapSqlParameterSource("tenant", tenant).addValue("change", changeId));
        if (found.isEmpty()) throw new AccessChangeRefusedException(NOT_FOUND, "No such change.");
        ChangeView change = found.get(0);
        if (!"PENDING".equals(change.status())) throw new AccessChangeRefusedException(NOT_PENDING, "This change was already decided.");
        String requestedBy = jdbc.queryForObject("SELECT requested_by FROM iam.access_change WHERE change_id = :change",
            new MapSqlParameterSource("change", changeId), String.class);
        if (actor.userId().toString().equals(requestedBy)) {
            throw new AccessChangeRefusedException(SAME_PERSON_APPROVES, "You asked for this change, so another administrator decides it.");
        }
        if (change.userId() != null) refuseSelf(actor, change.userId());
        if (change.roleId() != null && holds(tenant, actor.userId(), change.roleId())) {
            throw new AccessChangeRefusedException(SELF_CHANGE, "You hold this role, so another administrator decides it.");
        }
        if (approve) {
            if (change.userId() != null) {
                applyAccess(tenant, change.userId(), uuids(change.detail().get("roleIds")), strings(change.detail().get("permissions")));
            } else {
                applyRole(tenant, change.roleId(), change.detail());
            }
            ensureSomeoneCanManageUsers(tenant);
        }
        jdbc.update("UPDATE iam.access_change SET status = :status, decided_by = :actor, decided_by_name = :name, "
                + "decided_at = now() WHERE change_id = :change",
            new MapSqlParameterSource("change", changeId).addValue("status", approve ? "APPROVED" : "REJECTED")
                .addValue("actor", actor.userId().toString()).addValue("name", actor.name()));
    }

    // ---- internals ------------------------------------------------------------------------------------------------

    private Outcome changeAccess(Actor actor, UUID tenant, UUID userId, List<UUID> roleIds, Set<String> permissions) {
        UserView before = userView(tenant, userId);
        Set<String> after = effectiveOf(tenant, roleIds, permissions);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("roleIds", roleIds);
        detail.put("roles", roleCodes(tenant, roleIds));
        detail.put("permissions", permissions);
        detail.put("addedRoles", difference(roleCodes(tenant, roleIds), before.roles().stream().map(RoleRef::code).toList()));
        detail.put("removedRoles", difference(before.roles().stream().map(RoleRef::code).toList(), roleCodes(tenant, roleIds)));
        detail.put("addedPermissions", difference(permissions, before.extraPermissions()));
        detail.put("removedPermissions", difference(before.extraPermissions(), permissions));
        boolean grantsAdministration = after.stream()
            .anyMatch(p -> ADMINISTRATION.contains(p) && !before.effectivePermissions().contains(p));
        if (grantsAdministration) {
            return new Outcome(false, record(tenant, userId, null, "ACCESS_CHANGED", detail, "PENDING", actor));
        }
        applyAccess(tenant, userId, roleIds, permissions);
        ensureSomeoneCanManageUsers(tenant);
        return new Outcome(true, record(tenant, userId, null, "ACCESS_CHANGED", detail, "APPLIED", actor));
    }

    private void applyAccess(UUID tenant, UUID userId, Collection<UUID> roleIds, Collection<String> permissions) {
        MapSqlParameterSource p = new MapSqlParameterSource("tenant", tenant).addValue("user", userId);
        jdbc.update("DELETE FROM iam.user_role WHERE tenant_id = :tenant AND user_id = :user", p);
        for (UUID roleId : roleIds) {
            jdbc.update("INSERT INTO iam.user_role (tenant_id, user_id, role_id, granted_by) VALUES (:tenant, :user, :role, 'admin')",
                new MapSqlParameterSource(p.getValues()).addValue("role", roleId));
        }
        jdbc.update("DELETE FROM iam.user_permission WHERE tenant_id = :tenant AND user_id = :user", p);
        for (String permission : permissions) {
            jdbc.update("INSERT INTO iam.user_permission (tenant_id, user_id, permission, granted_by) "
                + "VALUES (:tenant, :user, :permission, 'admin')", new MapSqlParameterSource(p.getValues()).addValue("permission", permission));
        }
        access.evict(tenant, userId);
    }

    private void applyRole(UUID tenant, UUID roleId, Map<String, Object> detail) {
        jdbc.update("UPDATE iam.role SET name = :name, description = :description, active = :active, updated_at = now() "
                + "WHERE tenant_id = :tenant AND role_id = :role",
            new MapSqlParameterSource("tenant", tenant).addValue("role", roleId).addValue("name", detail.get("name"))
                .addValue("description", detail.get("description")).addValue("active", Boolean.TRUE.equals(detail.get("active"))));
        writeRolePermissions(tenant, roleId, new TreeSet<>(strings(detail.get("permissions"))));
        access.evictTenant(tenant);
    }

    private void writeRolePermissions(UUID tenant, UUID roleId, Set<String> permissions) {
        jdbc.update("DELETE FROM iam.role_permission WHERE tenant_id = :tenant AND role_id = :role",
            new MapSqlParameterSource("tenant", tenant).addValue("role", roleId));
        for (String permission : permissions) {
            jdbc.update("INSERT INTO iam.role_permission (tenant_id, role_id, permission) VALUES (:tenant, :role, :permission)",
                new MapSqlParameterSource("tenant", tenant).addValue("role", roleId).addValue("permission", permission));
        }
    }

    /** Refuses a change that would leave no active person able to manage users. */
    private void ensureSomeoneCanManageUsers(UUID tenant) {
        Integer managers = jdbc.queryForObject(
            "SELECT count(DISTINCT u.user_id) FROM iam.staff_user u WHERE u.tenant_id = :tenant AND u.active AND ("
                + "EXISTS (SELECT 1 FROM iam.user_role ur JOIN iam.role r ON r.role_id = ur.role_id "
                + "JOIN iam.role_permission rp ON rp.role_id = r.role_id WHERE ur.tenant_id = u.tenant_id "
                + "AND ur.user_id = u.user_id AND r.active AND rp.permission = 'users.manage') "
                + "OR EXISTS (SELECT 1 FROM iam.user_permission up WHERE up.tenant_id = u.tenant_id "
                + "AND up.user_id = u.user_id AND up.permission = 'users.manage'))",
            new MapSqlParameterSource("tenant", tenant), Integer.class);
        if (managers == null || managers == 0) {
            throw new AccessChangeRefusedException(LAST_ADMINISTRATOR,
                "That would leave no one able to manage users. Give someone else that first.");
        }
    }

    private void refuseSelf(Actor actor, UUID userId) {
        if (actor.userId().equals(userId)) {
            throw new AccessChangeRefusedException(SELF_CHANGE, "You cannot change your own access or login. Ask another administrator.");
        }
    }

    private boolean holds(UUID tenant, UUID userId, UUID roleId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM iam.user_role WHERE tenant_id = :tenant AND user_id = :user "
            + "AND role_id = :role", new MapSqlParameterSource("tenant", tenant).addValue("user", userId).addValue("role", roleId),
            Integer.class);
        return n != null && n > 0;
    }

    private UserView userView(UUID tenant, UUID userId) {
        MapSqlParameterSource p = new MapSqlParameterSource("tenant", tenant).addValue("user", userId);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM iam.staff_user WHERE tenant_id = :tenant AND user_id = :user", p);
        if (rows.isEmpty()) throw new AccessChangeRefusedException(NOT_FOUND, "No such staff member.");
        Map<String, Object> row = rows.get(0);
        List<RoleRef> roles = new ArrayList<>(jdbc.query("SELECT r.code, r.name FROM iam.user_role ur JOIN iam.role r "
                + "ON r.role_id = ur.role_id WHERE ur.tenant_id = :tenant AND ur.user_id = :user", p,
            (rs, i) -> new RoleRef(rs.getString("code"), rs.getString("name"))));
        roles.sort(Comparator.comparingInt((RoleRef r) -> BuiltInRoles.precedence(r.code())).thenComparing(RoleRef::name));
        List<String> extra = jdbc.queryForList("SELECT permission FROM iam.user_permission WHERE tenant_id = :tenant "
            + "AND user_id = :user ORDER BY permission", p, String.class);
        List<UUID> roleIds = jdbc.queryForList("SELECT role_id FROM iam.user_role WHERE tenant_id = :tenant AND user_id = :user",
            p, UUID.class);
        Integer pending = jdbc.queryForObject("SELECT count(*) FROM iam.access_change WHERE tenant_id = :tenant "
            + "AND user_id = :user AND status = 'PENDING'", p, Integer.class);
        Timestamp seen = (Timestamp) row.get("last_seen_at");
        return new UserView(userId, (String) row.get("username"), (String) row.get("display_name"), (String) row.get("email"),
            (String) row.get("phone"), (String) row.get("home_branch"), Boolean.TRUE.equals(row.get("active")),
            seen == null ? null : seen.toInstant(), List.copyOf(roles), extra,
            List.copyOf(effectiveOf(tenant, roleIds, extra)), pending != null && pending > 0);
    }

    private Set<String> effectiveOf(UUID tenant, Collection<UUID> roleIds, Collection<String> permissions) {
        Set<String> effective = new TreeSet<>(permissions);
        if (!roleIds.isEmpty()) {
            effective.addAll(jdbc.queryForList("SELECT rp.permission FROM iam.role_permission rp JOIN iam.role r "
                    + "ON r.role_id = rp.role_id WHERE rp.tenant_id = :tenant AND r.active AND rp.role_id IN (:roles)",
                new MapSqlParameterSource("tenant", tenant).addValue("roles", roleIds), String.class));
        }
        effective.removeIf(code -> Permission.fromCode(code).isEmpty());
        return effective;
    }

    private List<ChangeView> history(UUID tenant, UUID userId) {
        return changes(tenant, "c.tenant_id = :tenant AND c.user_id = :user",
            new MapSqlParameterSource("tenant", tenant).addValue("user", userId));
    }

    private List<ChangeView> changes(UUID tenant, String where, MapSqlParameterSource p) {
        return jdbc.query("SELECT c.*, u.display_name AS user_name, r.name AS role_name FROM iam.access_change c "
                + "LEFT JOIN iam.staff_user u ON u.tenant_id = c.tenant_id AND u.user_id = c.user_id "
                + "LEFT JOIN iam.role r ON r.role_id = c.role_id WHERE " + where + " ORDER BY c.requested_at DESC LIMIT 200", p,
            (rs, i) -> new ChangeView(rs.getObject("change_id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("user_name"), rs.getObject("role_id", UUID.class), rs.getString("role_name"),
                rs.getString("change_type"), readDetail(rs.getString("detail")), rs.getString("status"),
                rs.getString("requested_by_name"), rs.getTimestamp("requested_at").toInstant(),
                rs.getString("decided_by_name"),
                rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant()));
    }

    private UUID record(UUID tenant, UUID userId, UUID roleId, String type, Map<String, Object> detail, String status, Actor actor) {
        return jdbc.queryForObject("INSERT INTO iam.access_change (tenant_id, user_id, role_id, change_type, detail, status, "
                + "requested_by, requested_by_name) VALUES (:tenant, :user, :role, :type, CAST(:detail AS jsonb), :status, "
                + ":actor, :name) RETURNING change_id",
            new MapSqlParameterSource("tenant", tenant).addValue("user", userId).addValue("role", roleId)
                .addValue("type", type).addValue("detail", writeDetail(detail)).addValue("status", status)
                .addValue("actor", actor.userId().toString()).addValue("name", actor.name()), UUID.class);
    }

    private RoleView role(UUID roleId) {
        return roles().stream().filter(r -> r.roleId().equals(roleId)).findFirst()
            .orElseThrow(() -> new AccessChangeRefusedException(NOT_FOUND, "No such role."));
    }

    private List<UUID> roleIds(UUID tenant, List<String> codes) {
        List<UUID> ids = new ArrayList<>();
        for (String code : codes == null ? List.<String>of() : new LinkedHashSet<>(codes)) {
            List<UUID> found = jdbc.queryForList("SELECT role_id FROM iam.role WHERE tenant_id = :tenant AND code = :code AND active",
                new MapSqlParameterSource("tenant", tenant).addValue("code", code), UUID.class);
            if (found.isEmpty()) throw new AccessChangeRefusedException(UNKNOWN_ROLE, "There is no active role " + code + ".");
            ids.add(found.get(0));
        }
        return ids;
    }

    private List<String> roleCodes(UUID tenant, List<UUID> roleIds) {
        if (roleIds.isEmpty()) return List.of();
        return jdbc.queryForList("SELECT code FROM iam.role WHERE tenant_id = :tenant AND role_id IN (:roles) ORDER BY code",
            new MapSqlParameterSource("tenant", tenant).addValue("roles", roleIds), String.class);
    }

    private static Set<String> validPermissions(List<String> codes) {
        Set<String> valid = new TreeSet<>();
        for (String code : codes == null ? List.<String>of() : codes) {
            if (Permission.fromCode(code).isEmpty()) {
                throw new AccessChangeRefusedException(UNKNOWN_PERMISSION, "There is no permission " + code + ".");
            }
            valid.add(code);
        }
        return valid;
    }

    private static List<String> difference(Collection<String> a, Collection<String> b) {
        return a.stream().filter(x -> !b.contains(x)).sorted().toList();
    }

    private String temporaryPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    private static String required(String value, String what) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("The " + what + " is required.");
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String writeDetail(Map<String, Object> detail) {
        try {
            return json.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> readDetail(String text) {
        try {
            return text == null ? Map.of() : json.readValue(text, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof Collection<?> c) for (Object o : c) out.add(String.valueOf(o));
        return out;
    }

    private static List<UUID> uuids(Object value) {
        return strings(value).stream().map(UUID::fromString).toList();
    }
}
