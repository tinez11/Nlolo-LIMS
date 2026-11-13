import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as distributionApi from '@/api/distribution';
import type { CommissionPlanView } from '@/api/types';
import { grantAccess } from '@/test/access';
import { idle } from '@/store/createResourceSlice';
import { useDistributionStore } from '@/store/distributionStore';
import { CommissionPlansPage } from './CommissionPlansPage';

vi.mock('@/api/distribution');
vi.mock('react-oidc-context', () => ({
  useAuth: () => ({ user: { access_token: 'token-for-staff-one' } }),
}));
vi.mock('@/auth/claims', () => ({
  readIdentity: () => ({ subject: 'staff-one' }),
}));

const proposed: CommissionPlanView = {
  planId: 'plan-1', productId: 'prod-1', status: 'PROPOSED',
  rules: [{ commissionRuleId: 'rule-1', tierType: 'FIRST_YEAR', rate: '0.1000', flatAmount: null }],
  proposedBy: 'staff-two', proposedForAgentId: null, decidedBy: null, decisionReason: null,
  proposedAt: '2026-11-12T08:00:00Z', productName: 'CLive Savings', agentName: null,
};

function renderPage() {
  return render(<MemoryRouter><CommissionPlansPage /></MemoryRouter>);
}

describe('CommissionPlansPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useDistributionStore.setState({ proposedPlans: idle(), decidingPlan: {}, plans: {} });
  });

  it('lets a second person approve a waiting plan, showing what it pays', async () => {
    grantAccess('agents.view', 'commission.approve');
    vi.mocked(distributionApi.listProposedCommissionPlans).mockResolvedValueOnce([proposed]).mockResolvedValue([]);
    vi.mocked(distributionApi.decideCommissionPlan).mockResolvedValue({ ...proposed, status: 'ACTIVE' });
    renderPage();

    expect(await screen.findByText('CLive Savings')).toBeInTheDocument();
    expect(screen.getByText('First year: 10% of the premium')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Approve plan' }));
    await waitFor(() => expect(distributionApi.decideCommissionPlan).toHaveBeenCalledWith('plan-1', null));
    expect(await screen.findByText('Nothing waiting')).toBeInTheDocument();
  });

  it('bars its proposer from approving without commission.approve-own, but lets them withdraw it', async () => {
    grantAccess('agents.view', 'commission.approve');
    vi.mocked(distributionApi.listProposedCommissionPlans).mockResolvedValue([{ ...proposed, proposedBy: 'staff-one' }]);
    renderPage();

    expect(await screen.findByText('You proposed this, so someone else must approve it.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Approve plan' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Withdraw' })).toBeEnabled();
  });

  it('lets its proposer approve alone holding commission.approve-own', async () => {
    grantAccess('agents.view', 'commission.approve', 'commission.approve-own');
    vi.mocked(distributionApi.listProposedCommissionPlans).mockResolvedValue([{ ...proposed, proposedBy: 'staff-one' }]);
    renderPage();

    expect(await screen.findByRole('button', { name: 'Approve plan' })).toBeEnabled();
    expect(screen.getByText(/you may approve it alone/)).toBeInTheDocument();
  });

  it('rejects only with a reason', async () => {
    grantAccess('agents.view', 'commission.approve');
    vi.mocked(distributionApi.listProposedCommissionPlans).mockResolvedValue([proposed]);
    vi.mocked(distributionApi.decideCommissionPlan).mockResolvedValue({ ...proposed, status: 'REJECTED' });
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Reject' }));
    expect(screen.getByRole('button', { name: 'Reject plan' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/Why is it rejected/), 'Rate typed wrong');
    await userEvent.click(screen.getByRole('button', { name: 'Reject plan' }));
    await waitFor(() => expect(distributionApi.decideCommissionPlan).toHaveBeenCalledWith('plan-1', 'Rate typed wrong'));
  });
});
