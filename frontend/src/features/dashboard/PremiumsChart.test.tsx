import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PremiumsChart } from './PremiumsChart';

const p = (month: string, amount: string) => ({ month, amount: { amount, currencyCode: 'TZS' } });

describe('PremiumsChart', () => {
  /** Every bar is a report value; the month in progress is drawn as an outline, never as a complete month. */
  it('marks the month in progress as to date', () => {
    render(
      <PremiumsChart
        thisYear={[p('2026-09', '50204310.00'), p('2026-10', '22600000.00')]}
        lastYear={[p('2025-09', '45000000.00')]}
        currentMonth="2026-10"
      />,
    );

    // `getByTitle` only matches `svg > title`; a bar's tooltip is a `<title>` inside its own `<rect>`,
    // which is where SVG puts it, so the tooltips are found by their text instead.
    //
    // Each tooltip is prefixed with its SERIES since 2026-10-16, when the drawing moved into the shared
    // BarChart: two bars stand in one month, and "Sep 2026" alone did not say which year's it was.
    expect(screen.getByText('2026, Oct 2026 to date: TZS 22,600,000.00').closest('rect')).toHaveAttribute(
      'data-partial',
      'true',
    );
    expect(screen.getByText('2026, Sep 2026: TZS 50,204,310.00').closest('rect')).not.toHaveAttribute('data-partial');
    // Last year's September, drawn under this year's September so the two compare.
    expect(screen.getByText('2025, Sep 2026: TZS 45,000,000.00')).toBeInTheDocument();
  });

  /** The described image says the same as the bars, for a reader who cannot see them. */
  it('describes itself', () => {
    render(<PremiumsChart thisYear={[p('2026-09', '50204310.00')]} lastYear={[]} currentMonth="2026-10" />);

    expect(screen.getByRole('img', { name: /Premiums collected by month/ })).toBeInTheDocument();
  });

  it('says so when there is nothing to draw', () => {
    render(<PremiumsChart thisYear={[]} lastYear={[]} currentMonth="2026-10" />);

    expect(screen.getByText('No premiums collected in either year yet.')).toBeInTheDocument();
  });
});
