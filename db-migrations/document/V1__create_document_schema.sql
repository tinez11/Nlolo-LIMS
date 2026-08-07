-- Module: document (Document & Content Management) -- Deliverable 3 §9 (thin, generic)
-- Owns: document_record (metadata only; binary content lives in MinIO)

CREATE SCHEMA IF NOT EXISTS document;

CREATE TABLE document.document_record (
    document_ref          VARCHAR(255) PRIMARY KEY,   -- opaque storage key (MinIO object key)
    tenant_id               UUID NOT NULL,
    owner_context             VARCHAR(50) NOT NULL,       -- which module/aggregate this belongs to, free text by design
    document_type              VARCHAR(30) NOT NULL CHECK (document_type IN
        ('KYC_EVIDENCE','POLICY_DOCUMENT','CLAIM_EVIDENCE','SIGNED_FORM')),
    uploaded_by                 VARCHAR(100),
    uploaded_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_document_record_tenant ON document.document_record (tenant_id);
CREATE INDEX idx_document_record_owner ON document.document_record (owner_context);

ALTER TABLE document.document_record ENABLE ROW LEVEL SECURITY;
CREATE POLICY document_record_tenant_isolation ON document.document_record
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- app_role privileges -- migrations run as the postgres superuser (scripts/migrate.sh),
-- which becomes owner of every object created above; without these explicit grants
-- app_role (the application's runtime DB role, infra/postgres/init/01-create-app-role.sql.template)
-- has no access to this schema at all and every request against it fails with
-- "permission denied for schema document".
GRANT USAGE ON SCHEMA document TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA document TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA document GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;
