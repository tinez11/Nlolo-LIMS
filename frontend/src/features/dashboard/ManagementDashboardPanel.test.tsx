import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getManagementDashboard, type ManagementDashboard } from '@/api/dashboard';
import { idle } from '@/store/createResourceSlice';
import { useDashboardStore } from '@/store/dashboardStore';
import { renderScreen } from '@/test/renderScreen';
import { ManagementDashboardPanel } from './ManagementDashboardPanel';

vi.mock('@/api/dashboard');

const TZS = (amount: string) => ({ amount, currencyCode: 'TZS' });
const RANGE = { from: '2026-07', to: '2026-09' };
const VIEW: ManagementDashboard = {
  period: { from: '2026-07', to: '2026-09', compareFrom: '2025-07', compareTo: '2025-09', currentMonth: '2026-11' },
  collected: TZS('5000000.00'),
  collectedBefore: TZS('4000000.00'),
  collectedChangePercent: '25.0',
  newPolicies: 30,
  newPoliciesBefore: 40,
  newPoliciesChangePercent: '-25.0',
  policiesInForce: 412,
  collections: {
    from: '2026-07-01', to: '2026-09-30', due: 6000000, paid: 5000000, ratePercent: '83.3',
    dueTodayCount: 0, dueTodayOwed: 0, overdueCount: 4, overdueOwed: 250000, currency: 'TZS',
  },
  lapsed: 7,
  claimsPaid: TZS('9000000.00'),
  openClaims: 9,
  casesToDecide: 4,
  claimsToDecide: 2,
};

beforeEach(() => {
  useDashboardStore.setState({ overview: idle() } as never);
  vi.mocked(getManagementDashboard).mockResolvedValue(VIEW);
});

describe('ManagementDashboardPanel', () => {
  /** Money in full, each figure once, the change with an arrow and words. */
  it('leads with what came in, what was sold and what is on the books', async () => {
    renderScreen(<ManagementDashboardPanel range={RANGE} />);

    expect(await screen.findByText('TZS 5,000,000.00')).toBeInTheDocument();
    expect(screen.getByText(/25.0% up/)).toBeInTheDocument();
    expect(screen.getByText(/25.0% down/)).toBeInTheDocument();
    expect(screen.getByText('412')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Policies in force' })).toHaveAttribute('href', '/staff/reports/policies-in-force');
    expect(getManagementDashboard).toHaveBeenCalledWith(RANGE);
  });

  it('opens what each card counted', async () => {
    renderScreen(<ManagementDashboardPanel range={RANGE} />);

    expect(await screen.findByText('83.3%')).toBeInTheDocument();
    expect(screen.getByText('TZS 250,000.00 overdue')).toBeInTheDocument();
    expect(screen.getByText('9 still open')).toBeInTheDocument();
    expect(screen.getByText('4 proposals · 2 claims')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Claims paid/ })).toHaveAttribute('href', '/staff/reports/claims-in-progress');
    expect(screen.getByRole('link', { name: /Waiting for a decision/ })).toHaveAttribute('href', '/staff/underwriting');
  });

  /** A figure that could not be read says so; it is never shown as zero. */
  it('says a figure is not available rather than zero', async () => {
    vi.mocked(getManagementDashboard).mockResolvedValue({ ...VIEW, collected: null, policiesInForce: null, claimsToDecide: null });
    renderScreen(<ManagementDashboardPanel range={RANGE} />);

    expect(await screen.findAllByText('Not available')).not.toHaveLength(0);
    expect(screen.queryByText('TZS 0.00')).not.toBeInTheDocument();
  });
});
