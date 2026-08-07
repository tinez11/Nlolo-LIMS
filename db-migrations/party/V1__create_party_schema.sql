-- Module: party (Party Management) -- Deliverable 3 Rev 2 §7.1
-- Owns: party, kyc_record, party_relationship, group_membership

CREATE SCHEMA IF NOT EXISTS party;

CREATE TABLE party.party (
    party_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL,
    party_type           VARCHAR(20) NOT NULL CHECK (party_type IN ('INDIVIDUAL','CORPORATE','GROUP')),
    display_name         VARCHAR(255) NOT NULL,
    date_of_birth        DATE,                          -- INDIVIDUAL only
    registration_number  VARCHAR(50),                   -- CORPORATE only
    phone_number         VARCHAR(20),
    email                VARCHAR(255),
    kyc_status           VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (kyc_status IN ('PENDING','VERIFIED','REJECTED')),
    kyc_verified_at      TIMESTAMPTZ,
    version              BIGINT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           VARCHAR(100),
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(100)
);

CREATE UNIQUE INDEX ux_party_corporate_regno ON party.party (tenant_id, registration_number) WHERE party_type = 'CORPORATE';
CREATE INDEX idx_party_tenant ON party.party (tenant_id);
CREATE INDEX idx_party_kyc_status ON party.party (tenant_id, kyc_status);
CREATE INDEX idx_party_phone ON party.party (tenant_id, phone_number);

CREATE TABLE party.kyc_record (
    kyc_record_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    party_id              UUID NOT NULL REFERENCES party.party(party_id),
    evidence_document_ref VARCHAR(255) NOT NULL,
    status                VARCHAR(20) NOT NULL CHECK (status IN ('VERIFIED','REJECTED','PENDING')),
    verified_by           VARCHAR(100),
    verified_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_kyc_record_party ON party.kyc_record (party_id);
CREATE INDEX idx_kyc_record_tenant ON party.kyc_record (tenant_id);

CREATE TABLE party.party_relationship (
    party_relationship_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    from_party_id         UUID NOT NULL REFERENCES party.party(party_id),
    to_party_id           UUID NOT NULL REFERENCES party.party(party_id),
    relationship_type     VARCHAR(30) NOT NULL,  -- AUTHORIZED_SIGNATORY, etc.
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_party_relationship_from ON party.party_relationship (from_party_id);
CREATE INDEX idx_party_relationship_to ON party.party_relationship (to_party_id);
CREATE INDEX idx_party_relationship_tenant ON party.party_relationship (tenant_id);

-- Deliverable 3 Rev 2, Pa1: its own entity/table -- paginated, queried independently,
-- NEVER loaded as a collection on the Party aggregate (a 10,000-member SACCO group
-- would otherwise blow up the aggregate-size principle everything else here relies on).
CREATE TABLE party.group_membership (
    group_membership_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    group_party_id      UUID NOT NULL REFERENCES party.party(party_id),
    member_party_id     UUID NOT NULL REFERENCES party.party(party_id),
    join_date           DATE NOT NULL DEFAULT CURRENT_DATE,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REMOVED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_group_membership_group ON party.group_membership (group_party_id, status);
CREATE INDEX idx_group_membership_member ON party.group_membership (member_party_id);
CREATE UNIQUE INDEX ux_group_membership_active ON party.group_membership (group_party_id, member_party_id) WHERE status = 'ACTIVE';

-- Row-Level Security -- defense-in-depth alongside Hibernate's @TenantId filter.
-- NEW recommendation this deliverable (see Deliverable 6 covering doc §4) -- flagged
-- for your sign-off since RLS wasn't part of the multi-tenancy discussion in your
-- original spec. app.current_tenant_id is set per-transaction by a connection
-- interceptor reading the validated JWT's tenant_id claim.
ALTER TABLE party.party ENABLE ROW LEVEL SECURITY;
CREATE POLICY party_tenant_isolation ON party.party
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE party.kyc_record ENABLE ROW LEVEL SECURITY;
CREATE POLICY kyc_record_tenant_isolation ON party.kyc_record
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE party.party_relationship ENABLE ROW LEVEL SECURITY;
CREATE POLICY party_relationship_tenant_isolation ON party.party_relationship
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE party.group_membership ENABLE ROW LEVEL SECURITY;
CREATE POLICY group_membership_tenant_isolation ON party.group_membership
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- app_role privileges -- migrations run as the postgres superuser (scripts/migrate.sh),
-- which becomes owner of every object created above; without these explicit grants
-- app_role (the application's runtime DB role, infra/postgres/init/01-create-app-role.sql.template)
-- has no access to this schema at all and every request against it fails with
-- "permission denied for schema party".
GRANT USAGE ON SCHEMA party TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA party TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA party GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;
