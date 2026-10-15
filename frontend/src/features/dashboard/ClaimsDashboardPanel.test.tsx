import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getClaimsDashboard } from '@/api/dashboard';
import { useDashboardStore } from '@/store/dashboardStore';
import { renderScreen } from '@/test/renderScreen';
import { ClaimsDashboardPanel } from './ClaimsDashboardPanel';

vi.mock('@/api/dashboard');
vi.mock('@/components/PartyName', () => ({
  PartyName: ({ partyId }: { partyId: string }) => <span>{partyId}</span>,
}));

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
  recentActivity: [],
};

beforeEach(() => {
  useDashboardStore.setState({ claims: { status: 'idle', data: null, error: null, loadedAt: null } } as never);
  vi.mocked(getClaimsDashboard).mockResolvedValue(VIEW);
});

describe('ClaimsDashboardPanel', () => {
  it('links every stage to the claims list filtered to it', async () => {
    renderScreen(<ClaimsDashboardPanel />);

    expect(await screen.findByRole('link', { name: /^6 Awaiting settlement decision/ })).toHaveAttribute(
      'href',
      '/staff/claims?status=SETTLEMENT_REQUESTED',
    );
    expect(screen.getByRole('link', { name: /^17 Awaiting assessment/ })).toHaveAttribute(
      'href',
      '/staff/claims?status=REGISTERED',
    );
    expect(screen.getByRole('link', { name: /^4 Approved, not yet paid/ })).toHaveAttribute(
      'href',
      '/staff/claims?status=APPROVED',
    );
    // All time: the card must say so, because there is no date filter to make it "this month".
    expect(screen.getByRole('link', { name: /^128 Settled, all time/ })).toBeInTheDocument();
  });

  /** A manager's queue is titled as the settlement decisions it is. */
  it('titles the queue by whose work it is', async () => {
    renderScreen(<ClaimsDashboardPanel />);

    expect(
      await screen.findByRole('heading', { name: 'Awaiting your settlement decision' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open claim-1' })).toHaveAttribute('href', '/staff/claims/claim-1');
  });
});
