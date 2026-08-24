import { useEffect, useRef, type ReactNode } from 'react';
import { useAuth } from 'react-oidc-context';
import { Button } from '@/components/ui/button';
import { LoadingBlock } from '@/components/states';
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
    void auth.signinRedirect().catch(() => undefined);
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
              void auth.signinRedirect().catch(() => undefined);
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

  return <>{children}</>;
}
