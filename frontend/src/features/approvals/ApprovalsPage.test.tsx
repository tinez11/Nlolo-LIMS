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
      kind: 'account-withdrawal', label: 'Account withdrawals', act: 'Approve', total: 2, actionable: 1,
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
    const withdrawals = await screen.findByRole('region', { name: 'Account withdrawals' });
    expect(within(withdrawals).getByText('2 waiting · 1 for you')).toBeInTheDocument();
    expect(within(withdrawals).getByText('Neema Service')).toBeInTheDocument();
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
    const withdrawals = await screen.findByRole('region', { name: 'Account withdrawals' });
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
    const withdrawals = await screen.findByRole('region', { name: 'Account withdrawals' });
    expect(within(withdrawals).getByText('You — someone else signs')).toBeInTheDocument();
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
    await screen.findByRole('region', { name: 'Account withdrawals' });
    expect(screen.queryByRole('region', { name: 'Fund prices' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Everything you approve' }));
    expect(within(screen.getByRole('region', { name: 'Fund prices' })).getByText('nothing waiting')).toBeInTheDocument();
  });
});
