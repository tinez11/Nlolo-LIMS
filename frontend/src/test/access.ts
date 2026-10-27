import { useAccessStore } from '@/store/accessStore';
import { idle, success } from '@/store/createResourceSlice';

/**
 * Signs the test in as a staff member holding exactly these permissions (2026-10-26) -- what `GET /me/access` would
 * have returned. The console draws from permissions now, not from the roles on a token, so a test says what the
 * person may do rather than which job they have. Reset after every test in setup.ts.
 */
export function grantAccess(...permissions: string[]): void {
  useAccessStore.setState({
    mine: success({ userId: '00000000-0000-0000-0000-000000000001', displayName: 'Test Staff', active: true,
      roles: [], primaryRole: null, permissions }),
  });
}

const ALL = [
  'clients.view', 'clients.edit', 'clients.kyc', 'clients.portal', 'underwriting.view', 'underwriting.propose',
  'underwriting.assess', 'underwriting.decide', 'underwriting.decide-against-recommendation', 'underwriting.refer',
  'policies.view', 'policies.issue', 'policies.service', 'policies.status', 'policies.surrender',
  'policies.surrender-approve', 'schemes.view', 'schemes.manage', 'schemes.enrolments', 'claims.view', 'claims.register',
  'claims.assess', 'claims.settle', 'accounts.view', 'accounts.transact', 'accounts.approve', 'loans.manage',
  'collections.view', 'collections.manage', 'payouts.view', 'payouts.manage', 'payouts.approve', 'ledger.view',
  'ledger.manage', 'ledger.approve', 'ifrs17.view', 'ifrs17.manage', 'ifrs17.approve', 'reinsurance.view',
  'reinsurance.manage', 'reinsurance.approve', 'returns.view', 'returns.manage', 'agents.view', 'agents.manage',
  'commission.pay', 'funds.view', 'funds.manage', 'funds.approve', 'products.view', 'products.manage', 'products.charges',
  'products.declare', 'products.declare-approve', 'reports.view', 'reports.rebuild', 'communications.view',
  'communications.templates', 'audit.view', 'users.view', 'users.manage', 'roles.manage',
];

/**
 * The eight built-in roles as every tenant starts with them -- the server's day-one defaults (iam BuiltInRoles),
 * copied so a test can say "a finance approver" rather than list codes. Administrators may change a role's
 * permissions; the console never assumes a role means these, only tests do.
 */
export const BUILT_IN_ROLES: Record<string, readonly string[]> = {
  ADMIN: ALL.filter((p) => !['ledger.approve', 'ifrs17.approve', 'reinsurance.approve', 'underwriting.assess',
    'underwriting.decide', 'underwriting.decide-against-recommendation', 'underwriting.refer', 'schemes.manage',
    'claims.assess', 'claims.settle'].includes(p)),
  SENIOR_UNDERWRITER: ['underwriting.view', 'underwriting.decide-against-recommendation', 'clients.view', 'policies.view',
    'products.view'],
  UNDERWRITER: ['clients.view', 'clients.edit', 'underwriting.view', 'underwriting.propose', 'underwriting.assess',
    'underwriting.decide', 'underwriting.refer', 'policies.view', 'policies.issue', 'schemes.view', 'schemes.manage',
    'schemes.enrolments', 'claims.view', 'products.view'],
  CLAIMS_MANAGER: ['clients.view', 'policies.view', 'schemes.view', 'claims.view', 'claims.register', 'claims.settle',
    'accounts.view', 'payouts.view', 'products.view'],
  CLAIMS_ASSESSOR: ['clients.view', 'policies.view', 'schemes.view', 'claims.view', 'claims.register', 'claims.assess',
    'accounts.view', 'payouts.view', 'products.view'],
  FINANCE_APPROVER: ['ledger.view', 'ledger.approve', 'ifrs17.view', 'ifrs17.approve', 'reinsurance.view',
    'reinsurance.approve', 'reports.view'],
  FINANCE_OFFICER: ['clients.view', 'policies.view', 'policies.surrender-approve', 'schemes.view', 'claims.view',
    'accounts.view', 'accounts.approve', 'collections.view', 'collections.manage', 'payouts.view', 'payouts.manage',
    'payouts.approve', 'ledger.view', 'ledger.manage', 'ifrs17.view', 'ifrs17.manage', 'reinsurance.view',
    'reinsurance.manage', 'returns.view', 'returns.manage', 'agents.view', 'agents.manage', 'commission.pay',
    'funds.view', 'funds.manage', 'funds.approve', 'products.view', 'products.charges', 'products.declare-approve',
    'reports.view', 'communications.view'],
  CUSTOMER_SERVICE_REP: ['clients.view', 'clients.edit', 'clients.kyc', 'clients.portal', 'underwriting.view',
    'underwriting.propose', 'policies.view', 'policies.service', 'policies.status', 'policies.surrender', 'schemes.view',
    'claims.view', 'claims.register', 'accounts.view', 'accounts.transact', 'loans.manage', 'collections.view',
    'payouts.view', 'products.view', 'communications.view'],
};

/** Signs the test in holding these built-in roles, at their day-one permissions. */
export function grantRoles(...roles: string[]): void {
  grantAccess(...new Set(roles.flatMap((r) => BUILT_IN_ROLES[r] ?? [])));
}

export function clearAccess(): void {
  useAccessStore.setState({ mine: idle() });
}
