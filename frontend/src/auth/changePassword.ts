import { returnToState } from './returnTo';

/**
 * "Change password" (2026-11-06, universal login step 3): Keycloak's own application-initiated action, not a form of the
 * console's. Keycloak asks for the current password again when the sign-in is more than a few minutes old, shows the
 * password rules on the themed page, and ticks "sign out my other devices" by default; the platform then texts the
 * person that their password changed. The console only asks for it, and comes back to the page it was on.
 */
export function changePasswordArgs(): { extraQueryParams: Record<string, string>; state: ReturnType<typeof returnToState> } {
  return { extraQueryParams: { kc_action: 'UPDATE_PASSWORD' }, state: returnToState() };
}
