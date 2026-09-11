-- db-migrations/party/V4__registered_by_agent.sql
-- Record WHICH AGENT registered a client, in a form that can reach commission.
--
-- THE DEFECT THIS CLOSES. An agent who registers a customer earns nothing on that customer's
-- business. Commission accrues in distribution off `PolicyActivated.agentOfRecordId`, and
-- agentOfRecordId is whatever the form that opened the case happened to supply -- null for a
-- direct sale. Meanwhile "who registered this client" WAS already recorded, in `created_by`,
-- as the registering user's Keycloak SUBJECT. Nothing maps a subject to an agent: the agent
-- realm resolves a caller by a `party_id` token claim against distribution.agent_profile, and
-- a subject is not a party id. So the link existed and could never be followed, and an agent
-- earned only when somebody separately named them agent of record on each case.
--
-- WHY A PARTY ID AND NOT AN AGENT ID. The party module may depend on document and refdata and
-- nothing else -- emphatically not on distribution -- so it cannot resolve, validate or even
-- name an agent_id without breaking its own boundary. A party id is just a party id: party
-- owns that concept, the column needs no foreign key into another module's table, and the
-- resolution to an agent happens in policy, which is the one module allowed to see both.
--
-- NULL IS A REAL ANSWER, not missing data: a client registered by staff, or one who registered
-- themselves in the customer realm, has no registering agent and their business is direct or
-- attributed by hand. Every client registered before this column existed is null too, and is
-- deliberately not backfilled -- created_by holds subjects that cannot be resolved to agents
-- reliably, and inventing an attribution would be inventing who gets paid.
ALTER TABLE party.party
    ADD COLUMN registered_by_party_id UUID;

COMMENT ON COLUMN party.party.registered_by_party_id IS
    'The PARTY id of the agent who registered this client, taken from the registering token''s '
    'party_id claim. Resolved to an agent (and therefore to commission) by the policy module at '
    'issuance. NULL when staff registered the client or the client registered themselves, which '
    'is a real state and not missing data.';

-- "Everyone this agent brought in" is the read this exists for -- the agent's own book, and the
-- resolution at issuance. Partial, because the column is null for every client who has no agent.
CREATE INDEX idx_party_registered_by_agent
    ON party.party (tenant_id, registered_by_party_id)
    WHERE registered_by_party_id IS NOT NULL;
