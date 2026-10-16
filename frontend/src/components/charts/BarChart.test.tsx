import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { BarChart, type Series } from './BarChart';

// `getByTitle` only matches `svg > title`; a bar's tooltip is a `<title>` inside its own `<rect>`, which is where
// SVG puts it. The tooltips are therefore found by their text, as PremiumsChart's own tests do.
const months = ['2026-08', '2026-09', '2026-10'];
const series: Series[] = [
  {
    label: '2026',
    tone: 'ink',
    partialMonth: '2026-10',
    points: [
      { month: '2026-08', value: 49.1, display: 'TZS 49,100,000.00' },
      { month: '2026-09', value: 50.2, display: 'TZS 50,204,310.00' },
      { month: '2026-10', value: 22.6, display: 'TZS 22,600,000.00' },
    ],
  },
];

describe('BarChart', () => {
  it('draws one bar per point and names each by its own display value', () => {
    render(<BarChart series={series} months={months} description="Premiums by month." />);

    expect(screen.getByText('2026, Sep 2026: TZS 50,204,310.00').closest('rect')).toBeInTheDocument();
  });

  /** A month still running is an outline: a solid half-month beside complete ones reads as a collapse. */
  it('outlines the month in progress', () => {
    render(<BarChart series={series} months={months} description="Premiums by month." />);

    expect(screen.getByText(/Oct 2026 to date/).closest('rect')).toHaveAttribute('data-partial', 'true');
    expect(screen.getByText(/Sep 2026:/).closest('rect')).not.toHaveAttribute('data-partial');
  });

  /** A month with no value is a GAP, not a zero -- the platform said nothing, so the chart says nothing. */
  it('leaves a month with no value empty', () => {
    render(
      <BarChart
        months={['2026-08', '2026-09']}
        description="x"
        series={[{ label: '2026', tone: 'ink', points: [{ month: '2026-09', value: 1, display: 'TZS 1.00' }] }]}
      />,
    );

    expect(screen.queryByText(/Aug 2026/)).not.toBeInTheDocument();
  });

  it('describes itself, and labels its series', () => {
    render(<BarChart series={series} months={months} description="Premiums by month, 2026." />);

    expect(screen.getByRole('img', { name: 'Premiums by month, 2026.' })).toBeInTheDocument();
    // The legend, not the tooltip: the swatch carries the series name.
    expect(screen.getByText('2026', { selector: 'span' })).toBeInTheDocument();
  });

  /** Two series sit side by side in each month, the compared one in grey -- never a second hue. */
  it('draws a second series beside the first', () => {
    render(
      <BarChart
        months={months}
        description="x"
        series={[
          series[0]!,
          { label: '2025', tone: 'muted', points: [{ month: '2026-09', value: 45, display: 'TZS 45,000,000.00' }] },
        ]}
      />,
    );

    expect(screen.getByText('2025, Sep 2026: TZS 45,000,000.00')).toBeInTheDocument();
    expect(screen.getByText('2025', { selector: 'span' })).toBeInTheDocument();
    // The compared series is grey, never a second hue.
    expect(screen.getByText('2025, Sep 2026: TZS 45,000,000.00').closest('rect')).toHaveClass('fill-border-strong');
  });

  /** An empty chart still renders a described image rather than nothing at all. */
  it('survives a series with no points', () => {
    render(<BarChart months={months} description="Nothing yet." series={[{ label: '2026', tone: 'ink', points: [] }]} />);

    expect(screen.getByRole('img', { name: 'Nothing yet.' })).toBeInTheDocument();
  });
});
