import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderScreen } from '@/test/renderScreen';
import { QuotationInsights } from './QuotationInsights';
import type { QuoteResult } from './quoteResult';

const TZS = (amount: string) => ({ amount, currencyCode: 'TZS' });

const TERM_ACCOUNT: QuoteResult = {
  kind: 'savings',
  quote: {
    kind: 'TERM_ACCOUNT',
    amount: TZS('100000.00'),
    ratePercent: '8',
    offeredTermMonths: [],
    termMonths: 36,
    interest: TZS('420000.00'),
    maturityValue: TZS('4020000.00'),
    maturityDate: '2029-11-01',
    minimumBalance: null,
    smallestDeposit: null,
    refusal: null,
    termAccount: {
      frequency: 'MONTHLY',
      contributions: 36,
      paidIn: TZS('3600000.00'),
      charges: TZS('0.00'),
      targetAmount: null,
      minRatePercent: '3',
      maxRatePercent: '12',
      schedule: [
        { month: 12, date: '2027-11-01', paidIn: TZS('1200000.00'), charges: TZS('0.00'), interest: TZS('52000.00'), value: TZS('1252000.00') },
        { month: 24, date: '2028-11-01', paidIn: TZS('2400000.00'), charges: TZS('0.00'), interest: TZS('205000.00'), value: TZS('2605000.00') },
        { month: 36, date: '2029-11-01', paidIn: TZS('3600000.00'), charges: TZS('0.00'), interest: TZS('420000.00'), value: TZS('4020000.00') },
      ],
    },
  },
} as QuoteResult;

describe('QuotationInsights', () => {
  it("draws a term account's growth from the server's schedule and lists it year by year", () => {
    renderScreen(<QuotationInsights result={TERM_ACCOUNT} alternatives={[]} />);

    expect(screen.getByRole('img', { name: /to TZS 4,020,000.00 at maturity/ })).toBeInTheDocument();
    const table = screen.getByRole('table');
    expect(within(table).getByText('End of year 1')).toBeInTheDocument();
    expect(within(table).getByText('At maturity')).toBeInTheDocument();
    expect(within(table).getByText('TZS 2,605,000.00')).toBeInTheDocument();
  });

  it('prices each alternative on the server and marks the one quoted above', async () => {
    renderScreen(
      <QuotationInsights
        result={TERM_ACCOUNT}
        alternatives={[
          { key: 'a', label: 'TZS 75,000 monthly', current: false, price: () => Promise.resolve({ value: 'TZS 3,015,000.00', note: 'paid out at maturity' }) },
          { key: 'b', label: 'TZS 100,000 monthly', current: true, price: () => Promise.resolve({ value: 'TZS 4,020,000.00', note: 'paid out at maturity' }) },
          { key: 'c', label: 'TZS 150,000 monthly', current: false, price: () => Promise.reject(new Error('refused')) },
        ]}
      />,
    );

    expect(await screen.findByText('TZS 3,015,000.00')).toBeInTheDocument();
    expect(screen.getByText('Quoted above')).toBeInTheDocument();
    expect(screen.getByText('Not offered')).toBeInTheDocument();
  });

  it("draws an annuity's income against its price, and says when it has paid back", () => {
    const annuity = {
      kind: 'annuity',
      purchasePrice: '50000000',
      quote: {
        formCode: 'LIFE-BS', frequency: 'MONTHLY', paymentsPerYear: 12, timing: 'ARREARS',
        instalment: TZS('306250.00'), annualIncome: TZS('3750000.00'), annuitantAge: 61, jointAge: null,
        ageDifference: null, rateSex: 'MALE', annualRatePerMille: '75', factor: '0.98',
        purchasePrice: TZS('50000000.00'),
        schedule: Array.from({ length: 14 }, (_, i) => ({
          year: i + 1, age: 62 + i, received: TZS(String(3750000 * (i + 1)) + '.00'),
        })),
        breakEvenYear: 14,
      },
    } as QuoteResult;
    renderScreen(<QuotationInsights result={annuity} alternatives={[]} />);

    expect(screen.getByRole('img', { name: /against the purchase price of TZS 50,000,000.00/ })).toBeInTheDocument();
    expect(screen.getByText(/By the end of year 14, at age 75/)).toBeInTheDocument();
  });
});
