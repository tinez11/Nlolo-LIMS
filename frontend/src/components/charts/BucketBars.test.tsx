import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { BucketBars } from './BucketBars';

describe('BucketBars', () => {
  it('draws a bar per bucket, including the empty ones', () => {
    render(
      <BucketBars
        description="Receivables by age."
        buckets={[
          { label: 'Not due', value: 100000, display: 'TZS 100,000.00', count: 1 },
          { label: '1–30 days', value: 0, display: 'TZS 0.00', count: 0 },
          { label: 'Over 90 days', value: 400000, display: 'TZS 400,000.00', count: 1 },
        ]}
      />,
    );

    expect(screen.getByRole('img', { name: 'Receivables by age.' })).toBeInTheDocument();
    // An empty bucket keeps its row: "nothing is this old" is the answer, not missing data.
    expect(screen.getByText('1–30 days')).toBeInTheDocument();
    expect(screen.getByText('TZS 0.00')).toBeInTheDocument();
  });

  /** Horizontal bars, so the longest is the oldest money and the eye goes to the bottom of the list. */
  it('scales each bar against the largest bucket', () => {
    const { container } = render(
      <BucketBars
        description="x"
        buckets={[
          { label: 'A', value: 50, display: '50', count: 1 },
          { label: 'B', value: 100, display: '100', count: 1 },
        ]}
      />,
    );

    const widths = [...container.querySelectorAll('rect[data-bar]')].map((r) => Number(r.getAttribute('width')));
    expect(widths[1]).toBeCloseTo((widths[0] ?? 0) * 2, 1);
  });

  /** No colour anywhere: an age is not a status, and a red "over 90" bar would break the Stamp Rule. */
  it('draws every bar in ink', () => {
    const { container } = render(
      <BucketBars
        description="x"
        buckets={[
          { label: 'A', value: 50, display: '50', count: 1 },
          { label: 'B', value: 100, display: '100', count: 1 },
        ]}
      />,
    );

    expect([...container.querySelectorAll('rect[data-bar]')]).toSatisfy((bars: Element[]) =>
      bars.every((b) => b.getAttribute('class') === 'fill-foreground'),
    );
  });
});
