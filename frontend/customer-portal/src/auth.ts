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
      if (Date.now() < current.expiresAt) {
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
