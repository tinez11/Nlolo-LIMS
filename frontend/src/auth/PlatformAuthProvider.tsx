import { useEffect, useMemo, type ReactNode } from 'react';
import { AuthProvider, useAuth } from 'react-oidc-context';
import { setAccessTokenProvider, setUnauthenticatedHandler } from '@/lib/http';
import { CLIENT_ID, ISSUER } from './realms';
import { createStateStore, createUserStore } from './userStore';

/**
 * The one OIDC provider, mounted once at the root (2026-11-06, universal login).
 *
 * Until then there were four Keycloak realms, and `AuthProvider` takes a single authority, so one provider was mounted
 * per realm-scoped route subtree. With one realm for everyone, a single provider serves every workspace; the URL
 * prefix now names the workspace, not the realm (see `realms.ts`).
 */
export function PlatformAuthProvider({ children }: { children: ReactNode }) {
  // Built once; the stores are stateful.
  const settings = useMemo(
    () => ({
      authority: ISSUER,
      client_id: CLIENT_ID,
      // Everyone lands on "/", which restores the page they asked for or routes by workspace (WorkspaceHome).
      redirect_uri: `${window.location.origin}/`,
      post_logout_redirect_uri: `${window.location.origin}/`,
      response_type: 'code',
      scope: 'openid profile email',
      // Tokens in memory; only the single-use PKCE verifier touches sessionStorage.
      userStore: createUserStore(),
      stateStore: createStateStore(),
      automaticSilentRenew: true,
      // Keycloak's userinfo adds nothing the access token does not already carry,
      // and it costs a request per load.
      loadUserInfo: false,
      onSigninCallback: () => {
        // Strip ?code=&state= only, so a reload is not a replayed callback. The URL
        // is NOT rewritten to the final destination here: history.replaceState does
        // not notify React Router, so doing that would change the address bar while
        // the router kept rendering the old route. RestoreLocation (RequireAuth.tsx)
        // performs a real navigation instead.
        window.history.replaceState({}, '', window.location.pathname);
      },
    }),
    [],
  );

  return (
    <AuthProvider {...settings}>
      <HttpAuthBridge>{children}</HttpAuthBridge>
    </AuthProvider>
  );
}

/**
 * Hands the current access token to the Axios layer. A registration rather than an
 * import so `lib/http` stays free of any dependency on React or the auth library.
 */
function HttpAuthBridge({ children }: { children: ReactNode }) {
  const auth = useAuth();

  useEffect(() => {
    setAccessTokenProvider(() => auth.user?.access_token);
  }, [auth.user]);

  useEffect(() => {
    setUnauthenticatedHandler(() => {
      // A 401 despite a live session means the access token expired between renews.
      // Try silently; if the SSO session is genuinely gone, RequireAuth takes over.
      void auth.signinSilent().catch(() => undefined);
    });
  }, [auth]);

  return <>{children}</>;
}
