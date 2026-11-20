import type { AuthContextProps } from 'react-oidc-context';

/**
 * Where the console revokes tokens (Authentik migration, 2026-11-19). Authentik 2026.8.5's revoke endpoint sends no
 * CORS headers, so a browser on the console's origin cannot call it directly. The console calls this same-origin path
 * instead; the Vite dev server forwards it to Authentik's `/application/o/revoke/`, and so does Caddy on the app domain.
 */
export const REVOCATION_PATH = '/oauth/revoke/';

/**
 * Signing out: revoke the access and refresh tokens, then end the Authentik session.
 *
 * Authentik keeps a refresh token alive after its own sign-in session ends, so the revoke is what makes a refresh
 * token lifted before sign-out useless. A failed revoke must not keep anyone signed in, so the redirect follows
 * either way (oidc-client-ts's own `revokeTokensOnSignout` would abandon the sign-out instead).
 */
export async function signOut(auth: Pick<AuthContextProps, 'revokeTokens' | 'signoutRedirect'>): Promise<void> {
  try {
    await auth.revokeTokens();
  } catch {
    // The redirect below still ends the session; the refresh token then lapses within its 30 idle minutes.
  }
  await auth.signoutRedirect();
}
