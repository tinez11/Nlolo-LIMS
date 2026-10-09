/**
 * The last good response to a request, so a record tab opened a second time shows what it showed
 * the first time while it fetches again (2026-10-09, review decision D4).
 *
 * Record tabs unmount when you leave them (DESIGN.md, "Two shapes for a record"), and the panels
 * that load through a store already keep their data across that -- `loading(previous)` holds the
 * last result. The few that fetch into their own `useState` started empty on every mount, so
 * flicking from Billing to Overview and back drew the placeholder again for a schedule that had
 * not changed.
 *
 * Deliberately small: a module-level map, read once to seed `useState` and written on success.
 * Every mount still fetches, so nothing here decides freshness -- it only fills the wait. It lives
 * as long as the page: signing out leaves through Keycloak and reloads, which clears it. Capped so
 * a long day of opening records cannot grow it without bound.
 */
const LIMIT = 50;
const responses = new Map<string, unknown>();

export function remembered<T>(key: string): T | null {
  return responses.has(key) ? (responses.get(key) as T) : null;
}

export function remember<T>(key: string, value: T): T {
  // Re-inserted, so the map's insertion order is least-recently-written first.
  responses.delete(key);
  responses.set(key, value);
  if (responses.size > LIMIT) {
    const oldest = responses.keys().next().value;
    if (oldest !== undefined) responses.delete(oldest);
  }
  return value;
}

/** For tests only: start from nothing. */
export function forgetAll(): void {
  responses.clear();
}
