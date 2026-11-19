import { useEffect, useRef } from 'react';
import type { User } from 'oidc-client-ts';

/**
 * The 10-hour session cap (carried over from Keycloak's SSO session maximum; Authentik migration, 2026-11-19).
 *
 * Authentik ends its own sign-in session after 10 hours, but the console holds a refresh token that silent renew keeps
 * using, so a console left open would outlive that session. The token's `auth_time` names the moment the person last
 * typed their password; it survives every renew, so the count is from that moment, never from the latest token.
 */
export const SESSION_CAP_MS = 10 * 60 * 60 * 1000;

/** How often an open console re-checks the clock. A timer alone can fire late after the machine sleeps. */
const CHECK_EVERY_MS = 60 * 1000;

/** Milliseconds left before the cap, zero or less once reached; null with no user or no `auth_time` to count from. */
export function msUntilSessionCap(user: Pick<User, 'profile'> | null | undefined, now: number = Date.now()): number | null {
  const authTime = user?.profile?.auth_time;
  return typeof authTime === 'number' ? leftFrom(authTime, now) : null;
}

const leftFrom = (authTimeSeconds: number, now: number) => authTimeSeconds * 1000 + SESSION_CAP_MS - now;

/** Calls `signOut` once the signed-in session passes the cap: at once if it already has, otherwise when it does. */
export function useSessionCap(user: Pick<User, 'profile'> | null | undefined, signOut: () => void) {
  const signOutRef = useRef(signOut);
  useEffect(() => {
    signOutRef.current = signOut;
  }, [signOut]);

  const authTime = user?.profile?.auth_time;

  useEffect(() => {
    if (typeof authTime !== 'number') return undefined;
    let done = false;
    const check = () => {
      if (done) return;
      if (leftFrom(authTime, Date.now()) <= 0) {
        done = true;
        signOutRef.current();
      }
    };
    check();
    const timer = window.setInterval(check, CHECK_EVERY_MS);
    document.addEventListener('visibilitychange', check);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', check);
    };
  }, [authTime]);
}
