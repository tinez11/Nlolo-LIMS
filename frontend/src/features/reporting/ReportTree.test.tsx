import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as reporting from '@/api/reporting';
import type { ReportParts, ReportSection } from '@/api/reporting';
import { ReportTree } from './ReportTree';

vi.mock('@/api/reporting');

beforeEach(() => vi.clearAllMocks());

const columns = [
  { key: 'line', label: 'Line', type: 'TEXT' as const },
  { key: 'current', label: 'October 2026', type: 'MONEY' as const },
  { key: 'prior', label: 'September 2026', type: 'MONEY' as const },
  { key: 'changePercent', label: 'Change %', type: 'PERCENT' as const },
];

const SECTION: ReportSection = {
  title: 'Income statement',
  columns,
  rows: [
    { line: 'Insurance revenue', current: '1240.50', prior: '1168.10', changePercent: '6.2', _lineId: 'revenue', _hasParts: 'true', _kind: 'LINE', _goodWhen: 'UP' },
    { line: 'Profit for the period', current: '412.60', prior: '381.70', changePercent: '8.1', _lineId: 'profit', _hasParts: 'true', _kind: 'TOTAL', _goodWhen: 'UP' },
  ],
  totals: {},
};

const parts = (over: Partial<ReportParts>): ReportParts => ({
  lineId: 'revenue', label: 'Insurance revenue', columns, total: 2, page: 0, size: 2, balanced: true,
  check: '1,102.00 + 138.50 = 1,240.50',
  rows: [
    { line: '4100 Premium earned', current: '1102.00', _lineId: 'acct:4100', _hasParts: 'true' },
    { line: '4200 Fees', current: '138.50', _lineId: 'acct:4200', _hasParts: 'true' },
  ],
  ...over,
});

/** 2026-11-09, the user: the computation "until it reaches the figure summed up" -- a tree inside the table. */
describe('the computation tree', () => {
  it('opens a line to its parts and states the server’s sum beneath them', async () => {
    vi.mocked(reporting.getReportParts).mockResolvedValue(parts({}));
    render(<ReportTree reportKey="income-statement" params={{ monthFrom: '2026-10' }} section={SECTION} />);

    await userEvent.click(screen.getByRole('button', { name: 'Show what makes up Insurance revenue' }));

    expect(await screen.findByText('4100 Premium earned')).toBeInTheDocument();
    expect(screen.getByText('✓ 1,102.00 + 138.50 = 1,240.50')).toBeInTheDocument();
    expect(reporting.getReportParts).toHaveBeenCalledWith('income-statement', 'revenue', { monthFrom: '2026-10' }, 0);
    expect(screen.getByRole('button', { name: 'Hide what makes up Insurance revenue' })).toHaveAttribute('aria-expanded', 'true');
  });

  it('says plainly when the parts do not add up', async () => {
    vi.mocked(reporting.getReportParts).mockResolvedValue(parts({ balanced: false, check: '1,102.00 + 138.50 = 1,240.00' }));
    render(<ReportTree reportKey="income-statement" params={{}} section={SECTION} />);

    await userEvent.click(screen.getByRole('button', { name: 'Show what makes up Insurance revenue' }));

    expect(await screen.findByText('Does not add up: 1,102.00 + 138.50 = 1,240.00')).toBeInTheDocument();
  });

  it('offers the rest of a long postings list a page at a time', async () => {
    vi.mocked(reporting.getReportParts).mockResolvedValue(parts({
      total: 812, size: 20, check: '812 postings in 2026-10 = 1,102.00',
      rows: [{ line: '2026-10 · Premium collected · RC-000214 · POL-1', current: '20000.00', _lineId: 'posting:1' }],
    }));
    render(<ReportTree reportKey="income-statement" params={{}} section={SECTION} />);

    await userEvent.click(screen.getByRole('button', { name: 'Show what makes up Insurance revenue' }));

    expect(await screen.findByRole('button', { name: 'Show 20 more of 812' })).toBeInTheDocument();
  });

  it('colours a change by whether it is good news, with its direction in words', () => {
    render(<ReportTree reportKey="income-statement" params={{}} section={SECTION} />);
    expect(screen.getAllByText('up 6.2%')[0]).toHaveAttribute('data-delta', 'good');
  });
});
