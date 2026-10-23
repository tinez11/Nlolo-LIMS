import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getAgentDashboard } from '@/api/dashboard';
import { useDashboardStore } from '@/store/dashboardStore';
import { renderScreen } from '@/test/renderScreen';
import { AgentDashboardPanel } from './AgentDashboardPanel';

vi.mock('@/api/dashboard');

const TZS = (amount: string) => ({ amount, currencyCode: 'TZS' });
const RANGE = { from: '2026-07', to: '2026-09' };
const VIEW = {
  clients: 309,
  clientsAwaitingKyc: 23,
  clientsKycRejected: 1,
  policiesInBook: 412,
  applicationsInUnderwriting: 5,
  commissionPeriod: '2026-09',
  commissionEarned: TZS('1284500.00'),
  commissionStatus: 'CLOSED',
  awaitingKyc: [{ partyId: 'party-1', displayName: 'Amina Hassan', kycStatus: 'PENDING' as const }],
  period: { from: '2026-07', to: '2026-09', compareFrom: '2025-07', compareTo: '2025-09', currentMonth: '2026-10' },
  commissionInPeriod: TZS('1284500.00'),
  commissionSeries: [
    { month: '2026-07', paid: TZS('1000000.00'), pending: TZS('0.00') },
    { month: '2026-08', paid: TZS('0.00'), pending: TZS('0.00') },
    { month: '2026-09', paid: TZS('0.00'), pending: TZS('284500.00') },
  ],
  sold: 3,
  soldBefore: 1,
  soldChangePercent: '200.0',
  soldSeries: [{ month: '2026-07', count: 2 }, { month: '2026-08', count: 0 }, { month: '2026-09', count: 1 }],
  bookByProduct: [
    { key: 'TERM_LIFE', amount: null, count: 300, percent: '72.8' },
    { key: 'FUNERAL', amount: null, count: 112, percent: '27.2' },
  ],
};

beforeEach(() => {
  useDashboardStore.setState({ agent: { status: 'idle', data: null, error: null, loadedAt: null } } as never);
  vi.mocked(getAgentDashboard).mockResolvedValue(VIEW);
});

describe('AgentDashboardPanel', () => {
  it("links each count to the agent's own filtered list", async () => {
    renderScreen(<AgentDashboardPanel range={RANGE} />);

    expect(await screen.findByRole('link', { name: '309 My clients' })).toHaveAttribute('href', '/agents/clients');
    expect(screen.getByRole('link', { name: '23 Clients awaiting KYC' })).toHaveAttribute(
      'href',
      '/agents/clients?kycStatus=PENDING',
    );
    expect(screen.getByRole('link', { name: '412 Policies in my book' })).toHaveAttribute('href', '/agents/policies');
    expect(getAgentDashboard).toHaveBeenCalledWith(RANGE);
  });

  /** The agent's job here is sending the papers: each pending client opens straight on its KYC tab. */
  it('sends each pending client to their KYC tab', async () => {
    renderScreen(<AgentDashboardPanel range={RANGE} />);

    expect(await screen.findByRole('link', { name: 'Send documents for Amina Hassan' })).toHaveAttribute(
      'href',
      '/agents/parties/party-1?tab=kyc',
    );
  });

  /** Commission in full, never abbreviated: an agent checks it against their statement to the shilling. */
  it('shows commission in full', async () => {
    renderScreen(<AgentDashboardPanel range={RANGE} />);

    expect(await screen.findByText('TZS 1,284,500.00')).toBeInTheDocument();
  });

  it('counts the applications still in underwriting and the book by product', async () => {
    renderScreen(<AgentDashboardPanel range={RANGE} />);

    expect(await screen.findByText('Applications in underwriting')).toBeInTheDocument();
    expect(screen.getByText('72.8%')).toBeInTheDocument();
    expect(screen.getByText('Term life')).toBeInTheDocument();
  });
});
