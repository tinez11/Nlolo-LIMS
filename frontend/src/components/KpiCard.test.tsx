import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { countValue } from '@/lib/counts';
import { KpiRow } from './KpiCard';

function renderRow(kpis: Parameters<typeof KpiRow>[0]['kpis']) {
  return render(
    <MemoryRouter>
      <KpiRow kpis={kpis} />
    </MemoryRouter>,
  );
}

describe('KpiCard', () => {
  /** The card is a link to exactly what it counted, and its name is the whole sentence, not a bare number. */
  it('links to its filtered source and is named by its sentence', () => {
    renderRow([{ label: 'Open cases', value: '238', hint: 'awaiting first review', to: '/staff/underwriting?status=OPEN' }]);

    const link = screen.getByRole('link', { name: '238 Open cases, awaiting first review. Open the list.' });
    expect(link).toHaveAttribute('href', '/staff/underwriting?status=OPEN');
  });

  /** Nothing honest to open: no link at all, rather than a link to an unfiltered list showing more than the card says. */
  it('is not a link when it has no destination', () => {
    renderRow([{ label: 'Journals awaiting approval', value: '4', hint: 'submitted' }]);

    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.getByText('4')).toBeInTheDocument();
  });

  /** Could not be read: "Not available", never 0, and not a link to a list that would contradict it. */
  it('says a figure is not available rather than showing zero', () => {
    renderRow([{ label: 'Open cases', value: null, hint: 'awaiting first review', to: '/staff/underwriting?status=OPEN' }]);

    expect(screen.getByText('Not available')).toBeInTheDocument();
    expect(screen.queryByText('0')).not.toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  /** A direction is not a verdict: the delta has no status colour. */
  it('draws a delta in ink', () => {
    renderRow([{ label: 'Premiums collected, Sep', value: 'TZS 50,204,310.00', hint: 'last complete month',
      delta: { direction: 'up', text: '2.2% vs Aug' } }]);

    const delta = screen.getByText('2.2% vs Aug');
    expect(delta.closest('[data-delta]')?.className).not.toMatch(/status-(success|danger)/);
  });

  /** A figure never wraps: "50,204,31 / 0" reads as two numbers. */
  it('never lets a figure wrap', () => {
    renderRow([{ label: 'Total assets', value: 'TZS 742,580,120.00', hint: 'balance sheet' }]);

    expect(screen.getByText('TZS 742,580,120.00').className).toMatch(/whitespace-nowrap/);
  });

  it('formats a count, and keeps null as null', () => {
    expect(countValue(1234)).toBe('1,234');
    expect(countValue(0)).toBe('0');
    expect(countValue(null)).toBeNull();
    expect(countValue(undefined)).toBeNull();
  });
});
