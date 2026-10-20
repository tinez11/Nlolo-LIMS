import type { User } from 'oidc-client-ts';

/**
 * The pre-login destination, carried through Keycloak in the OIDC `state`.
 *
 * Needed because the auth round-trip always lands on the realm's single registered
 * redirect_uri. Without it every deep link and bookmark collapses to the realm root
 * -- and since tokens are held in memory only, that happens on EVERY page load, not
 * just the first sign-in.
 */

/**
 * Build the `state` payload to hand to signinRedirect.
 *
 * The page's router state goes with it (2026-10-20) -- React Router keeps it in `history.state.usr` -- so a page that
 * was opened with one, a record's way back to the client it came from, keeps it through the reload's round trip
 * rather than only its address. It is data: `useBreadcrumb` validates what it reads, as `readReturnTo` does the path.
 */
export function returnToState(): { returnTo: string; routerState?: unknown } {
  const routerState = (window.history.state as { usr?: unknown } | null)?.usr;
  return {
    returnTo: `${window.location.pathname}${window.location.search}`,
    ...(routerState != null ? { routerState } : {}),
  };
}

/** The router state carried through the round trip, or null. */
export function readReturnRouterState(user: User | null | undefined): unknown {
  const state = user && typeof user === 'object' ? (user as { state?: unknown }).state : undefined;
  if (typeof state !== 'object' || state === null) return null;
  return (state as { routerState?: unknown }).routerState ?? null;
}

/**
 * Read it back, defensively.
 *
 * Only same-origin ROOT-RELATIVE paths are honoured. `state` makes a round trip
 * through the browser, so treating it as a trusted navigation target would be an
 * open redirect: `//evil.example` is a protocol-relative ABSOLUTE url that a router
 * would happily follow off-site.
 */
export function readReturnTo(user: User | null | undefined): string | null {
  const state = user && typeof user === 'object' ? (user as { state?: unknown }).state : undefined;
  if (typeof state !== 'object' || state === null) return null;
  const returnTo = (state as { returnTo?: unknown }).returnTo;
  if (typeof returnTo !== 'string') return null;
  if (!returnTo.startsWith('/') || returnTo.startsWith('//')) return null;
  return returnTo;
}
