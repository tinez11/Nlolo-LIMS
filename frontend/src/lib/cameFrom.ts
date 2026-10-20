import { useLocation } from 'react-router-dom';

export interface Crumb {
  label: string;
  to: string;
}

/**
 * The way back to the record a page was opened from (2026-10-20).
 *
 * A record's breadcrumb says where it lives -- Policies, Claims, Underwriting -- and that left a
 * reader who opened a policy from a client's record with no way back to the client: the crumb went
 * to the list of every policy. A link from a record now carries that record's own trail in router
 * state, and the page it opens shows that trail instead. Opened any other way -- from a list, a
 * search, a typed address -- the page keeps its own crumb.
 *
 * Router state lives in the browser's history entry, so the trail survives a reload and Back.
 */
export function cameFrom(trail: Crumb[]): { cameFrom: Crumb[] } {
  return { cameFrom: trail };
}

/** The trail this page was opened with, or `where` -- the page's own crumb -- when it carries none. */
export function useBreadcrumb(where: Crumb[]): Crumb[] {
  const state = useLocation().state as { cameFrom?: unknown } | null;
  const trail = state?.cameFrom;
  const valid =
    Array.isArray(trail) &&
    trail.length > 0 &&
    trail.every(
      (c: unknown) =>
        typeof c === 'object' && c !== null &&
        typeof (c as Crumb).label === 'string' &&
        typeof (c as Crumb).to === 'string' && (c as Crumb).to.startsWith('/'),
    );
  return valid ? (trail as Crumb[]) : where;
}
