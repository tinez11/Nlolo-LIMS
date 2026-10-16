import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DecisionsChart } from './DecisionsChart';

// Already grouped by the server: 2026-09's 47 is 42 ACCEPT plus 5 LOADED, and its 3 POSTPONED are in neither.
const series = [
  { month: '2026-08', accepted: 40, declined: 6 },
  { month: '2026-09', accepted: 47, declined: 0 },
];

describe('DecisionsChart', () => {
  /** Accepted and loaded are both acceptances; the chart shows what was taken on against what was refused. */
  it('draws acceptances against declines', () => {
    render(<DecisionsChart series={series} months={['2026-08', '2026-09']} />);

    expect(screen.getByText('Accepted, Sep 2026: 47 cases')).toBeInTheDocument();
    expect(screen.getByText('Declined, Aug 2026: 6 cases')).toBeInTheDocument();
  });

  /** The legend names both series, since neither is told apart by hue. */
  it('labels both series', () => {
    render(<DecisionsChart series={series} months={['2026-08', '2026-09']} />);

    expect(screen.getByText('Accepted', { selector: 'span' })).toBeInTheDocument();
    expect(screen.getByText('Declined', { selector: 'span' })).toBeInTheDocument();
  });

  it('describes itself, naming the latest month', () => {
    render(<DecisionsChart series={series} months={['2026-08', '2026-09']} />);

    expect(
      screen.getByRole('img', { name: /Sep 2026: 47 accepted, 0 declined/ }),
    ).toBeInTheDocument();
  });

  it('says so when nothing has been decided', () => {
    render(<DecisionsChart series={[]} months={['2026-08', '2026-09']} />);

    expect(screen.getByText('No decisions in this period.')).toBeInTheDocument();
  });
});
