import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { BenefitsPaidChart } from './BenefitsPaidChart';

describe('BenefitsPaidChart', () => {
  /** TZS only, and it says so: adding a USD settlement into a TZS bar would be a number in no currency. */
  it('draws the working currency and names any other', () => {
    render(
      <BenefitsPaidChart
        months={['2026-08', '2026-09']}
        settlements={[
          { month: '2026-08', currency: 'TZS', claims: 3, totalApproved: { amount: '12000000.00', currencyCode: 'TZS' } },
          { month: '2026-09', currency: 'TZS', claims: 2, totalApproved: { amount: '10000000.00', currencyCode: 'TZS' } },
          { month: '2026-09', currency: 'USD', claims: 1, totalApproved: { amount: '1000.00', currencyCode: 'USD' } },
        ]}
      />,
    );

    expect(screen.getByText('Benefits paid, Sep 2026: TZS 10,000,000.00')).toBeInTheDocument();
    expect(screen.getByText(/also paid in USD/)).toBeInTheDocument();
  });

  it('describes itself, naming the latest month and its claim count', () => {
    render(
      <BenefitsPaidChart
        months={['2026-09']}
        settlements={[
          { month: '2026-09', currency: 'TZS', claims: 2, totalApproved: { amount: '10000000.00', currencyCode: 'TZS' } },
        ]}
      />,
    );

    expect(
      screen.getByRole('img', { name: /Sep 2026: TZS 10,000,000.00 across 2 claims/ }),
    ).toBeInTheDocument();
  });

  it('says so when nothing has been settled', () => {
    render(<BenefitsPaidChart settlements={[]} months={['2026-08']} />);

    expect(screen.getByText('No claims settled in this period.')).toBeInTheDocument();
  });
});
