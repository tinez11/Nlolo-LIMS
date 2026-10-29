import {
  Banknote,
  BookText,
  Building2,
  CalendarCheck,
  CalendarClock,
  ClipboardCheck,
  ArrowLeftRight,
  Stamp,
  FileWarning,
  FileText,
  HandCoins,
  Hourglass,
  House,
  LayoutDashboard,
  Landmark,
  MessageSquare,
  Scale,
  Package,
  Percent,
  Receipt,
  Route,
  ScrollText,
  Send,
  Shield,
  TrendingDown,
  TrendingUp,
  UserCheck,
  UserPlus,
  Users,
  Wallet,
  HeartHandshake,
  Briefcase,
  Milestone,
  History,
  BookOpen,
  Files,
  ListChecks,
  CircleMinus,
  Calculator,
  ListOrdered,
  Inbox,
  Coins,
  NotebookPen,
  ChartColumn,
  ShieldCheck,
  UserCog,
} from 'lucide-react';
import { NewRolePage } from '@/features/admin/NewRolePage';
import { ApprovalsPage } from '@/features/approvals/ApprovalsPage';
import { PaymentsPage } from '@/features/payments/PaymentsPage';
import { NewReceiptPage } from '@/features/receipts/NewReceiptPage';
import { ReceiptPage } from '@/features/receipts/ReceiptPage';
import { NewUserPage } from '@/features/admin/NewUserPage';
import { RoleDetailPage } from '@/features/admin/RoleDetailPage';
import { RolesPage } from '@/features/admin/RolesPage';
import { UserDetailPage } from '@/features/admin/UserDetailPage';
import { UsersPage } from '@/features/admin/UsersPage';
import { AccountChargesPage } from '@/features/products/AccountChargesPage';
import { CustomerApplicationsPage } from '@/features/customer/CustomerApplicationsPage';
import { CustomerClaimPage } from '@/features/customer/CustomerClaimPage';
import { CustomerClaimsPage } from '@/features/customer/CustomerClaimsPage';
import { CustomerReportClaimPage } from '@/features/customer/CustomerReportClaimPage';
import { CustomerDocumentsPage } from '@/features/customer/CustomerDocumentsPage';
import { CustomerProductsPage } from '@/features/customer/CustomerProductsPage';
import { CustomerHomePage } from '@/features/customer/CustomerHomePage';
import { CustomerMessagesPage } from '@/features/customer/CustomerMessagesPage';
import { CustomerPayPage } from '@/features/customer/CustomerPayPage';
import { CustomerPoliciesPage } from '@/features/customer/CustomerPoliciesPage';
import { CustomerPolicyPage } from '@/features/customer/CustomerPolicyPage';
import type { ReactNode } from 'react';
import { can, canAny } from '@/auth/access';
import type { readIdentity } from '@/auth/claims';
import type { Realm } from '@/auth/realms';
import { RedirectPreservingQuery } from '@/components/RedirectPreservingQuery';
import { dashboardsFor } from '@/features/dashboard/dashboards';
import {
  AuditLogPage,
  MessagesPage,
  TemplatesPage,
  ArrearsPage,
  FieldReceiptsPage,
  EftExecutionPage,
  ClaimDetailPage,
  ClaimsPage,
  RegisterClaimPage,
  AgentDetailPage,
  AgentProfilePage,
  MyCommissionPage,
  AgentsPage,
  OnboardAgentPage,
  ChartOfAccountsPage,
  GlPostingDetailPage,
  GlPostingsPage,
  ClientsPage,
  OnboardCustomerPage,
  EditClientPage,
  PartyDetailPage,
  CreditLifeSchemePage,
  IssueCreditLifeSchemePage,
  GroupSchemePage,
  IssueGroupSchemePage,
  ProposeGroupFuneralPage,
  IssuePolicyPage,
  PoliciesPage,
  PolicyDetailPage,
  CreateProductPage,
  ProductDetailPage,
  ProductsPage,
  RegulatoryReturnDetailPage,
  ReportsCataloguePage,
  ReportPage,
  RegulatoryReturnsPage,
  CreateTreatyPage,
  TreatiesPage,
  TreatyDetailPage,
  BordereauPage,
  StatementPage,
  ReinsuranceStatementsPage,
  OpenUnderwritingCasePage,
  QuotationCalculatorPage,
  SubmitProposalPage,
  AgentProposalsPage,
  AgentProposalPage,
  UnderwritingCaseDetailPage,
  UnderwritingQueuePage,
  PayoutsQueuePage,
  PayoutPage,
  PaymentRunsPage,
  PaymentRunPage,
  MaturitiesPage,
  WithholdingRulesPage,
  HeldVestingsPage,
  FundsPage,
  PeriodsPage,
  PolicyRegisterPage,
  PostingRulesPage,
  UnpostedEventsPage,
  ManualJournalsPage,
  ManualJournalEditorPage,
  ManualJournalDetailPage,
  JournalTemplatesPage,
  MonthEndPage,
  EnginePage,
  EngineRunPage,
  ExpenseAllocationPage,
  YearEndPage,
  YearEndClosePage,
  DashboardPage,
} from '@/lazyPages';

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
  | 'work'
  | 'clients'
  | 'new-business'
  | 'policies-claims'
  | 'collections'
  | 'paying-out'
  | 'ledger'
  | 'valuation'
  | 'reinsurance-returns'
  | 'reports'
  | 'distribution'
  | 'records'
  | 'communications'
  | 'configuration'
  | 'administration'
  | 'my-business'
  | 'my-cover';

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
  | 'receipts-overdue'
  | 'eft-awaiting'
  | 'needs-action'
  | 'approvals'
  | 'payments-attention';

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
  badge?: BadgeKey | (() => BadgeKey | undefined);
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
  /**
   * Staff only (2026-10-26): the permissions any one of which opens this page. The menu offers it only to someone who
   * holds one, and a typed address answers "not available to your role" to anyone else. Set from STAFF_PERMISSION.
   */
  permission?: readonly string[];
}

export interface NavGroup {
  id: NavGroupId;
  label: string;
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
    // First, and ungated: what is waiting for this person, before any register (2026-10-09, C5).
    { id: 'work', label: 'Your work' },
    { id: 'clients', label: 'Clients' },
    { id: 'new-business', label: 'New business' },
    { id: 'policies-claims', label: 'Policies & claims' },
    // Finance was ONE flat group of 21 destinations (2026-10-09, review C3). Split along the jobs
    // the finance team actually does, in the order money moves: what comes in, what goes out, the
    // books, valuation and the year-end, and what is ceded and filed. Every one carries the gate
    // the single group did, which mirrors the backend expression on every finance endpoint:
    // hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))
    { id: 'collections', label: 'Collections' },
    // "Paying out", not "Payouts": the group would otherwise share its name with an item in it.
    { id: 'paying-out', label: 'Paying out' },
    { id: 'ledger', label: 'Ledger' },
    { id: 'valuation', label: 'Valuation & close' },
    { id: 'reinsurance-returns', label: 'Reinsurance & returns' },
    { id: 'distribution', label: 'Distribution' },
    // Its own group, after the finance jobs and before Records: reports read across every area above, and
    // A finance approver sees it without seeing the finance groups: reports.view, not ledger.manage.
    { id: 'reports', label: 'Reports' },
    // UNGATED, to match its endpoint. `GET /audit-log` is `hasRole('REALM_STAFF')`
    // -- any staff member may read it -- so putting the journal in a
    // finance-gated group would make the nav stricter than the API and hide a
    // screen most staff are authorised to see. Named "Records" rather than
    // "Compliance" on purpose: it is an event journal, and five of the six
    // columns a compliance register needs exist nowhere on this platform, so a
    // group called Compliance would promise what the data cannot deliver.
    { id: 'records', label: 'Records' },
    // Its own group rather than two items scattered into Records and Configuration. What the
    // platform says to customers is one operational area: the wording and the evidence it was
    // sent get read together, usually by the same person answering the same complaint.
    { id: 'communications', label: 'Communications' },
    { id: 'configuration', label: 'Configuration' },
    // Last: who may do what (2026-10-26). Shown to whoever holds users.view -- administrators, by default.
    { id: 'administration', label: 'Administration' },
  ],
  // Dashboard on its own first, same as staff's ungrouped 'work' (2026-10-21) -- it is not "my business" or "new
  // business", it is the landing page. 'new-business' second: where a sale starts and how it is going, kept apart
  // from the book an agent already has -- the same split 'new-business' already draws for staff.
  agents: [
    { id: 'work', label: 'Your work' },
    { id: 'new-business', label: 'New business' },
    { id: 'my-business', label: 'My business' },
  ],
  customers: [{ id: 'my-cover', label: 'My cover' }],
  regulators: [],
};

/** Where a realm's index route redirects to. */
export const REALM_HOME: Record<Realm, string | null> = {
  staff: 'policies',
  agents: 'dashboard',
  customers: 'home',
  regulators: null,
};

/**
 * Where a signed-in person lands: their own queue, not a register of everyone's.
 *
 * Every staff member used to land on Policies, which is nobody's work -- a policy in force
 * is not a task. ADMIN keeps Policies because it holds every role, so no one queue is its
 * job. The claim statuses are the SAME filters `navBadges` counts, so the number beside
 * "Claims" is exactly the list a person lands on.
 */
export function homeFor(realm: Realm, identity: ReturnType<typeof readIdentity>): string | null {
  if (realm !== 'staff') return REALM_HOME[realm];
  // Anyone with a dashboard lands on it (2026-10-14, replacing Today, which showed the same counts
  // as a flat list). The per-role queues below stay as the answer for an identity with no dashboard.
  if (dashboardsFor('staff', identity).length > 0) return 'dashboard';
  // No dashboard: the first queue their permissions reach (2026-10-26), else the KYC queue customer service works,
  // else the dashboard page, which says plainly when a person holds nothing.
  // Whoever decides KYC works that queue first -- customer service, by default.
  if (can('clients.kyc')) return 'clients/individuals?kycStatus=PENDING';
  if (can('underwriting.view')) return 'underwriting';
  if (can('claims.view')) return 'claims?status=REGISTERED';
  if (can('collections.view')) return 'arrears';
  if (can('clients.view')) return 'clients/individuals?kycStatus=PENDING';
  if (can('policies.view')) return 'policies';
  return 'dashboard';
}

/**
 * The permission each staff page needs (2026-10-26) -- the same one its endpoints check, so the menu never offers a
 * page whose data would be refused. Any one of a list opens it. A staff path missing here fails screens.test.
 * Your-work pages carry none: every staff member has a dashboard, even an empty one.
 */
export const STAFF_PERMISSION: Record<string, readonly string[]> = {
  dashboard: [], today: [],
  // Anyone who signs anything the inbox lists (2026-10-27); the server keeps each group to its own permission.
  approvals: ['accounts.approve', 'policies.surrender-approve', 'payouts.approve', 'payouts.manage',
    'collections.manage', 'funds.approve', 'funds.manage', 'products.declare-approve', 'ledger.approve', 'ledger.manage',
    'ifrs17.approve', 'reinsurance.approve'],
  policies: ['policies.view'], 'policies/new': ['policies.issue'], 'policies/:policyNumber': ['policies.view'],
  'held-vestings': ['accounts.view'],
  'group-schemes/new': ['schemes.manage'], 'group-funeral-schemes/new': ['schemes.manage'],
  'credit-life-schemes/new': ['schemes.manage'], 'group-schemes/:policyNumber': ['schemes.view'],
  'credit-life-schemes/:policyNumber': ['schemes.view'],
  claims: ['claims.view'], 'claims/new': ['claims.register'], 'claims/:claimId': ['claims.view'],
  'audit-log': ['audit.view'], 'notifications/messages': ['communications.view'],
  'notifications/templates': ['communications.view'],
  products: ['products.view'], 'account-charges': ['products.view'], 'products/new': ['products.manage'],
  'products/:productId': ['products.view'],
  'clients/individuals': ['clients.view'], 'clients/organisations': ['clients.view'], kyc: ['clients.view'],
  clients: ['clients.view'], 'parties/:partyId': ['clients.view'], 'parties/:partyId/edit': ['clients.edit'],
  underwriting: ['underwriting.view'], 'underwriting/new': ['underwriting.propose'],
  'underwriting/:caseId': ['underwriting.view'],
  agents: ['agents.view'], 'agents/new': ['agents.manage'], 'agents/:agentId': ['agents.view'],
  payments: ['collections.view', 'payouts.view'],
  'receipts/new': ['collections.manage'], 'receipts/:receiptId': ['collections.view', 'collections.manage'],
  arrears: ['collections.view'], 'field-receipts': ['collections.view'], 'bank-transfers': ['payouts.view'],
  payouts: ['payouts.view'], 'payouts/:instalmentId': ['payouts.view'], 'payment-runs': ['payouts.view'],
  'payment-runs/:paymentRunId': ['payouts.view'], 'withholding-rules': ['payouts.view'], maturities: ['payouts.view'],
  funds: ['funds.view'],
  'gl-postings': ['ledger.view'], 'gl-postings/:journalEntryId': ['ledger.view'], 'chart-of-accounts': ['ledger.view'],
  periods: ['ledger.view'], 'accounting-policies': ['ledger.view'], 'posting-rules': ['ledger.view'],
  'unposted-events': ['ledger.view'], 'manual-journals': ['ledger.view'], 'manual-journals/new': ['ledger.manage'],
  'manual-journals/:id': ['ledger.view'], 'manual-journals/:id/edit': ['ledger.manage'],
  'journal-templates': ['ledger.view'],
  'month-end': ['ledger.view', 'ifrs17.view'], 'ifrs17-engine': ['ifrs17.view'], 'ifrs17-engine/runs/:runId': ['ifrs17.view'],
  'ifrs17-engine/allocations/:allocationId': ['ifrs17.view'], 'year-end': ['ifrs17.view'],
  'year-end/closes/:closeId': ['ifrs17.view'],
  treaties: ['reinsurance.view'], 'treaties/new': ['reinsurance.manage'], 'treaties/:treatyId': ['reinsurance.view'],
  'treaties/:treatyId/bordereaux/:bordereauId': ['reinsurance.view'],
  'treaties/:treatyId/statements/:statementId': ['reinsurance.view'], 'reinsurance-statements': ['reinsurance.view'],
  'regulatory-returns': ['returns.view'], 'regulatory-returns/:returnId': ['returns.view'],
  reports: ['reports.view'], 'reports/:reportKey': ['reports.view'],
  users: ['users.view'], 'users/new': ['users.manage'], 'users/:userId': ['users.view'], roles: ['users.view'],
  'roles/new': ['roles.manage'], 'roles/:roleId': ['users.view'],
};

function withPermissions(screens: Screen[]): Screen[] {
  return screens.map((screen) => {
    const permission = STAFF_PERMISSION[screen.path];
    return permission && permission.length > 0 ? { ...screen, permission } : screen;
  });
}

const STAFF_SCREENS: Screen[] = [
  { path: 'dashboard', element: <DashboardPage realm="staff" />, reach: { group: 'work', label: 'Dashboard', icon: LayoutDashboard } },
  // Every signature waiting on this person, across every policy (2026-10-27): finance signed in ~20 places, most of
  // them findable only by opening the right policy.
  { path: 'approvals', element: <ApprovalsPage />, reach: { group: 'work', label: 'Approvals', icon: Stamp, badge: 'approvals' } },
  // Retired 2026-10-14 for the dashboard. Kept as a redirect, with its query, for bookmarks and the old landing URL.
  { path: 'today', element: <RedirectPreservingQuery to="../dashboard" />, reach: 'drill-in' },
  {
    path: 'policies',
    element: <PoliciesPage />,
    reach: { group: 'policies-claims', label: 'Policies', icon: FileText },
  },
  { path: 'policies/new', element: <IssuePolicyPage />, reach: 'drill-in' },
  { path: 'policies/:policyNumber', element: <PolicyDetailPage />, reach: 'drill-in' },
  // Pensions the daily vesting sweep could not vest (product step 5 D2).
  {
    path: 'held-vestings',
    element: <HeldVestingsPage />,
    reach: { group: 'policies-claims', label: 'Held vestings', icon: Hourglass },
  },

  // Setting up a scheme is a NEW-BUSINESS act, so unlike the scheme record below
  // it earns a nav item: nobody arrives at it by drilling into something that
  // already exists. Its sibling `policies/new` is deliberately drill-in only --
  // manual issue is an exception path, and a scheme is not.
  {
    path: 'group-schemes/new',
    element: <IssueGroupSchemePage />,
    reach: { group: 'new-business', label: 'Group scheme', icon: Users },
  },
  // Its own nav item beside Group scheme rather than a fourth option inside it, because the
  // two are not the same act on different data. That form proposes an underwriting case about
  // a schedule of employees; underwriting's own benefit-basis enum has three values and its
  // member line has no room for a loan, so credit life could not travel that road without
  // widening four records and first deciding what underwriting a BOOK means. This one agrees
  // terms with a lender and issues.
  //
  // Until it existed, the console could not create a credit-life scheme at all: every one on
  // this platform was made with curl.
  // An association's families on one funeral plan (2026-10-07). Its own form, as credit life has: the
  // lives are families typed or read from the association's file, and there is no premium to type --
  // the bill is members x the plan's group rate.
  {
    path: 'group-funeral-schemes/new',
    element: <ProposeGroupFuneralPage />,
    reach: { group: 'new-business', label: 'Group funeral scheme', icon: HeartHandshake },
  },
  {
    path: 'credit-life-schemes/new',
    element: <IssueCreditLifeSchemePage />,
    reach: { group: 'new-business', label: 'Credit-life scheme', icon: Landmark },
  },
  // Drill-in from the policy record, not a nav item. An existing scheme is reached
  // by finding the contract first -- the same way a policy is -- and a sidebar
  // entry would lead to a "paste a policy number" screen, which reads as broken
  // software (PLAN.md §7). The policy page links here when the policy is a scheme.
  { path: 'group-schemes/:policyNumber', element: <GroupSchemePage />, reach: 'drill-in' },
  // A credit-life scheme gets its OWN page, not a branch inside GroupSchemePage: that page
  // renders grades and a salary multiple, and this product has neither. Drill-in for the same
  // reason its sibling above is -- a sidebar entry would lead to a "paste a policy number"
  // screen. The policy record links here when the category is CREDIT_LIFE.
  {
    path: 'credit-life-schemes/:policyNumber',
    element: <CreditLifeSchemePage />,
    reach: 'drill-in',
  },

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
      badge: () => (can('claims.settle') ? 'claims-settlement-pending' : 'claims-unassessed'),
    },
  },
  { path: 'claims/new', element: <RegisterClaimPage />, reach: 'drill-in' },
  { path: 'claims/:claimId', element: <ClaimDetailPage />, reach: 'drill-in' },

  {
    path: 'audit-log',
    element: <AuditLogPage />,
    reach: { group: 'records', label: 'Event journal', icon: History },
  },

  // Ungated beyond REALM_STAFF, matching both endpoints. Reading what the platform says to
  // customers, and whether it arrived, is not privileged work -- it is what somebody does while
  // a customer is on the phone. Only the EDIT is ADMIN, and the templates page says so rather
  // than hiding itself.
  {
    path: 'notifications/messages',
    element: <MessagesPage />,
    reach: { group: 'communications', label: 'Messages sent', icon: Send },
  },
  {
    path: 'notifications/templates',
    element: <TemplatesPage />,
    reach: { group: 'communications', label: 'Message templates', icon: MessageSquare },
  },

  {
    path: 'products',
    element: <ProductsPage />,
    reach: { group: 'configuration', label: 'Products', icon: Package },
  },
  // Savings account charges (2026-10-09): chosen per savings policy on its case or at issue.
  { path: 'account-charges', element: <AccountChargesPage />, reach: { group: 'configuration', label: 'Account charges', icon: Percent } },
  { path: 'users', element: <UsersPage />, reach: { group: 'administration', label: 'Users', icon: UserCog } },
  { path: 'users/new', element: <NewUserPage />, reach: 'drill-in' },
  { path: 'users/:userId', element: <UserDetailPage />, reach: 'drill-in' },
  { path: 'roles', element: <RolesPage />, reach: { group: 'administration', label: 'Roles', icon: ShieldCheck } },
  { path: 'roles/new', element: <NewRolePage />, reach: 'drill-in' },
  { path: 'roles/:roleId', element: <RoleDetailPage />, reach: 'drill-in' },
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
  // Staff only. Registration is open to agents and customers; rewriting a record is not the
  // same act, and an agent able to amend a client afterwards could change the identity a
  // policy was underwritten against.
  { path: 'parties/:partyId/edit', element: <EditClientPage />, reach: 'drill-in' },

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
    reach: { group: 'distribution', label: 'Agents', icon: Briefcase },
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
    reach: { group: 'collections', label: 'Arrears', icon: TrendingDown, badge: 'arrears-at-lapse' },
  },
  // Beside Arrears, because it is the module's other work queue and the same audience clears
  // both. It earned its nav item the same way: there was a medium-severity Prometheus alert
  // for overdue receipts and no endpoint that could name one, so the alert could only ever
  // escalate to somebody querying the database by hand.
  {
    path: 'field-receipts',
    element: <FieldReceiptsPage />,
    reach: { group: 'collections', label: 'Field receipts', icon: HandCoins, badge: 'receipts-overdue' },
  },
  // The third work queue, and the only one where the platform is the one who owes. Arrears and
  // field receipts are both about money coming IN and the platform can chase either by itself;
  // an EFT cannot move without a person, because the rail has no integration and no callback.
  // Both its endpoints shipped with credit-life plan 4 and NOTHING called them, so a claim could
  // be approved, valued and instructed and then simply stop, with no screen anywhere admitting
  // the instruction existed. A queue that could only fill.
  // Every payment in and out, opening on the stuck and the failed (2026-10-27): before it a payment could be read
  // only by its id.
  // A receipt for premium the gateway did not pull, and its confirmation by a second person (2026-10-27).
  { path: 'receipts/new', element: <NewReceiptPage />, reach: 'drill-in' },
  { path: 'receipts/:receiptId', element: <ReceiptPage />, reach: 'drill-in' },
  { path: 'payments', element: <PaymentsPage />, reach: { group: 'collections', label: 'Payments', icon: ArrowLeftRight, badge: 'payments-attention' } },
  // Money owed to LIVING policyholders (product step 2), as distinct from the claims queue, which
  // pays on death. Under finance for the same reason bank transfers are: reviewing and approving a
  // payout both move real money, and the endpoints are FINANCE_OFFICER/ADMIN only.
  {
    path: 'payouts',
    element: <PayoutsQueuePage />,
    reach: { group: 'paying-out', label: 'Payouts', icon: Coins },
  },
  { path: 'payouts/:instalmentId', element: <PayoutPage />, reach: 'drill-in' },
  {
    path: 'payment-runs',
    element: <PaymentRunsPage />,
    reach: { group: 'paying-out', label: 'Payment runs', icon: ListChecks },
  },
  { path: 'payment-runs/:paymentRunId', element: <PaymentRunPage />, reach: 'drill-in' },
  // Money OUT that someone makes by hand at the bank (2026-10-27): under Paying out, not Collections, and read with
  // payouts.view -- the permission its list endpoint checks.
  {
    path: 'bank-transfers',
    element: <EftExecutionPage />,
    reach: { group: 'paying-out', label: 'Payments to make', icon: Banknote, badge: 'eft-awaiting' },
  },
  // The unit-linked fund register (product step 6): funds, their two-person daily prices, the
  // price-correction adjustments and the 2150 reconciliation. Finance's: it prices the units.
  {
    path: 'funds',
    element: <FundsPage />,
    reach: { group: 'valuation', label: 'Funds', icon: TrendingUp },
  },
  // Tax withheld from payouts (product step 5): finance proposes a rule, a second person approves.
  {
    path: 'withholding-rules',
    element: <WithholdingRulesPage />,
    reach: { group: 'paying-out', label: 'Withholding rules', icon: CircleMinus },
  },
  // A policy read, but finance's question: it is how the money leaving over the next quarter is
  // planned for, and the endpoint is FINANCE_OFFICER/ADMIN only to match.
  {
    path: 'maturities',
    element: <MaturitiesPage />,
    reach: { group: 'paying-out', label: 'Maturities', icon: Milestone },
  },
  {
    path: 'gl-postings',
    element: <GlPostingsPage />,
    reach: { group: 'ledger', label: 'GL postings', icon: BookText },
  },
  { path: 'gl-postings/:journalEntryId', element: <GlPostingDetailPage />, reach: 'drill-in' },
  {
    path: 'chart-of-accounts',
    element: <ChartOfAccountsPage />,
    reach: { group: 'ledger', label: 'Chart of accounts', icon: Wallet },
  },
  // IFRS 17 I1: closing and locking months, and the accounting policy register -- both two-person.
  {
    path: 'periods',
    element: <PeriodsPage />,
    reach: { group: 'ledger', label: 'Accounting periods', icon: CalendarCheck },
  },
  {
    path: 'accounting-policies',
    element: <PolicyRegisterPage />,
    reach: { group: 'ledger', label: 'Accounting policies', icon: BookOpen },
  },
  // IFRS 17 I3a: the rules every event posts by (read-only) and the events they could not post.
  {
    path: 'posting-rules',
    element: <PostingRulesPage />,
    reach: { group: 'ledger', label: 'Posting rules', icon: Route },
  },
  {
    path: 'unposted-events',
    element: <UnpostedEventsPage />,
    reach: { group: 'ledger', label: 'Unposted events', icon: FileWarning },
  },
  // IFRS 17 I4: manual journals, prepared by one person and posted when a finance approver approves them.
  {
    path: 'manual-journals',
    element: <ManualJournalsPage />,
    reach: { group: 'ledger', label: 'Manual journals', icon: NotebookPen },
  },
  { path: 'manual-journals/new', element: <ManualJournalEditorPage />, reach: 'drill-in' },
  { path: 'manual-journals/:id', element: <ManualJournalDetailPage />, reach: 'drill-in' },
  { path: 'manual-journals/:id/edit', element: <ManualJournalEditorPage />, reach: 'drill-in' },
  {
    path: 'journal-templates',
    element: <JournalTemplatesPage />,
    reach: { group: 'ledger', label: 'Journal templates', icon: Files },
  },
  // Where a month's close stands (2026-10-27): the guide's eight steps, each opening the page that does it.
  {
    path: 'month-end',
    element: <MonthEndPage />,
    reach: { group: 'valuation', label: 'Month-end close', icon: ListOrdered },
  },
  // IFRS 17 I5a: month-end steps 6 and 7 -- the engine's extract, its results through 9160, the reconciliation.
  {
    path: 'ifrs17-engine',
    element: <EnginePage />,
    reach: { group: 'valuation', label: 'IFRS 17 engine', icon: Calculator },
  },
  { path: 'ifrs17-engine/runs/:runId', element: <EngineRunPage />, reach: 'drill-in' },
  // IFRS 17 I5b: P-19's expense allocation, decided by a second person.
  { path: 'ifrs17-engine/allocations/:allocationId', element: <ExpenseAllocationPage />, reach: 'drill-in' },
  // IFRS 17 I6: the year-end close -- classes 4-8 to retained earnings, two people; December locks only after it.
  {
    path: 'year-end',
    element: <YearEndPage />,
    reach: { group: 'valuation', label: 'Year-end close', icon: CalendarClock },
  },
  { path: 'year-end/closes/:closeId', element: <YearEndClosePage />, reach: 'drill-in' },
  {
    path: 'treaties',
    element: <TreatiesPage />,
    reach: { group: 'reinsurance-returns', label: 'Treaties', icon: Shield },
  },
  { path: 'treaties/new', element: <CreateTreatyPage />, reach: 'drill-in' },
  { path: 'treaties/:treatyId', element: <TreatyDetailPage />, reach: 'drill-in' },
  { path: 'treaties/:treatyId/bordereaux/:bordereauId', element: <BordereauPage />, reach: 'drill-in' },
  // IFRS 17 I3d: each treaty's quarter settled into the reinsurer current account; approvers find theirs in the list.
  { path: 'treaties/:treatyId/statements/:statementId', element: <StatementPage />, reach: 'drill-in' },
  {
    path: 'reinsurance-statements',
    element: <ReinsuranceStatementsPage />,
    reach: { group: 'reinsurance-returns', label: 'Reinsurance statements', icon: Scale },
  },
  {
    path: 'regulatory-returns',
    element: <RegulatoryReturnsPage />,
    reach: { group: 'reinsurance-returns', label: 'Regulatory returns', icon: Receipt },
  },
  { path: 'regulatory-returns/:returnId', element: <RegulatoryReturnDetailPage />, reach: 'drill-in' },
  { path: 'reports', element: <ReportsCataloguePage />, reach: { group: 'reports', label: 'Reports', icon: ChartColumn } },
  { path: 'reports/:reportKey', element: <ReportPage />, reach: 'drill-in' },
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
  // An agent lands here too (2026-10-14): their clients, their book and their commission in one place.
  { path: 'dashboard', element: <DashboardPage realm="agents" />, reach: { group: 'work', label: 'Dashboard', icon: LayoutDashboard } },
  {
    path: 'me',
    element: <AgentProfilePage />,
    reach: { group: 'my-business', label: 'My profile', icon: Users },
  },
  {
    path: 'commission',
    element: <MyCommissionPage />,
    reach: { group: 'my-business', label: 'My commission', icon: Wallet },
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
  { path: 'claims/:claimId', element: <ClaimDetailPage realm="agents" />, reach: 'drill-in' },
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
  // Where a sale starts (2026-10-20): a price and a paper for the customer before anybody is registered or saved.
  // Its own group with Proposals, below Dashboard (2026-10-21): new business, kept apart from the book above.
  {
    path: 'quotation',
    element: <QuotationCalculatorPage />,
    reach: { group: 'new-business', label: 'Quotation calculator', icon: Calculator },
  },
  // The confirmed quotation, submitted as a proposal once the customer is registered.
  { path: 'quotation/:quotationId', element: <SubmitProposalPage />, reach: 'drill-in' },
  // Every proposal submitted, with its current status -- open, in review, returned, or decided (2026-10-21).
  // "Needs your action" (returned, postponed, recent declines) is a filter on this page now, not a page of its
  // own: it used to be the only place a proposal was visible at all, so an accepted one, or one still being
  // reviewed, was invisible here until a policy -- or nothing -- eventually turned up. Badged the same way its
  // predecessor was: what is waiting on the agent, not the count of everything in the list.
  {
    path: 'proposals',
    element: <AgentProposalsPage />,
    reach: { group: 'new-business', label: 'Proposals', icon: Inbox, badge: 'needs-action' },
  },
  { path: 'proposals/:caseId', element: <AgentProposalPage />, reach: 'drill-in' },
];

/**
 * `regulators` is deliberately empty rather than stubbed: an
 * authenticating route into an empty app is worse than a 404, so `App.tsx`
 * mounts no subtree for a realm with no screens.
 */
/**
 * The customer portal (2026-10-08, the customer portal design): a policyholder invited by staff. Step 1 is the home
 * page; step 2 the dashboard (home) and My policies; documents, claims, products, payments and notifications follow.
 */
const CUSTOMER_SCREENS: Screen[] = [
  { path: 'home', element: <CustomerHomePage />, reach: { group: 'my-cover', label: 'Home', icon: House } },
  { path: 'policies', element: <CustomerPoliciesPage />, reach: { group: 'my-cover', label: 'My policies', icon: Shield } },
  { path: 'policies/:policyNumber', element: <CustomerPolicyPage />, reach: 'drill-in' },
  { path: 'claims', element: <CustomerClaimsPage />, reach: { group: 'my-cover', label: 'My claims', icon: ClipboardCheck } },
  { path: 'claims/new', element: <CustomerReportClaimPage />, reach: 'drill-in' },
  { path: 'claims/:claimId', element: <CustomerClaimPage />, reach: 'drill-in' },
  { path: 'documents', element: <CustomerDocumentsPage />, reach: { group: 'my-cover', label: 'Documents', icon: FileText } },
  { path: 'products', element: <CustomerProductsPage />, reach: { group: 'my-cover', label: 'Products', icon: Package } },
  { path: 'applications', element: <CustomerApplicationsPage />, reach: { group: 'my-cover', label: 'My applications', icon: ScrollText } },
  { path: 'pay', element: <CustomerPayPage />, reach: { group: 'my-cover', label: 'Pay a premium', icon: Wallet } },
  { path: 'messages', element: <CustomerMessagesPage />, reach: { group: 'my-cover', label: 'Messages', icon: MessageSquare } },
];

export const SCREENS: Record<Realm, Screen[]> = {
  staff: withPermissions(STAFF_SCREENS),
  agents: AGENTS_SCREENS,
  customers: CUSTOMER_SCREENS,
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

/**
 * The sidebar for one realm: groups the person may see, each with its screens. The identity is no longer read --
 * staff pages follow permissions (auth/access) -- and stays in the signature for the callers that pass it.
 */
export function navFor(
  realm: Realm,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _identity?: ReturnType<typeof readIdentity>,
): { label: string; items: NavItem[] }[] {
  return NAV_GROUPS[realm]
    .map((group) => ({
      label: group.label,
      items: SCREENS[realm]
        // A group shows when any of its pages does: every staff page carries its own permission (2026-10-26).
        .filter((screen) => !screen.permission || canAny(...screen.permission))
        .filter((screen) => screen.reach !== 'drill-in' && screen.reach.group === group.id)
        .map((screen) => {
          const reach = screen.reach as NavPlacement;
          // Resolved HERE, where the identity is already in hand, so AppShell keeps taking a
          // plain BadgeKey and useNavBadges can fetch exactly the counts that are on screen.
          const badge = typeof reach.badge === 'function' ? reach.badge() : reach.badge;
          const { badge: _declared, ...rest } = reach;
          // The key is OMITTED rather than set to undefined: exactOptionalPropertyTypes is on,
          // and an explicitly-undefined optional is not the same type as an absent one.
          return { ...rest, ...(badge ? { badge } : {}), to: screen.path };
        }),
    }))
    .filter((group) => group.items.length > 0);
}
