import {
  createBrowserRouter,
  createRoutesFromElements,
  Navigate,
  Outlet,
  Route,
  RouterProvider,
} from 'react-router-dom';
import { RealmAuthProvider } from '@/auth/RealmAuthProvider';
import { RequireAuth } from '@/auth/RequireAuth';
import { REALM_CONFIG, type Realm } from '@/auth/realms';
import { AppShell } from '@/components/AppShell';
import { RealmHome } from '@/components/RealmHome';
import { RealmPicker } from '@/features/RealmPicker';
import { REALM_HOME, SCREENS } from '@/screens';

/**
 * Realm-scoped routes, built from the screen manifest in `@/screens`.
 *
 * `react-oidc-context`'s AuthProvider takes exactly ONE authority, and the realm
 * must therefore be known before the user is authenticated -- a chicken-and-egg the
 * URL resolves. One provider is mounted per realm subtree, which also means a
 * customer never downloads the finance bundle, and no stale token from another
 * realm can reach the wrong provider.
 *
 * A realm with no screens gets no subtree at all: /customers and /regulators are
 * deliberately absent rather than stubbed, because an authenticating route into
 * an empty app is worse than a 404.
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
        <Route path="/" element={<RealmPicker />} />

        {realms.map((realm) => {
          const home = REALM_HOME[realm];
          return (
            <Route key={realm} path={`/${realm}`} element={<RealmSubtree realm={realm} />}>
              {home && <Route index element={<RealmHome realm={realm} />} />}
              {SCREENS[realm].map((screen) => (
                <Route key={screen.path} path={screen.path} element={screen.element} />
              ))}
            </Route>
          );
        })}

        <Route path="*" element={<Navigate to="/" replace />} />
    </>,
  ),
);

export function App() {
  return <RouterProvider router={router} />;
}

function RealmSubtree({ realm }: { realm: Realm }) {
  return (
    <RealmAuthProvider realm={realm}>
      <RequireAuth realm={realm}>
        <AppShell realm={realm}>
          <Outlet />
        </AppShell>
      </RequireAuth>
    </RealmAuthProvider>
  );
}
