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
  casesReferred: 3,
  decisions: [
    { month: '2026-09', outcome: 'ACCEPT' as const, cases: 42, averageDays: '3.4' },
    { month: '2026-09', outcome: 'DECLINED' as const, cases: 7, averageDays: '2.1' },
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

  /** Phase 2: the card that read "Not available" now reads a number, and links to the list that proves it. */
  it("shows last month decisions and links each to its own filtered list", async () => {
    renderScreen(<UnderwritingDashboardPanel />);

    expect(await screen.findByRole("link", { name: /^42 Accepted, September/ })).toHaveAttribute(
      "href",
      "/staff/underwriting?outcome=ACCEPT&decidedFrom=2026-09-01&decidedTo=2026-09-30",
    );
    expect(screen.getByRole("link", { name: /^7 Declined, September/ })).toBeInTheDocument();
    // The turnaround rides on the card as its hint, so a reader sees the count and the time together.
    expect(screen.getByText(/3.4 days on average/)).toBeInTheDocument();
  });

  it("counts the cases waiting on a senior", async () => {
    renderScreen(<UnderwritingDashboardPanel />);

    expect(await screen.findByRole("link", { name: /^3 Waiting on a senior/ })).toHaveAttribute(
      "href",
      "/staff/underwriting?referral=REFERRED_TO_SENIOR",
    );
  });
});
