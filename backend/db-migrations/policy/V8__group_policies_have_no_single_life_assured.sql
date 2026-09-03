-- Correction to V7: a group policy has no single life assured.
--
-- V7 backfilled life_assured_party_id = policyholder_party_id across every policy, on the
-- reasoning that the old model had one party slot and that slot was the life whose
-- mortality was rated. That reasoning holds for individual business and is wrong for
-- group business, which the client confirmed on 2026-09-03:
--
--     One master/group policy + many insured members. A member is NOT a separate policy.
--     ABC Company buys Group Life for 500 employees -> the COMPANY is the policyholder,
--     and the 500 employees are the lives assured.
--
-- So on a GROUP_LIFE policy the policyholder is an employer, and an employer is not a
-- life assured. Copying it into life_assured_party_id asserts that the company itself is
-- insured, which is not a thing.
--
-- A corrective migration rather than an edit to V7: V7 is committed and has been applied,
-- and rewriting an applied migration means environments silently disagree about what ran.
--
-- The lives on a group policy live in the member schedule (policy.policy_member), which
-- does not exist yet -- the group-business build is next. Until then a GROUP_LIFE policy
-- correctly answers "who is insured" with NULL, meaning "not a single person", rather
-- than with a confident wrong answer.

UPDATE policy.policy
   SET life_assured_party_id = NULL
 WHERE product_category = 'GROUP_LIFE'
   AND life_assured_party_id = policyholder_party_id;

COMMENT ON COLUMN policy.policy.life_assured_party_id IS
    'Whose life is insured on an individual policy. NULL on a GROUP_LIFE policy, where the '
    'lives are the member schedule rather than one person, and NULL on any policy issued '
    'before the column existed.';
