import { screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getApprovals } from '@/api/approvals';
import { getFinanceDashboard } from '@/api/dashboard';
import { useApprovalsStore } from '@/store/approvalsStore';
import { useDashboardStore } from '@/store/dashboardStore';
import { grantRoles } from '@/test/access';
import { renderScreen } from '@/test/renderScreen';
import { FinanceDashboardPanel } from './FinanceDashboardPanel';
import { lastDayOf, reportLink } from './reportLinks';

vi.mock('@/api/dashboard');
vi.mock('@/api/approvals');

const TZS = (amount: string) => ({ amount, currencyCode: 'TZS' });
const RANGE = { from: '2026-07', to: '2026-09' };
const VIEW = {
  premiumsMonth: '2026-09',
  premiumsCollected: TZS('50204310.00'),
  premiumsChangePercent: '2.2',
  balanceSheetMonth: '2026-10',
  totalAssets: TZS('742580120.00'),
  totalLiabilities: TZS('512340210.00'),
  resultFrom: '2026-01',
  resultTo: '2026-10',
  netResult: TZS('31204880.00'),
  journalsAwaitingApproval: 4,
  receiptsOverdue: 7,
  transfersAwaiting: 3,
  arrearsAtLapse: null,
  premiumsThisYear: [],
  premiumsLastYear: [],
  currentMonth: '2026-10',
  receivables: [
    {
      currency: 'TZS',
      buckets: [
        { name: 'NOT_DUE' as const, cases: 1, outstanding: TZS('100000.00') },
        { name: 'OVER_90' as const, cases: 2, outstanding: TZS('400000.00') },
      ],
    },
  ],
  recentActivity: [],
  period: { from: '2026-07', to: '2026-09', compareFrom: '2025-07', compareTo: '2025-09', currentMonth: '2026-10' },
  collected: TZS('150000000.00'),
  collectedBefore: TZS('100000000.00'),
  collectedChangePercent: '50.0',
  collectedSeries: [
    { month: '2026-07', amount: TZS('120000000.00') },
    { month: '2026-08', amount: TZS('0.00') },
    { month: '2026-09', amount: TZS('30000000.00') },
  ],
  collectedCompareSeries: [
    { month: '2025-07', amount: TZS('100000000.00') },
    { month: '2025-08', amount: TZS('0.00') },
    { month: '2025-09', amount: TZS('0.00') },
  ],
  collectedByProductLine: [
    { key: 'TERM_LIFE', amount: TZS('100000000.00'), count: null, percent: '66.7' },
    { key: 'FUNERAL', amount: TZS('50000000.00'), count: null, percent: '33.3' },
  ],
  newPolicies: 41,
  newPoliciesBefore: 50,
  newPoliciesChangePercent: '-18.0',
  newPoliciesSeries: [{ month: '2026-07', count: 20 }, { month: '2026-08', count: 11 }, { month: '2026-09', count: 10 }],
  outstanding: TZS('500000.00'),
};

beforeEach(() => {
  useDashboardStore.setState({ finance: { status: 'idle', data: null, error: null, loadedAt: null } } as never);
  vi.mocked(getFinanceDashboard).mockResolvedValue(VIEW);
  useApprovalsStore.setState({ approvals: { status: 'idle', data: null, error: null, loadedAt: null } } as never);
  vi.mocked(getApprovals).mockResolvedValue({ actionable: 2, groups: [] } as never);
});

describe('FinanceDashboardPanel', () => {
  it('reads the range it is given', async () => {
    renderScreen(<FinanceDashboardPanel range={RANGE} />);

    await screen.findByText('Premiums collected', { selector: 'span' });
    expect(getFinanceDashboard).toHaveBeenCalledWith(RANGE);
  });

  it("opens the range's collections as their report, and says the change is good news", async () => {
    renderScreen(<FinanceDashboardPanel range={RANGE} />);

    const tile = await screen.findByRole('link', { name: /Premiums collected/ });
    expect(tile).toHaveAttribute('href', '/staff/reports/premium-collections?from=2026-07-01&to=2026-09-30');
    expect(within(tile).getByText('TZS 150M')).toBeInTheDocument();
    expect(tile.querySelector('[data-delta="good"]')).toHaveTextContent('50.0%');
    expect(within(tile).getByText('vs Jul to Sep 2025')).toBeInTheDocument();
  });

  it('marks fewer new policies as bad news, in words as well as colour', async () => {
    renderScreen(<FinanceDashboardPanel range={RANGE} />);

    const label = await screen.findByText('New policies');
    const tile = label.closest('div') as HTMLElement;
    expect(tile.querySelector('[data-delta="bad"]')).toHaveTextContent('18.0% down');
  });

  it("names every product line with the server's share", async () => {
    renderScreen(<FinanceDashboardPanel range={RANGE} />);

    expect(await screen.findByText('Term life')).toBeInTheDocument();
    expect(screen.getByText('66.7%')).toBeInTheDocument();
    expect(screen.getByText('33.3%')).toBeInTheDocument();
  });

  it('ages what is owed, drawing a band the server left out at zero', async () => {
    renderScreen(<FinanceDashboardPanel range={RANGE} />);

    const bands = await screen.findByRole('list', { name: 'Owed by how overdue' });
    expect(within(bands).getByText('Over 90 days')).toBeInTheDocument();
    expect(within(bands).getByText('TZS 400K')).toBeInTheDocument();
    expect(within(bands).getByText('31 to 60 days')).toBeInTheDocument();
  });

  /** Only what the viewer can act on (2026-10-27): their own signatures, not every journal waiting for anyone. */
  it('counts the signatures waiting for this viewer, and each list they can work', async () => {
    grantRoles('FINANCE_OFFICER');
    renderScreen(<FinanceDashboardPanel range={RANGE} />);

    expect(await screen.findByRole('link', { name: /Waiting for your signatures*2/ })).toHaveAttribute('href', '/staff/approvals');
    expect(screen.queryByText('Journals waiting for approval')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Field receipts past their deadline/ })).toHaveAttribute(
      'href',
      '/staff/field-receipts?status=RECONCILIATION_OVERDUE',
    );
  });

  it('shows a finance approver no list they cannot work', async () => {
    grantRoles('FINANCE_APPROVER');
    renderScreen(<FinanceDashboardPanel range={RANGE} />);

    expect(await screen.findByRole('link', { name: /Waiting for your signature/ })).toBeInTheDocument();
    expect(screen.queryByText('Field receipts past their deadline')).not.toBeInTheDocument();
    expect(screen.queryByText('Bank transfers not yet made')).not.toBeInTheDocument();
    expect(screen.queryByText('Policies in arrears at the lapse level')).not.toBeInTheDocument();
  });

  it('builds links and month ends correctly', () => {
    expect(reportLink('balance-sheet', { monthFrom: '2026-10' })).toBe('/staff/reports/balance-sheet?monthFrom=2026-10');
    expect(lastDayOf('2026-02')).toBe('2026-02-28');
    expect(lastDayOf('2028-02')).toBe('2028-02-29');
  });
});
