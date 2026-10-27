import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as finApi from '@/api/finaccounting';
import type { MonthEndStep, MonthEndView } from '@/api/types';
import { useLedgerControlsStore } from '@/store/ledgerControlsStore';
import { MonthEndPage } from './MonthEndPage';
import { monthName, shiftMonth, stepLink } from './monthEndSteps';

vi.mock('@/api/finaccounting');
vi.mock('@/api/access');

const step = (number: number, key: MonthEndStep['key'], title: string, state: MonthEndStep['state'], detail: string,
  extra: Partial<MonthEndStep> = {}): MonthEndStep => ({ number, key, title, state, detail, ...extra });

const closing: MonthEndView = {
  period: '2026-09',
  status: 'CLOSING',
  current: 4,
  blockers: ['Expense allocation awaiting a decision; approve or reject it'],
  steps: [
    step(1, 'queue', 'Clear unposted events', 'DONE', 'No event is waiting to be posted.'),
    step(2, 'journals', 'Enter and approve manual journals', 'DONE', '2 journals approved.'),
    step(3, 'closing', 'Start closing the month', 'DONE', 'Event postings dated in the month now wait in Unposted events.',
      { by: 'system', at: '2026-10-02T07:00:00Z' }),
    step(4, 'allocation', "Allocate the month's expenses", 'WAITING', "Prepared; waiting for a finance approver's decision."),
    step(5, 'extract', 'Send the engine its figures', 'TODO', 'No extract yet.'),
    step(6, 'results', "Load the engine's results", 'TODO', 'No results uploaded yet.'),
    step(7, 'reconcile', 'Reconcile with the engine', 'TODO', "Reconciled once the engine's results are posted."),
    step(8, 'lock', 'Lock the month', 'TODO', 'Not yet: Expense allocation awaiting a decision; approve or reject it'),
  ],
};

function renderAt(url: string) {
  render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/staff/month-end" element={<MonthEndPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('MonthEndPage', () => {
  beforeEach(() => {
    useLedgerControlsStore.setState({ monthEnd: {} });
    vi.mocked(finApi.getMonthEnd).mockResolvedValue(closing);
  });

  it('lays out the eight steps in order, says which is next, and what stops the lock', async () => {
    renderAt('/staff/month-end?period=2026-09');
    const steps = await screen.findByRole('list', { name: 'Month-end steps' });
    expect(within(steps).getAllByRole('listitem')).toHaveLength(8);
    expect(screen.getByRole('heading', { name: 'Step 4 of 8 is next' })).toBeInTheDocument();
    const next = within(steps).getByRole('listitem', { name: "Step 4: Allocate the month's expenses" });
    expect(next).toHaveAttribute('aria-current', 'step');
    expect(within(next).getByText('Waiting on someone')).toBeInTheDocument();
    expect(within(next).getByRole('link', { name: 'Open IFRS 17 engine' })).toHaveAttribute(
      'href', '/staff/ifrs17-engine?period=2026-09');
    // A finished step names who finished it; the platform's own act says so.
    expect(within(steps).getByRole('listitem', { name: 'Step 3: Start closing the month' })).toHaveTextContent('The platform');
    expect(within(screen.getByRole('list', { name: 'What stops the lock' })).getByText(
      'Expense allocation awaiting a decision; approve or reject it')).toBeInTheDocument();
    expect(finApi.getMonthEnd).toHaveBeenCalledWith('2026-09');
  });

  it('steps to the month before', async () => {
    const user = userEvent.setup();
    renderAt('/staff/month-end?period=2026-09');
    await screen.findByRole('list', { name: 'Month-end steps' });
    await user.click(screen.getByRole('button', { name: 'Previous month' }));
    expect(finApi.getMonthEnd).toHaveBeenLastCalledWith('2026-08');
  });
});

describe('month-end words', () => {
  it('moves across a year and names the month', () => {
    expect(shiftMonth('2026-01', -1)).toBe('2025-12');
    expect(shiftMonth('2026-12', 1)).toBe('2027-01');
    expect(monthName('2026-10')).toBe('October 2026');
  });

  it('opens each step where its work is done', () => {
    expect(stepLink('queue', '2026-09').to).toBe('/staff/unposted-events');
    expect(stepLink('lock', '2026-09').to).toBe('/staff/periods');
    expect(stepLink('results', '2026-09').to).toBe('/staff/ifrs17-engine?period=2026-09');
  });
});
