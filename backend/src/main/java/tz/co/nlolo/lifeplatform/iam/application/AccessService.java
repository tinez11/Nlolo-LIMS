package tz.co.nlolo.lifeplatform.iam.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.iam.api.AccessApi;
import tz.co.nlolo.lifeplatform.iam.api.Permission;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves what a staff member holds (2026-10-26).
 *
 * <p>Two sources, chosen by {@code app.access.source}:
 * <ul>
 *   <li>{@code platform} (the default): the {@code iam} tables. A tenant's eight built-in roles are seeded the first
 *       time it is resolved, and a staff member the first time they are seen -- with the Keycloak roles their token
 *       carries, imported once. From then on the platform is the authority: Keycloak role changes are not read again.
 *   <li>{@code token}: the token's Keycloak roles, each read through its built-in definition. For tests that sign
 *       requests with synthetic roles and no {@code iam} schema, so they keep proving what a role may do. A staff
 *       token naming no built-in role at all holds every permission here: it is how the tests written before
 *       2026-10-26 say "any staff member", and what they prove is the act, not the gate.
 * </ul>
 *
 * <p>Read on every checked request, so it is cached -- briefly, and dropped for a person the moment anything about
 * their access changes on this instance. The short expiry is what carries a change to any other instance.
 */
@Service
public class AccessService implements AccessApi {

    private static final Logger log = LoggerFactory.getLogger(AccessService.class);
    private static final long CACHE_MILLIS = 30_000;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean platformManaged;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(AccessView view, long expiresAt) {}

    public AccessService(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                         @Value("${app.access.source:platform}") String source) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        // Not an injected Clock: two modules publish one each, and nothing here is about a business date.
        this.clock = Clock.systemUTC();
        this.platformManaged = !"token".equalsIgnoreCase(source);
    }

    @Override
    public AccessView accessOf(StaffIdentity identity) {
        if (!platformManaged) return fromToken(identity);
        String key = identity.tenantId() + "|" + identity.userId();
        long now = clock.millis();
        Cached hit = cache.get(key);
        if (hit != null && hit.expiresAt() > now) return hit.view();
        AccessView view = tx.execute(status -> load(identity));
        cache.put(key, new Cached(view, now + CACHE_MILLIS));
        return view;
    }

    @Override
    public Map<String, String> displayNames(java.util.Collection<String> subjects) {
        List<UUID> ids = subjects.stream().filter(java.util.Objects::nonNull).distinct().map(AccessService::uuidOrNull)
            .filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return Map.of();
        Map<String, String> names = new java.util.HashMap<>();
        tx.executeWithoutResult(status -> jdbc.query(
            "SELECT user_id, COALESCE(NULLIF(display_name, ''), username) AS name FROM iam.staff_user "
                + "WHERE tenant_id = :tenant AND user_id IN (:ids)",
            Map.of("tenant", TenantContext.get(), "ids", ids),
            rs -> { names.put(rs.getString("user_id"), rs.getString("name")); }));
        return names;
    }

    private static UUID uuidOrNull(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Forget what is cached for one person, after a change to their access. */
    public void evict(UUID tenantId, UUID userId) {
        cache.remove(tenantId + "|" + userId);
    }

    /** Forget a whole tenant, after a change to a role many people may hold. */
    public void evictTenant(UUID tenantId) {
        cache.keySet().removeIf(k -> k.startsWith(tenantId + "|"));
    }

    private AccessView fromToken(StaffIdentity identity) {
        List<RoleRef> roles = new ArrayList<>();
        Set<String> permissions = new TreeSet<>();
        for (BuiltInRoles.Definition d : BuiltInRoles.ALL) {
            if (identity.tokenRoles().contains(d.code())) {
                roles.add(new RoleRef(d.code(), d.name()));
                d.permissions().forEach(p -> permissions.add(p.code()));
            }
        }
        if (roles.isEmpty()) {
            for (Permission p : Permission.values()) permissions.add(p.code());
        }
        return new AccessView(identity.userId(), identity.displayName(), true, roles,
            roles.isEmpty() ? null : roles.get(0), permissions);
    }

    private AccessView load(StaffIdentity identity) {
        seedBuiltInRoles(identity.tenantId());
        MapSqlParameterSource p = new MapSqlParameterSource()
            .addValue("tenant", identity.tenantId()).addValue("user", identity.userId());
        List<Map<String, Object>> user = jdbc.queryForList(
            "SELECT display_name, active FROM iam.staff_user WHERE tenant_id = :tenant AND user_id = :user", p);
        if (user.isEmpty()) {
            importFromToken(identity);
            user = jdbc.queryForList(
                "SELECT display_name, active FROM iam.staff_user WHERE tenant_id = :tenant AND user_id = :user", p);
        }
        // When they were last here, for the Users screen -- at most once every five minutes, it is not an audit trail.
        jdbc.update("UPDATE iam.staff_user SET last_seen_at = now() WHERE tenant_id = :tenant AND user_id = :user "
            + "AND (last_seen_at IS NULL OR last_seen_at < now() - interval '5 minutes')", p);
        boolean active = Boolean.TRUE.equals(user.get(0).get("active"));
        String displayName = (String) user.get(0).get("display_name");

        List<RoleRef> roles = jdbc.query(
            "SELECT r.code, r.name FROM iam.user_role ur JOIN iam.role r ON r.role_id = ur.role_id "
                + "WHERE ur.tenant_id = :tenant AND ur.user_id = :user AND r.active",
            p, (rs, i) -> new RoleRef(rs.getString("code"), rs.getString("name")));
        roles = new ArrayList<>(roles);
        roles.sort(Comparator.comparingInt((RoleRef r) -> BuiltInRoles.precedence(r.code())).thenComparing(RoleRef::name));

        Set<String> permissions = new TreeSet<>(jdbc.queryForList(
            "SELECT rp.permission FROM iam.user_role ur JOIN iam.role r ON r.role_id = ur.role_id "
                + "JOIN iam.role_permission rp ON rp.role_id = r.role_id "
                + "WHERE ur.tenant_id = :tenant AND ur.user_id = :user AND r.active "
                + "UNION SELECT permission FROM iam.user_permission WHERE tenant_id = :tenant AND user_id = :user",
            p, String.class));
        // A code the catalogue no longer has is held by nobody: nothing can check it.
        permissions.removeIf(code -> Permission.fromCode(code).isEmpty());

        return new AccessView(identity.userId(), displayName != null ? displayName : identity.displayName(), active,
            List.copyOf(roles), active && !roles.isEmpty() ? roles.get(0) : null,
            active ? permissions : Set.of());
    }

    /** For the Roles screen, which may be the first thing a new tenant's administrator opens. */
    public void seedBuiltInRolesFor(UUID tenantId) {
        seedBuiltInRoles(tenantId);
    }

    /** The eight built-in roles, once per tenant. A concurrent first request inserts nothing twice. */
    private void seedBuiltInRoles(UUID tenantId) {
        MapSqlParameterSource t = new MapSqlParameterSource("tenant", tenantId);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM iam.role WHERE tenant_id = :tenant AND built_in", t,
            Integer.class);
        if (count != null && count >= BuiltInRoles.ALL.size()) return;
        for (BuiltInRoles.Definition d : BuiltInRoles.ALL) {
            List<UUID> inserted = jdbc.queryForList(
                "INSERT INTO iam.role (tenant_id, code, name, description, built_in, created_by) "
                    + "VALUES (:tenant, :code, :name, :description, true, 'platform') "
                    + "ON CONFLICT (tenant_id, code) DO NOTHING RETURNING role_id",
                new MapSqlParameterSource("tenant", tenantId).addValue("code", d.code()).addValue("name", d.name())
                    .addValue("description", d.description()),
                UUID.class);
            for (UUID roleId : inserted) {
                for (Permission permission : d.permissions()) {
                    jdbc.update("INSERT INTO iam.role_permission (tenant_id, role_id, permission) "
                            + "VALUES (:tenant, :role, :permission)",
                        new MapSqlParameterSource("tenant", tenantId).addValue("role", roleId)
                            .addValue("permission", permission.code()));
                }
            }
        }
    }

    /** First sight of a staff member: record them, and the Keycloak roles they arrived with, once. */
    private void importFromToken(StaffIdentity identity) {
        MapSqlParameterSource p = new MapSqlParameterSource()
            .addValue("tenant", identity.tenantId()).addValue("user", identity.userId())
            .addValue("username", identity.username() != null ? identity.username() : identity.userId().toString())
            .addValue("displayName", identity.displayName()).addValue("email", identity.email())
            .addValue("now", java.sql.Timestamp.from(Instant.now(clock)));
        int created = jdbc.update("INSERT INTO iam.staff_user (tenant_id, user_id, username, display_name, email, "
                + "created_by, last_seen_at) VALUES (:tenant, :user, :username, :displayName, :email, 'first-sign-in', :now) "
                + "ON CONFLICT DO NOTHING", p);
        if (created == 0) return;
        Set<String> imported = new LinkedHashSet<>();
        for (String code : identity.tokenRoles()) {
            int granted = jdbc.update("INSERT INTO iam.user_role (tenant_id, user_id, role_id, granted_by) "
                    + "SELECT :tenant, :user, role_id, 'keycloak' FROM iam.role WHERE tenant_id = :tenant AND code = :code "
                    + "ON CONFLICT DO NOTHING",
                new MapSqlParameterSource(p.getValues()).addValue("code", code));
            if (granted > 0) imported.add(code);
        }
        jdbc.update("INSERT INTO iam.access_change (tenant_id, user_id, change_type, detail, requested_by, "
                + "requested_by_name) VALUES (:tenant, :user, 'IMPORTED', CAST(:detail AS jsonb), 'keycloak', 'Keycloak')",
            new MapSqlParameterSource(p.getValues()).addValue("detail", "{\"roles\":" + toJsonArray(imported) + "}"));
        log.info("Staff member {} seen for the first time in tenant {}; imported roles {}", identity.username(),
            identity.tenantId(), imported);
    }

    private static String toJsonArray(Set<String> codes) {
        StringBuilder sb = new StringBuilder("[");
        for (String c : codes) {
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(c).append('"');   // role codes are ^[A-Z][A-Z0-9_]*$, nothing to escape
        }
        return sb.append(']').toString();
    }
}
