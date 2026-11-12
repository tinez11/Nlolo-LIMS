import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReportDefinition } from '@/api/reporting';
import { renderScreen } from '@/test/renderScreen';
import { ReportFilterBar } from './ReportFilterBar';
import { defaultFilters } from './reportParams';

vi.mock('@/api/products');
vi.mock('@/api/finaccounting');

const PERIOD_REPORT = {
  key: 'premium-collections', title: 'Premium collections', description: '', category: 'MANAGEMENT',
  dateFilterKind: 'PERIOD', groupByOptions: [], comparable: false, requiresAccount: false, comparesPeriods: false,
} as unknown as ReportDefinition;

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(2026, 10, 11, 9, 0));
});
afterEach(() => vi.useRealTimers());

describe('ReportFilterBar', () => {
  it('fills the dates from a period choice, and runs only on Run', async () => {
    const onRun = vi.fn();
    const user = userEvent.setup();
    renderScreen(<ReportFilterBar definition={PERIOD_REPORT} initial={defaultFilters(PERIOD_REPORT, new Date())} onRun={onRun} />);

    const period = screen.getByRole('group', { name: 'Period' });
    // The default is this month so far: that choice is lit.
    expect(within(period).getByRole('button', { name: 'This month' })).toHaveAttribute('aria-pressed', 'true');

    await user.click(within(period).getByRole('button', { name: 'Last quarter (Jul–Sep 2026)' }));
    expect(onRun).not.toHaveBeenCalled();
    expect(screen.getByLabelText('From')).toHaveValue('2026-07-01');
    expect(screen.getByLabelText('To')).toHaveValue('2026-09-30');

    await user.click(screen.getByRole('button', { name: /Run/ }));
    expect(onRun).toHaveBeenCalledWith(expect.objectContaining({ from: '2026-07-01', to: '2026-09-30' }));
  });

  it('lights Pick dates when the dates match no choice', async () => {
    const user = userEvent.setup();
    renderScreen(<ReportFilterBar definition={PERIOD_REPORT} initial={defaultFilters(PERIOD_REPORT, new Date())} onRun={vi.fn()} />);

    await user.clear(screen.getByLabelText('From'));
    await user.type(screen.getByLabelText('From'), '2026-10-05');
    expect(within(screen.getByRole('group', { name: 'Period' })).getByRole('button', { name: 'Pick dates…' }))
      .toHaveAttribute('aria-pressed', 'true');
  });
});
