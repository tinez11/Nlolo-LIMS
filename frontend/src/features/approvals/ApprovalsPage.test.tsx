import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as approvalsApi from '@/api/approvals';
import type { ApprovalsView } from '@/api/approvals';
import { idle, success } from '@/store/createResourceSlice';
import { useApprovalsStore } from '@/store/approvalsStore';
import { useAccumulationStore } from '@/store/accumulationStore';
import { usePolicyStore } from '@/store/policyStore';
import { grantRoles } from '@/test/access';
import { ApprovalsPage } from './ApprovalsPage';

vi.mock('@/api/approvals');
vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: { access_token: 'token' } }) }));
vi.mock('@/auth/claims', async (original) => ({
  ...(await original<typeof import('@/auth/claims')>()),
  readIdentity: () => ({ subject: 'finance-two' }),
}));

const view: ApprovalsView = {
  actionable: 3,
  groups: [
    {
      kind: 'account-withdrawal', label: 'Savings withdrawals', act: 'Approve', total: 2, actionable: 1,
      unreadable: false,
      rows: [
        { reference: 'w-1', policyNumber: 'POL-1', summary: 'Withdrawal to +255700000009',
          amount: { amount: '10000.00', currencyCode: 'TZS' }, requestedBy: 's-1', requestedByName: 'Neema Service',
          requestedAt: '2026-10-26T08:00:00Z', yours: false },
        { reference: 'w-2', policyNumber: 'POL-2', summary: 'Withdrawal to +255700000001',
          amount: { amount: '5000.00', currencyCode: 'TZS' }, requestedBy: 'me', requestedByName: 'Rehema Finance',
          requestedAt: '2026-10-26T09:00:00Z', yours: true },
      ],
    },
    { kind: 'surrender', label: 'Surrenders', act: 'Approve', total: 0, actionable: 0, unreadable: true, rows: [] },
    { kind: 'payout-review', label: 'Payouts to review', act: 'Review', total: 2, actionable: 2, unreadable: false,
      rows: [{ reference: 'i-1', policyNumber: 'POL-3', summary: 'Survival due 2026-10-04',
        amount: { amount: '200000.00', currencyCode: 'TZS' }, requestedBy: null, requestedByName: null,
        requestedAt: '2026-10-04T00:00:00Z', yours: false }] },
    { kind: 'fund-price', label: 'Fund prices', act: 'Approve', total: 0, actionable: 0, unreadable: false, rows: [] },
  ],
};

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/staff/approvals']}>
      <ApprovalsPage />
    </MemoryRouter>,
  );
}

describe('ApprovalsPage', () => {
  beforeEach(() => {
    useApprovalsStore.setState({ approvals: idle() });
    vi.mocked(approvalsApi.getApprovals).mockResolvedValue(view);
  });

  it('lists what waits by kind; a payout review links to the page that does the act', async () => {
    renderPage();
    const withdrawals = await screen.findByRole('region', { name: 'Savings withdrawals' });
    expect(within(withdrawals).getByText('2 waiting · 1 for you')).toBeInTheDocument();
    // Who asked is in the drawer, not on the row (2026-11-09, the user: "many info there").
    expect(within(withdrawals).queryByText('Neema Service')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Review' })).toHaveAttribute('href', '/staff/payouts/i-1?from=approvals');
  });

  /**
   * 2026-10-29, the user: finance should not have to open each policy to approve. A withdrawal is reviewed in place --
   * the policy's facts, then the request with its Approve -- and the policy is still a link away.
   */
  it('reviews an account withdrawal in place, with the policy’s facts and its Approve', async () => {
    grantRoles('FINANCE_OFFICER');
    const money = (amount: string) => ({ amount, currencyCode: 'TZS' });
    useAccumulationStore.setState({
      loadAccount: async () => {},
      loadMovements: async () => {},
      account: { 'POL-1': success({ policyNumber: 'POL-1', status: 'OPEN', balance: money('150000.00'),
        totals: { paidIn: money('200000.00'), paidOut: money('50000.00'), interest: money('0.00'), charges: money('0.00') },
        openedOn: '2026-01-01', closedReason: null, closedOn: null, entries: [] }) },
      withdrawals: { 'POL-1': success([{ withdrawalId: 'w-1', policyNumber: 'POL-1', amount: money('10000.00'),
        payeeRef: '+255700000009', status: 'REQUESTED' as const, requestedBy: 's-1', requestedAt: '2026-10-26T08:00:00Z',
        approvedBy: null, approvedAt: null, method: 'MOBILE_MONEY' as const }]) },
      adjustments: { 'POL-1': success([]) },
    });
    usePolicyStore.setState({ loadDetail: async () => {} });
    const user = userEvent.setup();
    renderPage();
    const withdrawals = await screen.findByRole('region', { name: 'Savings withdrawals' });
    await user.click(within(withdrawals).getByRole('button', { name: 'Review' }));
    const review = screen.getByRole('region', { name: 'Review POL-1' });
    expect(within(review).getByText('TZS 150,000.00')).toBeInTheDocument();
    expect(within(review).getByRole('button', { name: 'Approve' })).toBeInTheDocument();
    expect(within(review).getByRole('link', { name: 'Open the policy' })).toHaveAttribute(
      'href', '/staff/policies/POL-1?tab=account&from=approvals',
    );
  });

  it("marks the viewer's own request as someone else's to sign, with no act offered", async () => {
    renderPage();
    const withdrawals = await screen.findByRole('region', { name: 'Savings withdrawals' });
    expect(within(withdrawals).getByText(/yours — someone else signs/)).toBeInTheDocument();
    // Only the other person's request can be reviewed here.
    expect(within(withdrawals).getAllByRole('button', { name: 'Review' })).toHaveLength(1);
    expect(within(withdrawals).getByRole('link', { name: 'Open' })).toHaveAttribute(
      'href', '/staff/policies/POL-2?tab=account&from=approvals',
    );
  });

  it('says a list that could not be read is unknown, not empty', async () => {
    renderPage();
    const surrenders = await screen.findByRole('region', { name: 'Surrenders' });
    expect(within(surrenders).getByText(/unknown — not none/)).toBeInTheDocument();
  });

  it('hides kinds with nothing waiting until asked for everything', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('region', { name: 'Savings withdrawals' });
    expect(screen.queryByRole('region', { name: 'Fund prices' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Everything you approve' }));
    expect(within(screen.getByRole('region', { name: 'Fund prices' })).getByText('nothing waiting')).toBeInTheDocument();
  });
});

/** 2026-11-09, the user: the page was "not user friendly" and "doesn't show more details". */
describe('ApprovalsPage, the details a signer needs', () => {
  const detailed: ApprovalsView = {
    actionable: 1,
    groups: [
      { kind: 'receipt', label: 'Deposits to confirm', act: 'Confirm', total: 1, actionable: 0, unreadable: false,
        rows: [{ reference: 'r-1', policyNumber: 'POL-7', summary: 'Mobile money MM-1', clientName: 'Zawadi Mussa',
          amount: { amount: '800.00', currencyCode: 'TZS' }, requestedBy: 'finance-two', requestedByName: 'Rehema',
          requestedAt: '2026-11-08T08:00:00Z', yours: true, detail: null }] },
      { kind: 'payout-approval', label: 'Payouts to approve', act: 'Approve', total: 1, actionable: 1, unreadable: false,
        rows: [{ reference: 'i-9', policyNumber: 'POL-8', summary: 'Survival payout', clientName: 'Juma Ali',
          amount: { amount: '200000.00', currencyCode: 'TZS' }, requestedBy: '30710ea6-369a', requestedByName: null,
          requestedAt: '2026-10-01T00:00:00Z', yours: false, detail: 'Due Oct 1, 2026 · paid to +255700000001' }] },
    ],
  };

  beforeEach(() => {
    useApprovalsStore.setState({ approvals: idle() });
    vi.mocked(approvalsApi.getApprovals).mockResolvedValue(detailed);
  });

  it('keeps the row to what it is and whose money; who is paid and who asked open in the drawer', async () => {
    const user = userEvent.setup();
    renderPage();
    const payouts = await screen.findByRole('region', { name: 'Payouts to approve' });
    expect(within(payouts).getByText('Juma Ali')).toBeInTheDocument();
    // Lean rows (2026-11-09, the user: "many info there"): the payee line is not on the row.
    expect(within(payouts).queryByText('Due Oct 1, 2026 · paid to +255700000001')).not.toBeInTheDocument();
    // Weeks old, and money: overdue.
    expect(payouts.querySelector('[data-overdue="true"]')).not.toBeNull();

    await user.click(within(payouts).getByRole('button', { name: /^Details of Survival payout/ }));
    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByText('Due Oct 1, 2026 · paid to +255700000001')).toBeInTheDocument();
    expect(within(drawer).getByText('Someone not on the staff list')).toBeInTheDocument();
    expect(screen.queryByText(/30710ea6/)).not.toBeInTheDocument();
  });

  it('puts what the viewer can act on first and folds their own requests away', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('region', { name: 'Payouts to approve' });
    expect(screen.queryByRole('region', { name: 'Deposits to confirm' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Waiting on someone else/ }));
    expect(screen.getByRole('region', { name: 'Deposits to confirm' })).toBeInTheDocument();
  });

  it('narrows the queue by client or policy', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('region', { name: 'Payouts to approve' });
    await user.type(screen.getByLabelText('Search approvals'), 'nobody-like-this');
    expect(screen.getByText('No request matches')).toBeInTheDocument();
    await user.clear(screen.getByLabelText('Search approvals'));
    await user.type(screen.getByLabelText('Search approvals'), 'juma');
    expect(screen.getByRole('region', { name: 'Payouts to approve' })).toBeInTheDocument();
  });

  it('opens every fact in a drawer that leads to the page that approves', async () => {
    const user = userEvent.setup();
    renderPage();
    const payouts = await screen.findByRole('region', { name: 'Payouts to approve' });
    await user.click(within(payouts).getByRole('button', { name: /^Details of Survival payout/ }));
    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByText('TZS 200,000.00')).toBeInTheDocument();
    expect(within(drawer).getByText(/^Overdue:/)).toBeInTheDocument();
    expect(within(drawer).getByRole('link', { name: 'Approve on its page' })).toHaveAttribute(
      'href', '/staff/payouts/i-9?from=approvals');
  });
});
