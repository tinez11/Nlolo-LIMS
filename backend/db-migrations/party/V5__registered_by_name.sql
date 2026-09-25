-- db-migrations/party/V5__registered_by_name.sql
-- Record WHO registered a client, by name, at the moment they did it.
--
-- THE DEFECT THIS CLOSES. `created_by` holds the registering login's Keycloak SUBJECT -- a uuid
-- minted by Keycloak at realm import, which nothing on this platform can turn back into a
-- person. The client record showed it raw under "Registered by", so a reviewer read
-- "30b2ffb8-284e-..." where they needed "Juma Senior". The same gap in claims was closed the same
-- way: the assessor's display name is captured from their token when the assessment is written
-- (claims `assessor_name`), because asking Keycloak later is a dependency on an identity
-- provider this module has no business calling, and a name at the time is the honest record.
--
-- `created_by` is NOT replaced. It is what an agents-realm caller is scoped on -- an agent reads
-- only the clients IT registered -- and a display name is neither unique nor stable enough to
-- scope on.
--
-- BACKFILL, only where the answer is known exactly. A client an agent registered carries that
-- agent's own party id (V4), and the agent's party record holds their name, so those rows are
-- filled from it. Everything else -- staff registrations, self-registrations, and the clients
-- agents registered before V4 existed -- stays NULL, which the console states as "name not
-- recorded" rather than guessing from a subject.
ALTER TABLE party.party
    ADD COLUMN created_by_name VARCHAR(255);
COMMENT ON COLUMN party.party.created_by_name IS
    'Display name of whoever registered this record, captured from their token at registration. '
    'For display only -- created_by (the subject) is what access is scoped on. NULL for rows '
    'registered before V5 whose registrar could not be named exactly.';

UPDATE party.party p
   SET created_by_name = agent.display_name
  FROM party.party agent
 WHERE p.registered_by_party_id = agent.party_id
   AND p.tenant_id = agent.tenant_id
   AND p.created_by_name IS NULL;
