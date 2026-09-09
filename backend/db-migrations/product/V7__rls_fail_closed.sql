-- db-migrations/product/V7__rls_fail_closed.sql
-- Make this schema's RLS policies fail closed instead of raising.
--
-- Every policy on this platform was written:
--
--     USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)
--
-- and TenantAwareDataSource's javadoc states the assumption under it: "current_setting on a
-- not-yet-set app.current_tenant_id already returns NULL". That holds for a GUC that was NEVER
-- set. It does NOT hold for one SET and then RESET -- which is exactly what TenantAwareDataSource
-- does on every pooled connection borrowed without a tenant. Postgres returns the EMPTY STRING,
-- and ''::uuid raises "invalid input syntax for type uuid" instead of filtering.
--
-- So the policy does not fail closed as designed. It throws. NULLIF makes it mean what the whole
-- design already claims: no tenant, no rows.
--
-- FOUND IN PRODUCTION, not by a test. Eleven milestones of every query carrying a tenant hid it;
-- communication's reminder drain was the first that legitimately does not, and it failed on every
-- pass. No test could have caught it -- Testcontainers connects as the owning superuser, where
-- RLS is unenforced entirely.
--
-- NOT A DATA LEAK, before or after. A raising predicate returns no rows. The danger was always
-- the SHAPE of the failure: "invalid input syntax for type uuid" reads like a data bug, and the
-- tempting fixes are catching the exception or disabling RLS on the table -- and that second one
-- WOULD be a cross-tenant leak.
--
-- WHY A LOOP RATHER THAN 89 CREATE POLICY STATEMENTS. Ninety-one policies across fourteen schemas
-- are byte-identical. Writing them out by hand would trade one mechanical change for eighty-nine
-- chances to mistype a security predicate, and a mistyped USING clause is exactly the leak this
-- migration is not. The match below is EXACT STRING EQUALITY against the known-unsafe expression:
-- a policy that differs in any way -- communication's platform-default OR clause, pg_cron's own
-- policies, anything added later -- is left untouched and reported, never rewritten.
--
-- Partitions are included. Existing child partitions carry their own copies of the parent's
-- policy, so they are rewritten here too; FUTURE partitions are fine automatically, because
-- policyloan/V2's sync_partition_controls copies the parent's expression via pg_get_expr at
-- creation time.
DO $$
DECLARE
    unsafe_expr CONSTANT TEXT :=
        '(tenant_id = (current_setting(''app.current_tenant_id''::text, true))::uuid)';
    safe_expr CONSTANT TEXT :=
        '(tenant_id = (NULLIF(current_setting(''app.current_tenant_id''::text, true), ''''::text))::uuid)';
    rewritten INTEGER := 0;
    r RECORD;
BEGIN
    FOR r IN
        SELECT n.nspname AS schema_name,
               c.relname AS table_name,
               p.polname AS policy_name
          FROM pg_policy p
          JOIN pg_class c ON c.oid = p.polrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'product'
           -- Exact equality. Anything else is somebody's deliberate policy, not this one.
           AND pg_get_expr(p.polqual, p.polrelid) = unsafe_expr
           AND p.polwithcheck IS NULL
    LOOP
        EXECUTE format('DROP POLICY %I ON %I.%I', r.policy_name, r.schema_name, r.table_name);
        EXECUTE format('CREATE POLICY %I ON %I.%I USING (%s)',
            r.policy_name, r.schema_name, r.table_name, safe_expr);
        rewritten := rewritten + 1;
    END LOOP;

    RAISE NOTICE 'product: % RLS policies now fail closed', rewritten;
END;
$$;
