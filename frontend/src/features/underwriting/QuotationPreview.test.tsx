import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { quotePremium } from '@/api/products';
import { QuotationPreview } from './QuotationPreview';
import type { QuotationInputs } from './useQuotation';

vi.mock('@/api/products');

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

const render0 = (inputs: QuotationInputs = COMPLETE) =>
  render(<QuotationPreview inputs={inputs} premiumPayingTermMonths="" commencementDate="2026-10-16" />);

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(quotePremium).mockResolvedValue(QUOTE);
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
