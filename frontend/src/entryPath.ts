/**
 * Whether a workspace may have a catch-all route sending unknown paths to its own home.
 *
 * Only when it HAS a home. A workspace whose `REALM_HOME` is null (regulators) has no index route, so a splat would
 * match the workspace's own root -- `/regulators` matches `*` with an empty remainder -- and redirect it to itself, for
 * ever. Without the catch-all such a path falls through to the site entrance instead, which is what it did before.
 *
 * (The bare site address used to be decided here too -- a realm picker in development, the customer portal in
 * production. Since 2026-11-06 "/" is the one sign-in for everyone; see App.tsx.)
 */
export function hasRealmFallback(home: string | null): boolean {
  return home !== null;
}
