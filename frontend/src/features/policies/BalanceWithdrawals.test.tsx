import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as policiesApi from '@/api/policies';
import type { BalanceWithdrawalView } from '@/api/types';
import { grantAccess } from '@/test/access';
import { usePolicyStore } from '@/store/policyStore';
import { BalanceWithdrawalPage } from './BalanceWithdrawalPage';
import { BalanceWithdrawals } from './BalanceWithdrawals';

vi.mock('@/api/policies');
vi.mock('react-oidc-context', () => ({
  useAuth: () => ({ user: { access_token: 'token-for-staff-one' } }),
}));
vi.mock('@/auth/claims', () => ({
  readIdentity: () => ({ subject: 'staff-one' }),
}));

const asked: BalanceWithdrawalView = {
  withdrawalId: 'w-1', policyNumber: 'POL-000001', amount: { amount: '20000.00', currencyCode: 'TZS' },
  status: 'REQUESTED', reason: 'ASKED', method: 'MOBILE_MONEY', payeeRef: '+255712345678', requestedBy: 'staff-two',
  requestedAt: '2026-11-04T09:00:00Z', approvedBy: null, approvedAt: null, rejectedBy: null, rejectionReason: null,
  actedAlone: false,
};

beforeEach(() => {
  vi.clearAllMocks();
  usePolicyStore.setState({ balance: {}, balanceStatement: {}, balanceWithdrawals: {}, actingOnWithdrawal: {} });
  vi.mocked(policiesApi.getPolicyBalance).mockResolvedValue({
    policyNumber: 'POL-000001', balance: { amount: '25000.00', currencyCode: 'TZS' }, coversPremiumsTo: null,
  });
  vi.mocked(policiesApi.getBalanceStatement).mockResolvedValue({
    policyNumber: 'POL-000001', total: 0, page: 0, size: 25, lines: [],
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('BalanceWithdrawals', () => {
  it('lets a second person approve a withdrawal someone else asked for', async () => {
    grantAccess('payouts.approve');
    vi.mocked(policiesApi.listBalanceWithdrawals).mockResolvedValue([asked]);
    vi.mocked(policiesApi.decideBalanceWithdrawal).mockResolvedValue({ ...asked, status: 'APPROVED' });
    render(<BalanceWithdrawals policyNumber="POL-000001" />);

    await userEvent.click(await screen.findByRole('button', { name: 'Approve' }));
    await userEvent.click(screen.getByRole('button', { name: 'Pay TZS 20,000.00' }));
    await waitFor(() => expect(policiesApi.decideBalanceWithdrawal).toHaveBeenCalledWith('w-1', null));
  });

  it('bars the person who asked unless they may act alone', async () => {
    grantAccess('payouts.approve');
    vi.mocked(policiesApi.listBalanceWithdrawals).mockResolvedValue([{ ...asked, requestedBy: 'staff-one' }]);
    render(<BalanceWithdrawals policyNumber="POL-000001" />);

    expect(await screen.findByText('You asked for this withdrawal, so someone else must approve it.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Approve' })).toBeDisabled();
  });

  it('asks finance to name who receives a withdrawal raised when the policy ended', async () => {
    grantAccess('payouts.manage');
    vi.mocked(policiesApi.listBalanceWithdrawals).mockResolvedValue([
      { ...asked, status: 'AWAITING_PAYEE', reason: 'POLICY_ENDED', method: null, payeeRef: null, requestedBy: 'system' },
    ]);
    vi.mocked(policiesApi.nameBalanceWithdrawalPayee).mockResolvedValue(asked);
    render(<BalanceWithdrawals policyNumber="POL-000001" />);

    await userEvent.type(await screen.findByLabelText(/Mobile-money number/), '+255712000000');
    await userEvent.click(screen.getByRole('button', { name: 'Name the payee' }));
    await waitFor(() => expect(policiesApi.nameBalanceWithdrawalPayee)
      .toHaveBeenCalledWith('w-1', { method: 'MOBILE_MONEY', payeeRef: '+255712000000' }));
  });

  it('lists nothing once every withdrawal is settled', async () => {
    grantAccess('payouts.approve');
    vi.mocked(policiesApi.listBalanceWithdrawals).mockResolvedValue([{ ...asked, status: 'PAID' }]);
    const { container } = render(<BalanceWithdrawals policyNumber="POL-000001" />);
    await waitFor(() => expect(policiesApi.listBalanceWithdrawals).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});

describe('BalanceWithdrawalPage', () => {
  it('asks for a withdrawal after confirming the amount and payee, then returns to the policy', async () => {
    grantAccess('payouts.manage');
    vi.mocked(policiesApi.requestBalanceWithdrawal).mockResolvedValue(asked);
    render(
      <MemoryRouter initialEntries={['/staff/policies/POL-000001/withdraw']}>
        <Routes>
          <Route path="/staff/policies/:policyNumber/withdraw" element={<BalanceWithdrawalPage />} />
          <Route path="/staff/policies/:policyNumber" element={<p>Policy page</p>} />
        </Routes>
      </MemoryRouter>,
    );

    await userEvent.type(await screen.findByLabelText(/^Amount/), '20000.00');
    await userEvent.type(screen.getByLabelText(/Mobile-money number/), '+255712345678');
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
    await userEvent.click(screen.getByRole('button', { name: 'Ask for withdrawal' }));

    await waitFor(() => expect(policiesApi.requestBalanceWithdrawal).toHaveBeenCalledWith('POL-000001', {
      amount: { amount: '20000.00', currencyCode: 'TZS' },
      payee: { method: 'MOBILE_MONEY', payeeRef: '+255712345678' },
    }));
    expect(await screen.findByText('Policy page')).toBeInTheDocument();
  });
});
