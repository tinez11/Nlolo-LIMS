-- 2026-11-14, durable events: Spring Modulith's event publication registry. Every delivery of an event to an
-- after-commit listener is written here in the same transaction as the business change that raised it, and marked
-- complete when the listener finishes. A crash, a restart or a failing listener leaves it incomplete, and the platform
-- delivers it again (at start-up and every ten minutes). Before this, a commission accrual, a cession or a billing
-- update lost after commit was lost silently.
--
-- The table is the registry's own shape (spring-modulith-events-jdbc 1.2.5, schema-postgresql.sql) and lives in the
-- default schema because that version reads it from there. No tenant_id: the tenant travels inside the event, and only
-- the platform reads this table. Completed rows are deleted after seven days. Re-runnable.

CREATE TABLE IF NOT EXISTS event_publication
(
  id               UUID NOT NULL,
  listener_id      TEXT NOT NULL,
  event_type       TEXT NOT NULL,
  serialized_event TEXT NOT NULL,
  publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date  TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON event_publication (completion_date);

GRANT SELECT, INSERT, UPDATE, DELETE ON event_publication TO app_role;
