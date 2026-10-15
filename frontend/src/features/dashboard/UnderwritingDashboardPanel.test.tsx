import { screen } from '@testing-library/react';
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
  recentActivity: [],
};

beforeEach(() => {
  useDashboardStore.setState({
    underwriting: { status: 'idle', data: null, error: null, loadedAt: null },
  } as never);
  vi.mocked(getUnderwritingDashboard).mockResolvedValue(VIEW);
});

describe('UnderwritingDashboardPanel', () => {
  it('links each count to its own filtered queue', async () => {
    renderScreen(<UnderwritingDashboardPanel />);

    expect(await screen.findByRole('link', { name: /^238 Open cases/ })).toHaveAttribute(
      'href',
      '/staff/underwriting?status=OPEN',
    );
    expect(screen.getByRole('link', { name: /^41 In review/ })).toHaveAttribute(
      'href',
      '/staff/underwriting?status=IN_REVIEW',
    );
  });

  /** Could not be read: Not available, and no link to a list that would contradict it. */
  it('shows a figure it could not read as not available', async () => {
    renderScreen(<UnderwritingDashboardPanel />);

    await screen.findByRole('link', { name: /^238 Open cases/ });
    expect(screen.getAllByText('Not available').length).toBeGreaterThan(0);
  });

  it('lists the oldest open cases with a way into each', async () => {
    renderScreen(<UnderwritingDashboardPanel />);

    expect(await screen.findByText('party-1')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Review case-1' })).toHaveAttribute(
      'href',
      '/staff/underwriting/case-1',
    );
  });
});
