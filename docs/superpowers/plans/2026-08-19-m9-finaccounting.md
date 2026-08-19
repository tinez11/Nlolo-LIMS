# M9 — Financial Accounting (IFRS 17) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `finaccounting`'s double-entry GL posting layer — a chart of accounts, balanced journal entries derived from money-movement events, and the V2 hardening the provisional V1 schema needs — per `docs/superpowers/specs/2026-08-19-m9-finaccounting-design.md`. All C1-governed IFRS 17 measurement is deliberately deferred.

**Architecture:** `finaccounting` is a Spring Modulith module depending on `product::api` and `refdata::api`. Every business fact arrives by event: eight money-movement events each produce ONE `journal_entry` (the aggregate root, carrying the idempotency key) plus exactly TWO balanced `gl_posting` lines (its DR and CR legs), in one transaction. A pure `GlPostingCalculator` maps event facts to a balanced entry; the listeners are thin.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith, Postgres 16 (schema-per-module + RLS + declarative partitioning), Testcontainers, Micrometer/Prometheus.

---

## Global Constraints

Every task's requirements implicitly include this section.

- **`finaccounting`'s `allowedDependencies` MUST stay exactly `{ "product::api", "refdata::api" }`** (verified in `finaccounting/package-info.java`; `docs/02-module-architecture.md:176`). Do **not** add `billing::api`, `claims::api`, `distribution::api`, `reinsurance::api`, `policyloan::api`, or `payment::api` — `ModularityTests` enforces this, and every business fact must arrive by event.
- **`finaccounting/api/package-info.java` is currently MISSING `@NamedInterface("api")`** — verified: the file contains only a bare `package` declaration. Add it (Task 2), exactly as M7 did for `distribution` and M8 for `reinsurance`.
- **Editing three other modules to enrich ONE event each is pre-authorized** (Task 7, spec decision 6). Scope is exactly three publish sites: `distribution/application/PaymentEventListener.java` (`CommissionPaid`), `reinsurance/application/ReinsuranceApiImpl.java` (`RecoveryConfirmed`), `policyloan/application/PolicyLoanApiImpl.java` (`LoanDisbursed`). Nothing else in those modules.
- **NO IFRS 17 measurement.** `csm_ledger`, `lrc_ledger`, `lic_ledger` stay empty. Do not write a row to them, do not add a repository for them, do not compute CSM/LRC/LIC. V2 marks them `C1-BLOCKED`. This is a hard scope boundary, not a preference — see the spec's §1 for why (audited financial statements; Actuarial owns the decision).
- **Never credit an income (`4xxx`) account.** Premium income is earned as coverage is provided, which is LRC release, which is C1-blocked. M9 accumulates `2200 Unearned Premium` and recognises zero earned premium, deliberately.
- **Build commands.** Always on the host, in the FOREGROUND, never inside Docker (it breaks Testcontainers networking):
  ```bash
  export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
  ./mvnw -B -o test -Dtest=SomeSpecificTest      # the normal case
  ./mvnw -B -o test                              # only when the rule below says so
  ```
- **Run the NARROWEST test set that could detect a regression from your change.** The full suite is 563 tests in ~10 minutes, mostly container and Spring-context setup unrelated to your change.

  **Run the FULL suite only when your task does at least one of these:**
  1. changes production code **outside** `finaccounting` (Task 7 touches `distribution`, `reinsurance`, `policyloan`);
  2. changes a **shared test class or utility** (Task 9 edits `AppRolePrivilegesIntegrationTest` and `RowLevelSecurityIntegrationTest`, which every module's correctness leans on);
  3. changes **shared config** — `pom.xml`, `application.yml`, `SecurityConfig`;
  4. changes a migration that some existing test's own migration list applies. **For `finaccounting` specifically, no such test exists yet** — verified with `grep -rn "finaccounting" src/test/java`, which returns only four passing *comments* and one ArchUnit string list; not one test applies `finaccounting/V1`. That is precisely why V1's missing `GRANT` survived unnoticed since it was written. So Tasks 1's migration needs no Maven run at all (see Task 1 Step 5), and the schema comes under automated test for the first time in Tasks 5 and 9. Once Task 9 lands, this trigger becomes live for any later finaccounting migration change;
  5. is the final verification task (Task 10).

  **Never report coverage you did not execute.** If you ran a subset, say so plainly and say why. If a targeted run surfaces anything unexpected, escalate to the full suite immediately.
- **Baseline before M9: 563 tests, 0 failures, 0 errors.** Quote the count from whatever you actually ran and label it (`full suite` vs `-Dtest=X`).
- **Never edit an already-applied migration.** `finaccounting/V1` is immutable regardless of whether any test yet applies it (none did before this milestone — see the pre-flight scan finding above). Add `V2`.
- **Every real-Postgres `finaccounting` test needs `policyloan/V1` and `policyloan/V2` in its migration list, even when nothing in the test touches a loan.** `gl_posting` is `PARTITION BY RANGE (created_at)` with two hand-written partitions from V1; Postgres does not cascade RLS, policies, or GRANT/REVOKE from a partitioned parent onto its partitions, and `policyloan/V2` installs the one mechanism on this platform (`trg_partition_controls`, a database-wide event trigger) that mirrors those controls onto them — its own code comment names `finaccounting.gl_posting` as a table it protects. Verified empirically during Task 1's review: omitting `policyloan/V2` leaves `gl_posting`'s partitions with RLS disabled and `UPDATE`/`DELETE` still granted to `app_role`, reachable by any session naming a partition directly; applying migrations in the platform's real order (`scripts/migrate.sh`, where `policyloan` precedes `finaccounting`) closes it completely. This governs Tasks 5, 6, 7, and 9's real-Postgres tests, each noted individually where it applies.
- **Cross-module references are opaque columns, never FKs** (`docs/06-database-schema.md:29`). `journal_entry.policy_number` and `gl_posting.policy_number` have no FK. Intra-module FKs (`gl_posting.account_code` → `chart_of_account`) are fine.
- **Money on the wire is a decimal STRING, never a float** (`docs/06-database-schema.md:32`). Internally `BigDecimal`.
- **Event payload money shape is always** `Map.of("amount", <BigDecimal>.toPlainString(), "currencyCode", <String>)`. Use `toPlainString()`, never `toString()`.
- **An `AFTER_COMMIT` `@TransactionalEventListener` MUST use a `PROPAGATION_REQUIRES_NEW` `TransactionTemplate`.** A plain `@Transactional` (REQUIRED) call from an AFTER_COMMIT callback silently joins the already-committed producer transaction and never commits — empirically confirmed on this project. Copy `reinsurance/application/PolicyEventListener.java`.
- **Bean names must be explicit.** Several modules already declare `PolicyEventListener`/`PaymentEventListener`/`ClaimEventListener`. Use `@Component("finaccounting<X>EventListener")` or a context-startup bean-name collision fails the whole suite. **Also check every NEW class's simple name against the whole codebase before creating it** — M8 lost a task to `PolicyProjectionRepository` colliding with `distribution`'s identically-named repository (Spring Data derives bean names from the simple class name), and a second layer to a JPA `@Entity` name defaulting the same way. Run `find src/main/java -name "<NewClass>.java"` for each new class first.
- **A unique index on a partitioned table must include every partition-key column** — empirically confirmed against `postgres:16` while writing this plan. `gl_posting` is `PARTITION BY RANGE (created_at)`, so idempotency lives on the non-partitioned `journal_entry` table instead. Do not attempt a unique index on `gl_posting`.
- **Column widths must be checked against their CHECK vocabularies in the same migration.** M7 shipped a CHECK admitting a 16-character value into a `VARCHAR(15)`, making a whole payout path unwritable while every test stayed green.
- **Contract tests pair `openApi().isValid(...)` with `SpecTypeConformance.matchesDeclaredTypes(specPath, schemaName)`.** `isValid` does **not** enforce primitive JSON types (measured).
- **Every nested-resource path must verify its ids belong together.** M7's final review found a same-tenant IDOR where a nested id was trusted. M9's read endpoints are single-id, but apply the rule to anything added.

---

## File Structure

**New — `finaccounting` module:**
- `db-migrations/finaccounting/V2__grants_rls_chart_of_accounts_journal_entry_and_posting_columns.sql`
- `finaccounting/api/` — `FinaccountingApi`, `GlPostingView`, `JournalEntryView`, `ChartOfAccountView`, `AccountType`, `PostingDirection`, `JournalEntryNotFoundException`, `FinaccountingValidationException`, `package-info.java` (add `@NamedInterface`)
- `finaccounting/domain/` — `ChartOfAccount`, `JournalEntry`, `GlPosting`, `GlPostingId`, `GlPostingCalculator`, `PostingRule`
- `finaccounting/infrastructure/` — 3 repositories, `GlPostingController`, `ChartOfAccountController`, DTOs, `FinaccountingExceptionHandler`
- `finaccounting/application/` — `FinaccountingApiImpl`, and 5 listeners (`BillingEventListener`, `ClaimsEventListener`, `DistributionEventListener`, `ReinsuranceEventListener`, `PolicyLoanEventListener`)
- `api/openapi/openapi-finaccounting.yaml`
- Tests — `GlPostingCalculatorTest`, `JournalEntryBalanceTest`, `FinaccountingApiIntegrationTest`, `PremiumPostingEndToEndTest`, `ClaimAndCommissionPostingEndToEndTest`, `ReinsuranceAndLoanPostingEndToEndTest`, `FinaccountingContractTest`, `FinaccountingSpecParsesTest`

**Modified — other modules (each minimal and justified):**
- `distribution/application/PaymentEventListener.java`, `reinsurance/application/ReinsuranceApiImpl.java`, `policyloan/application/PolicyLoanApiImpl.java` — one event enrichment each
- `api/asyncapi-events.yaml`, `docs/05-event-catalog.md`, `docs/06-database-schema.md`
- `src/test/java/.../AppRolePrivilegesIntegrationTest.java`, `RowLevelSecurityIntegrationTest.java`

---

### Task 1: `finaccounting/V2` — grants, RLS, chart of accounts, journal_entry, posting columns

**Files:**
- Create: `db-migrations/finaccounting/V2__grants_rls_chart_of_accounts_journal_entry_and_posting_columns.sql`

**Interfaces:**
- Produces: the schema every later task maps entities onto — `chart_of_account` (new, seeded), `journal_entry` (new, carrying `ux_journal_entry_once`), `gl_posting` (+ `account_code`, `direction`, `policy_number`, `journal_entry_id`, `source_event`, `source_ref`; `group_id` now nullable), and RLS/grants on all seven tables in the schema.

- [ ] **Step 1: Read V1 first**

Read `db-migrations/finaccounting/V1__create_finaccounting_schema.sql` in full before writing anything. Note especially: its own header says the schema is PROVISIONAL and "do NOT treat column shapes as final"; `gl_posting` is `PARTITION BY RANGE (created_at)` with `PRIMARY KEY (posting_id, created_at)`; and it ends with `REVOKE UPDATE, DELETE ON finaccounting.gl_posting FROM app_role` **without any prior `GRANT`**.

- [ ] **Step 2: Write the migration**

```sql
-- Module: finaccounting V2 -- M9 Task 1.
--
-- V1 shipped as an explicitly PROVISIONAL skeleton gated on C1 (actuarial
-- cohort/grouping rules), and carries the recurring V1 defect set this project has
-- now caught in six consecutive modules: no RLS anywhere, no GRANTs at all, no
-- optimistic locking, no money guards.
--
-- Most consequentially: V1 ends with
--   REVOKE UPDATE, DELETE ON finaccounting.gl_posting FROM app_role;
-- with no prior GRANT. app_role therefore has NO privileges on the ledger at all --
-- the append-only *intent* was expressed, the ability to append never was. The
-- platform's append-only ledger has never once been writable, nor verified.
--
-- SCOPE BOUNDARY, load-bearing: this migration does NOT touch csm_ledger,
-- lrc_ledger or lic_ledger beyond hardening them. Populating them is IFRS 17
-- measurement, which is blocked on C1 (Actuarial) and lands in audited financial
-- statements. See docs/superpowers/specs/2026-08-19-m9-finaccounting-design.md §1.
-- =============================================================================
-- 1. Grants. V1 grants app_role nothing anywhere in the schema.
-- =============================================================================
GRANT USAGE ON SCHEMA finaccounting TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA finaccounting TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA finaccounting GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;

-- gl_posting is an append-only ledger (docs/06-database-schema.md:33). The blanket
-- grant above deliberately runs FIRST so this REVOKE has something to revoke --
-- V1's REVOKE-without-GRANT is exactly why app_role could not write the table.
-- Order matters: grant, then narrow.
REVOKE UPDATE, DELETE ON finaccounting.gl_posting FROM app_role;

-- =============================================================================
-- 2. RLS on all five existing tables. V1 enabled it on NONE of them, while every
--    one carries tenant_id NOT NULL -- so app_role could read every tenant's
--    ledger, balances and contract groups.
-- =============================================================================
ALTER TABLE finaccounting.group_of_contracts ENABLE ROW LEVEL SECURITY;
CREATE POLICY group_of_contracts_tenant_isolation ON finaccounting.group_of_contracts
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE finaccounting.csm_ledger ENABLE ROW LEVEL SECURITY;
CREATE POLICY csm_ledger_tenant_isolation ON finaccounting.csm_ledger
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE finaccounting.lrc_ledger ENABLE ROW LEVEL SECURITY;
CREATE POLICY lrc_ledger_tenant_isolation ON finaccounting.lrc_ledger
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE finaccounting.lic_ledger ENABLE ROW LEVEL SECURITY;
CREATE POLICY lic_ledger_tenant_isolation ON finaccounting.lic_ledger
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE finaccounting.gl_posting ENABLE ROW LEVEL SECURITY;
CREATE POLICY gl_posting_tenant_isolation ON finaccounting.gl_posting
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- =============================================================================
-- 3. C1 marker on the three measurement ledgers. These tables are real and
--    hardened, but NOTHING in M9 writes them: populating them is CSM roll-forward
--    / LRC / LIC computation, which requires the GMM-vs-PAA and cohort decisions
--    that Actuarial owns (docs/02-module-architecture.md:197). A wrong roll-forward
--    is a financial misstatement, not a fixable bug, so M9 declines to guess.
-- =============================================================================
COMMENT ON TABLE finaccounting.csm_ledger IS
    'C1-BLOCKED -- DO NOT POPULATE. CSM roll-forward requires the actuarial GMM/PAA and cohort decision. M9 builds GL posting only.';
COMMENT ON TABLE finaccounting.lrc_ledger IS
    'C1-BLOCKED -- DO NOT POPULATE. LRC release is how premium income is earned; M9 accumulates unearned premium (2200) instead and recognises no income.';
COMMENT ON TABLE finaccounting.lic_ledger IS
    'C1-BLOCKED -- DO NOT POPULATE. Requires the actuarial measurement decision.';

-- =============================================================================
-- 4. Missing tenant indexes and audit columns. docs/06-database-schema.md:25
--    requires tenant_id indexed on every tenant-scoped table.
-- =============================================================================
CREATE INDEX idx_csm_ledger_tenant ON finaccounting.csm_ledger (tenant_id);
CREATE INDEX idx_lrc_ledger_tenant ON finaccounting.lrc_ledger (tenant_id);
CREATE INDEX idx_lic_ledger_tenant ON finaccounting.lic_ledger (tenant_id);
CREATE INDEX idx_gl_posting_tenant ON finaccounting.gl_posting (tenant_id);

ALTER TABLE finaccounting.lrc_ledger ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE finaccounting.lic_ledger ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- group_of_contracts is the one mutable aggregate root here (status changes as a
-- group opens/closes). The ledgers are append-only or C1-blocked, so no version.
ALTER TABLE finaccounting.group_of_contracts ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE finaccounting.group_of_contracts ADD COLUMN created_by VARCHAR(100);
ALTER TABLE finaccounting.group_of_contracts ADD COLUMN updated_at TIMESTAMPTZ;
ALTER TABLE finaccounting.group_of_contracts ADD COLUMN updated_by VARCHAR(100);

-- =============================================================================
-- 5. chart_of_account -- finaccounting's own core reference data.
--
--    EVERY ACCOUNT BELOW IS AN INVENTED PLACEHOLDER pending Finance sign-off. No
--    document on this platform specifies account codes or their debit/credit
--    treatment (grepped all of db-migrations/refdata: nothing). Codes follow the
--    conventional five-block scheme (1xxx ASSET, 2xxx LIABILITY, 3xxx EQUITY,
--    4xxx INCOME, 5xxx EXPENSE) so Finance's real chart is more likely a
--    re-mapping of familiar blocks than a redesign.
--
--    Owned here rather than in refdata because a chart of accounts is STRUCTURED
--    reference data (type, normal balance) that refdata's flat key/value
--    reference_code_set models poorly.
-- =============================================================================
CREATE TABLE finaccounting.chart_of_account (
    tenant_id           UUID NOT NULL,
    account_code         VARCHAR(20) NOT NULL,
    name                  VARCHAR(200) NOT NULL,
    account_type           VARCHAR(20) NOT NULL
        CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
    normal_balance          VARCHAR(2) NOT NULL CHECK (normal_balance IN ('DR','CR')),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                VARCHAR(100),
    PRIMARY KEY (tenant_id, account_code)
);
CREATE INDEX idx_chart_of_account_tenant ON finaccounting.chart_of_account (tenant_id);

ALTER TABLE finaccounting.chart_of_account ENABLE ROW LEVEL SECURITY;
CREATE POLICY chart_of_account_tenant_isolation ON finaccounting.chart_of_account
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON finaccounting.chart_of_account TO app_role;

-- =============================================================================
-- 6. journal_entry -- the aggregate root, and WHERE IDEMPOTENCY LIVES.
--
--    Why not a unique index on gl_posting directly: gl_posting is
--    PARTITION BY RANGE (created_at), and Postgres requires every unique index on
--    a partitioned table to include all partition-key columns --
--      ERROR: unique constraint on partitioned table must include all
--             partitioning columns
--    (verified empirically against postgres:16 while planning M9). Omitting
--    created_at is rejected outright; INCLUDING it is worse than the error,
--    because it applies cleanly and then silently permits a double-post -- a
--    redelivered event arriving at a different timestamp satisfies the constraint.
--    A duplicate journal entry is exactly what distribution.CommissionPaid's and
--    reinsurance.RecoveryConfirmed's own code comments warn about.
--
--    This is also the better model: in double-entry bookkeeping the ENTRY is the
--    transaction and the postings are its legs.
-- =============================================================================
CREATE TABLE finaccounting.journal_entry (
    journal_entry_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    source_event           VARCHAR(60) NOT NULL,
    source_ref              VARCHAR(100) NOT NULL,
    period                   VARCHAR(7) NOT NULL,
    policy_number             VARCHAR(20),      -- opaque ref into policy; never an FK
    posted_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                  VARCHAR(100)
);
CREATE UNIQUE INDEX ux_journal_entry_once
    ON finaccounting.journal_entry (tenant_id, source_event, source_ref);
CREATE INDEX idx_journal_entry_tenant ON finaccounting.journal_entry (tenant_id);
CREATE INDEX idx_journal_entry_period ON finaccounting.journal_entry (tenant_id, period);
CREATE INDEX idx_journal_entry_policy ON finaccounting.journal_entry (tenant_id, policy_number);

ALTER TABLE finaccounting.journal_entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY journal_entry_tenant_isolation ON finaccounting.journal_entry
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- Append-only on the same terms as gl_posting: a posted entry is never amended.
GRANT SELECT, INSERT ON finaccounting.journal_entry TO app_role;
REVOKE UPDATE, DELETE ON finaccounting.journal_entry FROM app_role;

-- =============================================================================
-- 7. gl_posting becomes a real double-entry line.
--
--    V1's columns are (posting_id, tenant_id, group_id, period, amount, currency,
--    posting_type, created_at) -- NO account code and NO debit/credit indicator.
--    That cannot represent a double-entry posting at all.
--
--    group_id becomes NULLABLE because a "group of insurance contracts" IS the
--    C1-governed IFRS 17 unit of account. Keeping it NOT NULL would force
--    inventing a group, and group_of_contracts.measurement_model's CHECK would
--    then force inventing the GMM-vs-PAA answer too -- smuggling in the exact
--    decision this milestone defers.
-- =============================================================================
ALTER TABLE finaccounting.gl_posting ALTER COLUMN group_id DROP NOT NULL;

ALTER TABLE finaccounting.gl_posting ADD COLUMN journal_entry_id UUID;
ALTER TABLE finaccounting.gl_posting ADD COLUMN account_code VARCHAR(20);
ALTER TABLE finaccounting.gl_posting ADD COLUMN direction VARCHAR(2);
ALTER TABLE finaccounting.gl_posting ADD COLUMN policy_number VARCHAR(20);
ALTER TABLE finaccounting.gl_posting ADD COLUMN source_event VARCHAR(60);
ALTER TABLE finaccounting.gl_posting ADD COLUMN source_ref VARCHAR(100);

-- posting_type MUST be widened, and this is not cosmetic. V1 declared it VARCHAR(30);
-- GlPosting populates it with the source event name (V1 made it NOT NULL with no default and
-- V1 is immutable, so it cannot simply be left unset), and 'billing.PremiumInvoiceGenerated'
-- is 31 characters -- MEASURED, not estimated. Left at 30, the single most important posting
-- path on the platform would fail at runtime with
--   value too long for type character varying(30)
-- while every pure unit test stayed green, because none of them touch the database.
--
-- This is exactly the defect class documented at docs/06-database-schema.md:30's sub-bullet
-- (M7's VARCHAR(15) column admitting a 16-character CHECK value, which made a whole payout
-- path unwritable). It is being fixed here rather than discovered in Task 6.
-- 60 matches source_event's width, so the two columns cannot drift apart again.
ALTER TABLE finaccounting.gl_posting ALTER COLUMN posting_type TYPE VARCHAR(60);

-- Added nullable above then constrained here: no rows exist (nothing has ever
-- written this table -- app_role could not), but a bare NOT NULL ADD COLUMN on a
-- populated table would fail, and succeeding only because the table happens to be
-- empty is the kind of thing that breaks on the first deployment that has data.
UPDATE finaccounting.gl_posting SET journal_entry_id = gen_random_uuid() WHERE journal_entry_id IS NULL;
UPDATE finaccounting.gl_posting SET account_code = '1000' WHERE account_code IS NULL;
UPDATE finaccounting.gl_posting SET direction = 'DR' WHERE direction IS NULL;
UPDATE finaccounting.gl_posting SET source_event = 'legacy' WHERE source_event IS NULL;
UPDATE finaccounting.gl_posting SET source_ref = 'legacy' WHERE source_ref IS NULL;

ALTER TABLE finaccounting.gl_posting ALTER COLUMN journal_entry_id SET NOT NULL;
ALTER TABLE finaccounting.gl_posting ALTER COLUMN account_code SET NOT NULL;
ALTER TABLE finaccounting.gl_posting ALTER COLUMN direction SET NOT NULL;
ALTER TABLE finaccounting.gl_posting ALTER COLUMN source_event SET NOT NULL;
ALTER TABLE finaccounting.gl_posting ALTER COLUMN source_ref SET NOT NULL;

-- direction VARCHAR(2) against a longest value of 'DR'/'CR' (2). Deliberately
-- verified rather than assumed: M7 shipped a CHECK admitting a 16-character value
-- into a VARCHAR(15), making a whole payout path unwritable while tests stayed green.
ALTER TABLE finaccounting.gl_posting
    ADD CONSTRAINT gl_posting_direction_check CHECK (direction IN ('DR','CR'));

-- amount is a POSITIVE MAGNITUDE, strictly. The direction column carries the sign,
-- so a reversal is a NEW entry with the two directions swapped, never a negative
-- amount on the original. Signed amounts would also make the "every entry
-- balances" assertion ambiguous about whether a negative DR is really a CR.
ALTER TABLE finaccounting.gl_posting
    ADD CONSTRAINT gl_posting_amount_positive CHECK (amount > 0);

CREATE INDEX idx_gl_posting_entry ON finaccounting.gl_posting (journal_entry_id);
CREATE INDEX idx_gl_posting_account ON finaccounting.gl_posting (tenant_id, account_code, period);

-- =============================================================================
-- 8. Seed the chart of accounts. PLACEHOLDERS -- Finance sign-off required.
--
--    Seeded for the ONE well-known dev/test tenant only; a real deployment seeds
--    per tenant during onboarding. No 4xxx INCOME account is seeded, deliberately:
--    M9 never credits income (premium is earned via LRC release, which is
--    C1-blocked), and seeding an account nothing posts to would imply coverage
--    this milestone does not have.
-- =============================================================================
-- NOTE for the implementer: this INSERT is intentionally left OUT of the
-- migration. Seeding a hardcoded tenant_id into a shared migration would create a
-- row every tenant's RLS hides and nobody uses. Instead, ChartOfAccountSeeder
-- (Task 3) seeds these nine accounts per tenant on first use, and the tests seed
-- explicitly. The nine accounts are:
--   1000 Cash / Mobile Money        ASSET      DR
--   1200 Premium Receivable         ASSET      DR
--   1300 Reinsurance Recoverable    ASSET      DR
--   1400 Policy Loan Receivable     ASSET      DR
--   2200 Unearned Premium           LIABILITY  CR
--   2300 Reinsurance Payable        LIABILITY  CR
--   5000 Claims Expense             EXPENSE    DR
--   5100 Commission Expense         EXPENSE    DR
--   5200 Reinsurance Ceded Premium  EXPENSE    DR
```

- [ ] **Step 3: Apply against a disposable Postgres and verify — do NOT verify by inspection**

```bash
docker rm -f m9verify >/dev/null 2>&1
docker run -d --name m9verify -e POSTGRES_PASSWORD=pw postgres:16 >/dev/null
until docker exec m9verify pg_isready -U postgres -q; do sleep 1; done
docker exec m9verify psql -U postgres -q -c "CREATE ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'p';"
export MSYS_NO_PATHCONV=1
for f in db-migrations/finaccounting/V1__create_finaccounting_schema.sql \
         db-migrations/finaccounting/V2__grants_rls_chart_of_accounts_journal_entry_and_posting_columns.sql; do
  docker cp "$f" m9verify:/tmp/m.sql >/dev/null
  docker exec m9verify psql -U postgres -v ON_ERROR_STOP=1 -f /tmp/m.sql -q || echo "FAILED $f"
done
docker exec m9verify psql -U postgres -c "
SELECT c.relname, c.relrowsecurity AS rls,
       EXISTS(SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid) AS has_policy,
       has_table_privilege('app_role', c.oid, 'SELECT') AS sel,
       has_table_privilege('app_role', c.oid, 'INSERT') AS ins,
       has_table_privilege('app_role', c.oid, 'UPDATE') AS upd
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'finaccounting' AND c.relkind = 'p' OR (n.nspname='finaccounting' AND c.relkind='r')
ORDER BY c.relname;"
```

Expected: `chart_of_account`, `csm_ledger`, `gl_posting`, `group_of_contracts`, `journal_entry`, `lic_ledger`, `lrc_ledger` all `rls=t has_policy=t sel=t ins=t`. **`gl_posting` and `journal_entry` must show `upd=f`** (append-only); the others `upd=t`. Report the real table.

- [ ] **Step 4: Prove the append-only revoke and the idempotency index actually work**

Each must behave as stated. Paste real output.

```bash
# journal_entry: app_role can insert, cannot update
docker exec m9verify psql -U postgres -q -c "SET ROLE app_role; SET app.current_tenant_id = '11111111-1111-1111-1111-111111111111';
INSERT INTO finaccounting.journal_entry (tenant_id, source_event, source_ref, period)
VALUES ('11111111-1111-1111-1111-111111111111','billing.PremiumCollected','inv-1','2026-08');"
echo "--- duplicate must FAIL on ux_journal_entry_once ---"
docker exec m9verify psql -U postgres -c "SET ROLE app_role; SET app.current_tenant_id = '11111111-1111-1111-1111-111111111111';
INSERT INTO finaccounting.journal_entry (tenant_id, source_event, source_ref, period)
VALUES ('11111111-1111-1111-1111-111111111111','billing.PremiumCollected','inv-1','2026-08');"
echo "--- UPDATE must FAIL (append-only) ---"
docker exec m9verify psql -U postgres -c "SET ROLE app_role; SET app.current_tenant_id = '11111111-1111-1111-1111-111111111111';
UPDATE finaccounting.journal_entry SET period = '2026-09';"
echo "--- gl_posting: negative amount must FAIL ---"
docker exec m9verify psql -U postgres -c "SET ROLE app_role; SET app.current_tenant_id = '11111111-1111-1111-1111-111111111111';
INSERT INTO finaccounting.gl_posting (tenant_id, journal_entry_id, period, amount, account_code, direction, source_event, source_ref)
VALUES ('11111111-1111-1111-1111-111111111111', gen_random_uuid(), '2026-08', -5.00, '1000', 'DR', 'x', 'y');"
echo "--- gl_posting: bad direction must FAIL ---"
docker exec m9verify psql -U postgres -c "SET ROLE app_role; SET app.current_tenant_id = '11111111-1111-1111-1111-111111111111';
INSERT INTO finaccounting.gl_posting (tenant_id, journal_entry_id, period, amount, account_code, direction, source_event, source_ref)
VALUES ('11111111-1111-1111-1111-111111111111', gen_random_uuid(), '2026-08', 5.00, '1000', 'XX', 'x', 'y');"
```

Expected in order: insert OK; `duplicate key value violates unique constraint "ux_journal_entry_once"`; `permission denied for table journal_entry`; `violates check constraint "gl_posting_amount_positive"`; `violates check constraint "gl_posting_direction_check"`.

- [ ] **Step 5: Clean up and commit**

```bash
docker rm -f m9verify
git add db-migrations/finaccounting/
git commit -m "feat: finaccounting V2 -- grants, RLS, chart of accounts, journal_entry and real double-entry posting columns"
```

**No Maven run for this task, and that is a considered choice rather than a shortcut.** This task changes no Java and no shared config, and — verified with `grep -rn "finaccounting" src/test/java` — **not one existing test applies `finaccounting/V1`**, so there is no test whose behaviour could change. Running 563 tests for ten minutes to prove that would be theatre. Steps 3 and 4's real Postgres verification IS this task's verification, which is why they are non-negotiable and why their output must be pasted rather than summarised.

Report this plainly: state that no Maven run was performed, why, and that the schema comes under automated test for the first time in Tasks 5 and 9.

---

### Task 2: The `finaccounting` API surface

**Files:**
- Create: `finaccounting/api/AccountType.java`, `PostingDirection.java`, `ChartOfAccountView.java`, `GlPostingView.java`, `JournalEntryView.java`, `JournalEntryNotFoundException.java`, `FinaccountingValidationException.java`, `FinaccountingApi.java`
- Modify: `finaccounting/api/package-info.java`

**Interfaces:**
- Produces: every type Tasks 3-9 depend on. Exact signatures below.

- [ ] **Step 1: Add the missing named interface**

`finaccounting/api/package-info.java` currently contains only `package tz.co.nlolo.lifeplatform.finaccounting.api;`. Replace with:

```java
@org.springframework.modulith.NamedInterface("api")
package tz.co.nlolo.lifeplatform.finaccounting.api;
```

Same gap `distribution` had entering M7 and `reinsurance` entering M8.

- [ ] **Step 2: Write the two enums**

`AccountType.java`:
```java
package tz.co.nlolo.lifeplatform.finaccounting.api;

/** Matches {@code chart_of_account.account_type}'s CHECK (finaccounting/V2 section 5). */
public enum AccountType { ASSET, LIABILITY, EQUITY, INCOME, EXPENSE }
```

`PostingDirection.java`:
```java
package tz.co.nlolo.lifeplatform.finaccounting.api;

/**
 * Matches {@code gl_posting.direction}'s CHECK (finaccounting/V2 section 7).
 *
 * <p>The direction carries the SIGN of a posting: {@code gl_posting.amount} is always a positive
 * magnitude (enforced by {@code gl_posting_amount_positive}), and a reversal is a new journal
 * entry with the two directions swapped -- never a negative amount on the original.
 */
public enum PostingDirection { DR, CR }
```

- [ ] **Step 3: Write the three views**

```java
package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.math.BigDecimal;
import java.util.UUID;

/** Read view of one leg of a journal entry ({@code finaccounting.gl_posting}). */
public record GlPostingView(UUID postingId, UUID journalEntryId, String accountCode,
                             PostingDirection direction, BigDecimal amount, String currency,
                             String period, String policyNumber, String sourceEvent, String sourceRef) {}
```

```java
package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read view of a journal entry and its legs. An entry always has exactly two legs in M9, and
 * their DR and CR totals are always equal -- see {@code JournalEntry}'s own invariant. */
public record JournalEntryView(UUID journalEntryId, String sourceEvent, String sourceRef,
                                String period, String policyNumber, Instant postedAt,
                                List<GlPostingView> postings) {}
```

```java
package tz.co.nlolo.lifeplatform.finaccounting.api;

/** Read view of {@code finaccounting.chart_of_account}. Every account on this platform is
 * currently a PLACEHOLDER pending Finance sign-off. */
public record ChartOfAccountView(String accountCode, String name, AccountType accountType,
                                  PostingDirection normalBalance) {}
```

- [ ] **Step 4: Write the two exceptions**

Each following `reinsurance/api`'s exact shape (single `String message` constructor):

```java
package tz.co.nlolo.lifeplatform.finaccounting.api;

/** 404 at the REST boundary (FinaccountingExceptionHandler, Task 8). */
public class JournalEntryNotFoundException extends RuntimeException {
    public JournalEntryNotFoundException(String message) { super(message); }
}
```

Repeat verbatim for `FinaccountingValidationException` (422), changing only the class name and the javadoc's status code.

- [ ] **Step 5: Write the API interface**

```java
package tz.co.nlolo.lifeplatform.finaccounting.api;

import java.util.List;
import java.util.UUID;

/**
 * The `finaccounting` module's public surface: READ ONLY.
 *
 * <p>There is deliberately no write method. Every posting is derived from a domain event by this
 * module's own listeners -- nothing hand-enters a journal entry, which is what makes the ledger
 * trustworthy. A correction is a future reversal entry (deferred, see the design spec's §9), not
 * an edit.
 *
 * <p>IFRS 17 measurement (CSM roll-forward, LRC, LIC) is absent by design: it is blocked on C1
 * (Actuarial). M9 is the GL posting layer only.
 */
public interface FinaccountingApi {

    List<JournalEntryView> listJournalEntries(String period, String policyNumber);
    JournalEntryView getJournalEntry(UUID journalEntryId);
    List<ChartOfAccountView> listChartOfAccounts();
}
```

- [ ] **Step 6: Compile and commit**

```bash
export JAVA_HOME="/c/Users/USER/.vscode/extensions/redhat.java-1.55.0-win32-x64/jre/21.0.11-win32-x86_64"
./mvnw -B -o -q compile
git add src/main/java/tz/co/nlolo/lifeplatform/finaccounting/api/
git commit -m "feat: finaccounting API surface -- views, enums, exceptions and the named interface"
```

Expected: `compile` exits 0. No tests — this task adds only types.

---

### Task 3: Domain entities, the posting rule map, and repositories

**Files:**
- Create: `finaccounting/domain/ChartOfAccount.java`, `ChartOfAccountId.java`, `JournalEntry.java`, `GlPosting.java`, `GlPostingId.java`, `PostingRule.java`
- Create: `finaccounting/infrastructure/ChartOfAccountRepository.java`, `JournalEntryRepository.java`, `GlPostingRepository.java`, `ChartOfAccountSeeder.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/finaccounting/JournalEntryBalanceTest.java`

**Interfaces:**
- Consumes: `AccountType`, `PostingDirection` (Task 2).
- Produces: `JournalEntry` (with `addLeg`, `isBalanced`, `getLegs`, and the nested `JournalEntry.Leg` record), `GlPosting`, `ChartOfAccount`, `PostingRule` (the event→accounts map), and three repositories.

- [ ] **Step 1: Check every new class name for a collision FIRST**

M8 lost a task to exactly this. Before creating any file:

```bash
for c in ChartOfAccount ChartOfAccountId JournalEntry GlPosting GlPostingId PostingRule \
         ChartOfAccountRepository JournalEntryRepository GlPostingRepository ChartOfAccountSeeder; do
  hits=$(find src/main/java -name "$c.java" | wc -l)
  echo "$c: $hits existing"
done
```

Expected: all `0`. If any is non-zero, STOP and report — Spring Data derives repository bean names, and JPA derives entity names, from the simple class name, so a collision breaks every Spring context in the suite.

- [ ] **Step 2: Write the failing balance test**

`JournalEntryBalanceTest.java` — pure unit test, no Spring, no container. This is the ledger's core invariant:

```java
package tz.co.nlolo.lifeplatform.finaccounting;

import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JournalEntryBalanceTest {

    private static JournalEntry newEntry() {
        return new JournalEntry(UUID.randomUUID(), "billing.PremiumCollected", "inv-1",
            "2026-08", "POL-0001", "system:test");
    }

    @Test
    void aFreshEntryHasNoLegsAndIsNotBalanced() {
        JournalEntry entry = newEntry();
        assertThat(entry.getLegs()).isEmpty();
        // An empty entry is trivially equal on both sides, but it is not a valid entry --
        // isBalanced must require at least one leg on each side, not merely 0 == 0.
        assertThat(entry.isBalanced()).isFalse();
    }

    @Test
    void aMatchedDebitAndCreditBalances() {
        JournalEntry entry = newEntry();
        entry.addLeg("1000", PostingDirection.DR, new BigDecimal("15000.00"), "TZS");
        entry.addLeg("1200", PostingDirection.CR, new BigDecimal("15000.00"), "TZS");
        assertThat(entry.isBalanced()).isTrue();
        assertThat(entry.getLegs()).hasSize(2);
    }

    @Test
    void mismatchedAmountsDoNotBalance() {
        JournalEntry entry = newEntry();
        entry.addLeg("1000", PostingDirection.DR, new BigDecimal("15000.00"), "TZS");
        entry.addLeg("1200", PostingDirection.CR, new BigDecimal("14999.99"), "TZS");
        assertThat(entry.isBalanced()).isFalse();
    }

    @Test
    void twoLegsOnTheSameSideDoNotBalance() {
        JournalEntry entry = newEntry();
        entry.addLeg("1000", PostingDirection.DR, new BigDecimal("100.00"), "TZS");
        entry.addLeg("1200", PostingDirection.DR, new BigDecimal("100.00"), "TZS");
        assertThat(entry.isBalanced()).isFalse();
    }

    /** Both legs of one entry must share a currency. No FX table exists anywhere on this
     * platform, so a mixed-currency entry could never be meaningfully balanced. */
    @Test
    void aMixedCurrencyEntryIsRejected() {
        JournalEntry entry = newEntry();
        entry.addLeg("1000", PostingDirection.DR, new BigDecimal("100.00"), "TZS");
        assertThrows(IllegalArgumentException.class,
            () -> entry.addLeg("1200", PostingDirection.CR, new BigDecimal("100.00"), "USD"));
    }

    /** amount is a positive magnitude; direction carries the sign (V2's
     * gl_posting_amount_positive CHECK). A negative leg must never be constructible. */
    @Test
    void aNonPositiveLegIsRejected() {
        JournalEntry entry = newEntry();
        assertThrows(IllegalArgumentException.class,
            () -> entry.addLeg("1000", PostingDirection.DR, new BigDecimal("-1.00"), "TZS"));
        assertThrows(IllegalArgumentException.class,
            () -> entry.addLeg("1000", PostingDirection.DR, BigDecimal.ZERO, "TZS"));
    }
}
```

- [ ] **Step 3: Run it and confirm it fails**

```bash
./mvnw -B -o test -Dtest=JournalEntryBalanceTest
```
Expected: FAIL to compile — `JournalEntry` does not exist yet.

- [ ] **Step 4: Write `ChartOfAccountId` and `ChartOfAccount`**

`ChartOfAccountId.java` — copy the `@IdClass` shape from `reinsurance/domain/PolicyProjectionId.java` (public no-arg ctor, all-args ctor, `equals`/`hashCode` over every field), with fields `tenantId` (UUID) then `accountCode` (String), matching V2's `PRIMARY KEY (tenant_id, account_code)`.

```java
package tz.co.nlolo.lifeplatform.finaccounting.domain;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Maps {@code finaccounting.chart_of_account} (V2 section 5). Every account is a PLACEHOLDER
 * pending Finance sign-off -- no document on this platform specifies account codes. */
@Entity
@Table(name = "chart_of_account", schema = "finaccounting")
@IdClass(ChartOfAccountId.class)
public class ChartOfAccount {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "account_code")
    private String accountCode;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "normal_balance", nullable = false)
    private PostingDirection normalBalance;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    protected ChartOfAccount() {}

    public ChartOfAccount(UUID tenantId, String accountCode, String name,
                           AccountType accountType, PostingDirection normalBalance, String createdBy) {
        this.tenantId = tenantId;
        this.accountCode = accountCode;
        this.name = name;
        this.accountType = accountType;
        this.normalBalance = normalBalance;
        this.createdBy = createdBy;
    }

    public UUID getTenantId() { return tenantId; }
    public String getAccountCode() { return accountCode; }
    public String getName() { return name; }
    public AccountType getAccountType() { return accountType; }
    public PostingDirection getNormalBalance() { return normalBalance; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
}
```

- [ ] **Step 5: Write `GlPostingId` and `GlPosting`**

`gl_posting`'s PK is `(posting_id, created_at)` — the partition key must be in the PK, so this needs an `@IdClass` too. `GlPostingId` fields: `postingId` (UUID), `createdAt` (Instant).

```java
package tz.co.nlolo.lifeplatform.finaccounting.domain;

import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code finaccounting.gl_posting} -- ONE LEG of a journal entry. Append-only at the DB
 * level ({@code REVOKE UPDATE, DELETE}), and partitioned by {@code created_at}, which is why its
 * primary key is composite ({@code posting_id, created_at}) -- Postgres requires the partition key
 * in the PK.
 *
 * <p>{@code amount} is always a POSITIVE magnitude; {@link PostingDirection} carries the sign.
 * {@code groupId} is deliberately null throughout M9 -- a group of insurance contracts is the
 * C1-governed IFRS 17 unit of account.
 */
@Entity
@Table(name = "gl_posting", schema = "finaccounting")
@IdClass(GlPostingId.class)
public class GlPosting {

    @Id
    @Column(name = "posting_id")
    private UUID postingId = UUID.randomUUID();

    @Id
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "journal_entry_id", nullable = false)
    private UUID journalEntryId;

    /** Null for all of M9 -- see the class javadoc. */
    @Column(name = "group_id")
    private UUID groupId;

    @Column(name = "account_code", nullable = false)
    private String accountCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private PostingDirection direction;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "period", nullable = false)
    private String period;

    @Column(name = "policy_number")
    private String policyNumber;

    @Column(name = "posting_type", nullable = false)
    private String postingType;

    @Column(name = "source_event", nullable = false)
    private String sourceEvent;

    @Column(name = "source_ref", nullable = false)
    private String sourceRef;

    protected GlPosting() {}

    public GlPosting(UUID tenantId, UUID journalEntryId, String accountCode, PostingDirection direction,
                      BigDecimal amount, String currency, String period, String policyNumber,
                      String sourceEvent, String sourceRef) {
        this.tenantId = tenantId;
        this.journalEntryId = journalEntryId;
        this.accountCode = accountCode;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
        this.period = period;
        this.policyNumber = policyNumber;
        // V1 declares posting_type NOT NULL with no default. It predates account_code/direction and
        // is redundant now, but V1 is immutable, so it is populated with the source event rather
        // than left to fail the NOT NULL. V2 widens it from VARCHAR(30) to VARCHAR(60) to match
        // source_event -- required, not tidying: 'billing.PremiumInvoiceGenerated' is 31 characters.
        this.postingType = sourceEvent;
        this.sourceEvent = sourceEvent;
        this.sourceRef = sourceRef;
    }

    public UUID getPostingId() { return postingId; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getTenantId() { return tenantId; }
    public UUID getJournalEntryId() { return journalEntryId; }
    public UUID getGroupId() { return groupId; }
    public String getAccountCode() { return accountCode; }
    public PostingDirection getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getPeriod() { return period; }
    public String getPolicyNumber() { return policyNumber; }
    public String getPostingType() { return postingType; }
    public String getSourceEvent() { return sourceEvent; }
    public String getSourceRef() { return sourceRef; }
}
```

- [ ] **Step 6: Write `JournalEntry` — the aggregate root with the balance invariant**

```java
package tz.co.nlolo.lifeplatform.finaccounting.domain;

import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Maps {@code finaccounting.journal_entry} -- the aggregate root of a double-entry transaction,
 * and where idempotency lives: {@code ux_journal_entry_once (tenant_id, source_event, source_ref)}
 * makes a redelivered event a no-op.
 *
 * <p><b>Why the unique index is here and not on {@code gl_posting}.</b> {@code gl_posting} is
 * {@code PARTITION BY RANGE (created_at)}, and Postgres requires every unique index on a
 * partitioned table to include all partition-key columns -- verified empirically. Omitting
 * {@code created_at} is rejected outright; including it would apply cleanly and then silently
 * permit a double-post, since a redelivered event at a different timestamp satisfies it.
 *
 * <p>Legs are held {@link Transient} as plain {@link Leg} values rather than as a JPA
 * {@code @OneToMany} of {@link GlPosting}: {@code gl_posting} is append-only with a composite
 * partition-aware key, and a cascading collection would fight both. It also keeps the balance
 * invariant testable without a database.
 *
 * <p>Holding VALUES rather than entities is what avoids a null-id window: {@code journalEntryId} is
 * {@code @GeneratedValue} (this codebase's convention -- see {@code reinsurance.Cession}), so it does
 * not exist until the entry is persisted, while {@code gl_posting.journal_entry_id} is
 * {@code NOT NULL}. {@code FinaccountingApiImpl.postEntry} therefore saves the entry first and builds
 * {@link GlPosting} rows from these legs afterwards, when the id is real.
 */
@Entity
@Table(name = "journal_entry", schema = "finaccounting")
public class JournalEntry {

    @Id
    @GeneratedValue
    @Column(name = "journal_entry_id")
    private UUID journalEntryId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "source_event", nullable = false)
    private String sourceEvent;

    @Column(name = "source_ref", nullable = false)
    private String sourceRef;

    @Column(name = "period", nullable = false)
    private String period;

    @Column(name = "policy_number")
    private String policyNumber;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    /** One leg's facts, before it becomes a persistent {@link GlPosting} row. */
    public record Leg(String accountCode, PostingDirection direction, BigDecimal amount, String currency) {}

    @Transient
    private final List<Leg> legs = new ArrayList<>();

    protected JournalEntry() {}

    public JournalEntry(UUID tenantId, String sourceEvent, String sourceRef, String period,
                         String policyNumber, String createdBy) {
        this.tenantId = tenantId;
        this.sourceEvent = sourceEvent;
        this.sourceRef = sourceRef;
        this.period = period;
        this.policyNumber = policyNumber;
        this.createdBy = createdBy;
    }

    /**
     * @throws IllegalArgumentException if the amount is not a positive magnitude (the direction
     *         carries the sign -- see V2's {@code gl_posting_amount_positive}), or if the currency
     *         differs from an existing leg's (no FX table exists on this platform, so a
     *         mixed-currency entry could never be meaningfully balanced)
     */
    public void addLeg(String accountCode, PostingDirection direction, BigDecimal amount, String currency) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("A posting amount must be a positive magnitude; "
                + "direction carries the sign. Got: " + amount);
        }
        if (!legs.isEmpty() && !legs.get(0).currency().equals(currency)) {
            throw new IllegalArgumentException("All legs of one journal entry must share a currency; "
                + "entry is " + legs.get(0).currency() + ", leg is " + currency);
        }
        legs.add(new Leg(accountCode, direction, amount, currency));
    }

    /**
     * True only when there is at least one leg on EACH side and the two sides' totals are equal.
     * The at-least-one-per-side requirement matters: an entry with no legs would otherwise report
     * balanced on 0 == 0, and an entry with two same-side legs would report balanced only if both
     * were zero (which addLeg forbids) -- both are invalid entries, not balanced ones.
     */
    public boolean isBalanced() {
        BigDecimal debits = total(PostingDirection.DR);
        BigDecimal credits = total(PostingDirection.CR);
        if (debits.signum() == 0 || credits.signum() == 0) {
            return false;
        }
        return debits.compareTo(credits) == 0;
    }

    private BigDecimal total(PostingDirection direction) {
        return legs.stream()
            .filter(l -> l.direction() == direction)
            .map(Leg::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<Leg> getLegs() { return Collections.unmodifiableList(legs); }

    public UUID getJournalEntryId() { return journalEntryId; }
    public UUID getTenantId() { return tenantId; }
    public String getSourceEvent() { return sourceEvent; }
    public String getSourceRef() { return sourceRef; }
    public String getPeriod() { return period; }
    public String getPolicyNumber() { return policyNumber; }
    public Instant getPostedAt() { return postedAt; }
    public String getCreatedBy() { return createdBy; }
}
```

- [ ] **Step 7: Write `PostingRule` — the flagged event→accounts map**

```java
package tz.co.nlolo.lifeplatform.finaccounting.domain;

import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;

import java.util.Map;
import java.util.Optional;

/**
 * EVERY MAPPING BELOW IS AN INVENTED PLACEHOLDER pending FINANCE sign-off -- the same treatment
 * M2 gave the underwriting decision engine, M7 gave commission and M8 gave cession. No document on
 * this platform specifies account codes or their debit/credit treatment.
 *
 * <p><b>This is an ACCRUAL ledger, which is why premium takes two entries, not one.</b> The
 * obligation arises when an invoice is GENERATED, so {@code PremiumInvoiceGenerated} raises
 * {@code 1200 Premium Receivable} against {@code 2200 Unearned Premium}; {@code PremiumCollected}
 * then settles the receivable against cash. Neither touches an income account, deliberately:
 * premium is EARNED as coverage is provided, and that earning pattern is LRC release -- C1-blocked
 * (Actuarial). M9 therefore accumulates unearned premium and recognises zero earned premium, and no
 * {@code 4xxx} account is even seeded.
 *
 * <p><b>{@code policy.PolicyIssued} maps to nothing, deliberately.</b> Issuing a policy moves no
 * cash and creates no immediate obligation -- the obligation attaches per invoice, which
 * {@code PremiumInvoiceGenerated} above captures. What genuinely belongs at issuance is LRC/CSM
 * initial recognition, which is C1-blocked.
 */
public final class PostingRule {

    private PostingRule() {}

    /** One balanced pair: which account is debited, which is credited. */
    public record AccountPair(String debitAccount, String creditAccount) {}

    public static final String CASH = "1000";
    public static final String PREMIUM_RECEIVABLE = "1200";
    public static final String REINSURANCE_RECOVERABLE = "1300";
    public static final String POLICY_LOAN_RECEIVABLE = "1400";
    public static final String UNEARNED_PREMIUM = "2200";
    public static final String REINSURANCE_PAYABLE = "2300";
    public static final String CLAIMS_EXPENSE = "5000";
    public static final String COMMISSION_EXPENSE = "5100";
    public static final String REINSURANCE_CEDED_PREMIUM = "5200";

    private static final Map<String, AccountPair> RULES = Map.of(
        "billing.PremiumInvoiceGenerated", new AccountPair(PREMIUM_RECEIVABLE, UNEARNED_PREMIUM),
        "billing.PremiumCollected",        new AccountPair(CASH, PREMIUM_RECEIVABLE),
        "claims.ClaimSettled",             new AccountPair(CLAIMS_EXPENSE, CASH),
        "distribution.CommissionPaid",     new AccountPair(COMMISSION_EXPENSE, CASH),
        "reinsurance.CessionRecorded",     new AccountPair(REINSURANCE_CEDED_PREMIUM, REINSURANCE_PAYABLE),
        "reinsurance.RecoveryConfirmed",   new AccountPair(REINSURANCE_RECOVERABLE, CLAIMS_EXPENSE),
        "policyloan.LoanDisbursed",        new AccountPair(POLICY_LOAN_RECEIVABLE, CASH),
        "policyloan.LoanRepaid",           new AccountPair(CASH, POLICY_LOAN_RECEIVABLE));

    /** Empty for any event with no accounting consequence -- which is most events on this
     * platform, and is a normal outcome rather than an error. */
    public static Optional<AccountPair> forEvent(String eventType) {
        return Optional.ofNullable(RULES.get(eventType));
    }

    /** The nine accounts M9 posts to, for seeding. Deliberately no 4xxx INCOME account. */
    public static Map<String, String> seedAccounts() {
        return Map.of(
            CASH, "Cash / Mobile Money",
            PREMIUM_RECEIVABLE, "Premium Receivable",
            REINSURANCE_RECOVERABLE, "Reinsurance Recoverable",
            POLICY_LOAN_RECEIVABLE, "Policy Loan Receivable",
            UNEARNED_PREMIUM, "Unearned Premium",
            REINSURANCE_PAYABLE, "Reinsurance Payable",
            CLAIMS_EXPENSE, "Claims Expense",
            COMMISSION_EXPENSE, "Commission Expense",
            REINSURANCE_CEDED_PREMIUM, "Reinsurance Ceded Premium");
    }

    /** ASSET/EXPENSE accounts are normally debit-balanced; LIABILITY/EQUITY/INCOME credit. */
    public static PostingDirection normalBalanceFor(String accountCode) {
        return accountCode.startsWith("2") || accountCode.startsWith("3") || accountCode.startsWith("4")
            ? PostingDirection.CR : PostingDirection.DR;
    }
}
```

- [ ] **Step 8: Write the three repositories and the seeder**

```java
package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccount;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every method is tenant-scoped -- RLS is the backstop, not the only guard. */
public interface ChartOfAccountRepository extends JpaRepository<ChartOfAccount, ChartOfAccountId> {
    List<ChartOfAccount> findByTenantIdOrderByAccountCodeAsc(UUID tenantId);
    Optional<ChartOfAccount> findByTenantIdAndAccountCode(UUID tenantId, String accountCode);
    boolean existsByTenantId(UUID tenantId);
}
```

```java
package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {
    Optional<JournalEntry> findByJournalEntryIdAndTenantId(UUID journalEntryId, UUID tenantId);
    List<JournalEntry> findByTenantIdOrderByPostedAtDesc(UUID tenantId);
    List<JournalEntry> findByTenantIdAndPeriodOrderByPostedAtDesc(UUID tenantId, String period);
    List<JournalEntry> findByTenantIdAndPolicyNumberOrderByPostedAtDesc(UUID tenantId, String policyNumber);
    boolean existsByTenantIdAndSourceEventAndSourceRef(UUID tenantId, String sourceEvent, String sourceRef);
}
```

```java
package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPosting;
import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPostingId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GlPostingRepository extends JpaRepository<GlPosting, GlPostingId> {
    List<GlPosting> findByTenantIdAndJournalEntryIdOrderByDirectionAsc(UUID tenantId, UUID journalEntryId);
    List<GlPosting> findByTenantIdAndAccountCodeAndPeriod(UUID tenantId, String accountCode, String period);
}
```

`ChartOfAccountSeeder.java` — a `@Component` with one method `seedIfAbsent(UUID tenantId, String seededBy)` that returns early when `existsByTenantId` is true, else saves the nine accounts from `PostingRule.seedAccounts()`, deriving `AccountType` from the code's leading digit (1→ASSET, 2→LIABILITY, 3→EQUITY, 4→INCOME, 5→EXPENSE) and `normalBalance` from `PostingRule.normalBalanceFor`. Javadoc must state that these are Finance-sign-off placeholders and that a real deployment seeds a real chart during tenant onboarding.

- [ ] **Step 9: Run the test and commit**

```bash
./mvnw -B -o test -Dtest=JournalEntryBalanceTest
```
Expected: PASS, 6 tests.

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/finaccounting/ src/test/java/tz/co/nlolo/lifeplatform/finaccounting/
git commit -m "feat: finaccounting domain -- journal entry with its balance invariant, postings, chart of accounts and the flagged posting map"
```

---

### Task 4: `GlPostingCalculator` — event facts to a balanced entry, as a pure function

**Files:**
- Create: `finaccounting/domain/GlPostingCalculator.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/finaccounting/GlPostingCalculatorTest.java`

**Interfaces:**
- Consumes: `PostingRule`, `JournalEntry`, `PostingDirection` (Tasks 2-3).
- Produces: `GlPostingCalculator.calculate(UUID tenantId, String eventType, String sourceRef, String policyNumber, BigDecimal amount, String currency, String period, String createdBy)` returning `Optional<JournalEntry>` — a fully-populated, balanced entry, or empty when the event has no accounting consequence. Task 6's listeners call exactly this.

- [ ] **Step 1: Write the failing test**

```java
package tz.co.nlolo.lifeplatform.finaccounting;

import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPostingCalculator;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlPostingCalculatorTest {

    private static final UUID TENANT = UUID.randomUUID();

    private static Optional<JournalEntry> calc(String eventType, String amount) {
        return GlPostingCalculator.calculate(TENANT, eventType, "ref-1", "POL-0001",
            new BigDecimal(amount), "TZS", "2026-08", "system:test");
    }

    private static String accountFor(JournalEntry entry, PostingDirection direction) {
        return entry.getLegs().stream()
            .filter(l -> l.direction() == direction)
            .map(JournalEntry.Leg::accountCode)
            .findFirst().orElseThrow();
    }

    /** The invariant that matters most: EVERY mapping produces a balanced entry. */
    @Test
    void everyMappedEventProducesABalancedTwoLeggedEntry() {
        for (String eventType : new String[] {
                "billing.PremiumInvoiceGenerated", "billing.PremiumCollected", "claims.ClaimSettled",
                "distribution.CommissionPaid", "reinsurance.CessionRecorded",
                "reinsurance.RecoveryConfirmed", "policyloan.LoanDisbursed", "policyloan.LoanRepaid" }) {
            Optional<JournalEntry> entry = calc(eventType, "15000.00");
            assertThat(entry).as("%s must produce an entry", eventType).isPresent();
            assertThat(entry.get().getLegs()).as("%s must have exactly two legs", eventType).hasSize(2);
            assertThat(entry.get().isBalanced()).as("%s must balance", eventType).isTrue();
        }
    }

    @Test
    void premiumInvoiceGeneratedRaisesTheReceivableAgainstUnearnedPremium() {
        JournalEntry entry = calc("billing.PremiumInvoiceGenerated", "15000.00").orElseThrow();
        assertThat(accountFor(entry, PostingDirection.DR)).isEqualTo(PostingRule.PREMIUM_RECEIVABLE);
        assertThat(accountFor(entry, PostingDirection.CR)).isEqualTo(PostingRule.UNEARNED_PREMIUM);
    }

    @Test
    void premiumCollectedSettlesTheReceivableAgainstCash() {
        JournalEntry entry = calc("billing.PremiumCollected", "15000.00").orElseThrow();
        assertThat(accountFor(entry, PostingDirection.DR)).isEqualTo(PostingRule.CASH);
        assertThat(accountFor(entry, PostingDirection.CR)).isEqualTo(PostingRule.PREMIUM_RECEIVABLE);
    }

    /**
     * The accrual invariant across the two premium events: an invoice generated then collected
     * leaves 1200 Premium Receivable net FLAT (debited once, credited once, same amount). This is
     * the single assertion proving the two-entry split is coherent rather than double-counting.
     */
    @Test
    void thePremiumReceivableRoundTripsToNetZero() {
        JournalEntry generated = calc("billing.PremiumInvoiceGenerated", "15000.00").orElseThrow();
        JournalEntry collected = calc("billing.PremiumCollected", "15000.00").orElseThrow();

        BigDecimal net = BigDecimal.ZERO;
        for (JournalEntry entry : new JournalEntry[] { generated, collected }) {
            for (JournalEntry.Leg l : entry.getLegs()) {
                if (!PostingRule.PREMIUM_RECEIVABLE.equals(l.accountCode())) continue;
                net = l.direction() == PostingDirection.DR
                    ? net.add(l.amount()) : net.subtract(l.amount());
            }
        }
        assertThat(net).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** No 4xxx account is ever credited: premium is earned via LRC release, which is C1-blocked. */
    @Test
    void noMappingEverTouchesAnIncomeAccount() {
        for (String eventType : new String[] {
                "billing.PremiumInvoiceGenerated", "billing.PremiumCollected", "claims.ClaimSettled",
                "distribution.CommissionPaid", "reinsurance.CessionRecorded",
                "reinsurance.RecoveryConfirmed", "policyloan.LoanDisbursed", "policyloan.LoanRepaid" }) {
            JournalEntry entry = calc(eventType, "100.00").orElseThrow();
            assertThat(entry.getLegs())
                .as("%s must not post to a 4xxx income account", eventType)
                .noneMatch(l -> l.accountCode().startsWith("4"));
        }
    }

    /** policy.PolicyIssued has no accounting consequence under M9's flat model -- LRC/CSM initial
     * recognition is C1-blocked. Empty is the correct answer, not an error. */
    @Test
    void policyIssuedProducesNoEntry() {
        assertThat(calc("policy.PolicyIssued", "2000000.00")).isEmpty();
    }

    @Test
    void anUnmappedEventProducesNoEntry() {
        assertThat(calc("policy.BeneficiaryChanged", "100.00")).isEmpty();
        assertThat(calc("policy.PolicySuspended", "100.00")).isEmpty();
        assertThat(calc("policy.SurrenderValueCalculated", "100.00")).isEmpty();
    }

    @Test
    void theEntryCarriesItsSourceAndPeriodForTraceability() {
        JournalEntry entry = calc("claims.ClaimSettled", "500.00").orElseThrow();
        assertThat(entry.getSourceEvent()).isEqualTo("claims.ClaimSettled");
        assertThat(entry.getSourceRef()).isEqualTo("ref-1");
        assertThat(entry.getPeriod()).isEqualTo("2026-08");
        assertThat(entry.getPolicyNumber()).isEqualTo("POL-0001");
        assertThat(entry.getLegs()).allMatch(l -> "TZS".equals(l.currency()));
    }

    @Test
    void aNonPositiveAmountProducesNoEntry() {
        assertThat(calc("billing.PremiumCollected", "0.00")).isEmpty();
        assertThat(calc("billing.PremiumCollected", "-15000.00")).isEmpty();
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./mvnw -B -o test -Dtest=GlPostingCalculatorTest
```
Expected: FAIL to compile — `GlPostingCalculator` does not exist.

- [ ] **Step 3: Write `GlPostingCalculator`**

```java
package tz.co.nlolo.lifeplatform.finaccounting.domain;

import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns one event's facts into one balanced {@link JournalEntry}. A pure function: no I/O, no
 * Spring, no database, safe to unit-test without a container.
 *
 * <p>That purity is load-bearing rather than stylistic. It makes every account mapping and every
 * balance rule testable directly, and it is what would make a future async listener a thin-wrapper
 * change rather than a rewrite (the design spec's decision 5 defers async until IFRS 17 measurement
 * lands, because the ordering hazard documented at docs/05-event-catalog.md:62 concerns CSM
 * roll-forward, not independent postings).
 *
 * <p>All account mappings live in {@link PostingRule} and are INVENTED PLACEHOLDERS pending Finance
 * sign-off -- see that class's javadoc.
 */
public final class GlPostingCalculator {

    private GlPostingCalculator() {}

    /**
     * @return a balanced two-legged entry, or empty when this event has no accounting consequence
     *         (most events on this platform), or when the amount is not a positive magnitude.
     *         Empty is a normal outcome, never an error -- the caller logs and moves on.
     */
    public static Optional<JournalEntry> calculate(UUID tenantId, String eventType, String sourceRef,
                                                    String policyNumber, BigDecimal amount, String currency,
                                                    String period, String createdBy) {
        if (amount == null || amount.signum() <= 0) {
            return Optional.empty();
        }
        Optional<PostingRule.AccountPair> maybeRule = PostingRule.forEvent(eventType);
        if (maybeRule.isEmpty()) {
            return Optional.empty();
        }
        PostingRule.AccountPair rule = maybeRule.get();

        JournalEntry entry = new JournalEntry(tenantId, eventType, sourceRef, period, policyNumber, createdBy);
        entry.addLeg(rule.debitAccount(), PostingDirection.DR, amount, currency);
        entry.addLeg(rule.creditAccount(), PostingDirection.CR, amount, currency);
        return Optional.of(entry);
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
./mvnw -B -o test -Dtest=GlPostingCalculatorTest
```
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/finaccounting/domain/GlPostingCalculator.java src/test/java/tz/co/nlolo/lifeplatform/finaccounting/GlPostingCalculatorTest.java
git commit -m "feat: the GL posting calculator -- every mapped event yields one balanced entry"
```

---

### Task 5: `FinaccountingApiImpl` — the read surface and the posting primitive

**Files:**
- Create: `finaccounting/application/FinaccountingApiImpl.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/finaccounting/FinaccountingApiIntegrationTest.java`

**Interfaces:**
- Consumes: everything from Tasks 2-4.
- Produces: `FinaccountingApi`'s implementation, plus one package-private method the Task 6 listeners call directly (the same shape `reinsurance`'s listeners use against `ReinsuranceApiImpl`):
  - `Optional<JournalEntry> postEntry(JournalEntry entry)` — persists the entry and its legs atomically, returning empty when `ux_journal_entry_once` already has this `(tenant, source_event, source_ref)`.

- [ ] **Step 1: Write the failing integration test**

Real Postgres as `app_role`, migration list: `audit/V1`, `refdata/V1`, `product/V1`, `policyloan/V1`, `policyloan/V2`, `finaccounting/V1`, `finaccounting/V2`. Bootstrap `app_role` with `ALTER ROLE ... NOSUPERUSER NOBYPASSRLS` exactly as `ReinsuranceApiIntegrationTest` does (read that file for the harness).

**`policyloan/V1` and `V2` are required here even though this test never touches a loan.** `gl_posting` is `PARTITION BY RANGE (created_at)`; Postgres does not cascade RLS/GRANT/REVOKE from a partitioned parent onto its own hand-written partitions, and `policyloan/V2` installs the one mechanism on this platform (`trg_partition_controls`, a database-wide event trigger, not schema-scoped) that mirrors those controls — verified empirically during Task 1's review that omitting it leaves `gl_posting`'s two partitions with RLS disabled and `UPDATE`/`DELETE` still granted to `app_role`. Every `finaccounting` integration test from here on needs both migrations present for that reason.

Assert:
1. `postEntry` on a balanced entry writes one `journal_entry` and two `gl_posting` rows, readable back through `getJournalEntry` with both legs present.
2. `postEntry` for the same `(sourceEvent, sourceRef)` a second time returns `Optional.empty()` and writes nothing further — exactly one entry, two postings, still.
   **Also assert the constraint holds across differing `created_at` values** (spec §8): bypass the `existsBy...` early-return by inserting a second `journal_entry` row with the same `(tenant, source_event, source_ref)` directly via JDBC at a visibly later timestamp, and assert it fails on `ux_journal_entry_once`. This is precisely the case a unique index on the partitioned `gl_posting` could not have caught, so it must be proven rather than argued.
3. `postEntry` on an **unbalanced** entry throws (build one by hand with a single leg) — the ledger must never persist an unbalanced entry, and this is the DB-level half of the invariant `JournalEntryBalanceTest` covers in memory.
4. `getJournalEntry` throws `JournalEntryNotFoundException` for an unknown id, and for a **cross-tenant** id (a real entry belonging to another tenant must be invisible under real RLS, not merely absent).
5. `listJournalEntries` filters by `period` and by `policyNumber`, each asserted to genuinely narrow (include a non-matching case, since a filter ignoring its argument would pass a positive-only assertion).
6. `listChartOfAccounts` returns the nine seeded accounts after `ChartOfAccountSeeder.seedIfAbsent`, and seeding twice does not duplicate them.

- [ ] **Step 2: Run it and confirm it fails**

```bash
./mvnw -B -o test -Dtest=FinaccountingApiIntegrationTest
```
Expected: FAIL — no `FinaccountingApi` bean exists.

- [ ] **Step 3: Write `FinaccountingApiImpl`**

A `@Service` implementing `FinaccountingApi`, constructor-injecting `JournalEntryRepository`, `GlPostingRepository`, `ChartOfAccountRepository`, and `ApplicationEventPublisher`. Requirements:

- `postEntry(JournalEntry entry)`, package-private, `@Transactional`:
  - Return `Optional.empty()` immediately if `journalEntryRepository.existsByTenantIdAndSourceEventAndSourceRef(...)` — the convenience early-return; `ux_journal_entry_once` is the real backstop under a concurrent race.
  - **Throw `IllegalStateException` if `!entry.isBalanced()`** before persisting anything. An unbalanced entry is a bug in the calculator or a caller, and persisting one silently corrupts the ledger.
  - Save the entry **first**, then build one `GlPosting` per `JournalEntry.Leg` from the saved entry's now-real `journalEntryId`, and save them. This ordering is required, not stylistic: `journalEntryId` is `@GeneratedValue`, so it does not exist before the save, while `gl_posting.journal_entry_id` is `NOT NULL`. Do **not** add a setter to `GlPosting` to work around it — it is an append-only entity.
  - Publish `finaccounting.GlPostingRecorded` once per entry actually written, matching `asyncapi-events.yaml`'s `GlPostingRecordedPayload` (`postingId`, `groupId`, `period`, `amount`, `postingType`). **`groupId` is null throughout M9** — use a `LinkedHashMap`, since `Map.of` throws NPE on a null value.
- The three read methods, all tenant-scoped, mapping entities to views; `getJournalEntry` loads the entry then its legs via `GlPostingRepository`.

> **Implementer note on a real API/payload mismatch to surface, not paper over.** `GlPostingRecordedPayload` was declared per-*posting* (`postingId`, singular `amount`) before this milestone existed, but M9's unit of work is a journal *entry* with two legs. Publish one event per entry, using the entry's DR total as `amount` and the entry id as `postingId`, and **report this mismatch in your task report** — Task 7 updates the AsyncAPI schema, and the reviewer needs to know the shape was inherited rather than chosen.

- [ ] **Step 4: Run the test and commit**

```bash
./mvnw -B -o test -Dtest='FinaccountingApiIntegrationTest,JournalEntryBalanceTest,GlPostingCalculatorTest'
```
Expected: all pass.

```bash
git add -A
git commit -m "feat: finaccounting read surface and the atomic, idempotent, balance-checked posting primitive"
```

---

### Task 6: The five event listeners

**Files:**
- Create: `finaccounting/application/BillingEventListener.java`, `ClaimsEventListener.java`, `DistributionEventListener.java`, `ReinsuranceEventListener.java`, `PolicyLoanEventListener.java`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/finaccounting/PremiumPostingEndToEndTest.java`

**Interfaces:**
- Consumes: `FinaccountingApiImpl.postEntry`, `GlPostingCalculator.calculate`, `ChartOfAccountSeeder`.
- Produces: eight posting paths wired to real events.

- [ ] **Step 1: Check bean-name collisions before writing anything**

```bash
for c in BillingEventListener ClaimsEventListener DistributionEventListener \
         ReinsuranceEventListener PolicyLoanEventListener; do
  echo "$c: $(find src/main/java -name "$c.java" | wc -l) existing"
done
```

All must be `0`. Regardless, **every listener gets an explicit bean name** — several modules already declare `PolicyEventListener`/`PaymentEventListener`/`ClaimEventListener`, and this platform has already lost a task to a Spring bean-name collision. Use exactly these five:

```java
@Component("finaccountingBillingEventListener")
@Component("finaccountingClaimsEventListener")
@Component("finaccountingDistributionEventListener")
@Component("finaccountingReinsuranceEventListener")
@Component("finaccountingPolicyLoanEventListener")
```

- [ ] **Step 2: Write `BillingEventListener` (the two-event accrual pair)**

Copy `reinsurance/application/PolicyEventListener.java`'s exact mechanics: `@TransactionalEventListener(phase = AFTER_COMMIT)`, one reusable `PROPAGATION_REQUIRES_NEW` `TransactionTemplate`, `TenantContext` save/set/restore in a `withTenant` helper with a catch-all that logs, plus a `MeterRegistry` counter `lifeplatform_finaccounting_event_processing_failed_total` incremented in the catch block (see Step 6).

```java
@Component("finaccountingBillingEventListener")
```

Handles two events:
- `billing.PremiumInvoiceGenerated` → `sourceRef = invoiceId.toString()`, amount from `payload.get("amount")`, `policyNumber` from the payload, `period` from `YearMonth.now().toString()`
- `billing.PremiumCollected` → same shape, `sourceRef = invoiceId.toString()`

Both call `ChartOfAccountSeeder.seedIfAbsent(tenantId, "system:" + eventType)` before posting (a tenant's chart must exist before its first posting), then `GlPostingCalculator.calculate(...)`, then `FinaccountingApiImpl.postEntry(...)`, logging at INFO when the calculator returns empty.

The class javadoc must explain the accrual pair: why the same invoice produces two entries at different times, and that `1200 Premium Receivable` nets to zero across them.

- [ ] **Step 3: Write the other four listeners**

Same mechanics, same explicit-bean-name rule, one event each:

| Listener | Event | `sourceRef` | Amount from |
|---|---|---|---|
| `ClaimsEventListener` | `claims.ClaimSettled` | `claimId` | `settledAmount` |
| `DistributionEventListener` | `distribution.CommissionPaid` | `statementId` | `amount` (added in Task 7) |
| `ReinsuranceEventListener` | `reinsurance.CessionRecorded` | `cessionId` | `cededAmount` |
| `ReinsuranceEventListener` | `reinsurance.RecoveryConfirmed` | `recoveryId` | `amount` (added in Task 7) |
| `PolicyLoanEventListener` | `policyloan.LoanDisbursed` | `loanId` | `amount` (added in Task 7) |
| `PolicyLoanEventListener` | `policyloan.LoanRepaid` | `loanId` | `amount` (already present) |

`ReinsuranceEventListener` and `PolicyLoanEventListener` each handle two events via one switch, mirroring how `reinsurance`'s own listeners are structured.

**`policyNumber` is not on every payload.** `CommissionPaid`, `RecoveryConfirmed`, and the loan events carry no policy number, and `finaccounting` cannot look one up (no synchronous dependency). Pass `null` — `journal_entry.policy_number` is nullable precisely for this. Note it in each listener's javadoc rather than leaving a reader to wonder.

- [ ] **Step 4: Write `PremiumPostingEndToEndTest`**

The highest-value test in the milestone: it proves the accrual pair against the **real** billing chain, not hand-published payloads.

Migration list: `audit/V1`, `refdata/V1`-`V3`, `party/V1`, `product/V1`, `underwriting/V1`, `policy/V1`-`V4`, `billing/V1`-`V3`, `policyloan/V1`, `policyloan/V2`, `finaccounting/V1`-`V2`. Real Postgres as `app_role`. Issue a real policy through `PolicyApi` (which makes `billing` generate invoices, publishing `PremiumInvoiceGenerated`), then drive a real collection through `BillingApi.applyConfirmedPayment`.

**`policyloan/V1`/`V2` again required for the same reason as `FinaccountingApiIntegrationTest`** — `gl_posting`'s two hand-written partitions only inherit RLS/append-only privileges through `policyloan/V2`'s cross-module event trigger; see that test's note.

Assert:
1. Issuing the policy produces `journal_entry` rows for `billing.PremiumInvoiceGenerated`, each with two balanced legs, DR `1200` / CR `2200`.
2. Collecting an invoice in full produces one more entry for `billing.PremiumCollected`, DR `1000` / CR `1200`.
3. **`1200 Premium Receivable` nets to zero for that invoice** across the two entries — the accrual invariant, now proven end-to-end against real rows rather than in-memory objects.
4. **No `gl_posting` row anywhere has an account code starting with `4`** — M9 recognises no income, by design.
5. A redelivered `billing.PremiumCollected` (republish the envelope through `ApplicationEventPublisher` inside a `TransactionTemplate`) adds no second entry and no second posting pair, **and emits no second `finaccounting.GlPostingRecorded`** (spec §8 — capture published events with an `@RecordApplicationEvents`-style collector or a test listener, and assert the count is exactly one; an idempotent write that still re-announces itself would mislead every downstream consumer).
6. `csm_ledger`, `lrc_ledger`, and `lic_ledger` are **all still empty** — the C1 scope boundary, asserted rather than assumed. This is the test that catches a future well-meaning change starting to populate measurement.

- [ ] **Step 5: Run and commit**

```bash
./mvnw -B -o test -Dtest='PremiumPostingEndToEndTest,FinaccountingApiIntegrationTest'
git add -A
git commit -m "feat: five finaccounting event listeners, and the premium accrual pair proven end-to-end"
```

- [ ] **Step 6: Add the metric, its alert rule, and its producer registration**

`lifeplatform_finaccounting_event_processing_failed_total`, incremented in every listener's `withTenant` catch block. Then add a rule to `observability/alert-rules.yml` (copy the shape and severity-comment style of `ReinsuranceEventProcessingFailed`, which M8 added for the identical purpose) **and** add the metric name to `AlertRuleMetricProducerTest`'s `PRODUCED_BY_THIS_APPLICATION` set — that test fails otherwise, deliberately, because "a metric with no rule, or a rule with no producer, is silent."

The rule's annotation must say what makes this one financially consequential: a swallowed exception here means a business event moved money but no journal entry was written, so the ledger is silently incomplete.

```bash
./mvnw -B -o test -Dtest='AlertRuleMetricProducerTest,PremiumPostingEndToEndTest'
git add -A
git commit -m "feat: alertable counter for a finaccounting listener that fails to post"
```

---

### Task 7: Enrich three producer events

**Files:**
- Modify: `distribution/application/PaymentEventListener.java`, `reinsurance/application/ReinsuranceApiImpl.java`, `policyloan/application/PolicyLoanApiImpl.java`
- Modify: `api/asyncapi-events.yaml`, `docs/05-event-catalog.md`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/finaccounting/ClaimAndCommissionPostingEndToEndTest.java`, `ReinsuranceAndLoanPostingEndToEndTest.java`

- [ ] **Step 1: Enrich `distribution.CommissionPaid`**

In `distribution/application/PaymentEventListener.java`, the publish site currently reads:

```java
            eventPublisher.publishEvent(DomainEventEnvelope.of("distribution.CommissionPaid", tenantId,
                Map.of("statementId", statementId, "paidAt", Instant.now().toString())));
```

Add the statement's total. `statement` is already loaded and in scope at that point. Use `statement.getTotalAmount().toPlainString()` and `statement.getTotalCurrency()`. Add a comment explaining that `finaccounting` (M9) needs the amount to post commission expense, that this event named `finaccounting` as a consumer while giving it nothing postable, and that the change is purely additive inside the existing `wasAlreadyPaid` guard.

- [ ] **Step 2: Enrich `reinsurance.RecoveryConfirmed`**

In `reinsurance/application/ReinsuranceApiImpl.java`:

```java
        eventPublisher.publishEvent(DomainEventEnvelope.of("reinsurance.RecoveryConfirmed", tenantId,
            Map.of("recoveryId", recoveryId, "confirmedAt", recovery.getConfirmedAt().toString())));
```

Add `amount` from `recovery.getRecoverableAmount()`/`getRecoverableCurrency()` — `recovery` is in scope. Same comment rationale.

- [ ] **Step 3: Enrich `policyloan.LoanDisbursed`**

In `policyloan/application/PolicyLoanApiImpl.java`:

```java
            eventPublisher.publishEvent(DomainEventEnvelope.of("policyloan.LoanDisbursed", tenantId,
                Map.of("loanId", loanId, "disbursedAt", disbursedAt.toString())));
```

Add `amount` from `loan.getPrincipalAmount()`/`getPrincipalCurrency()` — both in scope. This sits inside the existing `if (!alreadyDisbursed)` guard, so it inherits that idempotency.

- [ ] **Step 4: Update `api/asyncapi-events.yaml` and `docs/05-event-catalog.md`**

For each of the three payloads, add the `amount` field using the **fully-specified decimal-string shape** that `ClaimSettlementRequestedPayload` established — `{type: object, required: [amount, currencyCode], properties: {amount: {type: string, description: "Plain-string decimal, e.g. \"12500.00\"."}, currencyCode: {type: string}}}` — not a bare `{type: object}`.

Also fix `GlPostingRecordedPayload`: its `description` still says "Provisional — finaccounting's aggregate design remains gated on C1." Update it to state that GL posting is now real while IFRS 17 measurement remains C1-blocked, and reconcile the field list with what `FinaccountingApiImpl` actually publishes (see Task 5's implementer note about the per-posting vs per-entry shape mismatch — resolve it here, documenting whichever shape ships).

Update each event's channel description to name `finaccounting` as a consumer that posts a journal entry from it.

- [ ] **Step 5: Write the two remaining end-to-end tests**

`ClaimAndCommissionPostingEndToEndTest` — drives a real claim to settlement (copy `ClaimSettlementEndToEndTest`'s WireMock harness, since settlement goes through `payment`'s request/confirm loop) and a real commission payout to PAID, then asserts: one balanced entry per event with the right accounts (DR `5000`/CR `1000`, and DR `5100`/CR `1000`), and a redelivery of each adds nothing. **Its migration list must include `policyloan/V1` and `policyloan/V2` even though no loan is exercised in this test** — `gl_posting`'s two hand-written partitions only inherit RLS/append-only privileges through `policyloan/V2`'s cross-module `trg_partition_controls` event trigger (see `FinaccountingApiIntegrationTest`'s note in Task 5); every real-Postgres `finaccounting` test needs it for that structural reason, independent of what the test is actually exercising.

`ReinsuranceAndLoanPostingEndToEndTest` — drives a real cession (via policy issuance under a real treaty), a real recovery confirmation, and a real loan disbursement and repayment. Asserts the four corresponding entries balance with the right accounts, and that **`1400 Policy Loan Receivable` nets to zero** across disburse-then-repay of the same amount — the loan-side mirror of the premium receivable invariant. Its migration list already needs `policyloan/V1`-`V4` for the loan scenario itself, so it inherits the `trg_partition_controls` protection incidentally — but include `policyloan/V2` in that list deliberately rather than relying on it riding along.

- [ ] **Step 6: Run the FULL suite and commit**

```bash
# FULL SUITE REQUIRED (Global Constraints trigger 1): this task changes production code in THREE
# other modules. Enriching a published event can affect anything consuming those events.
./mvnw -B -o test
git add -A
git commit -m "feat: enrich CommissionPaid, RecoveryConfirmed and LoanDisbursed with their amounts, and post from them"
```

---

### Task 8: REST layer, OpenAPI spec and exception handler

**Files:**
- Create: `finaccounting/infrastructure/FinaccountingExceptionHandler.java`, `MoneyDto.java`, `GlPostingResponseDto.java`, `JournalEntryResponseDto.java`, `ChartOfAccountResponseDto.java`, `GlPostingController.java`, `ChartOfAccountController.java`
- Create: `api/openapi/openapi-finaccounting.yaml`
- Test: `src/test/java/tz/co/nlolo/lifeplatform/finaccounting/FinaccountingSpecParsesTest.java`

- [ ] **Step 1: Write the exception handler**

Copy `reinsurance/infrastructure/ReinsuranceExceptionHandler.java`'s exact shape — `@RestControllerAdvice` + `@Order(Ordered.HIGHEST_PRECEDENCE)`, a private `problem(...)` helper setting both `errorCode` and `traceId` (`openapi-common.yaml` marks `traceId` required). Map:

| Exception | Status | errorCode |
|---|---|---|
| `JournalEntryNotFoundException` | 404 | `JOURNAL_ENTRY_NOT_FOUND` |
| `FinaccountingValidationException` | 422 | `FINACCOUNTING_VALIDATION_FAILED` |

Do **not** map `IllegalArgumentException` or `AccessDeniedException` — `GlobalExceptionHandler:78`/`:83` already map them to 400 and 403. A duplicate advice is the shadowing bug `@Order` exists to prevent.

- [ ] **Step 2: Write `MoneyDto` and the three response DTOs**

`MoneyDto` — copy `reinsurance/infrastructure/MoneyDto.java` verbatim (pattern `^-?\\d+(\\.\\d{1,2})?$`).

`GlPostingResponseDto`, `JournalEntryResponseDto` (nesting a `List<GlPostingResponseDto>`), `ChartOfAccountResponseDto` — each with a static `from(view)`, money as `MoneyDto` via `.toPlainString()`, never a raw `BigDecimal`.

- [ ] **Step 3: Write the two controllers**

**Read-only, both gated** `@PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")`. Record in the javadoc that this is a decision, not a spec quote — the same one M7 and M8 recorded, since no finance-specific staff role exists beyond `FINANCE_OFFICER`.

- `GlPostingController` — `GET /gl-postings` (optional `period` and `policyNumber` query params), `GET /gl-postings/{journalEntryId}` (the entry with both legs)
- `ChartOfAccountController` — `GET /chart-of-accounts`

**No write endpoints.** State in the javadoc why: every posting is derived from an event, nothing is hand-entered, and that is what makes the ledger trustworthy.

Check for URL collisions before committing:
```bash
grep -rn '"/gl-postings\|"/chart-of-accounts' --include=*.java src/main/java | grep -v finaccounting/infrastructure
```
Expected: no output.

- [ ] **Step 4: Write `openapi-finaccounting.yaml`**

Model on `openapi-reinsurance.yaml`'s structure: `openapi: 3.1.0`, shared `$ref`s into `openapi-common.yaml` for `Money`/`ProblemDetails`/`PolicyNumberRef`, reusable `components/parameters`/`responses`, `required` lists on every schema, and 401/403/404 declared where reachable. Every money field is a pattern-constrained decimal string. **Quote every description containing a comma** — an unquoted flow-style YAML description broke a whole contract test's spec load in M4.

The spec's own description should state plainly that this surface exposes GL postings only and that IFRS 17 measurement is not yet implemented (C1) — so an API consumer is not left expecting CSM/LRC/LIC endpoints.

- [ ] **Step 5: Write `FinaccountingSpecParsesTest`**

Copy `reinsurance/ReinsuranceSpecParsesTest.java`, changing the spec path and the asserted path keys (`/gl-postings`, `/gl-postings/{journalEntryId}`, `/chart-of-accounts`). Container-free, `ParseOptions.setResolve(true)`.

- [ ] **Step 6: Run and commit**

```bash
./mvnw -B -o test -Dtest='FinaccountingSpecParsesTest,FinaccountingApiIntegrationTest'
git add -A
git commit -m "feat: the finaccounting read-only REST layer, OpenAPI contract and exception handler"
```

---

### Task 9: Contract tests, guardrail coverage and doc reconciliation

**Files:**
- Test: `src/test/java/tz/co/nlolo/lifeplatform/finaccounting/FinaccountingContractTest.java`
- Modify: `src/test/java/tz/co/nlolo/lifeplatform/AppRolePrivilegesIntegrationTest.java`, `RowLevelSecurityIntegrationTest.java`
- Modify: `docs/06-database-schema.md`

- [ ] **Step 1: Write `FinaccountingContractTest`**

Follow `ReinsuranceContractTest`'s structure exactly: `@Testcontainers` + `@AutoConfigureMockMvc` + `@SpringBootTest(classes = Application.class, webEnvironment = MOCK)`, `jwt()` post-processors (`financeStaffOf`, `underwriterStaffOf` for the wrong-role case, `agentOf`), and `openApi().isValid(SPEC_PATH)` **paired with** `SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "<Schema>")` on every decimal-carrying response.

Migration list: `audit/V1`, `refdata/V1`, `party/V1`, `product/V1`, `policyloan/V1`, `policyloan/V2`, `finaccounting/V1`-`V2` via `MigrationTestSupport.applyMigration(...)`, exactly `ReinsuranceContractTest`'s own pattern. `policyloan/V1`/`V2` are required for the same structural reason as every other real-Postgres `finaccounting` test in this plan (Task 5's note): `gl_posting`'s hand-written partitions only inherit RLS/append-only privileges through `policyloan/V2`'s cross-module event trigger.

One test per reachable status:
- `GET /gl-postings`: 200 with the array; the `period` and `policyNumber` filters each genuinely narrowing (assert a non-matching value returns empty); 403 for `underwriterStaffOf`; 403 for `agentOf`
- `GET /gl-postings/{journalEntryId}`: 200 with **both legs** present and their DR/CR totals equal; 404 unknown; **404 cross-tenant** (not 403 — a 403 confirms the id exists elsewhere)
- `GET /chart-of-accounts`: 200 with the nine seeded accounts; 403 for a non-finance role

Seed real postings first (through `FinaccountingApiImpl.postEntry` directly — the fixture technique, since the endpoint under test is the read path), so every 403 has a real target and a broken `@PreAuthorize` returns 200 rather than an incidental 404.

- [ ] **Step 2: Add `finaccounting` to `AppRolePrivilegesIntegrationTest`, and prove the cross-module partition-control mirror actually protects a newly created partition**

Read the file in full first; append only. Add `finaccounting/V1` and `V2` to its migration list.

**Also add `db-migrations/policyloan/V2__partition_tenant_controls.sql` to the same migration list, before `finaccounting/V1`.** This is required, not incidental. `gl_posting` (from `finaccounting/V1`) is `PARTITION BY RANGE (created_at)` with two hand-written partitions; Postgres does **not** cascade RLS, policies, or GRANT/REVOKE from a partitioned parent onto its partitions — each partition is an independent relation with its own privilege ACL and its own row-security flag. `policyloan/V2` installs `trg_partition_controls`, a database-wide (not schema-scoped) event trigger that mirrors a partitioned parent's controls onto its partitions on every relevant DDL event, plus a backfill sweep over every partitioned table already in the database. Its own code comment names `finaccounting.gl_posting` explicitly as a table it will protect "when those milestones land." **Without `policyloan/V2` in this test's migration list, `gl_posting_2026_08`/`_09` would show RLS disabled and `UPDATE`/`DELETE` still granted to `app_role`, reachable by any session naming the partition directly** — this was found and empirically verified during Task 1's review: in isolation (`finaccounting/V1`+`V2` only) the drift is real; applying `policyloan/V1`-`V3` first (the platform's real deployment order — confirmed in `scripts/migrate.sh`, where `policyloan` precedes `finaccounting`) closes it completely, verified against a disposable Postgres 16 container. This is a genuine, previously-missing piece of cross-module test coverage, not decoration.

Then add three tests proving, through the app's own `DataSource`:
1. `app_role` **can** INSERT and SELECT a `journal_entry` row and a `gl_posting` row.
2. `app_role` **cannot** UPDATE or DELETE either — assert the `SQLException`/permission failure explicitly. V1's `REVOKE` without a prior `GRANT` means this platform's append-only ledger has **never once been verified**, and the append-only guarantee is a real audit property, not a convention.
3. **The mirroring mechanism itself, proven against a NEW partition created after migration — the actual pg_partman scenario, not the two hand-written ones.** Issue `CREATE TABLE finaccounting.gl_posting_2026_10 PARTITION OF finaccounting.gl_posting FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');` directly (as the superuser/migration role, mirroring what pg_partman's maintenance job does), then assert — querying `pg_class`/`pg_policy`/`has_table_privilege` exactly as `verify-partition-controls.sql` does — that the new partition immediately has RLS enabled, one policy, and `app_role` holding neither `UPDATE` nor `DELETE`, with **no manual step in between**. This is the first test anywhere on the platform that proves `trg_partition_controls` protects a partition it did not exist to backfill — `policyloan` itself has never had this proven, only the two-hand-written-partitions backfill case. Record this explicitly in the test's javadoc: it is closing a gap in the mechanism's own coverage, not just adding `finaccounting` to an existing pattern.

- [ ] **Step 3: Add `finaccounting` to `RowLevelSecurityIntegrationTest`**

Append the same migrations **including `policyloan/V2__partition_tenant_controls.sql` before `finaccounting/V1`** (Step 2's rationale applies identically here — without it, `gl_posting`'s own partitions would fail a direct-partition RLS check even though the parent enforces it correctly). Then add a test at the next unused `@Order` number (read the file to find it — do not assume). Seed a `journal_entry` + `gl_posting` + `chart_of_account` row in each of two tenants as the superuser; assert both tenants' rows are visible without RLS (the negative control, proving the seed worked); then read through a genuinely restricted `app_role` connection (`SET ROLE app_role; SET app.current_tenant_id = '<tenantA>'`) and assert only tenant A's rows are visible on **all three** tables.

- [ ] **Step 4: Reconcile `docs/06-database-schema.md`**

- Add `finaccounting.group_of_contracts` to the optimistic-locking list (V2 added `version`), noting the ledgers are excluded because they are append-only or C1-blocked.
- The append-only/WORM list at `:33` already names `finaccounting.gl_posting`. **Add `finaccounting.journal_entry`** — it is append-only on the same terms.
- Add a line recording that `finaccounting`'s schema is **no longer wholly provisional**: the GL posting layer is real as of M9, while `csm_ledger`/`lrc_ledger`/`lic_ledger` remain C1-blocked. `:67` currently says the whole schema is provisional pending C1, which is now only half true.

- [ ] **Step 5: Run the FULL suite and commit**

```bash
# FULL SUITE REQUIRED (Global Constraints trigger 2): this task changes
# AppRolePrivilegesIntegrationTest and RowLevelSecurityIntegrationTest, which every module leans on.
./mvnw -B -o test
git add -A
git commit -m "test: finaccounting contract tests, the two guardrail gaps, and doc reconciliation"
```

---

### Task 10: Full verification

- [ ] **Step 1: Full clean verify**

```bash
./mvnw -B -o clean verify
```

Expected `BUILD SUCCESS`, including `ModularityTests` (confirming `finaccounting`'s `allowedDependencies` are still exactly `{ product::api, refdata::api }` — it consumes events from six modules it must never call), `NoCircularDependencyTest`, `NoCrossModuleJoinTest`, `AlertRuleMetricProducerTest`, `ActuatorExposureTest`.

Aggregate the real counts rather than trusting the log tail:

```bash
grep -h "Tests run:" target/surefire-reports/*.txt | awk -F'[:,]' '{t+=$2; f+=$4; e+=$6} END {print "Total:", t, "Failures:", f, "Errors:", e}'
```

- [ ] **Step 2: Mirror the CI `db-migration-validation` job**

Apply every module's migrations in `scripts/migrate.sh`'s order (read the file; cross-check against `.github/workflows/ci-cd.yml`'s own job order rather than assuming they agree) against a fresh `postgres:16`, then run `db-migrations/_post-migration/verify-partition-controls.sql` and confirm zero drift. **This matters more than usual for M9**: `gl_posting` is partitioned and pg_partman-managed, and V2 adds `NOT NULL` columns to it — the partition-controls check is what confirms every partition still matches its parent's RLS and privileges after that change.

Re-run Task 1's privilege query and confirm all seven `finaccounting` tables report `rls=t has_policy=t sel=t ins=t`, with `gl_posting` and `journal_entry` showing `upd=f`.

- [ ] **Step 3: Confirm the C1 boundary held**

```bash
grep -rn "csm_ledger\|lrc_ledger\|lic_ledger" --include=*.java src/main/java | grep -v "^.*://"
```

Expected: **no production code references any of the three measurement ledgers.** If anything does, M9 has silently crossed its own scope boundary and that must be reported, not quietly accepted.

- [ ] **Step 4: Acceptance-criteria confirmation**

Confirm against `docs/08-implementation-roadmap.md:180`, naming the specific tests and real counts (read `target/surefire-reports/*.txt`, do not guess):
1. **Event-consumption plumbing** → the eight posting paths, each with an end-to-end test driving the real producer chain (`PremiumPostingEndToEndTest`, `ClaimAndCommissionPostingEndToEndTest`, `ReinsuranceAndLoanPostingEndToEndTest`)
2. **Provisional flat GL posting structure (no cohort grouping)** → `gl_posting` with `account_code`/`direction`/`journal_entry_id`, `group_id` nullable and never written
3. **DDL/aggregate skeleton** → `V2` hardening + `chart_of_account` + `journal_entry`; the three measurement ledgers present, empty, and `C1-BLOCKED`
4. **Additive, not a rewrite** → postings carry `policy_number` and `source_ref`, so a later milestone can assign `group_id` and compute measurement over existing rows
5. **State explicitly** that IFRS 17 measurement is NOT delivered and NOT claimed, and that M9 recognises zero premium income by design (unearned premium accumulates instead) — both are deliberate and must not be glossed.

- [ ] **Step 5: Report and stop**

Do not merge. Report the aggregate count, the acceptance mapping, every deviation, and every deferred item, so the final whole-branch review has an accurate baseline.

---

## Self-Review Notes

**Judgment calls flagged for the final review — each a place where this plan chose a side.**

1. **Every account code and every posting mapping is invented** (`PostingRule`). No document specifies them. Needs Finance sign-off before production use.
2. **Premium takes two entries, and neither credits income.** The receivable/unearned-premium split is standard accrual accounting, but the *absence* of income recognition is a direct consequence of deferring LRC release. M9's trial balance will show a growing `2200` and zero earned premium — correct by design, and surprising to anyone who does not know why.
3. **`policy.PolicyIssued` posts nothing.** Justified by the receivable arriving at invoice generation instead, but it is the one place where deferring measurement visibly removes a posting an accountant might expect.
4. **Idempotency moved to `journal_entry`** because a unique index on the partitioned `gl_posting` is impossible (empirically verified). A consequence worth noting: two *different* events that happen to share a `source_ref` would collide. Today `source_ref` is always an id from the producing module and `source_event` disambiguates, so this cannot occur — but the constraint's correctness depends on that.
5. **`GlPosting.postingType` is populated with the source event name** purely to satisfy V1's `NOT NULL` on a column that `account_code`/`direction` have made redundant. V1 is immutable, so the column cannot be dropped; a later milestone could. *(V2 must widen it to `VARCHAR(60)`: caught in the pre-flight scan, because `billing.PremiumInvoiceGenerated` is 31 characters against V1's `VARCHAR(30)` — the plan's own warned-about defect class, reproduced in the plan, and it would have broken the platform's most important posting path at runtime with every unit test green.)*

12. **No test has ever applied `finaccounting/V1`** — so this schema has had zero automated coverage since it was written, which is the root cause of the missing-`GRANT` defect surviving. Tasks 5 and 9 are therefore doing more load-bearing work than their size suggests: they are the first automated exercise of this schema, not an incremental addition to existing coverage.
6. **`JournalEntry` holds `@Transient` `Leg` VALUES, not JPA-mapped `GlPosting` entities.** Deliberate on two counts — `gl_posting` is append-only with a partition-aware composite key that a cascading `@OneToMany` would fight, and `journalEntryId` is `@GeneratedValue`, so legs built at `addLeg` time could not carry a non-null `journal_entry_id`. The consequence: the aggregate never loads its own legs, and `FinaccountingApiImpl` fetches them explicitly on read. A reader expecting a managed collection will be surprised. *(This was caught in the plan's own self-review — the first draft had `addLeg` construct `GlPosting` directly against a null generated id, which would have failed the `NOT NULL` at runtime while every pure unit test stayed green.)*
7. **`GlPostingRecordedPayload`'s declared shape is per-posting, but M9 publishes per-entry.** Inherited from a pre-M9 provisional schema; Task 7 reconciles the AsyncAPI. Flagged because the mismatch was discovered during implementation rather than designed.
8. **Five listeners, not one.** Grouped by producing module to keep each class small; the alternative (one listener with an eight-branch switch) would concentrate all eight paths in one file.
9. **`period` comes from `YearMonth.now()`**, not from the event's own timestamp. For a redelivered or late-arriving event this attributes the posting to the processing month rather than the business month — acceptable while nothing closes a period, and a real problem once period-close exists (deferred, spec §9).
10. **No reversal path.** A mis-posted entry cannot be corrected in-ledger. The schema supports the eventual shape (a new entry with swapped directions); no code creates one.
11. **`ChartOfAccountSeeder` seeds lazily, on first posting for a tenant.** Simple and self-healing, but it means the very first posting for a tenant does slightly more work, and a tenant with no postings has no chart — so `GET /chart-of-accounts` returns empty for a brand-new tenant rather than a default chart.
