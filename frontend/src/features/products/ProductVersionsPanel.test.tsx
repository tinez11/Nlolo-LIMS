import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as funeralApi from '@/api/funeral';
import * as productsApi from '@/api/products';
import type { FuneralTermsView, ProductVersionSummaryView, VersionRatingView, VersionValueTermsView } from '@/api/types';
import { ProductVersionsPanel } from './ProductVersionsPanel';

vi.mock('@/api/products');
vi.mock('@/api/funeral');

const version = (id: string, over: Partial<ProductVersionSummaryView> = {}): ProductVersionSummaryView => ({
  productVersionId: id, effectiveDate: '2026-10-08', retirementDate: null, current: false,
  publishedAt: '2026-10-08T08:00:00Z', publishedBy: 'admin', gracePeriodDays: 30,
  expectedProfitabilityBucket: 'REMAINING', measurementModelOverride: null, survivalInvestmentComponentPercent: null, description: null, ...over,
});

/** One plan, the main member only, priced flat at `premium` a year for 4,000,000 of cover. */
const funeral = (premium: number): FuneralTermsView => ({
  plans: [{ planCode: '01', name: 'nuruI', groupMonthlyRate: null, groupRatePeriod: 'MONTHLY' }],
  benefits: [{ planCode: '01', role: 'MAIN_MEMBER', benefit: 4000000 }],
  premiums: [{ planCode: '01', role: 'MAIN_MEMBER', ageFrom: 18, ageTo: 100, yearlyPremium: premium }],
  roles: [{ role: 'MAIN_MEMBER', maxLives: 1, minEntryAge: 18, maxEntryAge: 65, coverStopAge: null, studentStopAge: null }],
  maxPricedAge: 100, waitingPeriodMonths: 6, accidentWaivesWaiting: true, dependantClaimPayee: 'MAIN_MEMBER',
  onMainMemberDeath: 'POLICY_ENDS', freeCoverToPaidDate: true, soldAs: 'INDIVIDUAL',
} as FuneralTermsView);

const NO_VALUE: VersionValueTermsView = { bonus: null, cashValue: null, payouts: null, account: null, depositRates: [] };

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(productsApi.getVersionRating).mockResolvedValue({ baseRates: [], ratingFactors: [], benefitSchedule: [] } as VersionRatingView);
  vi.mocked(productsApi.getValueTerms).mockResolvedValue(NO_VALUE);
});

describe('ProductVersionsPanel', () => {
  it('lists several versions newest first, the current one open with its terms, and opens another on a click', async () => {
    vi.mocked(productsApi.listProductVersions).mockResolvedValue([
      version('v4', { current: true, publishedBy: 'admin-4' }),
      version('v3', { publishedBy: 'admin-3' }),
    ]);
    vi.mocked(funeralApi.getFuneralTerms).mockImplementation(async (_p, v) => funeral(v === 'v4' ? 100000 : 4000000));
    render(<ProductVersionsPanel productId="p-1" category="FUNERAL" refreshKey={0} />);

    const rows = await screen.findAllByRole('listitem');
    expect(rows).toHaveLength(2);
    const current = rows[0] as HTMLElement;
    expect(within(current).getByText('Version 2')).toBeInTheDocument();
    expect(within(current).getByText('Current')).toBeInTheDocument();
    expect(await within(current).findByText('01 nuruI: main member 4,000,000 for 100,000 a year')).toBeInTheDocument();
    // Open: its premium table, and no "unpriced" scare on a version priced by its premium table.
    expect(await within(current).findByRole('table', { name: 'Yearly premium per plan, role and age band' })).toHaveTextContent('100,000');
    expect(screen.queryByText(/unpriced/)).not.toBeInTheDocument();
    expect(screen.queryByText('Base rate cells')).not.toBeInTheDocument();

    const older = rows[1] as HTMLElement;
    expect(within(older).getByText('Superseded')).toBeInTheDocument();
    expect(await within(older).findByText('01 nuruI: main member 4,000,000 for 4,000,000 a year')).toBeInTheDocument();
    expect(within(older).getByRole('button')).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(within(older).getByRole('button'));
    expect(await within(older).findByRole('table', { name: 'Yearly premium per plan, role and age band' })).toHaveTextContent('4,000,000');
  });

  it('shows a single version whole, with no list', async () => {
    vi.mocked(productsApi.listProductVersions).mockResolvedValue([version('v1', { current: true })]);
    render(<ProductVersionsPanel productId="p-1" category="TERM_LIFE" refreshKey={0} />);

    expect(await screen.findByText(/One version, current — what a sale today is priced on/)).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Versions' })).not.toBeInTheDocument();
    // A term product is priced by base rates, so their count -- and its warning at zero -- stays.
    expect(await screen.findByText('Base rate cells')).toBeInTheDocument();
  });

  it('shows what a with-profits endowment builds up and pays back, in words', async () => {
    vi.mocked(productsApi.listProductVersions).mockResolvedValue([version('v1', { current: true })]);
    vi.mocked(productsApi.getValueTerms).mockResolvedValue({
      ...NO_VALUE,
      bonus: { method: 'COMPOUND', paidUpParticipates: false, surrenderBasis: 'OWN_SCALE',
        surrenderRows: [{ fromCompletedYears: 0, perMille: 0 }, { fromCompletedYears: 3, perMille: 500 }] },
      cashValue: { basisReference: 'ACT/2026/ENDOW-01', basisDate: '2026-01-10', paidUpBasis: 'PROPORTIONATE',
        minYearsForValue: 2, rows: [{ policyYear: 2, ageFrom: null, ageTo: null, cashValuePerMille: 350, paidUpPerMille: null }] },
      payouts: { terms: { freeLookDays: 15, proofOfLifeIntervalMonths: null, survivalBenefitsDeductedFromDeath: null,
        deathBenefitPremiumPercent: null },
        rows: [{ kind: 'MATURITY', fromPolicyYear: null, toPolicyYear: null, amountBasis: 'PERCENT_OF_SA', amountValue: 100, frequency: null }] },
    });
    render(<ProductVersionsPanel productId="p-1" category="ENDOWMENT" refreshKey={0} />);

    expect(await screen.findByText('With-profits bonuses')).toBeInTheDocument();
    expect(screen.getByText('Compound')).toBeInTheDocument();
    expect(screen.getByText('Stop receiving bonuses')).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Bonus surrender value by completed years' })).toHaveTextContent('3 or more500');
    expect(screen.getByRole('table', { name: 'Cash value per 1,000 of sum assured by policy year' })).toHaveTextContent('Year 235035%');
    expect(screen.getByText('After 2 full years')).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Payouts while the life assured is alive' }))
      .toHaveTextContent('Maturity benefitAt maturity100% of the sum assuredOnce');
    expect(screen.getByText('15 days from issue')).toBeInTheDocument();
  });

  it('shows a term life version no value sections at all', async () => {
    vi.mocked(productsApi.listProductVersions).mockResolvedValue([version('v1', { current: true })]);
    render(<ProductVersionsPanel productId="p-1" category="TERM_LIFE" refreshKey={0} />);
    expect(await screen.findByText('Base rate cells')).toBeInTheDocument();
    expect(screen.queryByText('With-profits bonuses')).not.toBeInTheDocument();
    expect(screen.queryByText('Cash value')).not.toBeInTheDocument();
    expect(screen.queryByText('Savings account')).not.toBeInTheDocument();
  });
});
