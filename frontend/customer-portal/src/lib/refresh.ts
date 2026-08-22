/**
 * Keycloak refresh-token exchange, isolated from NextAuth so it can be tested directly.
 *
 * Access tokens on the `customers` realm live ~5 minutes (the realm sets no accessTokenLifespan
 * override, so Keycloak's server default applies), which makes refresh a day-one requirement
 * rather than a refinement. Failure is returned as `error: 'RefreshFailed'` rather than thrown:
 * the caller's correct response is to end the session and re-authenticate, not to surface an
 * error to the user, because an idle-timeout logout is expected behaviour.
 */
export type PortalToken = {
  accessToken: string;
  refreshToken: string;
  expiresAt: number;
  error?: 'RefreshFailed';
};

export type RefreshDeps = {
  issuer: string;
  clientId: string;
  clientSecret: string;
  fetchImpl?: typeof fetch;
  now?: () => number;
};

/**
 * Refresh this many milliseconds BEFORE the access token actually expires, not at expiry.
 *
 * `middleware.ts`'s `auth()` call runs the `jwt` callback in `auth.ts` and writes the refreshed
 * session cookie onto the OUTGOING response — but a Route Handler running later in that SAME
 * request reads `getToken()` off the INCOMING request's headers, which still carry the
 * pre-refresh cookie (a middleware `set-cookie` is never visible to a downstream handler in the
 * same request). If we refresh exactly at expiry, that same-request read forwards an
 * already-expired token to the backend and gets a spurious 401. Refreshing with this skew means
 * the token minted here is still genuinely valid for another minute, so the same-request read
 * still succeeds; the NEXT request picks up the new cookie.
 */
export const REFRESH_SKEW_MS = 60_000;

/**
 * True when the token should be refreshed now — skewed BEFORE actual expiry, not at it, so a
 * same-request Route Handler reading the pre-refresh token still holds one that's genuinely
 * still valid. Pure and parameterized on `now` so it is testable without mocking `Date.now()`
 * globally, mirroring `refreshAccessToken` above.
 */
export function needsRefresh(expiresAt: number, now: number): boolean {
  return now >= expiresAt - REFRESH_SKEW_MS;
}

export async function refreshAccessToken(token: PortalToken, deps: RefreshDeps): Promise<PortalToken> {
  const doFetch = deps.fetchImpl ?? fetch;
  const now = deps.now ?? Date.now;

  try {
    const response = await doFetch(`${deps.issuer}/protocol/openid-connect/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'refresh_token',
        refresh_token: token.refreshToken,
        client_id: deps.clientId,
        client_secret: deps.clientSecret,
      }).toString(),
    });

    if (!response.ok) {
      return { ...token, error: 'RefreshFailed' };
    }

    const payload = (await response.json()) as {
      access_token: string;
      refresh_token?: string;
      expires_in: number;
    };

    return {
      accessToken: payload.access_token,
      // Keycloak may or may not rotate the refresh token; keep the old one when it does not.
      refreshToken: payload.refresh_token ?? token.refreshToken,
      expiresAt: now() + payload.expires_in * 1000,
    };
  } catch {
    return { ...token, error: 'RefreshFailed' };
  }
}
