/**
 * Validation patterns mirroring exact backend regexes/formats, kept in one place
 * so a client-side rule can't silently drift from the server rule it exists to
 * anticipate. Every form on this platform that validates a UUID, a policy number,
 * or a plain calendar date should use these rather than a local copy.
 */

/** Matches `party/infrastructure` and `policyloan`/`claims` UUID path variables. */
export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** `openapi-common.yaml#/components/schemas/PolicyNumberRef`. */
export const POLICY_NUMBER_PATTERN = /^[A-Z0-9-]{6,20}$/;

/** What a native `<input type="date">` always produces when filled. */
export const ISO_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
