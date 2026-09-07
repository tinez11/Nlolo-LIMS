import {
  BookText,
  Building2,
  ClipboardCheck,
  FileText,
  HandCoins,
  Package,
  Receipt,
  ScrollText,
  Shield,
  TrendingDown,
  UserCheck,
  UserPlus,
  Users,
  Wallet,
} from 'lucide-react';
import type { ReactNode } from 'react';
import { canSeeFinance, staffRoles, type StaffRoles, type readIdentity } from '@/auth/claims';
import type { Realm } from '@/auth/realms';
import { RedirectPreservingQuery } from '@/components/RedirectPreservingQuery';
import { AuditLogPage } from '@/features/audit/AuditLogPage';
import { ArrearsPage } from '@/features/billing/ArrearsPage';
import { FieldReceiptsPage } from '@/features/billing/FieldReceiptsPage';
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
import { GroupSchemePage } from '@/features/policies/GroupSchemePage';
import { IssueGroupSchemePage } from '@/features/policies/IssueGroupSchemePage';
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
 * state, so the number means "this many are waiting for someone". There are only
 * as many as are expressible: the API allows one status filter at a time, and
 * there is no analytics endpoint anywhere (PLAN.md §6 — a number with nothing
 * behind it is worse than no number). Policies get none deliberately: a policy in
 * force is not work.
 *
 * KYC is counted twice because the client register is two nav items. One combined
 * count on one of them would not describe the list it sits beside.
 */
export type BadgeKey =
  | 'kyc-pending-individuals'
  | 'kyc-pending-organisations'
  | 'underwriting-open'
  | 'claims-unassessed'
  | 'claims-settlement-pending'
  | 'arrears-at-lapse'
  | 'receipts-overdue';

interface NavPlacement {
  group: NavGroupId;
  label: string;
  icon: typeof FileText;
  /**
   * A count of work waiting. A FUNCTION when the queue depends on the viewer's job: the
   * Claims item counts unassessed claims for an assessor and settlement decisions for a
   * manager, because those are two different queues behind one nav item and a badge showing
   * the other role's backlog is a number the reader cannot act on.
   */
  badge?: BadgeKey | ((roles: StaffRoles) => BadgeKey | undefined);
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

  // Setting up a scheme is a NEW-BUSINESS act, so unlike the scheme record below
  // it earns a nav item: nobody arrives at it by drilling into something that
  // already exists. Its sibling `policies/new` is deliberately drill-in only --
  // manual issue is an exception path, and a scheme is not.
  {
    path: 'group-schemes/new',
    element: <IssueGroupSchemePage />,
    reach: { group: 'new-business', label: 'Group scheme', icon: Users },
  },
  // Drill-in from the policy record, not a nav item. An existing scheme is reached
  // by finding the contract first -- the same way a policy is -- and a sidebar
  // entry would lead to a "paste a policy number" screen, which reads as broken
  // software (PLAN.md §7). The policy page links here when the policy is a scheme.
  { path: 'group-schemes/:policyNumber', element: <GroupSchemePage />, reach: 'drill-in' },

  {
    path: 'claims',
    element: <ClaimsPage />,
    reach: {
      group: 'policies-claims',
      label: 'Claims',
      icon: ScrollText,
      // A manager's whole job is the settlement decision, and it had no work signal at all
      // while the assessor's queue had one. Manager wins when an identity carries both
      // roles (staff.admin does): a settlement decision is the more consequential queue.
      badge: (roles) => (roles.CLAIMS_MANAGER ? 'claims-settlement-pending' : 'claims-unassessed'),
    },
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
  //
  // TWO nav items, one register component. A natural person and an organisation are
  // different work -- an individual's KYC is an ID scan and a date of birth, a
  // company's is a registration number and a certificate -- and until now the only
  // thing distinguishing them on screen was a `Type` column hidden below `sm`.
  //
  // Two real paths rather than one path with a query parameter, because `NavLink`
  // decides its active state from the path: `?partyType=` on a shared path would light
  // up both items at once and the sidebar would stop answering "where am I".
  //
  // Each carries its OWN pending count. One combined badge on one of the two items
  // would be a number that does not belong to the list beside it -- click it and the
  // area shows fewer rows than the badge promised, the rest being behind the other
  // item.
  {
    path: 'clients/individuals',
    element: <ClientsPage area="individuals" />,
    reach: {
      group: 'clients',
      label: 'Individuals',
      icon: UserCheck,
      badge: 'kyc-pending-individuals',
    },
  },
  {
    path: 'clients/organisations',
    element: <ClientsPage area="organisations" />,
    reach: {
      group: 'clients',
      // 'Corporate & groups' truncated to 'Corporate & grou...' in the 224px sidebar.
      // Nav labels are deliberately `truncate`, but a label that ALWAYS truncates is a
      // label chosen badly -- and this is the one item whose whole job is to be
      // distinguishable at a glance. The page's own <h1> still says the fuller phrase,
      // where there is room for it.
      label: 'Corporate/Group',
      icon: Building2,
      badge: 'kyc-pending-organisations',
    },
  },
  // The old register path, kept as a redirect rather than deleted: the badge's own
  // links, staff bookmarks and the e2e suite all pointed at `kyc`. It lands on
  // Individuals -- the larger area by far -- with any `kycStatus`/`q` intact, and the
  // sidebar makes the other area visible from there.
  {
    path: 'kyc',
    element: <RedirectPreservingQuery to="../clients/individuals" />,
    reach: 'drill-in',
  },
  // `/staff/clients` is the obvious thing to type for a group whose items both live
  // under it, and without this it matches no route and falls through the catch-all to
  // the realm picker. Landing on Individuals is the same choice the retired path makes.
  {
    path: 'clients',
    element: <RedirectPreservingQuery to="../clients/individuals" />,
    reach: 'drill-in',
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

  // FIRST in the finance group, because it is the only screen in it that is WORK rather
  // than a record: GL postings, the chart of accounts and treaties are all things you look
  // up, and this is a queue somebody has to clear. It earned a nav item the day GET /arrears
  // existed -- before that the platform escalated policies through five dunning levels and
  // recommended them for lapse with no screen able to show a single one of them.
  {
    path: 'arrears',
    element: <ArrearsPage />,
    reach: { group: 'finance', label: 'Arrears', icon: TrendingDown, badge: 'arrears-at-lapse' },
  },
  // Beside Arrears, because it is the module's other work queue and the same audience clears
  // both. It earned its nav item the same way: there was a medium-severity Prometheus alert
  // for overdue receipts and no endpoint that could name one, so the alert could only ever
  // escalate to somebody querying the database by hand.
  {
    path: 'field-receipts',
    element: <FieldReceiptsPage />,
    reach: { group: 'finance', label: 'Field receipts', icon: HandCoins, badge: 'receipts-overdue' },
  },
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

/**
 * One nav item, with its badge already RESOLVED to a concrete key.
 *
 * Distinct from NavPlacement on purpose: a placement may declare its badge as a function of
 * the viewer's roles, and nothing downstream should have to know that. By the time an item
 * leaves navFor the question has been answered.
 */
export type NavItem = Omit<NavPlacement, 'badge'> & { badge?: BadgeKey; to: string };

/** The sidebar for one realm: groups the identity may see, each with its screens. */
export function navFor(
  realm: Realm,
  identity: ReturnType<typeof readIdentity>,
): { label: string; items: NavItem[] }[] {
  return NAV_GROUPS[realm]
    .filter((group) => group.requires?.(identity) ?? true)
    .map((group) => ({
      label: group.label,
      items: SCREENS[realm]
        .filter((screen) => screen.reach !== 'drill-in' && screen.reach.group === group.id)
        .map((screen) => {
          const reach = screen.reach as NavPlacement;
          // Resolved HERE, where the identity is already in hand, so AppShell keeps taking a
          // plain BadgeKey and useNavBadges can fetch exactly the counts that are on screen.
          const badge =
            typeof reach.badge === 'function' ? reach.badge(staffRoles(identity)) : reach.badge;
          const { badge: _declared, ...rest } = reach;
          // The key is OMITTED rather than set to undefined: exactOptionalPropertyTypes is on,
          // and an explicitly-undefined optional is not the same type as an absent one.
          return { ...rest, ...(badge ? { badge } : {}), to: screen.path };
        }),
    }))
    .filter((group) => group.items.length > 0);
}
