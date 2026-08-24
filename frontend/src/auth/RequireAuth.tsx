import { useEffect, useRef, type ReactNode } from 'react';
import { useAuth } from 'react-oidc-context';
import { Navigate, useLocation } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { LoadingBlock } from '@/components/states';
import { readReturnTo, returnToState } from './returnTo';
import { REALM_CONFIG, type Realm } from './realms';

/**
 * Gate for a realm's routes.
 *
 * Because tokens are held in memory only, a page reload always starts
 * unauthenticated. That is the intended trade: instead of a refresh token sitting
 * in web storage for an XSS to lift, we redirect to Keycloak, which recognises its
 * own SSO cookie and bounces straight back without prompting.
 *
 * The redirect is fired at most once per mount. Re-firing on every render would
 * produce a redirect loop the user cannot escape if the SSO session is genuinely
 * gone.
 */
export function RequireAuth({ realm, children }: { realm: Realm; children: ReactNode }) {
  const auth = useAuth();
  const attempted = useRef(false);

  useEffect(() => {
    if (auth.isLoading || auth.isAuthenticated || auth.error) return;
    if (auth.activeNavigator) return; // a redirect/renew is already in flight
    if (attempted.current) return;
    attempted.current = true;
    // Carry the intended destination through Keycloak so the callback can restore
    // it. Without this every deep link and bookmark collapses to the realm root,
    // and because tokens are memory-only that happens on EVERY load, not just the
    // first sign-in.
    void auth
      .signinRedirect({
        state: returnToState(),
      })
      .catch(() => undefined);
  }, [auth]);

  if (auth.error) {
    return (
      <div className="grid h-full place-items-center px-6">
        <div className="max-w-sm text-center">
          <p className="text-sm font-medium">Could not sign in</p>
          <p className="mt-1 text-xs text-muted-foreground">{auth.error.message}</p>
          <p className="mt-3 text-xs text-subtle-foreground">
            Check that Keycloak is running and that the <code>lifeplatform-spa</code> client
            exists in the <code>{REALM_CONFIG[realm].realm}</code> realm.
          </p>
          <Button
            className="mt-4"
            onClick={() => {
              attempted.current = false;
              void auth
                .signinRedirect({
                  state: returnToState(),
                })
                .catch(() => undefined);
            }}
          >
            Try again
          </Button>
        </div>
      </div>
    );
  }

  if (!auth.isAuthenticated) {
    return <LoadingBlock label="Signing in" />;
  }

  return <RestoreLocation realm={realm}>{children}</RestoreLocation>;
}

/**
 * Sends the user to the destination they originally asked for.
 *
 * The auth round-trip always lands on the realm's registered redirect_uri, so
 * without this every deep link collapses to the realm root -- and because tokens are
 * memory-only that happens on EVERY load, not just the first sign-in.
 *
 * Two things here were arrived at by measurement rather than reasoning, and both
 * matter:
 *
 * 1. It is DECLARATIVE. An effect calling `navigate()` loses a race: while the
 *    router is still at the realm root it matches the index route, whose own
 *    `<Navigate to="policies">` effect fires from deeper in the tree and clobbers
 *    the restored query string. Rendering `<Navigate>` INSTEAD of the children
 *    means the index route never mounts, so there is nothing to race.
 *
 * 2. It triggers only while the OIDC callback parameters are still in the router's
 *    location. That is what makes it ONE-SHOT without any state to track: `code`
 *    and `state` appear on exactly one render and never again, so the redirect
 *    cannot re-fire. Gating on `returnTo !== here` instead loops forever -- once the
 *    app legitimately navigates on from `/staff` to `/staff/policies`, a stale
 *    `returnTo` of `/staff` keeps pulling it back.
 *
 * Note that onSigninCallback's history.replaceState does NOT clear these from the
 * router's own location, which is precisely why they are still readable here.
 */
function RestoreLocation({ realm, children }: { realm: Realm; children: ReactNode }) {
  const auth = useAuth();
  const location = useLocation();

  const params = new URLSearchParams(location.search);
  const isOidcCallback = params.has('code') && params.has('state');

  if (isOidcCallback) {
    // The callback URL is never a real destination, so always leave it.
    return <Navigate to={readReturnTo(auth.user) ?? `/${REALM_CONFIG[realm].slug}`} replace />;
  }

  return <>{children}</>;
}
