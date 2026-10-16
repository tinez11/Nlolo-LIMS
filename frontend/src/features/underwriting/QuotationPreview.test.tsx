import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { quotePremium } from '@/api/products';
import { quoteProductSavings } from '@/api/accumulation';
import type { SavingsQuoteView } from '@/api/types';
import { QuotationPreview } from './QuotationPreview';
import type { QuotationInputs } from './useQuotation';

vi.mock('@/api/products');
vi.mock('@/api/accumulation');

const COMPLETE: QuotationInputs = {
  productId: 'prod-1',
  sumAssuredAmount: '5000000.00',
  sumAssuredCurrency: 'TZS',
  frequency: 'MONTHLY',
  policyTermMonths: '60',
  dateOfBirth: '1990-02-01',
  sex: 'MALE',
  smokerStatus: 'NON_SMOKER',
  occupationClass: 'STANDARD',
};

const QUOTE = {
  productVersionId: 'ver-1',
  currency: 'TZS',
  ageAtEntry: 36,
  ageFrom: 18,
  ageTo: 60,
  ratePerMille: 182.4,
  annualBase: 912000,
  appliedFactors: [
    { factorType: 'OCCUPATION_CLASS' as const, band: 'STANDARD', multiplier: 1 },
    { factorType: 'SUM_ASSURED_BAND' as const, band: '5M', multiplier: 1 },
  ],
  annualAfterFactors: 912000,
  frequencyLoadingPercent: 0,
  annualAfterFrequencyLoading: 912000,
  frequency: 'MONTHLY' as const,
  instalmentsPerYear: 12,
  instalmentAmount: 76000,
  totalOverTerm: 4560000,
  instalmentsOverTerm: 60,
};

const render0 = (inputs: QuotationInputs = COMPLETE, portfolioCode = 'END') =>
  render(
    <QuotationPreview
      inputs={inputs}
      premiumPayingTermMonths=""
      commencementDate="2026-10-16"
      portfolioCode={portfolioCode}
      productVersionId="ver-1"
    />,
  );

const DEPOSIT_QUOTE: SavingsQuoteView = {
  kind: 'DEPOSIT',
  amount: { amount: '5000000.00', currencyCode: 'TZS' },
  ratePercent: '6.5',
  offeredTermMonths: [3, 6, 12],
  termMonths: 12,
  interest: { amount: '325000.00', currencyCode: 'TZS' },
  maturityValue: { amount: '5325000.00', currencyCode: 'TZS' },
  maturityDate: '2027-10-16',
  minimumBalance: null,
  smallestDeposit: { amount: '500000.00', currencyCode: 'TZS' },
  refusal: null,
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(quotePremium).mockResolvedValue(QUOTE);
  vi.mocked(quoteProductSavings).mockResolvedValue(DEPOSIT_QUOTE);
});

describe('QuotationPreview', () => {
  it('shows the premium, the annual equivalent and the term as three separate facts', async () => {
    render0();

    // The instalment and the annual figure are BOTH the server's; nothing here multiplies one by twelve.
    expect(await screen.findByText('TZS 76,000.00')).toBeInTheDocument();
    // Twice over: the annual equivalent, and the annual base in the derivation -- they are equal here because
    // no multiplier moved the figure, which is the ordinary case.
    expect(screen.getAllByText('TZS 912,000.00').length).toBe(2);
    // Coverage term, paying term and frequency are three rows, because they are three different things.
    expect(screen.getByText('Coverage term')).toBeInTheDocument();
    expect(screen.getByText('Premium-paying term')).toBeInTheDocument();
    expect(screen.getByText('Payment frequency')).toBeInTheDocument();
    expect(screen.getByText('5 years')).toBeInTheDocument();
    // The explanatory notes were removed (2026-10-16): each said what its own label already said, and "4 a year"
    // beside the annual figure read as a stray number. The frequency stays, on the premium, because the figure
    // means nothing without it.
    expect(screen.queryByText('how long cover runs')).not.toBeInTheDocument();
    // Narrow on purpose: "Rate per mille, a year" is a real label in the derivation and must survive.
    expect(screen.queryByText('4 a year')).not.toBeInTheDocument();
    expect(screen.queryByText('12 a year')).not.toBeInTheDocument();
    expect(screen.getByText('monthly')).toBeInTheDocument();
  });

  it('shows how the premium was derived', async () => {
    render0();

    expect(await screen.findByText('182.4')).toBeInTheDocument();
    expect(screen.getByText('SUM_ASSURED_BAND 5M')).toBeInTheDocument();
    // Both factors sit at 1.0 on this version, so the multiplier shows twice.
    expect(screen.getAllByText('× 1').length).toBe(2);
  });

  /** It must say what is still needed rather than sit blank, and must not call the server to find out. */
  it('names what is missing instead of quoting', () => {
    render0({ ...COMPLETE, sumAssuredAmount: '', dateOfBirth: null });

    expect(screen.getByText(/a sum assured/)).toBeInTheDocument();
    expect(screen.getByText(/date of birth/)).toBeInTheDocument();
    expect(quotePremium).not.toHaveBeenCalled();
  });

  /** A version that cannot price this life is an ordinary state, not a broken screen. */
  it('explains a refusal rather than failing', async () => {
    vi.mocked(quotePremium).mockRejectedValue({ status: 422, detail: 'No base rate for age 36' });
    render0();

    expect(await screen.findByText(/cannot price this life/)).toBeInTheDocument();
  });

  /** An assumed occupation class is named on screen, never applied silently. */
  it('says when it assumed an occupation class', async () => {
    render0({ ...COMPLETE, occupationClass: null });

    expect(await screen.findByText(/occupation class STANDARD/)).toBeInTheDocument();
  });

  /** The question a customer actually asks, answered by the SERVER rather than a browser multiplying. */
  it("shows what the whole term costs", async () => {
    render0();

    expect(await screen.findByText(/Total over 5 years/)).toBeInTheDocument();
    expect(screen.getByText('TZS 4,560,000.00')).toBeInTheDocument();
    expect(screen.getByText('60 payments')).toBeInTheDocument();
  });

  /** Absent, not guessed: a null total is printed as such rather than as a zero or a browser product. */
  it("says the total is not determinable when the server cannot say", async () => {
    vi.mocked(quotePremium).mockResolvedValue({ ...QUOTE, totalOverTerm: null, instalmentsOverTerm: null });
    render0();

    expect(await screen.findByText('Not determinable')).toBeInTheDocument();
  });
});

/**
 * The panel asks the question the PRODUCT answers (2026-10-16).
 *
 * <p>It used to ask every product for a premium off a base rate table, which 5 of 693 dev versions carry. A deposit,
 * a savings account, a funeral plan, an annuity and a unit-linked policy are each priced on a different basis, and
 * all of them answered that one question with a red 422 while working exactly as designed.
 */
describe('QuotationPreview, per pricing basis', () => {
  it('prices a fixed-term deposit on its own grid, not as a premium', async () => {
    render0(COMPLETE, 'DEP');

    expect(await screen.findByText('Value at maturity')).toBeInTheDocument();
    expect(screen.getByText('TZS 5,325,000.00')).toBeInTheDocument();
    // The rate is for the WHOLE term, not a year -- saying which is the difference between a correct figure and
    // one the customer disputes.
    expect(screen.getByText('for the term')).toBeInTheDocument();
    expect(screen.getByText('2027-10-16')).toBeInTheDocument();
    // It never asks the premium engine, which is what used to produce the refusal.
    expect(quotePremium).not.toHaveBeenCalled();
  });

  /** A term the grid does not sell is an answer, not an error: it names what IS offered. */
  it('names the terms a deposit does offer when the chosen one is not sold', async () => {
    vi.mocked(quoteProductSavings).mockResolvedValue({
      ...DEPOSIT_QUOTE,
      ratePercent: null,
      interest: null,
      maturityValue: null,
      termMonths: 9,
      refusal: 'This deposit is not offered over 9 months.',
    });
    render0({ ...COMPLETE, policyTermMonths: '9' }, 'DEP');

    expect(await screen.findByText('This deposit is not offered over 9 months.')).toBeInTheDocument();
    expect(screen.getByText(/offered: 3 months, 6 months, 1 year/)).toBeInTheDocument();
  });

  it('quotes a savings account as a rate, since it has no maturity to quote', async () => {
    vi.mocked(quoteProductSavings).mockResolvedValue({
      kind: 'ACCOUNT',
      amount: { amount: '200000.00', currencyCode: 'TZS' },
      ratePercent: '4',
      offeredTermMonths: [],
      termMonths: null,
      interest: null,
      maturityValue: null,
      maturityDate: null,
      minimumBalance: { amount: '50000.00', currencyCode: 'TZS' },
      smallestDeposit: null,
      refusal: null,
    });
    render0(COMPLETE, 'SAV');

    expect(await screen.findByText('Opening balance')).toBeInTheDocument();
    expect(screen.getByText('4%')).toBeInTheDocument();
    expect(screen.getByText('a year')).toBeInTheDocument();
    expect(screen.getByText('Minimum balance')).toBeInTheDocument();
    expect(screen.queryByText('Value at maturity')).not.toBeInTheDocument();
  });

  /** Priced by its own fields on this same form: two prices for one thing on one screen is a defect, not a feature. */
  it('points at the fields that price a funeral plan rather than quoting it twice', () => {
    render0(COMPLETE, 'FUN');

    expect(screen.getByText(/family fields above price this plan/)).toBeInTheDocument();
    expect(quotePremium).not.toHaveBeenCalled();
    expect(quoteProductSavings).not.toHaveBeenCalled();
  });

  it('says a credit-life premium belongs to the scheme, not to this life', () => {
    render0(COMPLETE, 'CRL');

    expect(screen.getByText(/priced per scheme, not per life/)).toBeInTheDocument();
    expect(quotePremium).not.toHaveBeenCalled();
  });

  /** An unknown portfolio keeps the old behaviour rather than going blank. */
  it('falls back to a premium quote for a portfolio it does not know', async () => {
    render0(COMPLETE, 'SOMETHING_NEW');

    expect(await screen.findByText('TZS 76,000.00')).toBeInTheDocument();
  });
});
