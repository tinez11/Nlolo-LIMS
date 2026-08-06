package tz.co.nlolo.lifeplatform;

import java.util.UUID;

/**
 * Per-thread tenant identity. Business code MUST call get() (throws if unset --
 * a deliberate fail-loud guard against silently writing rows with no tenant).
 * getOrNull() exists ONLY for TenantAwareDataSource, which must not crash
 * connections acquired outside any tenant-scoped operation (health checks,
 * refdata's non-tenant-scoped queries).
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static UUID get() {
        UUID tenantId = CURRENT_TENANT.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set for the current thread");
        }
        return tenantId;
    }

    public static UUID getOrNull() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
