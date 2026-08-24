-- db-migrations/claims/V3__registration_idempotency_key.sql
-- Closes a real double-payout path Task 9's review surfaced: ClaimsApi.registerClaim already
-- took an idempotencyKey parameter, but nothing in this schema ever used it to deduplicate.
-- Two independently-created Claim rows for the SAME real-world event (e.g. a beneficiary and an
-- agent both filing the same death claim, or a client retrying a registration call after a
-- timeout with no way to tell if the first attempt landed) could each be independently assessed
-- and settled -- an actual double payout that, before this migration, only a human noticing two
-- open claims for the same policy/event prevented.
--
-- This is a DIFFERENT concern from claim.settlement_idempotency_key (claims/V2:75-77): that
-- column guards against double-PAYING money out through payment for one already-approved claim;
-- this one guards against double-CREATING the Claim row in the first place. A claim that never
-- exists twice can never be independently approved and settled twice.

ALTER TABLE claims.claim ADD COLUMN registration_idempotency_key VARCHAR(100);

-- Tenant-scoped for the same reason claims/V2:86-87 documents for settlement_idempotency_key --
-- itself matching payment/V2's registry fix: a GLOBALLY unique key would let one tenant's key
-- collide with another's, either blocking a legitimate registration outright (an INSERT that
-- should succeed fails because a different tenant happens to have already claimed the same
-- string) or, worse, returning the wrong tenant's claim on a "duplicate" lookup if the uniqueness
-- check were ever done by key alone.
--
-- A PARTIAL index (WHERE registration_idempotency_key IS NOT NULL), not a plain unique
-- constraint, for the same reason claims/V2:86-90 uses one for settlement_idempotency_key: a
-- unique constraint over a nullable column would still allow multiple NULLs under Postgres' own
-- NULL-is-distinct-from-NULL semantics, so this partial form is not strictly required for that --
-- but it is written explicitly anyway to document the reason NULL rows are expected and safe:
-- every claim registered before this column existed, and any future registration path that omits
-- the key, has registration_idempotency_key = NULL, and none of those rows are duplicates of one
-- another just because they share the same (absent) key.
CREATE UNIQUE INDEX idx_claim_registration_key
    ON claims.claim (tenant_id, registration_idempotency_key)
    WHERE registration_idempotency_key IS NOT NULL;
