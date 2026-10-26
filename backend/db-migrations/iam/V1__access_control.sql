-- Module: iam (Identity & Access) -- users, roles and permissions held by the platform (2026-10-26).
--
-- Keycloak keeps the login: the password, the reset, the session. What a signed-in staff member may SEE and DO is
-- decided here, so an administrator changes it from the console and it takes effect on the next request rather than
-- the next login. The permission codes themselves are a catalogue in code (iam.api.Permission); these tables hold
-- which role carries which, who holds which role, and the extra single permissions given to one person.
--
-- Staff only. Agents and customers are scoped to their own business by their realm and party, unchanged.

CREATE SCHEMA IF NOT EXISTS iam;

-- A role is a named bundle of permissions. The eight built-in roles are seeded per tenant from code the first time
-- the tenant is resolved (BuiltInRoles), so a tenant starts with exactly what Keycloak gave it; an administrator may
-- then change what they carry, or add roles of their own.
CREATE TABLE iam.role (
    role_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL,
    code         VARCHAR(60) NOT NULL,
    name         VARCHAR(120) NOT NULL,
    description  TEXT,
    built_in     BOOLEAN NOT NULL DEFAULT false,
    active       BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   VARCHAR(255) NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_role_code UNIQUE (tenant_id, code),
    CONSTRAINT ck_role_code CHECK (code ~ '^[A-Z][A-Z0-9_]*$')
);

CREATE TABLE iam.role_permission (
    tenant_id   UUID NOT NULL,
    role_id     UUID NOT NULL REFERENCES iam.role (role_id) ON DELETE CASCADE,
    permission  VARCHAR(80) NOT NULL,
    PRIMARY KEY (role_id, permission)
);
CREATE INDEX idx_role_permission_tenant ON iam.role_permission (tenant_id);

-- A staff member, keyed by their Keycloak subject. Recorded the first time they are seen (their Keycloak roles are
-- imported then, once) or when an administrator adds them from the console.
CREATE TABLE iam.staff_user (
    tenant_id     UUID NOT NULL,
    user_id       UUID NOT NULL,
    username      VARCHAR(255) NOT NULL,
    display_name  VARCHAR(255),
    email         VARCHAR(255),
    phone         VARCHAR(40),
    home_branch   VARCHAR(20),
    active        BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    VARCHAR(255) NOT NULL,
    last_seen_at  TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, user_id)
);

CREATE TABLE iam.user_role (
    tenant_id   UUID NOT NULL,
    user_id     UUID NOT NULL,
    role_id     UUID NOT NULL REFERENCES iam.role (role_id) ON DELETE CASCADE,
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (tenant_id, user_id, role_id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES iam.staff_user (tenant_id, user_id) ON DELETE CASCADE
);

-- Add-only: a single permission given to one person on top of their roles (the user's decision 2026-10-26). There is
-- deliberately no "deny" -- what a person cannot do is what nothing grants them.
CREATE TABLE iam.user_permission (
    tenant_id   UUID NOT NULL,
    user_id     UUID NOT NULL,
    permission  VARCHAR(80) NOT NULL,
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (tenant_id, user_id, permission),
    FOREIGN KEY (tenant_id, user_id) REFERENCES iam.staff_user (tenant_id, user_id) ON DELETE CASCADE
);

-- Every change to who may do what, in order: the history the Users screen shows, and the queue for the one change
-- that needs a second administrator -- giving someone ADMIN (status PENDING until another administrator decides).
CREATE TABLE iam.access_change (
    change_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    user_id       UUID,
    role_id       UUID,
    change_type   VARCHAR(40) NOT NULL,
    detail        JSONB NOT NULL DEFAULT '{}'::jsonb,
    status        VARCHAR(20) NOT NULL DEFAULT 'APPLIED',
    requested_by       VARCHAR(255) NOT NULL,
    requested_by_name  VARCHAR(255),
    requested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by         VARCHAR(255),
    decided_by_name    VARCHAR(255),
    decided_at    TIMESTAMPTZ,
    CONSTRAINT ck_access_change_status CHECK (status IN ('APPLIED', 'PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'))
);
CREATE INDEX idx_access_change_user ON iam.access_change (tenant_id, user_id, requested_at DESC);
CREATE INDEX idx_access_change_pending ON iam.access_change (tenant_id) WHERE status = 'PENDING';

GRANT USAGE ON SCHEMA iam TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON iam.role, iam.role_permission, iam.staff_user, iam.user_role,
    iam.user_permission TO app_role;
-- The history is append-only apart from deciding a pending change.
GRANT SELECT, INSERT, UPDATE ON iam.access_change TO app_role;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['role', 'role_permission', 'staff_user', 'user_role', 'user_permission', 'access_change'] LOOP
        EXECUTE format('ALTER TABLE iam.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON iam.%I USING (tenant_id = '
            'NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)', t || '_tenant_isolation', t);
    END LOOP;
END $$;
