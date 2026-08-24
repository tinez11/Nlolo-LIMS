import { BrowserRouter, Navigate, Outlet, Route, Routes } from 'react-router-dom';
import { RealmAuthProvider } from '@/auth/RealmAuthProvider';
import { RequireAuth } from '@/auth/RequireAuth';
import { AppShell } from '@/components/AppShell';
import { RealmPicker } from '@/features/RealmPicker';
import { ClaimDetailPage } from '@/features/claims/ClaimDetailPage';
import { ClaimsPage } from '@/features/claims/ClaimsPage';
import { RegisterClaimPage } from '@/features/claims/RegisterClaimPage';
import { IssuePolicyPage } from '@/features/policies/IssuePolicyPage';
import { PoliciesPage } from '@/features/policies/PoliciesPage';
import { PolicyDetailPage } from '@/features/policies/PolicyDetailPage';
import { CreateProductPage } from '@/features/products/CreateProductPage';
import { ProductDetailPage } from '@/features/products/ProductDetailPage';
import { ProductsPage } from '@/features/products/ProductsPage';

/**
 * Realm-scoped routes.
 *
 * `react-oidc-context`'s AuthProvider takes exactly ONE authority, and the realm
 * must therefore be known before the user is authenticated -- a chicken-and-egg the
 * URL resolves. One provider is mounted per realm subtree, which also means a
 * customer never downloads the finance bundle, and no stale token from another
 * realm can reach the wrong provider.
 *
 * Only /staff is built. The other three realms are deliberately absent rather than
 * stubbed: an authenticating route into an empty app is worse than a 404.
 */
export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<RealmPicker />} />

        <Route path="/staff" element={<StaffRealm />}>
          <Route index element={<Navigate to="policies" replace />} />
          <Route path="policies" element={<PoliciesPage />} />
          <Route path="policies/new" element={<IssuePolicyPage />} />
          <Route path="policies/:policyNumber" element={<PolicyDetailPage />} />
          <Route path="claims" element={<ClaimsPage />} />
          <Route path="claims/new" element={<RegisterClaimPage />} />
          <Route path="claims/:claimId" element={<ClaimDetailPage />} />
          <Route path="products" element={<ProductsPage />} />
          <Route path="products/new" element={<CreateProductPage />} />
          <Route path="products/:productId" element={<ProductDetailPage />} />
        </Route>

        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}

function StaffRealm() {
  return (
    <RealmAuthProvider realm="staff">
      <RequireAuth realm="staff">
        <AppShell realm="staff">
          <Outlet />
        </AppShell>
      </RequireAuth>
    </RealmAuthProvider>
  );
}
