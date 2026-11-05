import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as benefitPayoutsApi from '@/api/benefitPayouts';
import type { PolicyView } from '@/api/types';
import { grantAccess } from '@/test/access';
import { useBenefitPayoutStore } from '@/store/benefitPayoutStore';
import { FreeLookPanel } from './FreeLookPanel';

vi.mock('@/api/benefitPayouts');
vi.mock('react-oidc-context', () => ({
  useAuth: () => ({ user: { access_token: 'token-for-staff-one' } }),
}));
vi.mock('@/auth/claims', () => ({
  readIdentity: () => ({ subject: 'staff-one' }),
}));

const policy = { policyNumber: 'POL-1', status: 'ACTIVE', productCategory: 'TERM_LIFE' } as PolicyView;

function renderPanel() {
  render(
    <MemoryRouter>
      <FreeLookPanel policy={policy} />
    </MemoryRouter>,
  );
}

describe('FreeLookPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useBenefitPayoutStore.setState({ freeLook: {} });
    vi.mocked(benefitPayoutsApi.findFreeLook).mockResolvedValue(null);
  });

  it('offers the cancellation to whoever may prepare one', async () => {
    grantAccess('policies.status');
    renderPanel();
    expect(await screen.findByRole('button', { name: 'Cancel in free-look' })).toBeInTheDocument();
  });

  it('offers finance no form the server would refuse -- only that nothing is prepared (2026-11-05)', async () => {
    grantAccess('payouts.approve');
    renderPanel();
    expect(await screen.findByText('No free-look cancellation has been prepared on this policy.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel in free-look' })).not.toBeInTheDocument();
  });
});
