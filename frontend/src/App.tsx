import { BrowserRouter, Navigate, Outlet, Route, Routes } from 'react-router-dom';
import { RealmAuthProvider } from '@/auth/RealmAuthProvider';
import { RequireAuth } from '@/auth/RequireAuth';
import { AppShell } from '@/components/AppShell';
import { RealmPicker } from '@/features/RealmPicker';
import { ClaimDetailPage } from '@/features/claims/ClaimDetailPage';
import { ClaimsPage } from '@/features/claims/ClaimsPage';
import { RegisterClaimPage } from '@/features/claims/RegisterClaimPage';
import { AgentDetailPage } from '@/features/distribution/AgentDetailPage';
import { AgentProfilePage } from '@/features/distribution/AgentProfilePage';
import { OnboardAgentPage } from '@/features/distribution/OnboardAgentPage';
import { KycReviewPage } from '@/features/party/KycReviewPage';
import { OnboardCustomerPage } from '@/features/party/OnboardCustomerPage';
import { PartyDetailPage } from '@/features/party/PartyDetailPage';
import { IssuePolicyPage } from '@/features/policies/IssuePolicyPage';
import { PoliciesPage } from '@/features/policies/PoliciesPage';
import { PolicyDetailPage } from '@/features/policies/PolicyDetailPage';
import { CreateProductPage } from '@/features/products/CreateProductPage';
import { ProductDetailPage } from '@/features/products/ProductDetailPage';
import { ProductsPage } from '@/features/products/ProductsPage';
import { ChartOfAccountsPage } from '@/features/finaccounting/ChartOfAccountsPage';
import { GlPostingDetailPage } from '@/features/finaccounting/GlPostingDetailPage';
import { GlPostingsPage } from '@/features/finaccounting/GlPostingsPage';
import { RegulatoryReturnDetailPage } from '@/features/regreporting/RegulatoryReturnDetailPage';
import { RegulatoryReturnsPage } from '@/features/regreporting/RegulatoryReturnsPage';
import { CreateTreatyPage } from '@/features/reinsurance/CreateTreatyPage';
import { TreatiesPage } from '@/features/reinsurance/TreatiesPage';
import { TreatyDetailPage } from '@/features/reinsurance/TreatyDetailPage';
import { OpenUnderwritingCasePage } from '@/features/underwriting/OpenUnderwritingCasePage';
import { UnderwritingCaseDetailPage } from '@/features/underwriting/UnderwritingCaseDetailPage';
import { UnderwritingQueuePage } from '@/features/underwriting/UnderwritingQueuePage';

/**
 * Realm-scoped routes.
 *
 * `react-oidc-context`'s AuthProvider takes exactly ONE authority, and the realm
 * must therefore be known before the user is authenticated -- a chicken-and-egg the
 * URL resolves. One provider is mounted per realm subtree, which also means a
 * customer never downloads the finance bundle, and no stale token from another
 * realm can reach the wrong provider.
 *
 * /staff and /agents are built. /customers and /regulators are deliberately
 * absent rather than stubbed: an authenticating route into an empty app is
 * worse than a 404.
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
          <Route path="underwriting" element={<UnderwritingQueuePage />} />
          <Route path="underwriting/new" element={<OpenUnderwritingCasePage />} />
          <Route path="underwriting/:caseId" element={<UnderwritingCaseDetailPage />} />
          <Route path="agents/new" element={<OnboardAgentPage />} />
          <Route path="agents/:agentId" element={<AgentDetailPage />} />
          <Route path="parties/:partyId" element={<PartyDetailPage />} />
          <Route path="kyc" element={<KycReviewPage />} />
          <Route path="treaties" element={<TreatiesPage />} />
          <Route path="treaties/new" element={<CreateTreatyPage />} />
          <Route path="treaties/:treatyId" element={<TreatyDetailPage />} />
          <Route path="gl-postings" element={<GlPostingsPage />} />
          <Route path="gl-postings/:journalEntryId" element={<GlPostingDetailPage />} />
          <Route path="chart-of-accounts" element={<ChartOfAccountsPage />} />
          <Route path="regulatory-returns" element={<RegulatoryReturnsPage />} />
          <Route path="regulatory-returns/:returnId" element={<RegulatoryReturnDetailPage />} />
        </Route>

        <Route path="/agents" element={<AgentsRealm />}>
          <Route index element={<Navigate to="me" replace />} />
          <Route path="me" element={<AgentProfilePage />} />
          <Route path="customers/new" element={<OnboardCustomerPage />} />
          <Route
            path="policies"
            element={
              <PoliciesPage
                title="My policies"
                description="Policies where you are the agent of record, or someone in your downline is."
                showIssueAction={false}
              />
            }
          />
          <Route path="policies/:policyNumber" element={<PolicyDetailPage realm="agents" />} />
          <Route
            path="claims"
            element={
              <ClaimsPage
                title="My claims"
                description="Claims against a policy where you are the agent of record, or someone in your downline is."
                showNewClaimAction={false}
              />
            }
          />
          <Route path="claims/:claimId" element={<ClaimDetailPage />} />
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

function AgentsRealm() {
  return (
    <RealmAuthProvider realm="agents">
      <RequireAuth realm="agents">
        <AppShell realm="agents">
          <Outlet />
        </AppShell>
      </RequireAuth>
    </RealmAuthProvider>
  );
}
