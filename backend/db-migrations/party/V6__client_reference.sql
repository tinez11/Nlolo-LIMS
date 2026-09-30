-- The insurer's OWN reference for a client, supplied rather than minted.
--
-- Not an identity document and not a platform id. `party_id` identifies a client to the
-- platform and `id_number` identifies a person to the state; neither is the number the people
-- running this business already use for the same client in their own records. Without somewhere
-- to put it, reconciling a platform client against an existing book means matching on name and
-- date of birth, which is exactly the matching that goes wrong.
--
-- OPTIONAL, deliberately. It is for reconciliation by whoever is using the platform, so a client
-- registered by an agent who has no such number is a complete client, not an incomplete one. A
-- required field here would be filled with a fabricated value within a week.
--
-- SUPPLIED, not generated: the number already exists in the insurer's own system and the point
-- is to carry it, not to invent a second one alongside it (client, 2026-09-30).
ALTER TABLE party.party
    ADD COLUMN client_reference VARCHAR(50);

-- Unique WHERE PRESENT, per tenant. Two clients sharing one reference would defeat the only
-- thing the column is for, and a partial index is what lets it stay optional: NULLs are not
-- compared, so any number of clients may have none.
CREATE UNIQUE INDEX ux_party_client_reference
    ON party.party (tenant_id, client_reference)
    WHERE client_reference IS NOT NULL;

-- Blank is not a reference. Without this a caller sending "" would occupy the unique index and
-- refuse the empty string to everybody else, which is a confusing way to discover a typo.
ALTER TABLE party.party
    ADD CONSTRAINT chk_party_client_reference_not_blank
        CHECK (client_reference IS NULL OR length(btrim(client_reference)) > 0);
