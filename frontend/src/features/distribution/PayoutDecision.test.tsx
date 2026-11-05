import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as distributionApi from '@/api/distribution';
import type { CommissionStatementView } from '@/api/types';
import { grantAccess } from '@/test/access';
import { useDistributionStore } from '@/store/distributionStore';
import { PayoutDecision } from './PayoutDecision';

vi.mock('@/api/distribution');
vi.mock('react-oidc-context', () => ({
  useAuth: () => ({ user: { access_token: 'token-for-staff-one' } }),
}));
vi.mock('@/auth/claims', () => ({
  readIdentity: () => ({ subject: 'staff-one' }),
}));

const waiting: CommissionStatementView = {
  statementId: 'stmt-1', agentId: 'agent-1', period: '2026-10', totalAmount: { amount: '50000.00', currencyCode: 'TZS' },
  status: 'AWAITING_PAYOUT_APPROVAL', closedAt: '2026-11-01T00:00:00Z', paidAt: null, payeeRef: 'MPESA-0715000001',
  payoutRequestedBy: 'staff-two', payoutRefusalReason: null,
};

describe('PayoutDecision', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useDistributionStore.setState({ decidingPayout: {} });
    vi.mocked(distributionApi.listCommissionStatements).mockResolvedValue([]);
  });

  it('lets a second person release a commission payout, naming whom it pays', async () => {
    grantAccess('payouts.approve');
    vi.mocked(distributionApi.decideCommissionPayout).mockResolvedValue({ ...waiting, status: 'PAYOUT_REQUESTED' });
    render(<PayoutDecision agentId="agent-1" statement={waiting} />);

    expect(screen.getByText(/Payout asked to MPESA-0715000001/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Release' }));
    await userEvent.click(screen.getByRole('button', { name: 'Release payout' }));
    await waitFor(() => expect(distributionApi.decideCommissionPayout).toHaveBeenCalledWith('stmt-1', null));
  });

  it('bars the person who asked unless they may act alone', () => {
    grantAccess('payouts.approve');
    render(<PayoutDecision agentId="agent-1" statement={{ ...waiting, payoutRequestedBy: 'staff-one' }} />);
    expect(screen.getByText('You asked for this payout, so someone else must release it.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Release' })).toBeDisabled();
  });

  it('refuses only with a reason', async () => {
    grantAccess('payouts.approve');
    vi.mocked(distributionApi.decideCommissionPayout).mockResolvedValue({ ...waiting, status: 'CLOSED' });
    render(<PayoutDecision agentId="agent-1" statement={waiting} />);
    await userEvent.click(screen.getByRole('button', { name: 'Refuse' }));
    expect(screen.getByRole('button', { name: 'Refuse payout' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Why is it refused?'), 'Wrong number');
    await userEvent.click(screen.getByRole('button', { name: 'Refuse payout' }));
    await waitFor(() => expect(distributionApi.decideCommissionPayout).toHaveBeenCalledWith('stmt-1', 'Wrong number'));
  });
});
