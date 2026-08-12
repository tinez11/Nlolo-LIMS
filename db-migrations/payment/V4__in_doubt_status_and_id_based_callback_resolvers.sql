-- db-migrations/payment/V4__in_doubt_status_and_id_based_callback_resolvers.sql
-- M5 final whole-branch review, fix wave. V1/V2/V3 are already applied elsewhere and must never
-- be edited -- this file is additive, like V2 and V3 before it.
--
-- Two independent findings, both about the SEAM between "the rail told us something" and "we
-- recorded what the rail told us", land in one migration because they are two halves of the same
-- recovery story: section 1 gives an indeterminate outcome a state of its own instead of lying
-- about it, and section 2 gives the inbound webhook a second way to find the row that state is
-- recorded on.

-- =============================================================================
-- 1. IN_DOUBT: a third, NON-TERMINAL state for both ledgers' status column.
--
-- WHY (review finding C2, escalated to the user because it contradicts the plan's own Global
-- Constraints, and decided in favour of correctness):
--
-- The plan's Global Constraints say "a gateway failure must land the row in FAILED with a
-- published *Failed event, never in a log line alone". That is exactly right for a rail that
-- DECLINES -- an explicit business rejection ("INSUFFICIENT_FLOAT") is definite knowledge that no
-- money moved, and the requesting module must hear about it and compensate.
--
-- It is WRONG for the two cases where we have no knowledge at all:
--   (a) a read timeout (mobile-money.read-timeout-ms: 10000) -- the request was sent, and the
--       rail may well have processed it before the socket went quiet;
--   (b) an ACCEPTED response that omits gatewayReference (MobileMoneyGatewayAdapter's own
--       "an ACCEPTED with nothing to reconcile against is untrustworthy" check) -- the rail says
--       it took the money but gives us nothing to reconcile the payout against.
-- Both were recorded as definitive FAILED, which published payment.DisbursementFailed, which
-- drove policyloan.PaymentEventListener.handleFailed -> PolicyLoanApiImpl.markDisbursementFailed
-- -> a REVERSAL ledger row plus policyApi.releaseEncumbrance(...). That is a REAL financial
-- compensation for a payout that may have succeeded -- the platform giving the policyholder's
-- loan value back while the rail has already paid it out. And because DisbursementInstruction
-- .markCompleted throws on a FAILED row while MobileMoneyCallbackController swallows every
-- exception and acks 200, a later genuine SUCCESS callback for that same payout was silently
-- discarded and the aggregator stopped retrying.
--
-- IN_DOUBT is deliberately a distinct state rather than "just leave it PENDING":
-- reconciliation -- a human or a future sweep comparing this ledger against the aggregator's
-- statement -- must be able to tell "we never attempted this" (PENDING) from "we attempted this
-- and do not know the outcome; money may have moved" (IN_DOUBT). Collapsing them makes the one
-- population that genuinely needs chasing invisible inside the one that does not.
--
-- No *Failed event is published for IN_DOUBT (see PaymentApiImpl.markDisbursementInDoubt /
-- markCollectionInDoubt), so policyloan leaves the loan at DISBURSEMENT_REQUESTED with its
-- encumbrance intact -- the honest state for "we do not know". The alertable signal is a
-- Micrometer counter (lifeplatform_payment_in_doubt_total) plus an ERROR log naming the id;
-- observability/alert-rules.yml alerts on the counter.
--
-- WIDTH: both columns are VARCHAR(15) (V1:15, V1:56) and 'IN_DOUBT' is 8 characters, so no type
-- change is needed. Checked, not assumed.
--
-- CONSTRAINT NAMES: verified against a live PostgreSQL 16 container running V1+V2+V3 as written,
-- via pg_constraint -- NOT guessed from the naming pattern. Postgres auto-generates
-- <table>_<column>_check for an unnamed inline column-level CHECK, giving exactly
-- payment_transaction_status_check and disbursement_instruction_status_check. (V2:152 already
-- relies on the same fact for disbursement_instruction_purpose_check, so this is the established
-- method here, and policyloan/V4's header records the same verification for its own constraint.)
--
-- PARTITIONED PARENTS: both tables are PARTITION BY RANGE (created_at) with two hand-written
-- partitions each (V1:21-24, V1:64-67) -- four in total, and V2 had to re-issue RLS/policy
-- statements against each one individually because those genuinely do NOT propagate. CHECK
-- constraints are different, and this was tested rather than assumed: against a live container,
-- pg_constraint showed each partition carrying an INHERITED copy (conislocal = false,
-- coninhcount = 1) of the parent's constraint; a single DROP CONSTRAINT on the parent removed all
-- three rows (parent + both partitions), and a single ADD CONSTRAINT on the parent re-created all
-- three with the widened definition and the same name. So -- unlike RLS -- no per-partition
-- statement is needed or possible here (an ALTER on an inherited constraint's child is rejected
-- outright). Future pg_partman-created partitions inherit it the same way, since they are created
-- as PARTITION OF this parent.
-- =============================================================================
ALTER TABLE payment.payment_transaction
    DROP CONSTRAINT payment_transaction_status_check;
ALTER TABLE payment.payment_transaction
    ADD CONSTRAINT payment_transaction_status_check CHECK (status IN
        ('PENDING','IN_DOUBT','CONFIRMED','FAILED'));

-- NOTE the asymmetry, which is V1's and is deliberately preserved rather than "tidied": the
-- collection ledger's success state is CONFIRMED (V1:15) and the disbursement ledger's is
-- COMPLETED (V1:56). openapi-payment.yaml's PaymentStatusResponse.status enum is documented as
-- the "union of both ledgers' terminal vocabularies" for exactly this reason.
ALTER TABLE payment.disbursement_instruction
    DROP CONSTRAINT disbursement_instruction_status_check;
ALTER TABLE payment.disbursement_instruction
    ADD CONSTRAINT disbursement_instruction_status_check CHECK (status IN
        ('PENDING','IN_DOUBT','COMPLETED','FAILED'));

-- payout_batch.status is deliberately NOT widened. Its allowed set (IN_PROGRESS / COMPLETED /
-- PARTIAL_FAILURE) is derived from its members by PayoutBatch.deriveStatus, and an IN_DOUBT
-- member is simply not yet terminal -- i.e. the batch stays IN_PROGRESS, which is already an
-- allowed value and already the correct answer. Adding a fourth batch value would create a state
-- with no producer.

-- =============================================================================
-- 2. ID-BASED tenant resolvers for the inbound webhook.
--
-- WHY (review finding C1): V3's resolvers key ONLY on gateway_reference, and V2's supporting
-- indexes are even `WHERE gateway_reference IS NOT NULL`. But the rows most likely to need
-- webhook recovery are precisely the ones with NO gateway_reference: a transport failure means
-- the rail never told us its reference (PaymentRequestListener passes null on that path, which is
-- the honest value -- there is no `result` at all when the rail never responded). Section 1's
-- IN_DOUBT rows are exactly that population. So the aggregator's later async notification for a
-- payout that DID go through resolved to nothing, was logged at WARN, and acked 200 -- the row
-- was permanently unrecoverable by any callback, forever.
--
-- The recovery handle already exists and was being thrown away. The callback DTO carries
-- `reference`: the MERCHANT reference this platform itself generated and sent to the rail
-- (PaymentRequestListener:151/185 pass disbursementId.toString() / transactionId.toString() as
-- GatewayDisbursementRequest.reference / GatewayCollectionRequest.reference). The aggregator
-- echoes it back. MobileMoneyCallbackController used it for log lines only.
--
-- These two functions are STRICTLY NARROWER than V3's existing pair, in the one way that matters:
-- V3's needed a COUNT(DISTINCT tenant_id) = 1 guard because gateway_reference is the AGGREGATOR's
-- identifier and V2's own comment records that a gateway may legitimately reuse one across
-- tenants -- two tenants' rows really can share it, and picking one arbitrarily was a real
-- wrong-tenant financial mutation (V3's Critical-5 fix). disbursement_id / payment_transaction_id
-- are OUR OWN gen_random_uuid()/UUID.randomUUID() primary keys, part of each ledger's PRIMARY KEY
-- (V1:18, V1:61). Cross-tenant ambiguity is therefore structurally impossible, not merely
-- unlikely, so no aggregation guard is needed here -- stated explicitly because the ABSENCE of
-- V3's guard in an otherwise-identical function would otherwise look like the same bug V3 was
-- fixed for, rather than a difference in what the input column is.
--
-- `LIMIT 1` is safe for the same structural reason, but is written as a plain scalar SELECT with
-- no LIMIT at all: the id is a PK component, so at most one row can match, and a plain SELECT
-- would raise Postgres's own "more than one row returned by a subquery used as an expression" if
-- that invariant were ever violated -- failing loudly beats silently picking one. (Note the PK is
-- composite, (disbursement_id, created_at), because the table is partitioned by created_at --
-- Postgres requires the partition key in it. disbursement_id alone is still globally unique in
-- practice: it is a fresh random UUID per row and nothing ever re-uses one.)
--
-- Security posture is copied from V3's pair, statement for statement, deliberately and not
-- approximately: SECURITY DEFINER (so the migration-role OWNER's RLS exemption applies -- see
-- V3's header for the full mechanism and why app_role never gets BYPASSRLS), a pinned
-- `SET search_path = payment, pg_temp` (so a caller cannot shadow the referenced table via their
-- own search_path), a return type of `uuid` and nothing else -- never a row, never any other
-- column, so this is a bootstrap and not an enumeration surface -- and REVOKE ALL FROM PUBLIC
-- BEFORE the GRANT to app_role, since SECURITY DEFINER functions are callable by PUBLIC by
-- default.
-- =============================================================================
CREATE FUNCTION payment.resolve_disbursement_tenant_by_id(p_disbursement_id uuid)
RETURNS uuid
LANGUAGE sql
SECURITY DEFINER
SET search_path = payment, pg_temp
AS $$
    SELECT tenant_id
    FROM payment.disbursement_instruction
    WHERE disbursement_id = p_disbursement_id;
$$;

CREATE FUNCTION payment.resolve_payment_transaction_tenant_by_id(p_payment_transaction_id uuid)
RETURNS uuid
LANGUAGE sql
SECURITY DEFINER
SET search_path = payment, pg_temp
AS $$
    SELECT tenant_id
    FROM payment.payment_transaction
    WHERE payment_transaction_id = p_payment_transaction_id;
$$;

REVOKE ALL ON FUNCTION payment.resolve_disbursement_tenant_by_id(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION payment.resolve_payment_transaction_tenant_by_id(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION payment.resolve_disbursement_tenant_by_id(uuid) TO app_role;
GRANT EXECUTE ON FUNCTION payment.resolve_payment_transaction_tenant_by_id(uuid) TO app_role;

-- No REVOKE statements on tables in this file, so the "a REVOKE issued before a later blanket
-- GRANT is silently re-granted" ordering rule (policyloan/V1:137-139, billing/V2:76-79, and this
-- plan's Global Constraints) has nothing to trip over here. The four REVOKE ALL ... FROM PUBLIC
-- statements above are each immediately followed by their own narrower GRANT, in that order,
-- which is the intended and only correct sequence for that pair.
