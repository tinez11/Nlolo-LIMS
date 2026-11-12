import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getMySales, getMySalesReport, getPoliciesSold } from '@/api/agentSales';
import { getOwnAgent, getOwnTeam } from '@/api/distribution';
import type { ReportResult } from '@/api/reporting';
import { idle } from '@/store/createResourceSlice';
import { useDistributionStore } from '@/store/distributionStore';
import { renderScreen } from '@/test/renderScreen';
import { MySalesPage } from './MySalesPage';

vi.mock('@/api/agentSales');
vi.mock('@/api/distribution');

const REPORT = {
  key: 'my-sales', title: 'My sales', filterSummary: '1 Jan 2025 to 12 Nov 2026', generatedAt: '2026-11-12T08:00:00Z',
  figures: [{
    key: 'premiumAYear', label: 'Premium sold a year', value: '1200000.00', prior: '600000.00', change: '600000.00',
    changePercent: '100.0', goodWhen: 'UP', lead: true, unit: 'MONEY',
  }],
  sections: [],
  chart: null,
  comparisonLabel: 'the period before',
  notices: [],
  dataUpTo: null,
} as ReportResult;

const ME = { agentId: 'agent-me', partyId: 'party-me' };

beforeEach(() => {
  vi.clearAllMocks();
  useDistributionStore.setState({ ownAgent: idle(), ownTeam: idle() } as never);
  vi.mocked(getOwnAgent).mockResolvedValue(ME as never);
  vi.mocked(getOwnTeam).mockResolvedValue([ME] as never);
  vi.mocked(getMySalesReport).mockResolvedValue(REPORT);
  vi.mocked(getMySales).mockResolvedValue([{
    bucket: '2026-Q4', currency: 'TZS', policies: 2, premiumAYear: 1200000, singlePremiums: 500000,
    cover: 2000000, savingsDeposits: 20000,
  }]);
  vi.mocked(getPoliciesSold).mockResolvedValue([
    {
      policyNumber: 'A-TERM', productName: 'Term life', clientName: 'Neema Paid', onCoverFrom: '2026-10-05',
      frequency: 'MONTHLY', premium: 100000, premiumAYear: 1200000, cover: 2000000, savingsDeposit: null,
      currency: 'TZS', agentName: 'Asha Agent',
    },
    {
      policyNumber: 'A-SAV', productName: 'Savings', clientName: 'Juma Saver', onCoverFrom: '2026-10-07',
      frequency: 'MONTHLY', premium: 20000, premiumAYear: null, cover: null, savingsDeposit: 20000,
      currency: 'TZS', agentName: 'Asha Agent',
    },
  ]);
});

describe('MySalesPage', () => {
  it('asks for the quarter by default, compared with the period before, and shows the figures', async () => {
    renderScreen(<MySalesPage />, { route: '/agents/sales?from=2025-01-01&to=2026-11-12' });

    expect(await screen.findByText('Premium sold a year')).toBeInTheDocument();
    expect(getMySalesReport).toHaveBeenCalledWith({
      from: '2025-01-01', to: '2026-11-12', by: 'QUARTER', compareWith: 'PRIOR_PERIOD',
    });
    expect(screen.queryByRole('group', { name: 'Whose sales' })).not.toBeInTheDocument();
  });

  /** Each period opens the policies behind it -- a savings policy shows its deposit, not a premium a year (D3). */
  it('opens the policies sold in a period', async () => {
    renderScreen(<MySalesPage />, { route: '/agents/sales?from=2025-01-01&to=2026-11-12' });

    await userEvent.click(await screen.findByRole('button', { name: 'Oct–Dec 2026 · 2' }));

    expect(await screen.findByRole('link', { name: 'A-TERM' })).toHaveAttribute('href', '/agents/policies/A-TERM');
    expect(screen.getByText('Deposit TZS 20,000.00')).toBeInTheDocument();
    expect(getPoliciesSold).toHaveBeenCalledWith('2026-10-01', '2026-11-12', undefined);
  });

  it('shows by month when asked, and lets a supervisor narrow to their own sales', async () => {
    vi.mocked(getOwnTeam).mockResolvedValue([ME, { agentId: 'agent-2', partyId: 'party-2' }] as never);
    renderScreen(<MySalesPage />, { route: '/agents/sales?from=2025-01-01&to=2026-11-12' });

    await userEvent.click(await screen.findByRole('button', { name: 'Month' }));
    await waitFor(() => expect(getMySalesReport).toHaveBeenLastCalledWith(expect.objectContaining({ by: 'MONTH' })));

    await userEvent.click(await screen.findByRole('button', { name: 'Me' }));
    await waitFor(() => expect(getMySalesReport).toHaveBeenLastCalledWith(expect.objectContaining({ agentId: 'agent-me' })));
  });

  it('says so when nothing was sold in the dates', async () => {
    vi.mocked(getMySales).mockResolvedValue([]);
    renderScreen(<MySalesPage />, { route: '/agents/sales?from=2025-01-01&to=2026-11-12' });

    expect(await screen.findByText('Nothing sold in these dates')).toBeInTheDocument();
  });
});
