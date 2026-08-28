import {
  BookText,
  ClipboardCheck,
  FileText,
  Package,
  Receipt,
  ScrollText,
  Shield,
  UserCheck,
  UserPlus,
  Users,
  Wallet,
} from 'lucide-react';
import type { ReactNode } from 'react';
import { canSeeFinance, type readIdentity } from '@/auth/claims';
import type { Realm } from '@/auth/realms';
import { AuditLogPage } from '@/features/audit/AuditLogPage';
import { ClaimDetailPage } from '@/features/claims/ClaimDetailPage';
import { ClaimsPage } from '@/features/claims/ClaimsPage';
import { RegisterClaimPage } from '@/features/claims/RegisterClaimPage';
import { AgentDetailPage } from '@/features/distribution/AgentDetailPage';
import { AgentProfilePage } from '@/features/distribution/AgentProfilePage';
import { AgentsPage } from '@/features/distribution/AgentsPage';
import { OnboardAgentPage } from '@/features/distribution/OnboardAgentPage';
import { ChartOfAccountsPage } from '@/features/finaccounting/ChartOfAccountsPage';
import { GlPostingDetailPage } from '@/features/finaccounting/GlPostingDetailPage';
import { GlPostingsPage } from '@/features/finaccounting/GlPostingsPage';
import { ClientsPage } from '@/features/party/ClientsPage';
import { OnboardCustomerPage } from '@/features/party/OnboardCustomerPage';
import { PartyDetailPage } from '@/features/party/PartyDetailPage';
import { IssuePolicyPage } from '@/features/policies/IssuePolicyPage';
import { PoliciesPage } from '@/features/policies/PoliciesPage';
import { PolicyDetailPage } from '@/features/policies/PolicyDetailPage';
import { CreateProductPage } from '@/features/products/CreateProductPage';
import { ProductDetailPage } from '@/features/products/ProductDetailPage';
import { ProductsPage } from '@/features/products/ProductsPage';
import { RegulatoryReturnDetailPage } from '@/features/regreporting/RegulatoryReturnDetailPage';
import { RegulatoryReturnsPage } from '@/features/regreporting/RegulatoryReturnsPage';
import { CreateTreatyPage } from '@/features/reinsurance/CreateTreatyPage';
import { TreatiesPage } from '@/features/reinsurance/TreatiesPage';
import { TreatyDetailPage } from '@/features/reinsurance/TreatyDetailPage';
import { OpenUnderwritingCasePage } from '@/features/underwriting/OpenUnderwritingCasePage';
import { UnderwritingCaseDetailPage } from '@/features/underwriting/UnderwritingCaseDetailPage';
import { UnderwritingQueuePage } from '@/features/underwriting/UnderwritingQueuePage';

/**
 * The one place a screen is declared.
 *
 * The router (`App.tsx`) and the sidebar (`AppShell.tsx`) are both maps over
 * this. They used to be two hand-maintained lists -- 34 `<Route>` entries in one
 * file, 14 nav items in the other, no shared source -- and they were the two
 * most-churned files in the frontend's history. Adding a screen meant editing
 * both, and nothing caught the drift.
 *
 * `reach` is what the two lists could not express. Most screens here are
 * deliberately NOT in the sidebar: a detail page is reached by clicking a row,
 * and a nav item leading to a "paste an ID" screen reads as broken software
 * (PLAN.md §7). Making that a required field turns an absence into a statement:
 * a new screen cannot be added without saying how a user gets to it, so
 * "deliberately drill-in only" and "somebody forgot the nav item" stop looking
 * identical.
 */

type NavGroupId =
  | 'clients'
  | 'new-business'
  | 'policies-claims'
  | 'finance'
  | 'distribution'
  | 'records'
  | 'configuration'
  | 'my-business';

/**
 * A count of WORK WAITING beside a nav item, never a count of total volume.
 *
 * Each maps to `totalElements` on a real paged search filtered to the unstarted
 * state, so the number means "this many are waiting for someone". Only three
 * exist because only three are expressible: the API allows one status filter at
 * a time, and there is no analytics endpoint anywhere (PLAN.md §6 — a number with
 * nothing behind it is worse than no number). Policies get none deliberately: a
 * policy in force is not work.
 */
export type BadgeKey = 'kyc-pending' | 'underwriting-open' | 'claims-unassessed';

interface NavPlacement {
  group: NavGroupId;
  label: string;
  icon: typeof FileText;
  badge?: BadgeKey;
}

export interface Screen {
  /** Path relative to the realm root, exactly as `<Route path>` takes it. */
  path: string;
  element: ReactNode;
  /**
   * How a user reaches this screen: a sidebar placement, or an explicit
   * declaration that it is only reachable by drilling in from another screen.
   */
  reach: NavPlacement | 'drill-in';
}

export interface NavGroup {
  id: NavGroupId;
  label: string;
  /** Undefined means always visible. */
  requires?: (identity: ReturnType<typeof readIdentity>) => boolean;
}

/**
 * Group order is sidebar order. A group with no visible screens renders nothing,
 * so gating happens per-group here and per-screen on the entries below.
 */
export const NAV_GROUPS: Record<Realm, NavGroup[]> = {
  staff: [
    // Ordered by where the work sits in the business flow rather than by module:
    // a client is registered and assessed, becomes a policy, generates claims,
    // then finance and distribution settle up. Configuration comes LAST on
    // purpose — authoring a product is rare actuarial set-up, not daily
    // operations, so it belongs away from the customer journey.
    //
    // Gating is per GROUP, so a group must be entirely gated or entirely open.
    // That constrains the shape: the five finance-gated screens cannot be mixed
    // in with the five open ones however neatly the flow would read.
    // FIRST, and its own group rather than an item under New business. Clients are
    // the entity everything else hangs off -- policies, claims, KYC, beneficiary
    // exposure and documents all belong to a person -- and this screen is the way
    // into all of them. Filing the customer record under acquisition is what made
    // it a KYC queue rather than a register for as long as it was one.
    { id: 'clients', label: 'Clients' },
    { id: 'new-business', label: 'New business' },
    { id: 'policies-claims', label: 'Policies & claims' },
    {
      id: 'finance',
      label: 'Finance',
      // Mirrors the backend expression on every finance endpoint:
      // hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))
      requires: canSeeFinance,
    },
    { id: 'distribution', label: 'Distribution', requires: canSeeFinance },
    // UNGATED, to match its endpoint. `GET /audit-log` is `hasRole('REALM_STAFF')`
    // -- any staff member may read it -- so putting the journal in a
    // finance-gated group would make the nav stricter than the API and hide a
    // screen most staff are authorised to see. Named "Records" rather than
    // "Compliance" on purpose: it is an event journal, and five of the six
    // columns a compliance register needs exist nowhere on this platform, so a
    // group called Compliance would promise what the data cannot deliver.
    { id: 'records', label: 'Records' },
    { id: 'configuration', label: 'Configuration' },
  ],
  agents: [{ id: 'my-business', label: 'My business' }],
  customers: [],
  regulators: [],
};

/** Where a realm's index route redirects to. */
export const REALM_HOME: Record<Realm, string | null> = {
  staff: 'policies',
  agents: 'me',
  customers: null,
  regulators: null,
};

const STAFF_SCREENS: Screen[] = [
  {
    path: 'policies',
    element: <PoliciesPage />,
    reach: { group: 'policies-claims', label: 'Policies', icon: FileText },
  },
  { path: 'policies/new', element: <IssuePolicyPage />, reach: 'drill-in' },
  { path: 'policies/:policyNumber', element: <PolicyDetailPage />, reach: 'drill-in' },

  {
    path: 'claims',
    element: <ClaimsPage />,
    reach: { group: 'policies-claims', label: 'Claims', icon: ScrollText, badge: 'claims-unassessed' },
  },
  { path: 'claims/new', element: <RegisterClaimPage />, reach: 'drill-in' },
  { path: 'claims/:claimId', element: <ClaimDetailPage />, reach: 'drill-in' },

  {
    path: 'audit-log',
    element: <AuditLogPage />,
    reach: { group: 'records', label: 'Event journal', icon: ScrollText },
  },

  {
    path: 'products',
    element: <ProductsPage />,
    reach: { group: 'configuration', label: 'Products', icon: Package },
  },
  { path: 'products/new', element: <CreateProductPage />, reach: 'drill-in' },
  { path: 'products/:productId', element: <ProductDetailPage />, reach: 'drill-in' },

  // Order WITHIN a group is manifest order, so these two are declared in the
  // order the work happens: a client is identified and KYC-verified before their
  // risk is assessed.
  // The path stays `kyc`: the badge, existing bookmarks and the e2e suite all point
  // at it, and renaming a route to match a label is churn that buys nothing a
  // reader can see. The badge still counts parties awaiting KYC, so the work queue
  // survives the screen becoming a register.
  {
    path: 'kyc',
    element: <ClientsPage />,
    reach: { group: 'clients', label: 'Clients', icon: UserCheck, badge: 'kyc-pending' },
  },
  // A party PENDING KYC with nothing referencing it yet is invisible to staff
  // except through the KYC queue above, which is why that queue is a real list
  // rather than a lookup box.
  { path: 'parties/:partyId', element: <PartyDetailPage />, reach: 'drill-in' },

  {
    path: 'underwriting',
    element: <UnderwritingQueuePage />,
    reach: { group: 'new-business', label: 'Underwriting', icon: ClipboardCheck, badge: 'underwriting-open' },
  },
  { path: 'underwriting/new', element: <OpenUnderwritingCasePage />, reach: 'drill-in' },
  { path: 'underwriting/:caseId', element: <UnderwritingCaseDetailPage />, reach: 'drill-in' },

  // Agents used to be the one nav item pointing at a create form rather than a
  // list, because `POST /agents` was the only entry point onto the domain that
  // existed server-side (PLAN.md §7's recorded exception). `GET /agents` in M13
  // retired that: this is a real list now.
  {
    path: 'agents',
    element: <AgentsPage />,
    reach: { group: 'distribution', label: 'Agents', icon: Users },
  },
  { path: 'agents/new', element: <OnboardAgentPage />, reach: 'drill-in' },
  { path: 'agents/:agentId', element: <AgentDetailPage />, reach: 'drill-in' },

  {
    path: 'gl-postings',
    element: <GlPostingsPage />,
    reach: { group: 'finance', label: 'GL postings', icon: BookText },
  },
  { path: 'gl-postings/:journalEntryId', element: <GlPostingDetailPage />, reach: 'drill-in' },
  {
    path: 'chart-of-accounts',
    element: <ChartOfAccountsPage />,
    reach: { group: 'finance', label: 'Chart of accounts', icon: Wallet },
  },
  {
    path: 'treaties',
    element: <TreatiesPage />,
    reach: { group: 'finance', label: 'Treaties', icon: Shield },
  },
  { path: 'treaties/new', element: <CreateTreatyPage />, reach: 'drill-in' },
  { path: 'treaties/:treatyId', element: <TreatyDetailPage />, reach: 'drill-in' },
  {
    path: 'regulatory-returns',
    element: <RegulatoryReturnsPage />,
    reach: { group: 'finance', label: 'Regulatory returns', icon: Receipt },
  },
  { path: 'regulatory-returns/:returnId', element: <RegulatoryReturnDetailPage />, reach: 'drill-in' },
];

/**
 * Policies and claims reuse the exact same page components the staff console
 * mounts. Scoping to "this agent's own book" happens entirely server-side
 * (`PolicyController`/`ClaimController` resolve the caller's hierarchy team via
 * `DistributionApi.resolveAgentTeam` and filter the query), so the frontend
 * needs no filter UI. The only differences are cosmetic: title, description, and
 * hiding the staff-only create actions.
 */
const AGENTS_SCREENS: Screen[] = [
  {
    path: 'me',
    element: <AgentProfilePage />,
    reach: { group: 'my-business', label: 'My profile', icon: Users },
  },
  {
    path: 'policies',
    element: (
      <PoliciesPage
        title="My policies"
        description="Policies where you are the agent of record, or someone in your downline is."
        showIssueAction={false}
      />
    ),
    reach: { group: 'my-business', label: 'Policies', icon: FileText },
  },
  { path: 'policies/:policyNumber', element: <PolicyDetailPage realm="agents" />, reach: 'drill-in' },
  {
    path: 'claims',
    element: (
      <ClaimsPage
        title="My claims"
        description="Claims against a policy where you are the agent of record, or someone in your downline is."
        showNewClaimAction={false}
      />
    ),
    reach: { group: 'my-business', label: 'Claims', icon: ScrollText },
  },
  { path: 'claims/:claimId', element: <ClaimDetailPage />, reach: 'drill-in' },
  // Clients an agent REGISTERED, which is not the same set as their book: a client
  // they registered may be written by another agent, and their book contains
  // policyholders they never registered. The scoping is the server's -- GET /parties
  // force-scopes an agents-realm token to its own `createdBy` -- so this screen
  // cannot widen it by passing the wrong prop.
  {
    path: 'clients',
    element: (
      <ClientsPage
        title="My clients"
        description="Everyone you have registered. Open one for their policies, claims and documents."
      />
    ),
    reach: { group: 'my-business', label: 'My clients', icon: UserCheck },
  },
  { path: 'parties/:partyId', element: <PartyDetailPage realm="agents" />, reach: 'drill-in' },
  {
    path: 'customers/new',
    element: <OnboardCustomerPage />,
    reach: { group: 'my-business', label: 'Onboard a customer', icon: UserPlus },
  },
];

/**
 * `customers` and `regulators` are deliberately empty rather than stubbed: an
 * authenticating route into an empty app is worse than a 404, so `App.tsx`
 * mounts no subtree for a realm with no screens.
 */
export const SCREENS: Record<Realm, Screen[]> = {
  staff: STAFF_SCREENS,
  agents: AGENTS_SCREENS,
  customers: [],
  regulators: [],
};

/** The sidebar for one realm: groups the identity may see, each with its screens. */
export function navFor(
  realm: Realm,
  identity: ReturnType<typeof readIdentity>,
): { label: string; items: (NavPlacement & { to: string })[] }[] {
  return NAV_GROUPS[realm]
    .filter((group) => group.requires?.(identity) ?? true)
    .map((group) => ({
      label: group.label,
      items: SCREENS[realm]
        .filter((screen) => screen.reach !== 'drill-in' && screen.reach.group === group.id)
        .map((screen) => ({ ...(screen.reach as NavPlacement), to: screen.path })),
    }))
    .filter((group) => group.items.length > 0);
}
