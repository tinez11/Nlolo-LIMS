-- db-migrations/communication/V4__dispatch_reason_and_policy.sql
-- Two things a dispatch row has to be able to say and could not.
--
-- failure_reason. V1 gave the table a FAILED status and nowhere to record why. Nothing on this
-- platform retries a notification, deliberately -- a retry policy needs a backoff and a give-up
-- rule that deserve their own decision -- so the row IS the whole trace that somebody was owed a
-- message and did not get it. "FAILED" with no reason tells an operator that something went
-- wrong and gives them no way to tell an unreachable aggregator from a customer with no phone
-- number on file, which are different problems with different fixes.
--
-- policy_number. Nullable, because not every notification is about a policy. The ones that are
-- should say so in a column rather than leave a reader inferring it from a template key and a
-- timestamp: the question the desk actually asks is "has THIS customer been told about THIS
-- policy", and answering that from a party-keyed outbox means knowing a party id and filtering
-- by hand. It is the policy detail page's Messages panel that makes this earn its place.
ALTER TABLE communication.notification_dispatch
    ADD COLUMN failure_reason TEXT,
    ADD COLUMN policy_number VARCHAR(20);

CREATE INDEX idx_notification_dispatch_policy
    ON communication.notification_dispatch (tenant_id, policy_number);

COMMENT ON COLUMN communication.notification_dispatch.failure_reason IS
    'Why a FAILED dispatch failed, in words an operator can act on. Null on PENDING and SENT.';
COMMENT ON COLUMN communication.notification_dispatch.policy_number IS
    'The policy this message was about, when it was about one. No FK: policy is another module.';
