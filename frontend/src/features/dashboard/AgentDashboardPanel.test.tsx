import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getAgentDashboard } from '@/api/dashboard';
import { useDashboardStore } from '@/store/dashboardStore';
import { renderScreen } from '@/test/renderScreen';
import { AgentDashboardPanel } from './AgentDashboardPanel';

vi.mock('@/api/dashboard');

const VIEW = {
  clients: 309,
  clientsAwaitingKyc: 23,
  clientsKycRejected: 1,
  policiesInBook: 412,
  applicationsInUnderwriting: 5,
  commissionPeriod: '2026-09',
  commissionEarned: { amount: '1284500.00', currencyCode: 'TZS' },
  commissionStatus: 'CLOSED',
  awaitingKyc: [{ partyId: 'party-1', displayName: 'Amina Hassan', kycStatus: 'PENDING' as const }],
};

beforeEach(() => {
  useDashboardStore.setState({ agent: { status: 'idle', data: null, error: null, loadedAt: null } } as never);
  vi.mocked(getAgentDashboard).mockResolvedValue(VIEW);
});

describe('AgentDashboardPanel', () => {
  it('links each count to the agent own filtered list', async () => {
    renderScreen(<AgentDashboardPanel />);

    expect(await screen.findByRole('link', { name: /^309 My clients/ })).toHaveAttribute('href', '/agents/clients');
    expect(screen.getByRole('link', { name: /^23 Clients awaiting KYC/ })).toHaveAttribute(
      'href',
      '/agents/clients?kycStatus=PENDING',
    );
    expect(screen.getByRole('link', { name: /^412 Policies in my book/ })).toHaveAttribute(
      'href',
      '/agents/policies',
    );
  });

  /** The agent's job here is sending the papers: each pending client opens straight on its KYC tab. */
  it('sends each pending client to their KYC tab', async () => {
    renderScreen(<AgentDashboardPanel />);

    expect(await screen.findByRole('link', { name: 'Send documents for Amina Hassan' })).toHaveAttribute(
      'href',
      '/agents/parties/party-1?tab=kyc',
    );
  });

  /** Commission in full, never abbreviated: an agent checks it against their statement to the shilling. */
  it('shows commission in full', async () => {
    renderScreen(<AgentDashboardPanel />);

    expect(await screen.findByText(/1,284,500\.00/)).toBeInTheDocument();
  });
  /** Phase 2: never a gap -- the agent own applications still with underwriting. */
  it("counts the agent own applications in underwriting", async () => {
    renderScreen(<AgentDashboardPanel />);

    expect(await screen.findByText(/Applications in underwriting/)).toBeInTheDocument();
    expect(screen.getByText("5")).toBeInTheDocument();
  });

});
