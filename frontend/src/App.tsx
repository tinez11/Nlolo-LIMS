import { BrowserRouter, Navigate, Outlet, Route, Routes } from 'react-router-dom';
import { RealmAuthProvider } from '@/auth/RealmAuthProvider';
import { RequireAuth } from '@/auth/RequireAuth';
import { REALM_CONFIG, type Realm } from '@/auth/realms';
import { AppShell } from '@/components/AppShell';
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
export function App() {
  const realms = (Object.keys(REALM_CONFIG) as Realm[]).filter(
    (realm) => SCREENS[realm].length > 0,
  );

  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<RealmPicker />} />

        {realms.map((realm) => {
          const home = REALM_HOME[realm];
          return (
            <Route key={realm} path={`/${realm}`} element={<RealmSubtree realm={realm} />}>
              {home && <Route index element={<Navigate to={home} replace />} />}
              {SCREENS[realm].map((screen) => (
                <Route key={screen.path} path={screen.path} element={screen.element} />
              ))}
            </Route>
          );
        })}

        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
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
