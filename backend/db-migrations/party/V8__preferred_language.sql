-- db-migrations/party/V8__preferred_language.sql
-- The language a client is written to in (2026-11-08). Every message went out in Swahili, because nothing said
-- otherwise, and the English templates were never used. Swahili stays the default; staff set English on the client.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE party.party ADD COLUMN IF NOT EXISTS preferred_language VARCHAR(2) NOT NULL DEFAULT 'sw';
ALTER TABLE party.party DROP CONSTRAINT IF EXISTS party_preferred_language_known;
ALTER TABLE party.party ADD CONSTRAINT party_preferred_language_known CHECK (preferred_language IN ('sw', 'en'));
