import NextAuth from 'next-auth';
import Keycloak from 'next-auth/providers/keycloak';
import { refreshAccessToken, type PortalToken } from '@/lib/refresh';

/**
 * `customers` realm only, confidential client. The client secret is read here — on the server —
 * and never reaches the browser, which is the whole reason this portal is a BFF: the backend has
 * no CORS policy at all, deliberately, so a browser could not call it anyway.
 */
const ISSUER = process.env.KEYCLOAK_ISSUER!;
const CLIENT_ID = process.env.KEYCLOAK_CLIENT_ID!;
const CLIENT_SECRET = process.env.KEYCLOAK_CLIENT_SECRET!;

/**
 * Refresh this many milliseconds BEFORE the access token actually expires, not at expiry.
 *
 * `middleware.ts`'s `auth()` call runs the `jwt` callback below and writes the refreshed
 * session cookie onto the OUTGOING response — but a Route Handler running later in that SAME
 * request reads `getToken()` off the INCOMING request's headers, which still carry the
 * pre-refresh cookie (a middleware `set-cookie` is never visible to a downstream handler in the
 * same request). If we refresh exactly at expiry, that same-request read forwards an
 * already-expired token to the backend and gets a spurious 401. Refreshing with this skew means
 * the token minted here is still genuinely valid for another minute, so the same-request read
 * still succeeds; the NEXT request picks up the new cookie.
 */
const REFRESH_SKEW_MS = 60_000;

export const { handlers, auth, signIn, signOut } = NextAuth({
  providers: [
    Keycloak({ issuer: ISSUER, clientId: CLIENT_ID, clientSecret: CLIENT_SECRET }),
  ],
  session: { strategy: 'jwt' },
  callbacks: {
    async jwt({ token, account }) {
      // Initial sign-in: persist the triple the refresh cycle needs.
      if (account) {
        return {
          ...token,
          accessToken: account.access_token as string,
          refreshToken: account.refresh_token as string,
          expiresAt: Date.now() + (account.expires_in as number) * 1000,
        };
      }

      const current = token as unknown as PortalToken & Record<string, unknown>;
      if (!current.refreshToken) {
        return token;
      }
      if (Date.now() < current.expiresAt - REFRESH_SKEW_MS) {
        return token;
      }

      const refreshed = await refreshAccessToken(
        {
          accessToken: current.accessToken,
          refreshToken: current.refreshToken,
          expiresAt: current.expiresAt,
        },
        { issuer: ISSUER, clientId: CLIENT_ID, clientSecret: CLIENT_SECRET },
      );
      return { ...token, ...refreshed };
    },

    async session({ session, token }) {
      // Surface ONLY the failure flag to the client, never the tokens themselves.
      (session as unknown as { error?: string }).error =
        (token as unknown as PortalToken).error;
      return session;
    },
  },
});
