import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getFinanceDashboard } from '@/api/dashboard';
import { useDashboardStore } from '@/store/dashboardStore';
import { renderScreen } from '@/test/renderScreen';
import { FinanceDashboardPanel } from './FinanceDashboardPanel';
import { lastDayOf, reportLink } from './reportLinks';

vi.mock('@/api/dashboard');

const TZS = (amount: string) => ({ amount, currencyCode: 'TZS' });
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
};

beforeEach(() => {
  useDashboardStore.setState({ finance: { status: 'idle', data: null, error: null, loadedAt: null } } as never);
  vi.mocked(getFinanceDashboard).mockResolvedValue(VIEW);
});

describe('FinanceDashboardPanel', () => {
  it('opens each money figure as its report, with the period it was read for', async () => {
    renderScreen(<FinanceDashboardPanel />);

    expect(await screen.findByRole('link', { name: /Premiums collected, Sep/ })).toHaveAttribute(
      'href',
      '/staff/reports/premium-collections?from=2026-09-01&to=2026-09-30',
    );
    expect(screen.getByRole('link', { name: /Total assets/ })).toHaveAttribute(
      'href',
      '/staff/reports/balance-sheet?monthFrom=2026-10',
    );
    expect(screen.getByRole('link', { name: /Net result, year to date/ })).toHaveAttribute(
      'href',
      '/staff/reports/income-statement?monthFrom=2026-01&monthTo=2026-10',
    );
  });

  it('shows the delta in words, against the month it compares to', async () => {
    renderScreen(<FinanceDashboardPanel />);

    expect(await screen.findByText('2.2% vs Aug')).toBeInTheDocument();
  });

  /** Journals cannot link: the journals list does not read a status from its URL, so it would show every journal. */
  it('does not link a count its list cannot reproduce', async () => {
    renderScreen(<FinanceDashboardPanel />);

    await screen.findByText('2.2% vs Aug');
    expect(
      screen.queryByRole('link', { name: /journals submitted, awaiting approval/ }),
    ).not.toBeInTheDocument();
    expect(screen.getByText('journals submitted, awaiting approval')).toBeInTheDocument();
  });

  it('builds links and month ends correctly', () => {
    expect(reportLink('balance-sheet', { monthFrom: '2026-10' })).toBe(
      '/staff/reports/balance-sheet?monthFrom=2026-10',
    );
    expect(lastDayOf('2026-02')).toBe('2026-02-28');
    expect(lastDayOf('2028-02')).toBe('2028-02-29');
    expect(lastDayOf('2026-09')).toBe('2026-09-30');
  });
  /** Phase 2: the receivables card, aged, with every band drawn. */
  it("ages the receivables", async () => {
    renderScreen(<FinanceDashboardPanel />);

    expect(await screen.findByText("Receivables")).toBeInTheDocument();
    expect(screen.getAllByText("Over 90 days").length).toBeGreaterThan(0);
    // Twice over: on the bar and in the list beneath it.
    expect(screen.getAllByText("TZS 400,000.00").length).toBeGreaterThan(0);
    // A band the server omitted is still drawn, at zero.
    expect(screen.getAllByText("31-60 days".replace("-", "–")).length).toBeGreaterThan(0);
  });

});
