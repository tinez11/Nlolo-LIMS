# Deliverable 6 — Database Schema
**Digital Life Insurance Core Platform — Tanzania (Phase 0, Artifact 6 of 8)**

> Companion files: 16 Flyway-style migration files under `db-migrations/<module>/V1__create_<module>_schema.sql` — one per module, per Deliverable 2 Rev 2's per-module-schema decision (A1). Every migration was executed end-to-end against a real, locally-provisioned PostgreSQL 16 instance (not just syntax-checked) — the fresh-database run-through, plus four targeted smoke tests, are reported in §1 because they surfaced a real bug and a real operational gotcha worth knowing before this goes anywhere near production.

---

## 1. Validation: What Was Actually Tested, and What It Found

Every file was first parsed with `pglast` (the real PostgreSQL grammar, not a generic SQL linter) — all 16 passed. Then all 16 were executed in sequence against a clean PostgreSQL 16 database, which is a stronger check: it catches cross-statement and semantic errors that pure parsing can't.

**Bug found and fixed:** `payment.payment_transaction` and `payment.disbursement_instruction` are partitioned by `created_at` for volume, and each originally had `CREATE UNIQUE INDEX ... (idempotency_key)` — this **failed on execution**, because PostgreSQL requires a partitioned table's unique constraints to include the partition key column. Widening the index to `(idempotency_key, created_at)` would have "fixed" the error message but silently broken the actual guarantee — a replayed event landing under a different `created_at` would no longer be caught as a duplicate, defeating the entire reason that constraint exists (Deliverable 3 §7.3). The real fix: a small, **non-partitioned** `*_idempotency_registry` table per ledger, holding just `(idempotency_key PRIMARY KEY, transaction_id, created_at)`. The application inserts into the registry in the same transaction as the ledger row; a conflict there means "already processed," and the event is dropped as a safe duplicate. This is a standard, small pattern for exactly this PostgreSQL limitation, and it preserves the actual invariant instead of just satisfying the DDL.

**Smoke tests run against the live database, all passing:**
1. `policy.beneficiary`'s exactly-one-of-`party_id`/`freeform_designee` CHECK constraint correctly rejected an invalid row (Po2, Deliverable 3).
2. The new idempotency registry correctly rejected a duplicate key on the second insert.
3. `audit.audit_log` correctly rejected a row dated outside any declared partition — confirms the partition-ahead-of-need convention (§3) is a real operational requirement, not a nicety.
4. **Row-Level Security correctly isolated tenants** — but only once tested properly. My first attempt showed *both* tenants' rows visible under a session claiming to be tenant A; that wasn't an RLS bug, it was me querying as the Postgres superuser, which **always bypasses RLS regardless of policy** (documented Postgres behavior, easy to forget). Re-tested via `SET ROLE app_role` (a genuinely restricted, non-superuser role) and got the correct result: exactly one row visible per tenant. **Operational consequence, worth stating plainly: if the application's runtime connection ever uses a superuser role, or a role with `BYPASSRLS`, or the table owner role, the entire RLS layer silently does nothing.** This has to be a deployment/ops checklist item, not just a DDL concern — flagging it for Deliverable 7.

---

## 2. Conventions Applied to Every Table

- **Schema-per-module** (A1): `CREATE SCHEMA <module>;` — `party`, `product`, `underwriting`, `policy`, `policyloan`, `billing`, `claims`, `distribution`, `payment`, `reinsurance`, `finaccounting`, `regreporting`, `communication`, `document`, `refdata`, `audit`.
- **`tenant_id UUID NOT NULL`** on every tenant-scoped table, indexed (IC2), with a Row-Level Security policy (`USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)`) as defense-in-depth alongside Hibernate's `@TenantId`/filter — this is new this deliverable (not discussed in Deliverables 1–3) and needs your explicit sign-off; see §5.
- **`refdata` is the deliberate exception** — genuinely global, no `tenant_id`, no RLS, per your original multi-tenancy rules on shared reference data.
- **Optimistic locking**: `version BIGINT NOT NULL DEFAULT 0` on every aggregate root table with real concurrent-write exposure (`policy`, `underwriting_case`, `policy_loan`, `claim`, `party`, `agent_profile`).
- **Audit columns**: `created_at`/`created_by`/`updated_at`/`updated_by` on every mutable table.
- **Cross-module references are opaque columns, never FKs** — e.g. `claims.claim.policy_number` has no foreign key to `policy.policy`, consistent with "modules never access another module's tables directly" (existence is checked via the owning module's API at write time, per Deliverable 2).
- **Enums as `VARCHAR` + `CHECK`**, not native Postgres `ENUM` types — a native `ENUM`'s value set can't be altered inside a transaction pre-PG12 and is still awkward to extend safely under Flyway's migration model; `VARCHAR`+`CHECK` trades a little storage efficiency for migrations that are simple `ALTER TABLE ... DROP CONSTRAINT / ADD CONSTRAINT` statements.
- **Money as `NUMERIC(19,2)` + `CHAR(3)` currency code** — exact decimal arithmetic, matching the API layer's decimal-string convention from Deliverable 4 (never a float anywhere in the stack, wire format or storage).
- **Append-only ledgers get `REVOKE UPDATE, DELETE ... FROM app_role`** at the DB level, not just an application convention: `policyloan.loan_transaction`, `finaccounting.gl_posting`, `audit.audit_log`, `policy.endorsement`.

`payment.payment_transaction` and `payment.disbursement_instruction` were originally in this list but were removed in M5: both are instruction records with a real status lifecycle (`PENDING → CONFIRMED/COMPLETED/FAILED`, plus a `gateway_reference` filled in on callback and a `batch_id` set on batching), not immutable movement ledgers, so an append-only `REVOKE` and a mutable `status` column could not both hold. The independent append-only audit trail for payment activity is `audit.audit_log`, which records every `payment.*` event generically.

---

## 3. Indexing Strategy (IC1, IC2)

Every cross-module opaque ID column is indexed — `policy_number`, `party_id`, `agent_of_record_id`/`agent_id`, `product_id`/`product_version_id`, `claim_id`, `loan_id`, etc. — since these are the join points the application layer relies on in place of foreign keys. `tenant_id` is indexed on every tenant-scoped table, typically as a composite leading column (`(tenant_id, status)`, `(tenant_id, code)`) since virtually every query filters by tenant first. A few targeted partial indexes support specific hot paths named in earlier deliverables: `billing.premium_invoice`'s `(policy_number, due_date) WHERE status IN ('DUE','IN_GRACE')` exists specifically to serve Bi2's `getNextDueInvoice` USSD lookup; `policy.loan_value_reservation`'s `(ttl_expires_at) WHERE status = 'RESERVED'` exists specifically to serve the reservation-sweep background job from Deliverable 3's B1 fix.

---

## 4. Partitioning Strategy

**Partitioned tables** (PostgreSQL native declarative `PARTITION BY RANGE`): `policyloan.loan_transaction` (monthly — highest write velocity in that module), `payment.payment_transaction` and `payment.disbursement_instruction` (monthly), `finaccounting.gl_posting` (monthly), `audit.audit_log` (monthly — the single highest-volume table platform-wide, since every event from every module lands here), `billing.premium_invoice` (yearly — high row count but much lower write velocity than the ledger tables, so coarser partitions are appropriate).

**Operational convention, stated explicitly rather than left implicit:** only two partitions are pre-created per table in these migration files (enough to prove the mechanism and give initial headroom). Real operation requires partitions to exist *ahead of* the data that needs them — smoke test #3 above demonstrated exactly what happens otherwise (a hard insert failure, not a graceful fallback). This needs either `pg_partman` (a well-established Postgres extension for exactly this) or a scheduled Flyway migration/cron job that creates the next N months' partitions on a rolling basis. This is a Deliverable 7 (Infrastructure) concern — flagged here so it isn't lost, since a partitioning strategy without a maintenance mechanism behind it isn't actually a complete strategy.

**Retention/archival policy is explicitly not decided here** — particularly for `audit.audit_log`, where TIRA's tamper-evidence expectations and general records-retention law both bear on how long partitions are kept before archival or drop. That's a compliance decision, not an architecture one; noted as an open item (§6) rather than guessed at.

---

## 5. New Recommendation Needing Your Sign-Off: Row-Level Security

RLS wasn't part of the multi-tenancy discussion in your original spec (Hibernate's `@TenantId`/`@Filter` was the stated mechanism) — I've added Postgres RLS policies as a second, independent enforcement layer on every tenant-scoped table, on the reasoning that "Security by Design" is one of your stated principles and defense-in-depth against a forgotten Hibernate filter (a real, common mistake — a native query or a misconfigured repository method bypassing the filter) is cheap to add now and expensive to retrofit later. The trade-off: every DB connection the application pool uses must run as a role that is **not** a superuser and does **not** have `BYPASSRLS`, and must call `SET app.current_tenant_id = ...` at the start of every transaction (typically via a connection-acquisition interceptor). If you'd rather rely on Hibernate's filter alone and skip the DB-level layer, say so and I'll strip the `ENABLE ROW LEVEL SECURITY`/`CREATE POLICY` statements out in the next revision — they're additive and easy to remove.

---

## 6. Open Items Before Deliverable 7 (Infrastructure Architecture)

1. **Confirm the RLS addition** (§5) — keep, or revert to Hibernate-filter-only.
2. **Partition-maintenance mechanism** (§4) — `pg_partman` vs. a custom scheduled job; needs to be designed as part of Deliverable 7's infrastructure, not left as a manual task.
3. **`audit_log` retention/archival policy** — a compliance question for you/legal, not an architecture one.
4. **`app_role` provisioning** — needs to be created with the correct (non-superuser, non-`BYPASSRLS`) attributes as part of environment bootstrap, and this needs to be enforced by infrastructure-as-code, not left to a manual `CREATE ROLE` someone might get wrong once.
5. Carrying forward, unchanged: `finaccounting`'s schema remains provisional pending C1; `refdata`'s seed values (contestability period, reinstatement window, suspension-to-lapse window, offline-receipt SLA) are explicit placeholders pending Legal/Product/Actuarial sign-off, marked as such directly in the seed `INSERT` statements so nobody mistakes them for confirmed values.

*Holding here per your process — once reviewed, I'll proceed to Deliverable 7 (Infrastructure Architecture: Docker Compose, CI/CD pipeline, monitoring/logging/observability).*
