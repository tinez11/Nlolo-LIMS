import { screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getClaimsDashboard } from '@/api/dashboard';
import { useDashboardStore } from '@/store/dashboardStore';
import { renderScreen } from '@/test/renderScreen';
import { ClaimsDashboardPanel } from './ClaimsDashboardPanel';

vi.mock('@/api/dashboard');
vi.mock('@/components/PartyName', () => ({
  PartyName: ({ partyId }: { partyId: string }) => <span>{partyId}</span>,
}));

const TZS = (amount: string) => ({ amount, currencyCode: 'TZS' });
const RANGE = { from: '2026-07', to: '2026-09' };
const VIEW = {
  registered: 17,
  underAssessment: 9,
  settlementRequested: 6,
  approved: 4,
  reopened: 2,
  settled: 128,
  queueStatus: 'SETTLEMENT_REQUESTED' as const,
  queue: [
    {
      claimId: 'claim-1',
      policyNumber: 'POL-00A4B7A1',
      claimantPartyId: 'party-1',
      claimType: 'DEATH' as const,
      dateOfEvent: '2026-09-29',
      status: 'SETTLEMENT_REQUESTED' as const,
    },
  ],
  openDocumentRequests: 11,
  settlements: [],
  settlementSeries: [],
  recentActivity: [],
  period: { from: '2026-07', to: '2026-09', compareFrom: '2025-07', compareTo: '2025-09', currentMonth: '2026-10' },
  paid: TZS('75000000.00'),
  paidBefore: TZS('50000000.00'),
  paidChangePercent: '50.0',
  paidSeries: [
    { month: '2026-07', amount: TZS('0.00') },
    { month: '2026-08', amount: TZS('75000000.00') },
    { month: '2026-09', amount: TZS('0.00') },
  ],
  paidByType: [
    { key: 'DEATH', amount: TZS('60000000.00'), count: 2, percent: '80.0' },
    { key: 'MATURITY', amount: TZS('15000000.00'), count: 1, percent: '20.0' },
  ],
  settledInPeriod: 3,
  registeredInPeriod: 5,
  registeredSeries: [{ month: '2026-07', count: 4 }, { month: '2026-08', count: 0 }, { month: '2026-09', count: 1 }],
  settledSeries: [{ month: '2026-07', count: 0 }, { month: '2026-08', count: 3 }, { month: '2026-09', count: 0 }],
  averageDaysToSettle: '10.0',
  turnaroundSeries: [],
};

beforeEach(() => {
  useDashboardStore.setState({ claims: { status: 'idle', data: null, error: null, loadedAt: null } } as never);
  vi.mocked(getClaimsDashboard).mockResolvedValue(VIEW);
});

describe('ClaimsDashboardPanel', () => {
  it("opens the manager's own queue and reads the range", async () => {
    renderScreen(<ClaimsDashboardPanel range={RANGE} />);

    expect(await screen.findByRole('link', { name: /^6 Awaiting your decision/ })).toHaveAttribute(
      'href',
      '/staff/claims?status=SETTLEMENT_REQUESTED',
    );
    expect(getClaimsDashboard).toHaveBeenCalledWith(RANGE);
  });

  it("shows what was paid over the range, and the server's days to settle", async () => {
    renderScreen(<ClaimsDashboardPanel range={RANGE} />);

    const tile = await screen.findByRole('link', { name: /Benefits paid/ });
    expect(within(tile).getByText('TZS 75M')).toBeInTheDocument();
    expect(tile.querySelector('[data-delta="good"]')).toHaveTextContent('50.0%');
    expect(screen.getByText('10.0 days')).toBeInTheDocument();
  });

  it('names what was paid by cause, with its share', async () => {
    renderScreen(<ClaimsDashboardPanel range={RANGE} />);

    expect(await screen.findByText('80.0%')).toBeInTheDocument();
    expect(screen.getByText('Maturity')).toBeInTheDocument();
  });

  it('draws every open stage, linking those with claims to their list', async () => {
    renderScreen(<ClaimsDashboardPanel range={RANGE} />);

    const stages = await screen.findByRole('list', { name: 'Open claims by stage' });
    expect(within(stages).getByText('Reopened')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Approved, payout not made' })).toHaveAttribute(
      'href',
      '/staff/claims?status=APPROVED',
    );
  });

  /** A manager's queue is titled as the settlement decisions it is. */
  it('titles the queue by whose work it is', async () => {
    renderScreen(<ClaimsDashboardPanel range={RANGE} />);

    expect(await screen.findByRole('heading', { name: 'Awaiting your settlement decision' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open claim-1' })).toHaveAttribute('href', '/staff/claims/claim-1');
  });
});
