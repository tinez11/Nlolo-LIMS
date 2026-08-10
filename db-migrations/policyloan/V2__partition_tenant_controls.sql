-- Self-maintaining tenant-isolation + privilege controls for partitions created AFTER
-- the migrations run (M3 final whole-branch review, Important I2).
--
-- THE DEFECT THIS CLOSES
-- ----------------------
-- Postgres does NOT cascade `ENABLE ROW LEVEL SECURITY`, a `CREATE POLICY`, or a `REVOKE`
-- from a partitioned parent to its partitions -- each partition is an independent relation
-- with its own row-security flag, its own pg_policy rows, and its own ACL. Task 1 proved
-- this empirically and documents it at length in V1 (see the "OPERATIONAL TRAP FOR FUTURE
-- PARTITIONS" comment there); V1 therefore stamps RLS + policy + REVOKE onto each of the
-- two hand-written 2026-08/2026-09 partitions by name.
--
-- What V1 could not do is defend the partitions that do not exist yet.
-- db-migrations/_post-migration/configure-pg-partman.sql registers
-- policyloan.loan_transaction with pg_partman and schedules
-- `CALL partman.run_maintenance_proc()` nightly via pg_cron, so from 2026-10 onward the
-- partitions of this schema's highest-volume financial ledger are created by automation,
-- not by a migration anybody reviews. Verified empirically against pg_partman 5.5.0 on
-- PostgreSQL 16.14 (throwaway container built from infra/postgres/Dockerfile, i.e. the real
-- image), BEFORE this file existed:
--
--   relname                    | rls | policies | app_role UPDATE | app_role DELETE
--   loan_transaction           | t   |        1 | f               | f       <- parent, correct
--   loan_transaction_2026_08   | t   |        1 | f               | f       <- V1, correct
--   loan_transaction_2026_09   | t   |        1 | f               | f       <- V1, correct
--   loan_transaction_p20261001 | f   |        0 | t               | t       <- pg_partman
--   loan_transaction_p20261101 | f   |        0 | t               | t       <- pg_partman
--   loan_transaction_p20261201 | f   |        0 | t               | t       <- pg_partman
--   loan_transaction_default   | f   |        0 | t               | t       <- pg_partman
--
-- i.e. every automatically-created partition lands with RLS off, no tenant policy, and the
-- append-only WORM control (V1's `REVOKE UPDATE, DELETE`) silently removed -- the last two
-- because `ALTER DEFAULT PRIVILEGES IN SCHEMA policyloan GRANT ... TO app_role` (V1) does
-- apply to tables created later, while `REVOKE` has no symmetric "default revoke".
-- Note also the DEFAULT partition, which the review did not anticipate: pg_partman creates
-- one, it catches every out-of-range row, and it was unprotected too.
--
-- WHY NOT pg_partman'S TEMPLATE TABLE
-- -----------------------------------
-- pg_partman's documented mechanism for propagating properties that `CREATE TABLE ...
-- PARTITION OF` does not inherit is the template table (`p_template_table` /
-- part_config.template_table), plus `part_config.inherit_privileges` for ACLs. CHECKED, NOT
-- ASSUMED: `grep -in 'rowsecurity|ROW LEVEL SECURITY|pg_policy' pg_partman--5.5.0.sql`
-- returns ZERO hits in the entire extension -- pg_partman has no concept of row security at
-- all, so the template table cannot carry RLS or policies no matter how it is configured.
-- `inherit_privileges` would carry the ACL (it defaults to false, and turning it on requires
-- superuser), but it is only half the control and it is pg_partman-specific: it would do
-- nothing for a partition added by a future Flyway migration or by hand.
--
-- THE MECHANISM USED INSTEAD
-- --------------------------
-- A `ddl_command_end` event trigger that MIRRORS the parent's controls onto any newly
-- created partition. Mirroring (rather than hardcoding "enable RLS + revoke UPDATE/DELETE")
-- is deliberate: this trigger is database-wide and will also fire for
-- payment.payment_transaction, finaccounting.gl_posting, audit.audit_log and
-- billing.premium_invoice when those milestones land. Hardcoding an append-only REVOKE would
-- be wrong for billing.premium_invoice, whose rows legitimately get UPDATEd. Mirroring is
-- always right by construction: a partition ends up with exactly the controls its parent
-- declares, which is what every reader of the parent's migration already assumes is true.

-- Idempotent sync of one partitioned table's controls onto its partitions. Callable on its
-- own (e.g. from a future migration, or a manual repair) as well as from the event trigger.
CREATE OR REPLACE FUNCTION public.sync_partition_controls(p_parent regclass)
RETURNS integer
LANGUAGE plpgsql
-- SECURITY DEFINER: ALTER TABLE / CREATE POLICY / GRANT require table ownership. Partitions
-- are created by whichever role runs pg_partman's maintenance (the pg_cron job owner), which
-- is not necessarily the owner of the parent. Definer here is the migration role, which owns
-- every object the migrations create.
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_parent_rls   boolean;
    v_parent_acl   aclitem[];
    v_child        oid;
    v_child_name   text;
    v_policy       record;
    v_acl          record;
    v_grantee      text;
    v_synced       integer := 0;
BEGIN
    SELECT c.relrowsecurity, c.relacl INTO v_parent_rls, v_parent_acl
      FROM pg_class c WHERE c.oid = p_parent AND c.relkind = 'p';
    IF NOT FOUND THEN
        RETURN 0;   -- not a partitioned table; nothing to mirror
    END IF;

    FOR v_child IN SELECT i.inhrelid FROM pg_inherits i WHERE i.inhparent = p_parent
    LOOP
        v_child_name := v_child::regclass::text;

        -- 1. Row-security flag. Enabled only when the parent has it, and only when the child
        --    does not already -- so the nested ALTER TABLE cannot recurse indefinitely.
        IF v_parent_rls AND NOT (SELECT c.relrowsecurity FROM pg_class c WHERE c.oid = v_child) THEN
            EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', v_child_name);
        END IF;

        -- 2. Policies. Copied only onto a child that has NONE, so a partition already
        --    configured by hand (loan_transaction_2026_08/09 in V1, under their own policy
        --    names) is left exactly as it is rather than acquiring a redundant duplicate.
        IF NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = v_child) THEN
            FOR v_policy IN
                SELECT p.polname,
                       p.polpermissive,
                       p.polcmd,
                       pg_get_expr(p.polqual, p.polrelid)      AS using_expr,
                       pg_get_expr(p.polwithcheck, p.polrelid) AS check_expr,
                       COALESCE((SELECT string_agg(CASE WHEN ro = 0 THEN 'PUBLIC' ELSE quote_ident(r.rolname) END, ', ')
                                   FROM unnest(p.polroles) AS ro
                                   LEFT JOIN pg_roles r ON r.oid = ro), 'PUBLIC') AS roles
                  FROM pg_policy p WHERE p.polrelid = p_parent
            LOOP
                EXECUTE format('CREATE POLICY %I ON %s AS %s FOR %s TO %s%s%s',
                    v_policy.polname,
                    v_child_name,
                    CASE WHEN v_policy.polpermissive THEN 'PERMISSIVE' ELSE 'RESTRICTIVE' END,
                    CASE v_policy.polcmd WHEN 'r' THEN 'SELECT' WHEN 'a' THEN 'INSERT'
                                         WHEN 'w' THEN 'UPDATE' WHEN 'd' THEN 'DELETE'
                                         ELSE 'ALL' END,
                    v_policy.roles,
                    CASE WHEN v_policy.using_expr IS NULL THEN '' ELSE ' USING (' || v_policy.using_expr || ')' END,
                    CASE WHEN v_policy.check_expr IS NULL THEN '' ELSE ' WITH CHECK (' || v_policy.check_expr || ')' END);
            END LOOP;
        END IF;

        -- 3. Table ACL. `ALTER DEFAULT PRIVILEGES` has already handed the fresh partition the
        --    schema-wide default grant (SELECT, INSERT, UPDATE, DELETE for app_role in this
        --    schema), which is precisely how V1's append-only REVOKE gets silently undone.
        --    Reset the child's non-owner ACL and replay the parent's, so the child's effective
        --    privileges are the parent's -- no more, no less. Skipped when the parent has no
        --    explicit ACL of its own (relacl NULL = owner-only defaults), because "mirror
        --    nothing" would then revoke access the parent never restricted.
        IF v_parent_acl IS NOT NULL THEN
            FOR v_grantee IN
                SELECT DISTINCT CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(r.rolname) END
                  FROM pg_class c, aclexplode(c.relacl) a
                  LEFT JOIN pg_roles r ON r.oid = a.grantee
                 WHERE c.oid = v_child AND a.grantee <> c.relowner
            LOOP
                EXECUTE format('REVOKE ALL ON TABLE %s FROM %s', v_child_name, v_grantee);
            END LOOP;
            FOR v_acl IN
                SELECT CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE quote_ident(r.rolname) END AS grantee,
                       a.privilege_type
                  FROM pg_class c, aclexplode(c.relacl) a
                  LEFT JOIN pg_roles r ON r.oid = a.grantee
                 WHERE c.oid = p_parent AND a.grantee <> c.relowner
            LOOP
                EXECUTE format('GRANT %s ON TABLE %s TO %s', v_acl.privilege_type, v_child_name, v_acl.grantee);
            END LOOP;
        END IF;

        v_synced := v_synced + 1;
    END LOOP;

    RETURN v_synced;
END;
$$;

COMMENT ON FUNCTION public.sync_partition_controls(regclass) IS
    'Mirrors a partitioned table''s row-security flag, policies and table ACL onto its partitions. Postgres does not cascade any of the three.';

CREATE OR REPLACE FUNCTION public.partition_controls_ddl_end()
RETURNS event_trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_obj    record;
    v_parent oid;
BEGIN
    -- Re-entrancy guard. sync_partition_controls issues ALTER TABLE / CREATE POLICY /
    -- GRANT / REVOKE, every one of which is itself a `ddl_command_end` event in this
    -- trigger's TAG list and would re-enter this function. The GUC is transaction-local
    -- (set_config's third argument is is_local), so it needs no cleanup on failure.
    IF COALESCE(current_setting('lifeplatform.partition_sync_active', true), 'off') = 'on' THEN
        RETURN;
    END IF;
    PERFORM set_config('lifeplatform.partition_sync_active', 'on', true);

    FOR v_obj IN SELECT * FROM pg_event_trigger_ddl_commands()
    LOOP
        v_parent := NULL;
        IF v_obj.object_type = 'table' THEN
            -- A partition was created or attached -> mirror its parent's controls.
            -- The partitioned parent itself was altered -> re-sync every partition, so a
            -- migration that tightens the parent cannot leave an older child behind.
            SELECT i.inhparent INTO v_parent FROM pg_inherits i WHERE i.inhrelid = v_obj.objid;
            IF v_parent IS NULL AND EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = v_obj.objid AND c.relkind = 'p') THEN
                v_parent := v_obj.objid;
            END IF;
        ELSIF v_obj.object_type = 'policy' THEN
            -- CREATE/ALTER POLICY reports the POLICY's oid, not the table's. Needed because
            -- every module migration enables RLS on the parent and then creates its policy in
            -- a separate statement; without this branch the partitions would be left
            -- RLS-enabled-but-policy-less (i.e. default-deny) after the ALTER TABLE.
            SELECT p.polrelid INTO v_parent FROM pg_policy p WHERE p.oid = v_obj.objid;
            IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = v_parent AND c.relkind = 'p') THEN
                v_parent := NULL;
            END IF;
        END IF;

        IF v_parent IS NOT NULL THEN
            PERFORM public.sync_partition_controls(v_parent::regclass);
        END IF;
    END LOOP;

    -- GRANT and REVOKE report object_type 'TABLE' with a NULL objid (verified on 16.14), so
    -- the affected relation cannot be identified from the event at all -- and `GRANT ... ON
    -- ALL TABLES IN SCHEMA` is exactly how each module's V1 hands app_role its privileges,
    -- which is the statement that silently re-grants UPDATE/DELETE on a ledger partition.
    -- Fall back to a full sweep for those two tags only. They occur in migrations, not in
    -- pg_partman's maintenance path, so this costs nothing at runtime.
    IF EXISTS (SELECT 1 FROM pg_event_trigger_ddl_commands() WHERE command_tag IN ('GRANT', 'REVOKE')) THEN
        FOR v_parent IN
            SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE c.relkind = 'p' AND n.nspname NOT IN ('pg_catalog', 'information_schema')
        LOOP
            PERFORM public.sync_partition_controls(v_parent::regclass);
        END LOOP;
    END IF;

    PERFORM set_config('lifeplatform.partition_sync_active', 'off', true);
END;
$$;

-- Deliberately NOT wrapped in an exception handler: if the controls cannot be applied, the
-- CREATE TABLE that triggered it fails and pg_partman's maintenance run fails loudly. That is
-- the fail-closed choice this platform makes everywhere else (see app_role's NOSUPERUSER
-- NOBYPASSRLS bootstrap and ci-cd.yml's build-failing rolbypassrls assertion) -- a missing
-- partition is an outage that gets fixed in minutes; an unprotected partition on the loan
-- ledger is a cross-tenant leak nobody notices.
DROP EVENT TRIGGER IF EXISTS trg_partition_controls;
CREATE EVENT TRIGGER trg_partition_controls
    ON ddl_command_end
    WHEN TAG IN ('CREATE TABLE', 'ALTER TABLE', 'CREATE POLICY', 'ALTER POLICY', 'GRANT', 'REVOKE')
    EXECUTE FUNCTION public.partition_controls_ddl_end();

-- Backfill: apply the same mirroring to every partitioned table that already exists at this
-- point in the migration sequence, so the control is not merely forward-looking. Today this
-- is a no-op for policyloan.loan_transaction (V1 already stamped both partitions by hand) and
-- it is what makes the two statements above verifiable in a fresh database.
SELECT public.sync_partition_controls(c.oid::regclass)
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind = 'p' AND n.nspname NOT IN ('pg_catalog', 'information_schema');
