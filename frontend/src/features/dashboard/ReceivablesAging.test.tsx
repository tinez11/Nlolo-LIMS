import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ReceivablesAging } from './ReceivablesAging';

const TZS = (amount: string) => ({ amount, currencyCode: 'TZS' });

describe('ReceivablesAging', () => {
  it('shows every bucket, including the empty ones', () => {
    render(
      <ReceivablesAging
        aging={[
          {
            currency: 'TZS',
            buckets: [
              { name: 'NOT_DUE', cases: 1, outstanding: TZS('100000.00') },
              { name: 'D1_30', cases: 0, outstanding: TZS('0.00') },
              { name: 'D31_60', cases: 1, outstanding: TZS('250000.00') },
              { name: 'D61_90', cases: 0, outstanding: TZS('0.00') },
              { name: 'OVER_90', cases: 1, outstanding: TZS('400000.00') },
            ],
          },
        ]}
      />,
    );

    expect(screen.getByText('Not due')).toBeInTheDocument();
    expect(screen.getByText('61–90 days')).toBeInTheDocument();
    expect(screen.getByText('TZS 400,000.00')).toBeInTheDocument();
  });

  /**
   * A band the server left out is still drawn, at zero. An absent band means "nothing is this overdue", which is
   * the answer a collections officer came for -- unlike an absent month in a series, which means no data.
   */
  it('draws a band the server did not send', () => {
    render(
      <ReceivablesAging
        aging={[{ currency: 'TZS', buckets: [{ name: 'OVER_90', cases: 2, outstanding: TZS('400000.00') }] }]}
      />,
    );

    expect(screen.getByText('31–60 days')).toBeInTheDocument();
    expect(screen.getAllByText('TZS 0.00').length).toBeGreaterThan(0);
  });

  /** Another currency is named, never added in: the reader must know the total is not the whole book. */
  it('names a currency it is not showing', () => {
    render(<ReceivablesAging aging={[{ currency: 'TZS', buckets: [] }, { currency: 'USD', buckets: [] }]} />);

    expect(screen.getByText(/also owed in USD/)).toBeInTheDocument();
  });

  it('says so when nothing is owed', () => {
    render(<ReceivablesAging aging={[]} />);

    expect(screen.getByText('Nothing is owed.')).toBeInTheDocument();
  });
});
