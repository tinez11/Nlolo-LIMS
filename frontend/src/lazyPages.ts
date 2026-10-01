import { lazy } from 'react';

/**
 * Every screen, loaded on first visit rather than in one 1.1 MB bundle at sign-in.
 *
 * Its own module because `screens.tsx` exports the manifest, the nav groups and two
 * functions -- all non-components -- and React Fast Refresh only works for a file whose
 * exports are ALL components. Thirty-eight lazy components living there cost the whole
 * file its fast refresh, and said so in thirty-eight lint warnings.
 *
 * The import inside `lazy` stays dynamic, so this file costs nothing at load: naming a
 * screen here does not pull its chunk in. That is what makes App.tsx's claim true -- an
 * agent no longer downloads the finance and underwriting screens it can never open.
 */
export const AuditLogPage = lazy(() =>
  import('@/features/audit/AuditLogPage').then((m) => ({ default: m.AuditLogPage })),
);
export const MessagesPage = lazy(() =>
  import('@/features/communications/MessagesPage').then((m) => ({ default: m.MessagesPage })),
);
export const TemplatesPage = lazy(() =>
  import('@/features/communications/TemplatesPage').then((m) => ({ default: m.TemplatesPage })),
);
export const ArrearsPage = lazy(() =>
  import('@/features/billing/ArrearsPage').then((m) => ({ default: m.ArrearsPage })),
);
export const FieldReceiptsPage = lazy(() =>
  import('@/features/billing/FieldReceiptsPage').then((m) => ({ default: m.FieldReceiptsPage })),
);
export const EftExecutionPage = lazy(() =>
  import('@/features/payments/EftExecutionPage').then((m) => ({ default: m.EftExecutionPage })),
);
export const ClaimDetailPage = lazy(() =>
  import('@/features/claims/ClaimDetailPage').then((m) => ({ default: m.ClaimDetailPage })),
);
export const ClaimsPage = lazy(() =>
  import('@/features/claims/ClaimsPage').then((m) => ({ default: m.ClaimsPage })),
);
export const RegisterClaimPage = lazy(() =>
  import('@/features/claims/RegisterClaimPage').then((m) => ({ default: m.RegisterClaimPage })),
);
export const AgentDetailPage = lazy(() =>
  import('@/features/distribution/AgentDetailPage').then((m) => ({ default: m.AgentDetailPage })),
);
export const AgentProfilePage = lazy(() =>
  import('@/features/distribution/AgentProfilePage').then((m) => ({ default: m.AgentProfilePage })),
);
export const AgentsPage = lazy(() =>
  import('@/features/distribution/AgentsPage').then((m) => ({ default: m.AgentsPage })),
);
export const OnboardAgentPage = lazy(() =>
  import('@/features/distribution/OnboardAgentPage').then((m) => ({ default: m.OnboardAgentPage })),
);
export const ChartOfAccountsPage = lazy(() =>
  import('@/features/finaccounting/ChartOfAccountsPage').then((m) => ({ default: m.ChartOfAccountsPage })),
);
export const GlPostingDetailPage = lazy(() =>
  import('@/features/finaccounting/GlPostingDetailPage').then((m) => ({ default: m.GlPostingDetailPage })),
);
export const GlPostingsPage = lazy(() =>
  import('@/features/finaccounting/GlPostingsPage').then((m) => ({ default: m.GlPostingsPage })),
);
export const ClientsPage = lazy(() =>
  import('@/features/party/ClientsPage').then((m) => ({ default: m.ClientsPage })),
);
export const OnboardCustomerPage = lazy(() =>
  import('@/features/party/OnboardCustomerPage').then((m) => ({ default: m.OnboardCustomerPage })),
);
export const EditClientPage = lazy(() =>
  import('@/features/party/EditClientPage').then((m) => ({ default: m.EditClientPage })),
);
export const PartyDetailPage = lazy(() =>
  import('@/features/party/PartyDetailPage').then((m) => ({ default: m.PartyDetailPage })),
);
export const CreditLifeSchemePage = lazy(() =>
  import('@/features/policies/CreditLifeSchemePage').then((m) => ({ default: m.CreditLifeSchemePage })),
);
export const IssueCreditLifeSchemePage = lazy(() =>
  import('@/features/policies/IssueCreditLifeSchemePage').then((m) => ({ default: m.IssueCreditLifeSchemePage })),
);
export const GroupSchemePage = lazy(() =>
  import('@/features/policies/GroupSchemePage').then((m) => ({ default: m.GroupSchemePage })),
);
export const IssueGroupSchemePage = lazy(() =>
  import('@/features/policies/IssueGroupSchemePage').then((m) => ({ default: m.IssueGroupSchemePage })),
);
export const IssuePolicyPage = lazy(() =>
  import('@/features/policies/IssuePolicyPage').then((m) => ({ default: m.IssuePolicyPage })),
);
export const PoliciesPage = lazy(() =>
  import('@/features/policies/PoliciesPage').then((m) => ({ default: m.PoliciesPage })),
);
export const PolicyDetailPage = lazy(() =>
  import('@/features/policies/PolicyDetailPage').then((m) => ({ default: m.PolicyDetailPage })),
);
export const CreateProductPage = lazy(() =>
  import('@/features/products/CreateProductPage').then((m) => ({ default: m.CreateProductPage })),
);
export const ProductDetailPage = lazy(() =>
  import('@/features/products/ProductDetailPage').then((m) => ({ default: m.ProductDetailPage })),
);
export const ProductsPage = lazy(() =>
  import('@/features/products/ProductsPage').then((m) => ({ default: m.ProductsPage })),
);
export const RegulatoryReturnDetailPage = lazy(() =>
  import('@/features/regreporting/RegulatoryReturnDetailPage').then((m) => ({ default: m.RegulatoryReturnDetailPage })),
);
export const RegulatoryReturnsPage = lazy(() =>
  import('@/features/regreporting/RegulatoryReturnsPage').then((m) => ({ default: m.RegulatoryReturnsPage })),
);
export const CreateTreatyPage = lazy(() =>
  import('@/features/reinsurance/CreateTreatyPage').then((m) => ({ default: m.CreateTreatyPage })),
);
export const TreatiesPage = lazy(() =>
  import('@/features/reinsurance/TreatiesPage').then((m) => ({ default: m.TreatiesPage })),
);
export const TreatyDetailPage = lazy(() =>
  import('@/features/reinsurance/TreatyDetailPage').then((m) => ({ default: m.TreatyDetailPage })),
);
export const OpenUnderwritingCasePage = lazy(() =>
  import('@/features/underwriting/OpenUnderwritingCasePage').then((m) => ({ default: m.OpenUnderwritingCasePage })),
);
export const UnderwritingCaseDetailPage = lazy(() =>
  import('@/features/underwriting/UnderwritingCaseDetailPage').then((m) => ({ default: m.UnderwritingCaseDetailPage })),
);
export const UnderwritingQueuePage = lazy(() =>
  import('@/features/underwriting/UnderwritingQueuePage').then((m) => ({ default: m.UnderwritingQueuePage })),
);

export const PayoutsQueuePage = lazy(() =>
  import('@/features/payouts/PayoutsQueuePage').then((m) => ({ default: m.PayoutsQueuePage })),
);
export const PayoutPage = lazy(() =>
  import('@/features/payouts/PayoutPage').then((m) => ({ default: m.PayoutPage })),
);
