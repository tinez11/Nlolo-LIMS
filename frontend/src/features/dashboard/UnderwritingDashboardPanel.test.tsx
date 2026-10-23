import { screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getUnderwritingDashboard } from '@/api/dashboard';
import { useDashboardStore } from '@/store/dashboardStore';
import { renderScreen } from '@/test/renderScreen';
import { UnderwritingDashboardPanel } from './UnderwritingDashboardPanel';

vi.mock('@/api/dashboard');
vi.mock('@/components/PartyName', () => ({
  PartyName: ({ partyId }: { partyId: string }) => <span>{partyId}</span>,
}));
vi.mock('@/components/ProductName', () => ({ ProductName: () => <span>Nuru-Mpya</span> }));

const RANGE = { from: '2026-07', to: '2026-09' };
const VIEW = {
  openCases: 238,
  casesInReview: 41,
  casesAwaitingIssue: null,
  queue: [
    {
      caseId: 'case-1',
      applicantPartyId: 'party-1',
      lifeAssuredPartyId: null,
      productId: 'prod-1',
      sumAssured: { amount: '25000000.00', currencyCode: 'TZS' },
      status: 'OPEN' as const,
    },
  ],
  casesReferred: 3,
  decisions: [],
  decisionSeries: [],
  recentActivity: [],
  period: { from: '2026-07', to: '2026-09', compareFrom: '2025-07', compareTo: '2025-09', currentMonth: '2026-10' },
  decided: 10,
  decidedBefore: 8,
  decidedChangePercent: '25.0',
  acceptanceRate: '70.0',
  averageDays: '3.6',
  outcomeTotals: [
    { key: 'ACCEPT', amount: null, count: 6, percent: '60.0' },
    { key: 'DECLINED', amount: null, count: 4, percent: '40.0' },
  ],
  outcomeSeries: [
    { month: '2026-07', accepted: 6, postponed: 0, declined: 0 },
    { month: '2026-08', accepted: 0, postponed: 0, declined: 0 },
    { month: '2026-09', accepted: 0, postponed: 0, declined: 4 },
  ],
  turnaroundSeries: [
    { month: '2026-07', averageDays: '3.0' },
    { month: '2026-08', averageDays: null },
    { month: '2026-09', averageDays: '5.0' },
  ],
};

beforeEach(() => {
  useDashboardStore.setState({
    underwriting: { status: 'idle', data: null, error: null, loadedAt: null },
  } as never);
  vi.mocked(getUnderwritingDashboard).mockResolvedValue(VIEW);
});

describe('UnderwritingDashboardPanel', () => {
  it('links the open cases to their queue and the decided ones to the range they were decided in', async () => {
    renderScreen(<UnderwritingDashboardPanel range={RANGE} />);

    expect(await screen.findByRole('link', { name: /^238 Open cases/ })).toHaveAttribute(
      'href',
      '/staff/underwriting?status=OPEN',
    );
    expect(screen.getByRole('link', { name: /^10 Decided/ })).toHaveAttribute(
      'href',
      '/staff/underwriting?decidedFrom=2026-07-01&decidedTo=2026-09-30',
    );
    expect(getUnderwritingDashboard).toHaveBeenCalledWith(RANGE);
  });

  it("shows the server's rate and average, never its own", async () => {
    renderScreen(<UnderwritingDashboardPanel range={RANGE} />);

    expect(await screen.findByText('70.0%')).toBeInTheDocument();
    expect(screen.getByText('3.6 days')).toBeInTheDocument();
  });

  it('names how cases ended beside the ring', async () => {
    renderScreen(<UnderwritingDashboardPanel range={RANGE} />);

    expect(await screen.findByText('Accepted', { selector: 'li span' })).toBeInTheDocument();
    expect(screen.getByText('60.0%')).toBeInTheDocument();
  });

  it('draws where open cases are, with a figure it could not read as a dash', async () => {
    renderScreen(<UnderwritingDashboardPanel range={RANGE} />);

    const stages = await screen.findByRole('list', { name: 'Open cases by stage' });
    expect(within(stages).getByText('Waiting on a senior')).toBeInTheDocument();
    expect(within(stages).getByText('Accepted, awaiting issue')).toBeInTheDocument();
    expect(within(stages).getAllByText('—').length).toBeGreaterThan(0);
  });

  it('lists the oldest open cases with a way into each', async () => {
    renderScreen(<UnderwritingDashboardPanel range={RANGE} />);

    expect(await screen.findByText('party-1')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Review case-1' })).toHaveAttribute('href', '/staff/underwriting/case-1');
  });
});
