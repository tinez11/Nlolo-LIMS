-- db-migrations/policy/V35__covered_life.sql
-- Family funeral cover: one policy, many covered lives. The main member is the policyholder and the
-- life assured; each dependant is a name on the policy (credit life's FREEFORM reason), promoted to a
-- party at claim. Read only after the product's category says FUNERAL, so no other test class needs it.

-- The plan the family bought (the anniversary re-prices against it) and the spouse a takeover is waiting
-- on (plan R8). Its own 1:1 table rather than columns on policy.policy: a mapped column there would make
-- every test class that never sells a funeral plan need this migration.
CREATE TABLE policy.funeral_policy (
    policy_number             VARCHAR(20) PRIMARY KEY REFERENCES policy.policy(policy_number),
    tenant_id                 UUID NOT NULL,
    plan_code                 VARCHAR(20) NOT NULL,
    awaiting_takeover_life_id UUID,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE policy.covered_life (
    covered_life_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL,
    policy_number      VARCHAR(20) NOT NULL REFERENCES policy.funeral_policy(policy_number),
    role               VARCHAR(20) NOT NULL CHECK (role IN ('MAIN_MEMBER','SPOUSE','CHILD','PARENT','EXTENDED')),
    full_name          VARCHAR(200) NOT NULL,
    date_of_birth      DATE NOT NULL,
    sex                VARCHAR(10) CHECK (sex IS NULL OR sex IN ('FEMALE','MALE')),
    id_number          VARCHAR(50),
    student            BOOLEAN NOT NULL DEFAULT false,
    -- The main member always; a dependant once promoted at claim.
    party_id           UUID,
    -- What a death pays: stored when the cover was set, never re-read from the plan.
    benefit            NUMERIC(19,2) NOT NULL CHECK (benefit > 0),
    yearly_premium     NUMERIC(19,2) NOT NULL CHECK (yearly_premium > 0),
    priced_at_age      INTEGER NOT NULL CHECK (priced_at_age >= 0),
    -- The life's own cover start: its waiting period runs from here.
    cover_start        DATE NOT NULL,
    -- A scheduled end (a removal, plan R5's free cover) and why: the life stays ACTIVE and covered until
    -- the nightly sweep reaches cover_end, then ENDS with pending_end_reason.
    cover_end          DATE,
    pending_end_reason VARCHAR(30) CHECK (pending_end_reason IS NULL OR pending_end_reason IN ('REMOVED','FREE_COVER_ENDED')),
    status             VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ENDED')),
    end_reason         VARCHAR(30) CHECK (end_reason IS NULL OR end_reason IN
                           ('DECEASED','REMOVED','AGED_OUT','MAIN_MEMBER_DIED','FREE_COVER_ENDED','POLICY_ENDED')),
    ended_on           DATE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         VARCHAR(100) NOT NULL,
    CHECK ((status = 'ACTIVE' AND end_reason IS NULL AND ended_on IS NULL)
        OR (status = 'ENDED' AND end_reason IS NOT NULL AND ended_on IS NOT NULL)),
    CHECK ((cover_end IS NULL) = (pending_end_reason IS NULL)),
    CHECK (role <> 'MAIN_MEMBER' OR party_id IS NOT NULL)
);
-- One active main member and one active spouse per policy.
CREATE UNIQUE INDEX ux_covered_life_one_main ON policy.covered_life (policy_number)
    WHERE status = 'ACTIVE' AND role = 'MAIN_MEMBER';
CREATE UNIQUE INDEX ux_covered_life_one_spouse ON policy.covered_life (policy_number)
    WHERE status = 'ACTIVE' AND role = 'SPOUSE';
CREATE INDEX idx_covered_life_policy ON policy.covered_life (tenant_id, policy_number, status);

ALTER TABLE policy.funeral_policy
    ADD CONSTRAINT fk_funeral_policy_takeover_life
    FOREIGN KEY (awaiting_takeover_life_id) REFERENCES policy.covered_life(covered_life_id);

ALTER TABLE policy.funeral_policy ENABLE ROW LEVEL SECURITY;
CREATE POLICY funeral_policy_tenant_isolation ON policy.funeral_policy
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE policy.covered_life ENABLE ROW LEVEL SECURITY;
CREATE POLICY covered_life_tenant_isolation ON policy.covered_life
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON policy.funeral_policy, policy.covered_life TO app_role;
