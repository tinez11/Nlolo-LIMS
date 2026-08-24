import { useEffect, useMemo, type ReactNode } from 'react';
import { AuthProvider, useAuth } from 'react-oidc-context';
import { WebStorageStateStore } from 'oidc-client-ts';
import { setAccessTokenProvider, setUnauthenticatedHandler } from '@/lib/http';
import { CLIENT_ID, issuerFor, REALM_CONFIG, type Realm } from './realms';
import { createStateStore, createUserStore } from './userStore';

/**
 * Mounts one OIDC provider for one realm.
 *
 * `AuthProvider` accepts a single `authority`, so this is instantiated per
 * realm-scoped route subtree rather than once at the app root. Keeping exactly one
 * mounted at a time is what prevents a stale token from another realm being handed
 * to the wrong provider.
 */
export function RealmAuthProvider({
  realm,
  children,
}: {
  realm: Realm;
  children: ReactNode;
}) {
  const config = REALM_CONFIG[realm];

  // Recreated only when the realm changes; the stores are stateful.
  const settings = useMemo(
    () => ({
      authority: issuerFor(realm),
      client_id: CLIENT_ID,
      // Land back inside this realm's own route subtree.
      redirect_uri: `${window.location.origin}/${config.slug}`,
      post_logout_redirect_uri: `${window.location.origin}/`,
      response_type: 'code',
      scope: 'openid profile email',
      // Tokens in memory; only the single-use PKCE verifier touches sessionStorage.
      userStore: createUserStore(),
      stateStore: createStateStore(realm),
      automaticSilentRenew: true,
      // Keycloak's userinfo adds nothing the access token does not already carry,
      // and it costs a request per load.
      loadUserInfo: false,
      onSigninCallback: () => {
        // Strip ?code=&state= so a reload is not a replayed callback.
        window.history.replaceState({}, '', `/${config.slug}`);
      },
    }),
    [realm, config.slug],
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

/** Re-exported so consumers do not each import from two places. */
export { WebStorageStateStore };
