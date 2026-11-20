import { ISSUER } from './realms';

/** Authentik's change-password flow (Authentik migration, ticket 06 of the cut-over, 2026-11-19). */
const FLOW = 'lifeplatform-change-password';

/**
 * "Change password": Authentik's own flow, not a form of the console's. It asks for the current password, then a new
 * one twice under the platform's password rules, and the platform then texts the person that their password changed.
 * Afterwards Authentik sends the browser back to this page: it accepts only a page path on the console's own origin, so
 * the query string is not carried.
 *
 * (Until 2026-11-19 this was Keycloak's application-initiated update-password action.)
 */
export function changePasswordUrl(): string {
  const authOrigin = new URL(ISSUER).origin;
  return `${authOrigin}/if/flow/${FLOW}/?return_to=${window.location.pathname}`;
}
