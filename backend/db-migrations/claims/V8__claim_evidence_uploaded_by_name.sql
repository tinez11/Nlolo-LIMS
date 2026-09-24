-- WHO attached a piece of claim evidence, in words a person can read.
--
-- The same defect V7 fixed for assessments: `uploaded_by` holds the uploader's Keycloak
-- subject, and the console printed it verbatim -- "uploaded by 1697c88f-78d8-…" under a death
-- certificate. It stays the identity; this is only the label.
--
-- Evidence is attached from three realms -- the claimant, their agent, or staff -- so the name
-- is whichever that caller's own token carried, captured at the moment of upload.
--
-- Nullable: rows attached before this have no name to recover.

ALTER TABLE claims.claim_evidence
    ADD COLUMN uploaded_by_name VARCHAR(255);
