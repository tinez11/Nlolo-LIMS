-- Fails loudly if ANY partition's tenant-isolation / privilege controls have drifted from
-- its partitioned parent's. Same shape, and same intent, as ci-cd.yml's existing
-- `rolsuper OR rolbypassrls` assertion: an automated guard, not a documented convention.
--
-- Why parent-vs-child parity rather than an absolute "every tenant_id table must have RLS":
-- billing/payment/finaccounting are not built yet (M4+), so their parents legitimately
-- carry no RLS, no policy and no app_role grant at all. An absolute check would fail the
-- build on those from day one and get switched off. Parity is the invariant that is true
-- today for every schema and that the M3 final review found broken:
-- db-migrations/policyloan/V1 stamps RLS + policy + `REVOKE UPDATE, DELETE` onto
-- loan_transaction and its two hand-written partitions, and every partition pg_partman
-- creates afterwards used to arrive with none of it.
--
-- Run: psql "$DB_URL" -v ON_ERROR_STOP=1 -f db-migrations/_post-migration/verify-partition-controls.sql
DO $$
DECLARE
    v_drift text;
BEGIN
    SELECT string_agg(msg, E'\n  ') INTO v_drift
    FROM (
        SELECT format('%s (partition of %s): %s',
                      child.oid::regclass,
                      parent.oid::regclass,
                      concat_ws('; ',
                          CASE WHEN parent.relrowsecurity AND NOT child.relrowsecurity
                               THEN 'row-level security is DISABLED but the parent has it enabled' END,
                          CASE WHEN parent.relrowsecurity
                                AND NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = child.oid)
                               THEN 'no row-security policy, but the parent has one' END,
                          CASE WHEN NOT has_table_privilege('app_role', parent.oid, 'UPDATE')
                                AND has_table_privilege('app_role', child.oid, 'UPDATE')
                               THEN 'app_role holds UPDATE, revoked on the parent (append-only control lost)' END,
                          CASE WHEN NOT has_table_privilege('app_role', parent.oid, 'DELETE')
                                AND has_table_privilege('app_role', child.oid, 'DELETE')
                               THEN 'app_role holds DELETE, revoked on the parent (append-only control lost)' END))
               AS msg
          FROM pg_inherits i
          JOIN pg_class child  ON child.oid  = i.inhrelid
          JOIN pg_class parent ON parent.oid = i.inhparent
          JOIN pg_namespace n  ON n.oid = parent.relnamespace
         WHERE parent.relkind = 'p'
           AND n.nspname NOT IN ('pg_catalog', 'information_schema')
           AND (
                (parent.relrowsecurity AND NOT child.relrowsecurity)
             OR (parent.relrowsecurity AND NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = child.oid))
             OR (NOT has_table_privilege('app_role', parent.oid, 'UPDATE') AND has_table_privilege('app_role', child.oid, 'UPDATE'))
             OR (NOT has_table_privilege('app_role', parent.oid, 'DELETE') AND has_table_privilege('app_role', child.oid, 'DELETE'))
           )
    ) drift;

    IF v_drift IS NOT NULL THEN
        RAISE EXCEPTION E'Partition control drift detected -- these partitions are less protected than their parent:\n  %', v_drift
            USING HINT = 'db-migrations/policyloan/V2 installs trg_partition_controls to prevent exactly this. Check that the event trigger exists and is enabled, then run SELECT public.sync_partition_controls(''<schema>.<parent>'') to repair.';
    END IF;

    RAISE NOTICE 'OK: every partition matches its parent''s row-security and app_role UPDATE/DELETE privileges (% checked).',
        (SELECT count(*) FROM pg_inherits i JOIN pg_class p ON p.oid = i.inhparent WHERE p.relkind = 'p');
END;
$$;
