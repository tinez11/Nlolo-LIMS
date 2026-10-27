import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { grantRoles } from '@/test/access';
import type { AccountView } from '@/api/types';
import { success } from '@/store/createResourceSlice';
import { useAccumulationStore } from '@/store/accumulationStore';
import { AccountPanel } from './AccountPanel';

vi.mock('react-oidc-context', () => ({
  useAuth: () => ({ user: { access_token: 'token-for-staff-one' } }),
}));
vi.mock('@/auth/claims', () => ({
  readIdentity: () => ({ subject: 'staff-one' }),
  canSeeFinance: () => true,
}));

const money = (amount: string) => ({ amount, currencyCode: 'TZS' });

const account: AccountView = {
  policyNumber: 'POL-1',
  status: 'OPEN',
  balance: money('94000.00'),
  openedOn: '2026-01-01',
  closedReason: null,
  closedOn: null,
  entries: [
    { entryId: 'e1', seq: 1, type: 'TOP_UP', amount: money('100000.00'), balanceAfter: money('100000.00'),
      effectiveDate: '2026-02-01', postedAt: '2026-02-01T08:00:00Z', sourceType: 'topup', sourceRef: 'topup:1',
      reversesEntryId: null, reason: 'Top-up from +255700000002', createdBy: 'staff-one', approvedBy: null },
    { entryId: 'e2', seq: 2, type: 'WITHDRAWAL', amount: money('-6000.00'), balanceAfter: money('94000.00'),
      effectiveDate: '2026-02-02', postedAt: '2026-02-02T08:00:00Z', sourceType: 'withdrawal', sourceRef: 'withdrawal:1',
      reversesEntryId: null, reason: null, createdBy: 'staff-one', approvedBy: 'finance-two' },
    { entryId: 'e3', seq: 3, type: 'REVERSAL', amount: money('6000.00'), balanceAfter: money('100000.00'),
      effectiveDate: '2026-02-03', postedAt: '2026-02-03T08:00:00Z', sourceType: 'reversal', sourceRef: 'reversal:e2',
      reversesEntryId: 'e2', reason: 'Withdrawal payment failed', createdBy: 'system', approvedBy: null },
  ],
};

/**
 * The account panel against a loaded store, with no network. It stubs the loads, because the panel
 * fires them on mount and this test is about what a loaded account looks like.
 *
 * It also holds the render-loop fix: an earlier version selected `data ?? []` inside a Zustand
 * selector, a fresh array on every call, and the page went blank in the browser while every other
 * test was green. Rendering it here at all is the regression test -- the loop throws "Maximum update
 * depth exceeded" before any assertion runs.
 */
beforeEach(() => {
  useAccumulationStore.setState({
    account: { 'POL-1': success(account) },
    withdrawals: {},
    adjustments: {},
    deposit: {},
    termAccount: {},
    loadAccount: async () => {},
    loadMovements: async () => {},
    loadDeposit: async () => {},
    loadTermAccount: async () => {},
  });
});

const termAccount = (status: 'RUNNING' | 'MATURED' | 'AWAITING_PAYEE') => ({
  policyNumber: 'POL-1',
  ratePercent: 5.25,
  maturityDate: '2028-10-21',
  status,
  defaultPayeeRef: status === 'AWAITING_PAYEE' ? null : '+255700000777',
  maturedOn: status === 'RUNNING' ? null : '2028-10-21',
});

// A finance officer, as the page reads it now: permissions, not the token (2026-10-26).
beforeEach(() => grantRoles('FINANCE_OFFICER'));

describe('AccountPanel on a term account', () => {
  it('shows the locked rate and maturity, takes money in, and offers no withdrawal', () => {
    // Both sides of the counter: customer service requests the top-up, finance records a transfer in.
    grantRoles('FINANCE_OFFICER', 'CUSTOMER_SERVICE_REP');
    useAccumulationStore.setState({ termAccount: { 'POL-1': success(termAccount('RUNNING')) } });
    render(<AccountPanel policyNumber="POL-1" />);
    expect(screen.getByText('5.25% a year')).toBeInTheDocument();
    expect(screen.getByText(/Nothing can be withdrawn or surrendered until it matures/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request withdrawal' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Request top-up' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Record transfer in' })).toBeInTheDocument();
  });

  it('once matured and waiting, offers finance the payee form and takes nothing in', () => {
    useAccumulationStore.setState({ termAccount: { 'POL-1': success(termAccount('AWAITING_PAYEE')) } });
    render(<AccountPanel policyNumber="POL-1" />);
    expect(screen.getByRole('button', { name: 'Record payee and pay' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request top-up' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Record transfer in' })).not.toBeInTheDocument();
  });
});

describe('AccountPanel', () => {
  it('shows the balance and every entry in sequence order, a reversal naming what it reverses', () => {
    render(<AccountPanel policyNumber="POL-1" />);
    expect(screen.getByText('TZS 94,000.00')).toBeInTheDocument();
    const ledger = screen.getByRole('list', { name: 'Ledger' });
    expect(ledger).toHaveTextContent('Top-up');
    expect(ledger).toHaveTextContent('Withdrawal');
    expect(ledger).toHaveTextContent('reverses #2');
  });

  it('offers finance the transfer in and the adjustment, but not the withdrawal or top-up', () => {
    render(<AccountPanel policyNumber="POL-1" />);
    for (const name of ['Record transfer in', 'Propose adjustment']) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument();
    }
    // Requesting money movements is customer service's (accounts.transact, 2026-10-26); the server would refuse it.
    for (const name of ['Request withdrawal', 'Request top-up']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
    }
  });

  it('offers customer service the withdrawal and top-up, but not the transfer in or the adjustment', () => {
    grantRoles('CUSTOMER_SERVICE_REP');
    render(<AccountPanel policyNumber="POL-1" />);
    for (const name of ['Request withdrawal', 'Request top-up']) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument();
    }
    for (const name of ['Record transfer in', 'Propose adjustment']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
    }
  });

  it('on a deposit shows the term and its rate, and offers no money movement but the adjustment', () => {
    // Both sides of the counter, so "no money movement" is not merely a permission missing.
    grantRoles('FINANCE_OFFICER', 'CUSTOMER_SERVICE_REP');
    useAccumulationStore.setState({
      deposit: {
        'POL-1': success({
          policyNumber: 'POL-1',
          periods: [
            { periodId: 'p1', seq: 1, principal: money('1000000.00'), termMonths: 3, ratePercent: 3, rateVersionId: 'v1',
              startDate: '2026-10-01', maturityDate: '2027-01-01', status: 'RUNNING', interestPosted: null, closedOn: null },
          ],
          instruction: null,
          interestSoFar: money('3260.87'),
          defaultPayeeRef: '+255700000777',
          awaitingPayee: false,
          termsOffered: [3, 6, 12],
          schedule: {
            principal: money('1000000.00'), termMonths: 3, ratePercent: 3, startDate: '2026-10-01', maturityDate: '2027-01-01',
            interestAtMaturity: money('30000.00'), amountAtMaturity: money('1030000.00'),
            ifClosedEarly: [
              { closedOn: '2026-11-01', interest: money('10108.70'), paidOut: money('1010108.70') },
              { closedOn: '2026-12-01', interest: money('19891.30'), paidOut: money('1019891.30') },
              { closedOn: '2027-01-01', interest: money('30000.00'), paidOut: money('1030000.00') },
            ],
          },
        }),
      },
    });
    render(<AccountPanel policyNumber="POL-1" />);
    expect(screen.getByText('3 months, 3% for the term')).toBeInTheDocument();
    // The term worked forward (2026-10-09): what it pays at maturity, and if closed early.
    expect(screen.getByText('Paid at maturity')).toBeInTheDocument();
    // Once as the maturity figure, once as the table's last row.
    expect(screen.getAllByText('TZS 1,030,000.00')).toHaveLength(2);
    const early = screen.getByRole('table', { name: 'If closed early' });
    expect(early).toHaveTextContent('TZS 1,010,108.70');
    expect(early).toHaveTextContent('(maturity)');
    expect(screen.getByRole('button', { name: 'Download the deposit schedule as PDF' })).toBeInTheDocument();
    expect(screen.getByText('No instruction: it will be paid out to +255700000777.')).toBeInTheDocument();
    for (const name of ['Request withdrawal', 'Request top-up', 'Record transfer in']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
    }
    expect(screen.getByRole('button', { name: 'Propose adjustment' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Record instruction' })).toBeInTheDocument();
  });

  it('offers finance the payee form on a matured deposit waiting for one', () => {
    useAccumulationStore.setState({
      deposit: {
        'POL-1': success({
          policyNumber: 'POL-1',
          periods: [
            { periodId: 'p1', seq: 1, principal: money('1000000.00'), termMonths: 3, ratePercent: 3, rateVersionId: 'v1',
              startDate: '2026-06-01', maturityDate: '2026-09-01', status: 'MATURED', interestPosted: money('30000.00'),
              closedOn: '2026-09-01' },
          ],
          instruction: null,
          interestSoFar: money('0.00'),
          defaultPayeeRef: null,
          awaitingPayee: true,
          termsOffered: [3, 6, 12],
        }),
      },
    });
    render(<AccountPanel policyNumber="POL-1" />);
    expect(screen.getByRole('button', { name: 'Record payee and pay' })).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'At maturity' })).not.toBeInTheDocument();
  });

  it('on a deposit not yet paid says it is waiting for the money, not an empty list of terms', () => {
    useAccumulationStore.setState({
      account: { 'POL-1': success({ ...account, balance: money('0.00'), entries: [] }) },
      deposit: {
        'POL-1': success({
          policyNumber: 'POL-1',
          periods: [],
          instruction: null,
          interestSoFar: money('0.00'),
          defaultPayeeRef: null,
          awaitingPayee: false,
          termsOffered: [3, 6, 12],
        }),
      },
    });
    render(<AccountPanel policyNumber="POL-1" />);
    expect(screen.getByText('Waiting for the deposit')).toBeInTheDocument();
    expect(screen.queryByText('Nothing on the ledger yet')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request top-up' })).not.toBeInTheDocument();
  });

  it('offers no money movement on a closed account', () => {
    useAccumulationStore.setState({
      account: { 'POL-1': success({ ...account, status: 'CLOSED', closedReason: 'SURRENDERED', closedOn: '2026-03-01' }) },
    });
    render(<AccountPanel policyNumber="POL-1" />);
    expect(screen.queryByRole('button', { name: 'Request withdrawal' })).not.toBeInTheDocument();
  });
});
