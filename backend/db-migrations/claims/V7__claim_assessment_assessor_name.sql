-- WHO assessed a claim, in words a person can read.
--
-- `assessor` holds the Keycloak subject -- a uuid -- and must go on doing so: separation of
-- duties compares it against the decider's own subject, and a display name is neither unique
-- nor stable. But it is the only identity the row carried, so the manager deciding a claim was
-- told it was "recommended by 1697c88f-78d8-40e2-ae75-1bad3d6180ca".
--
-- The name is captured from the assessor's token at the moment of writing, not looked up later:
-- a record of who did something should say who they were then, and should not need an identity
-- provider to be up to be read.
--
-- Nullable, because every row written before this has no name to recover. The console renders
-- those as a name not recorded, never as the uuid.

ALTER TABLE claims.claim_assessment
    ADD COLUMN assessor_name VARCHAR(255);
