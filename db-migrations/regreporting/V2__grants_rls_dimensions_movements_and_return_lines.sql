-- Module: regreporting V2 -- M10 Task 1.
--
-- CITATION CORRECTION. V1's line 1 reads "Deliverable 3 §10 (CQRS read-model only)".
-- That section does not say this: docs/03-aggregate-design.md:179's §10 is "Thin/Generic
-- Modules -- unchanged from Rev 1 (iam, document, refdata, omnichannel)", which does not
-- mention regreporting at all, and no CQRS read-model section for regreporting exists
-- anywhere in that document. The DESIGN INTENT V1's comment describes is sound and this
-- migration implements it; only the citation was wrong. Recorded because this project has
-- repeatedly found false doc citations used as justification for a design decision.
--
-- V1 also carries the recurring V1 defect set -- the SEVENTH consecutive module: no RLS on
-- either table, no GRANT to app_role anywhere in the schema, no audit columns. Both V1
-- tables carry tenant_id NOT NULL, so app_role could read every tenant's reporting data.
-- No test has ever applied regreporting/V1 (verified: grep -rn "regreporting" src/test
-- returns one prose comment and one ArchUnit string list), which is why none of it showed up.
-- =============================================================================
-- 1. Grants. V1 grants app_role nothing anywhere in this schema.
-- =============================================================================
GRANT USAGE ON SCHEMA regreporting TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA regreporting TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA regreporting GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;

-- =============================================================================
-- 2. RLS on V1's two tables. Neither had it.
-- =============================================================================
ALTER TABLE regreporting.regulatory_return ENABLE ROW LEVEL SECURITY;
CREATE POLICY regulatory_return_tenant_isolation ON regreporting.regulatory_return
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- policy_in_force_summary's RLS is deliberately deferred to section 4, AFTER its rename:
-- ALTER TABLE ... RENAME TO does NOT rename the table's policies, so creating the policy here
-- would leave policy_movement carrying a policy called policy_in_force_summary_tenant_isolation
-- forever -- functional, but a permanently misleading artifact in pg_policy.

-- =============================================================================
-- 3. Audit columns and the regeneration key on regulatory_return.
--
--    generateReturn is idempotent per (tenant, return_type, period): regenerating REPLACES
--    the prior lines rather than accumulating duplicates, so that triple must be unique.
--    A return is a DERIVED artifact -- re-deriving it must be safe.
-- =============================================================================
ALTER TABLE regreporting.regulatory_return ADD COLUMN generated_by VARCHAR(100);
ALTER TABLE regreporting.regulatory_return ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX ux_regulatory_return_once
    ON regreporting.regulatory_return (tenant_id, return_type, period);

-- status stays VARCHAR(15) CHECK IN ('GENERATING','READY') exactly as V1 declared it.
-- Under synchronous generation (design spec §7) GENERATING is UNREACHABLE -- a return is
-- complete when it is created -- and READY is the only value ever written. The CHECK is left
-- alone because dropping an unreachable enum value is not worth a migration.
-- NOTE FOR WHOEVER ADDS SUBMISSION STATES AFTER C2: 'SUBMISSION_FAILED' is 17 characters and
-- would need this column widened in the SAME migration that adds it to the CHECK. That exact
-- oversight (a CHECK admitting a value the column cannot store) made a whole payout path
-- unwritable in M7 while every test stayed green -- see docs/06-database-schema.md:30.
COMMENT ON COLUMN regreporting.regulatory_return.status IS
    'Always READY. GENERATING is unreachable under synchronous generation; retained because V1 is immutable.';
COMMENT ON COLUMN regreporting.regulatory_return.document_ref IS
    'C2-BLOCKED -- never written. Rendering a submission artifact requires TIRA''s file format, which C2 has not supplied.';

-- =============================================================================
-- 4. policy_in_force_summary -> policy_movement, with GROSS per-cause measures.
--
--    THE RENAME IS NOT COSMETIC. Under the movement model this table stores per-period
--    movements, not a snapshot of what is in force; a reader trusting the old name would
--    compute in-force figures wrongly. Its existing unique index on
--    (tenant_id, period, product_id) is already the correct grain and is retained.
--
--    GROSS, NOT NET: a single signed delta cannot distinguish "10 issued, 3 lapsed" from
--    "7 issued, 0 lapsed" -- both net +7 -- and every prudential return needs gross new
--    business and gross terminations as separate lines. Storing the net loses the gross
--    irrecoverably; storing the gross derives the net by arithmetic.
-- =============================================================================
ALTER TABLE regreporting.policy_in_force_summary RENAME TO policy_movement;
ALTER INDEX regreporting.ux_policy_in_force_summary RENAME TO ux_policy_movement;

-- Promote the composite business key to the PRIMARY KEY and drop V1's surrogate summary_id.
--
-- WHY THIS IS NECESSARY, not tidying: V1 made summary_id the PK and left
-- (tenant_id, period, product_id) as a mere UNIQUE INDEX. Its three sibling fact tables in
-- section 6 all use the composite AS the primary key, so without this the domain model would
-- have to map policy_movement on a surrogate id while the other three use @IdClass -- an
-- asymmetry with no upside, and the plan's Task 3 maps all four the same way.
--
-- WHY IT IS SAFE REGARDLESS OF EXISTING DATA -- which matters, because "it works because the
-- table happens to be empty today" is exactly the reasoning that breaks on the first deployment
-- that has rows: the columns being promoted are already NOT NULL, and ux_policy_movement is
-- already a UNIQUE index over exactly this tuple, so the promotion cannot fail on either null
-- or duplicate data. The uniqueness it depends on is already enforced.
ALTER TABLE regreporting.policy_movement DROP CONSTRAINT policy_in_force_summary_pkey;
ALTER TABLE regreporting.policy_movement DROP COLUMN summary_id;
ALTER TABLE regreporting.policy_movement ADD PRIMARY KEY (tenant_id, period, product_id);
-- ux_policy_movement is now redundant with the PK's own implicit unique index. Dropped rather
-- than left as a second identical index that every write has to maintain.
DROP INDEX regreporting.ux_policy_movement;

ALTER TABLE regreporting.policy_movement RENAME COLUMN policy_count TO policies_issued;
ALTER TABLE regreporting.policy_movement RENAME COLUMN total_sum_assured_amount TO sum_assured_issued;
ALTER TABLE regreporting.policy_movement RENAME COLUMN total_sum_assured_currency TO currency;

ALTER TABLE regreporting.policy_movement ADD COLUMN policies_reinstated INTEGER NOT NULL DEFAULT 0;
ALTER TABLE regreporting.policy_movement ADD COLUMN policies_lapsed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE regreporting.policy_movement ADD COLUMN policies_matured INTEGER NOT NULL DEFAULT 0;
ALTER TABLE regreporting.policy_movement ADD COLUMN policies_claim_terminated INTEGER NOT NULL DEFAULT 0;
ALTER TABLE regreporting.policy_movement ADD COLUMN sum_assured_terminated NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE regreporting.policy_movement ADD COLUMN updated_at TIMESTAMPTZ;

-- Every measure is a GROSS non-negative figure, so >= 0 is the right guard.
-- Deliberately >= 0 and not > 0: an upsert creates the row with zeros and then increments ONE
-- column, so zero is a normal value for every other cause in that period. This is the opposite
-- of finaccounting.gl_posting.amount's strict > 0, where a separate direction column carries the
-- sign and a zero-amount posting is meaningless. Do not "fix" this into > 0.
ALTER TABLE regreporting.policy_movement
    ADD CONSTRAINT policy_movement_non_negative CHECK (
        policies_issued >= 0 AND policies_reinstated >= 0 AND policies_lapsed >= 0
        AND policies_matured >= 0 AND policies_claim_terminated >= 0
        AND sum_assured_issued >= 0 AND sum_assured_terminated >= 0);

CREATE INDEX idx_policy_movement_tenant ON regreporting.policy_movement (tenant_id);

-- RLS created here, after the rename, so the policy carries the table's real name (see section 2).
ALTER TABLE regreporting.policy_movement ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_movement_tenant_isolation ON regreporting.policy_movement
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- =============================================================================
-- 5. The two dimension tables.
--
--    These exist because the attributes needed to ATTRIBUTE a movement arrive on a different
--    event from the movement itself. Verified against api/asyncapi-events.yaml: PolicyIssued
--    (:421) carries productId and sumAssured, but PolicyLapsed/Reinstated/Matured/Surrendered
--    carry only a policy number and a timestamp; ClaimRegistered carries claimType, but
--    ClaimApproved/Rejected/Settled do not. Same pattern distribution (M7) and reinsurance (M8)
--    each established with their own policy_projection, for the identical allowedDependencies
--    constraint. policy_number and claim_id are OPAQUE refs -- no FKs (docs/06-database-schema.md:29).
-- =============================================================================
CREATE TABLE regreporting.policy_dimension (
    tenant_id             UUID NOT NULL,
    policy_number          VARCHAR(20) NOT NULL,
    product_id              UUID NOT NULL,
    sum_assured_amount       NUMERIC(19,2) NOT NULL,
    sum_assured_currency      CHAR(3) NOT NULL DEFAULT 'TZS',
    issue_date                 DATE NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, policy_number),
    CONSTRAINT policy_dimension_sum_assured_positive CHECK (sum_assured_amount > 0)
);
CREATE INDEX idx_policy_dimension_tenant ON regreporting.policy_dimension (tenant_id);
ALTER TABLE regreporting.policy_dimension ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_dimension_tenant_isolation ON regreporting.policy_dimension
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

CREATE TABLE regreporting.claim_dimension (
    tenant_id           UUID NOT NULL,
    claim_id             UUID NOT NULL,
    claim_type            VARCHAR(20) NOT NULL
        CHECK (claim_type IN ('DEATH','DISABILITY','CRITICAL_ILLNESS','MATURITY')),
    policy_number          VARCHAR(20) NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, claim_id)
);
CREATE INDEX idx_claim_dimension_tenant ON regreporting.claim_dimension (tenant_id);
ALTER TABLE regreporting.claim_dimension ENABLE ROW LEVEL SECURITY;
CREATE POLICY claim_dimension_tenant_isolation ON regreporting.claim_dimension
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- claim_type VARCHAR(20) against a longest value of 'CRITICAL_ILLNESS' (16). MEASURED, not
-- estimated, per docs/06-database-schema.md:30's own warning.

-- =============================================================================
-- 6. The remaining three fact tables. All measures gross and non-negative.
-- =============================================================================
CREATE TABLE regreporting.claims_movement (
    tenant_id          UUID NOT NULL,
    period              VARCHAR(10) NOT NULL,
    claim_type           VARCHAR(20) NOT NULL,
    registered_count      INTEGER NOT NULL DEFAULT 0,
    approved_count         INTEGER NOT NULL DEFAULT 0,
    rejected_count          INTEGER NOT NULL DEFAULT 0,
    settled_count            INTEGER NOT NULL DEFAULT 0,
    approved_amount           NUMERIC(19,2) NOT NULL DEFAULT 0,
    settled_amount             NUMERIC(19,2) NOT NULL DEFAULT 0,
    currency                    CHAR(3) NOT NULL DEFAULT 'TZS',
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, period, claim_type),
    CONSTRAINT claims_movement_non_negative CHECK (
        registered_count >= 0 AND approved_count >= 0 AND rejected_count >= 0
        AND settled_count >= 0 AND approved_amount >= 0 AND settled_amount >= 0)
);
CREATE INDEX idx_claims_movement_tenant ON regreporting.claims_movement (tenant_id);
ALTER TABLE regreporting.claims_movement ENABLE ROW LEVEL SECURITY;
CREATE POLICY claims_movement_tenant_isolation ON regreporting.claims_movement
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

CREATE TABLE regreporting.premium_movement (
    tenant_id         UUID NOT NULL,
    period             VARCHAR(10) NOT NULL,
    product_id          UUID NOT NULL,
    collected_amount     NUMERIC(19,2) NOT NULL DEFAULT 0,
    currency              CHAR(3) NOT NULL DEFAULT 'TZS',
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, period, product_id),
    CONSTRAINT premium_movement_non_negative CHECK (collected_amount >= 0)
);
CREATE INDEX idx_premium_movement_tenant ON regreporting.premium_movement (tenant_id);
ALTER TABLE regreporting.premium_movement ENABLE ROW LEVEL SECURITY;
CREATE POLICY premium_movement_tenant_isolation ON regreporting.premium_movement
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- reinsurance_movement is deliberately NOT attributed by product. reinsurance.CessionRecorded
-- is published from reinsurance's own transaction reacting to PolicyIssued, so its arrival is
-- NOT ordered against regreporting's own PolicyIssued listener (docs/05-event-catalog.md:62
-- documents that ordering across fan-in consumers is not guaranteed). Keying it by product
-- would create a race where a cession can arrive before the dimension row it needs.
CREATE TABLE regreporting.reinsurance_movement (
    tenant_id            UUID NOT NULL,
    period                VARCHAR(10) NOT NULL,
    ceded_risk_amount      NUMERIC(19,2) NOT NULL DEFAULT 0,
    ceded_premium_amount    NUMERIC(19,2) NOT NULL DEFAULT 0,
    currency                 CHAR(3) NOT NULL DEFAULT 'TZS',
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, period),
    CONSTRAINT reinsurance_movement_non_negative CHECK (
        ceded_risk_amount >= 0 AND ceded_premium_amount >= 0)
);
CREATE INDEX idx_reinsurance_movement_tenant ON regreporting.reinsurance_movement (tenant_id);
ALTER TABLE regreporting.reinsurance_movement ENABLE ROW LEVEL SECURITY;
CREATE POLICY reinsurance_movement_tenant_isolation ON regreporting.reinsurance_movement
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- =============================================================================
-- 7. Return definitions -- THE DATA HALF of the return format (design spec §5).
--
--    Line COMPOSITION is data (these two tables). Metric COMPUTATION is code (the
--    MetricReaderRegistry). The precise consequence, which no comment here or anywhere else
--    may overstate: re-shaping a return out of the seventeen metrics that already exist,
--    optionally filtered by product or claim type, is a SEED change. Asking for a figure
--    nobody computes yet is a JAVA change. M9's final review caught its own spec claiming
--    account codes were "data" when they were compiled constants -- do not repeat that.
--
--    period_kind exists so a return type is pinned to one period format. Without it an annual
--    and a quarterly period could be cumulative-summed together, which would be silently wrong.
-- =============================================================================
CREATE TABLE regreporting.return_definition (
    tenant_id        UUID NOT NULL,
    return_type       VARCHAR(30) NOT NULL,
    label              VARCHAR(200) NOT NULL,
    description         VARCHAR(500),
    period_kind          VARCHAR(10) NOT NULL CHECK (period_kind IN ('QUARTERLY','ANNUAL')),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, return_type)
);
CREATE INDEX idx_return_definition_tenant ON regreporting.return_definition (tenant_id);
ALTER TABLE regreporting.return_definition ENABLE ROW LEVEL SECURITY;
CREATE POLICY return_definition_tenant_isolation ON regreporting.return_definition
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

CREATE TABLE regreporting.return_definition_line (
    tenant_id       UUID NOT NULL,
    return_type      VARCHAR(30) NOT NULL,
    line_no           INTEGER NOT NULL,
    line_code          VARCHAR(30) NOT NULL,
    label               VARCHAR(200) NOT NULL,
    metric_name          VARCHAR(40) NOT NULL,
    dimension_filter      VARCHAR(40),   -- a product_id, or a claim_type; null = unfiltered
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, return_type, line_no)
);
CREATE INDEX idx_return_definition_line_tenant ON regreporting.return_definition_line (tenant_id);
ALTER TABLE regreporting.return_definition_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY return_definition_line_tenant_isolation ON regreporting.return_definition_line
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- metric_name VARCHAR(40) against the longest of the seventeen registry names,
-- 'POLICIES_CLAIM_TERMINATED' (25). MEASURED. dimension_filter VARCHAR(40) holds either a
-- 36-character UUID string or a claim type (longest 'CRITICAL_ILLNESS', 16).

-- =============================================================================
-- 8. return_line -- a generated return's actual content.
--
--    return_id IS an intra-module FK and SHOULD be: a line without its header is orphaned
--    data. ON DELETE CASCADE because regeneration replaces a return's lines wholesale.
-- =============================================================================
CREATE TABLE regreporting.return_line (
    return_line_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    return_id          UUID NOT NULL REFERENCES regreporting.regulatory_return (return_id) ON DELETE CASCADE,
    tenant_id           UUID NOT NULL,
    line_no              INTEGER NOT NULL,
    line_code             VARCHAR(30) NOT NULL,
    label                  VARCHAR(200) NOT NULL,
    metric_name             VARCHAR(40) NOT NULL,
    numeric_value            NUMERIC(19,2) NOT NULL,
    currency                  CHAR(3),   -- null for a count; set for a money figure
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_return_line_no ON regreporting.return_line (return_id, line_no);
CREATE INDEX idx_return_line_tenant ON regreporting.return_line (tenant_id);
ALTER TABLE regreporting.return_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY return_line_tenant_isolation ON regreporting.return_line
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- numeric_value carries no non-negativity CHECK, deliberately and unlike every fact table
-- above: a legitimate DERIVED figure can be negative. POLICIES_IN_FORCE is a cumulative sum of
-- (issued + reinstated - lapsed - matured - claim_terminated), and a period whose terminations
-- exceed its issuances is a real business outcome, not corrupt data.

-- =============================================================================
-- 9. Seed the ONE placeholder return definition.
--
--    EVERY LINE CODE AND LABEL BELOW IS AN INVENTED PLACEHOLDER pending the TIRA circular
--    (C2, docs/02-module-architecture.md:198). Ten lines chosen to exercise all four fact
--    tables end to end -- NOT because TIRA asks for these figures, which nobody yet knows.
--
--    Seeded for the well-known dev/test tenant only; a real deployment seeds per tenant during
--    onboarding, and replacing this catalog with TIRA's real one is a seed change to the extent
--    it asks for figures the registry already computes (design spec §5).
-- =============================================================================
INSERT INTO regreporting.return_definition (tenant_id, return_type, label, description, period_kind)
VALUES ('11111111-1111-1111-1111-111111111111', 'QUARTERLY_PRUDENTIAL',
        'Quarterly Prudential Return (PLACEHOLDER)',
        'PLACEHOLDER pending the TIRA return catalog (C2). Line codes and labels are invented.',
        'QUARTERLY');

INSERT INTO regreporting.return_definition_line
    (tenant_id, return_type, line_no, line_code, label, metric_name, dimension_filter)
VALUES
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',1,'PL-01','Policies in force at period end','POLICIES_IN_FORCE',NULL),
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',2,'PL-02','Sum assured in force at period end','SUM_ASSURED_IN_FORCE',NULL),
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',3,'PL-03','New policies issued in period','POLICIES_ISSUED',NULL),
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',4,'PL-04','New business sum assured in period','NEW_BUSINESS_SUM_ASSURED',NULL),
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',5,'PL-05','Policies lapsed in period','POLICIES_LAPSED',NULL),
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',6,'CL-01','Claims registered in period','CLAIMS_REGISTERED',NULL),
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',7,'CL-02','Claims settled in period','CLAIMS_SETTLED',NULL),
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',8,'CL-03','Death claims settled amount in period','CLAIMS_SETTLED_AMOUNT','DEATH'),
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',9,'PR-01','Premium collected in period','PREMIUM_COLLECTED',NULL),
    ('11111111-1111-1111-1111-111111111111','QUARTERLY_PRUDENTIAL',10,'RI-01','Reinsurance premium ceded in period','REINSURANCE_CEDED_PREMIUM',NULL);
