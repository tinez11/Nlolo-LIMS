import {
  createBrowserRouter,
  createRoutesFromElements,
  Navigate,
  Outlet,
  Route,
  RouterProvider,
} from 'react-router-dom';
import { PlatformAuthProvider } from '@/auth/PlatformAuthProvider';
import { RequireAuth } from '@/auth/RequireAuth';
import { RequireWorkspace } from '@/auth/RequireWorkspace';
import { REALM_CONFIG, type Realm } from '@/auth/realms';
import { AppShell } from '@/components/AppShell';
import { RealmHome } from '@/components/RealmHome';
import { RequirePermission, StaffAccessGate } from '@/components/StaffAccess';
import { WorkspaceHome } from '@/features/WorkspaceHome';
import { hasRealmFallback } from '@/entryPath';
import { REALM_HOME, SCREENS } from '@/screens';
import { Suspense } from 'react';
import { LoadingBlock } from '@/components/states';
import { CustomerProductsPage } from '@/features/customer/CustomerProductsPage';
import { CustomerQuotePage } from '@/features/customer/CustomerQuotePage';
import { PublicQuoteApp } from '@/lazyPages';

/**
 * Workspace-scoped routes, built from the screen manifest in `@/screens`.
 *
 * One Keycloak realm for everyone since 2026-11-06 (universal login), so one OIDC provider is mounted once, above the
 * router. "/" is the sign-in for everyone; the `/staff`, `/agents`, `/customers` prefixes name the WORKSPACE a page is
 * in, which the console sends the backend as `X-Workspace`. Each workspace's pages are lazily loaded, so a customer
 * still never downloads the finance bundle.
 *
 * A workspace with no screens gets no subtree at all: /regulators is deliberately absent rather than stubbed, because
 * an authenticating route into an empty app is worse than a 404.
 */
const realms = (Object.keys(REALM_CONFIG) as Realm[]).filter(
  (realm) => SCREENS[realm].length > 0,
);

/*
  A DATA router (2026-10-09, review C1), not <BrowserRouter>. The route tree is the same JSX as
  before; what changes is that `useBlocker` works, and `UnsavedGuard` needs it -- a sidebar click
  on a half-built product version used to discard it without a word. Built once, at module level,
  as React Router asks: the manifest it reads is static.
*/
const router = createBrowserRouter(
  createRoutesFromElements(
    <>
        {/* One sign-in for everyone (2026-11-06): "/" signs in, then opens the account's workspace or offers a choice. */}
        <Route path="/" element={<RequireAuth><WorkspaceHome /></RequireAuth>} />

        {/* The public quote link (2026-11-02): no realm, no sign-in. The customer portal's own Get-cover and price
            screens, priced on what the visitor types -- see PublicQuoteApp for what guards it. */}
        <Route path="/quote" element={<Suspense fallback={<LoadingBlock />}><PublicQuoteApp /></Suspense>}>
          <Route index element={<CustomerProductsPage />} />
          <Route path=":productId" element={<CustomerQuotePage />} />
        </Route>

        {realms.map((realm) => {
          const home = REALM_HOME[realm];
          return (
            <Route key={realm} path={`/${realm}`} element={<RealmSubtree realm={realm} />}>
              {home && <Route index element={<RealmHome realm={realm} />} />}
              {SCREENS[realm].map((screen) => (
                <Route
                  key={screen.path}
                  path={screen.path}
                  element={
                    screen.permission ? (
                      <RequirePermission permissions={screen.permission}>{screen.element}</RequirePermission>
                    ) : (
                      screen.element
                    )
                  }
                />
              ))}
              {/* A mistyped path inside a realm belongs to that realm's home, not to the site entrance: a customer
                  who fat-fingers /customers/polices should not land on a sign-in that is not theirs. Only where the
                  realm HAS a home -- see hasRealmFallback for the redirect loop this guard prevents. */}
              {hasRealmFallback(home) && <Route path="*" element={<Navigate to={`/${realm}`} replace />} />}
            </Route>
          );
        })}

        <Route path="*" element={<Navigate to="/" replace />} />
    </>,
  ),
);

export function App() {
  return (
    <PlatformAuthProvider>
      <RouterProvider router={router} />
    </PlatformAuthProvider>
  );
}

function RealmSubtree({ realm }: { realm: Realm }) {
  return (
    <RequireAuth>
      <RequireWorkspace realm={realm}>
        {/* Staff pages are drawn for what the person holds, so their access arrives first. */}
        {realm === 'staff' ? (
          <StaffAccessGate>
            <AppShell realm={realm}>
              <Outlet />
            </AppShell>
          </StaffAccessGate>
        ) : (
          <AppShell realm={realm}>
            <Outlet />
          </AppShell>
        )}
      </RequireWorkspace>
    </RequireAuth>
  );
}
