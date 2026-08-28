-- Index the beneficiary table's OTHER direction.
--
-- V1 created idx_beneficiary_policy on (policy_number) WHERE active, because until now the only
-- question ever asked was "who are the beneficiaries of this policy". The client register asks the
-- reverse -- "which policies name this person" -- which nothing on the platform could answer, and
-- which would otherwise be a sequential scan of every beneficiary row in the tenant on a table that
-- grows with every policy ever issued.
--
-- Partial on `active`, mirroring the existing index exactly: replacing a policy's beneficiaries
-- deactivates the old rows rather than deleting them, so the inactive rows accumulate forever and
-- no query on this path ever wants them. Indexing them would grow the index without bound for no
-- reader.
--
-- party_id is nullable by design -- chk_beneficiary_exactly_one_designation makes a beneficiary
-- either a PARTY (party_id set) or a FREEFORM designee (a name string, no party). Freeform rows are
-- simply absent from this index, which is correct: an unidentified designee cannot be looked up by
-- party id, and that limitation belongs in the data model rather than being papered over here.

CREATE INDEX idx_beneficiary_party ON policy.beneficiary (party_id) WHERE active;
