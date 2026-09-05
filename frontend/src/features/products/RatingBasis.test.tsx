import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ProductSnapshot, ProductSummary, VersionRatingView } from '@/api/types';
import { idle, success } from '@/store/createResourceSlice';
import { useProductStore } from '@/store/productStore';
import { ProductDetailPage } from './ProductDetailPage';

/**
 * ProductDetailPage reads the signed-in identity to decide whether to offer the publish
 * panel -- authoring is ADMIN-only server-side. These tests are about the rating basis, so
 * the identity is stubbed to the plainest useful shape: signed in, no roles. That also means
 * the publish panel is absent throughout, which is correct for a non-admin and is asserted
 * for real, against real tokens, in the e2e suite rather than against a stub here.
 */
vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: undefined }) }));

/**
 * The PRICED branch of the rating panel cannot be reached through this console:
 * `PublishVersionForm` sends no `baseRates`, so every version publishable here is
 * unpriced, and the e2e proof against real data can only ever exercise the empty
 * case. Until an actuary supplies a rate table (M13's outstanding blocker) this is
 * the only coverage the priced rendering has -- so it asserts the two things a
 * wrong rendering would get wrong: that `ageTo` reads as INCLUSIVE, and that a
 * rate is shown exactly as sent rather than padded to a fixed precision.
 */

const PRODUCT_ID = '11111111-1111-1111-1111-111111111111';
const VERSION_ID = '22222222-2222-2222-2222-222222222222';

const product = {
  productId: PRODUCT_ID,
  productCode: 'T-1',
  productName: 'Priced Term',
  category: 'TERM_LIFE',
  status: 'ACTIVE',
  defaultCurrency: 'TZS',
} as ProductSummary;

const snapshot = {
  productId: PRODUCT_ID,
  productVersionId: VERSION_ID,
  effectiveDate: '2026-01-01',
  ifrsMeasurementModel: 'PAA',
  gracePeriodDays: 30,
} as ProductSnapshot;

function renderPage(rating: VersionRatingView) {
  useProductStore.setState({
    list: success([product]),
    snapshots: { [PRODUCT_ID]: success(snapshot) },
    ratings: { [VERSION_ID]: success(rating) },
    creating: idle(),
    publishing: {},
    // The page loads on mount; the store is already populated, so these must not
    // overwrite it with a pending fetch.
    loadList: vi.fn(async () => {}),
    loadSnapshot: vi.fn(async () => {}),
    loadRating: vi.fn(async () => {}),
  });

  render(
    <MemoryRouter initialEntries={[`/staff/products/${PRODUCT_ID}`]}>
      <Routes>
        <Route path="/staff/products/:productId" element={<ProductDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('rating basis', () => {
  beforeEach(() => {
    useProductStore.setState({ ratings: {} });
  });

  it('renders a real rate table, with an inclusive upper age and an unpadded rate', () => {
    renderPage({
      productId: PRODUCT_ID,
      productVersionId: VERSION_ID,
      effectiveDate: '2026-01-01',
      baseRates: [
        { ageFrom: 18, ageTo: 25, sex: 'MALE', smokerStatus: 'NON_SMOKER', ratePerMille: 2.5 },
        { ageFrom: 26, ageTo: 35, sex: 'FEMALE', smokerStatus: 'SMOKER', ratePerMille: 4.125 },
      ],
      ratingFactors: [{ factorType: 'SUM_ASSURED_BAND', band: 'LOW', multiplier: 1.1 }],
      benefitSchedule: [{ benefitType: 'DEATH', calculationMethod: 'SUM_ASSURED' }],
    });

    expect(screen.getByRole('table', { name: 'Base rate table' })).toBeInTheDocument();
    // A closed interval: 18-25 covers a 25-year-old, per BaseRate in the spec.
    expect(screen.getByText('18–25')).toBeInTheDocument();
    expect(screen.getByText('26–35')).toBeInTheDocument();
    // Shown as sent. "2.500" would assert precision the actuary did not give.
    expect(screen.getByText('2.5')).toBeInTheDocument();
    expect(screen.getByText('4.125')).toBeInTheDocument();

    expect(screen.getByText('× 1.1')).toBeInTheDocument();
    expect(screen.getByText('SUM_ASSURED')).toBeInTheDocument();

    // The unpriced warning must NOT appear next to a real rate table.
    expect(screen.queryByText(/unpriced/)).not.toBeInTheDocument();
  });

  it('shows the age bounds an AGE factor actually resolves against, not just its band label', () => {
    // The band is free text an actuary typed; ageFrom/ageTo are what the platform resolves
    // against, and before product V5 they did not exist -- which is how age went unrated here.
    // A screen showing only the band cannot tell a reader whether the range behind it is right.
    renderPage({
      productId: PRODUCT_ID,
      productVersionId: VERSION_ID,
      effectiveDate: '2026-01-01',
      baseRates: [],
      ratingFactors: [
        { factorType: 'AGE', band: '18-30', multiplier: 1, ageFrom: 18, ageTo: 30 },
        { factorType: 'SUM_ASSURED_BAND', band: 'LOW', multiplier: 1.1 },
      ],
      benefitSchedule: [],
    });

    expect(screen.getByText('age · 18-30 (ages 18–30)')).toBeInTheDocument();
    // Only AGE rows carry bounds; nothing invented for the ones that do not.
    expect(screen.getByText('sum assured band · LOW')).toBeInTheDocument();
  });

  it('says a version with no rate table is unpriced, rather than showing an empty table', () => {
    renderPage({
      productId: PRODUCT_ID,
      productVersionId: VERSION_ID,
      effectiveDate: '2026-01-01',
      baseRates: [],
      ratingFactors: [{ factorType: 'AGE', band: '30-39', multiplier: 1 }],
      benefitSchedule: [],
    });

    // "No rows" would leave a reader to guess. The consequence is stated -- in
    // the panel, and again on the summary Field a reader may see first.
    expect(screen.getAllByText(/unpriced/)).toHaveLength(2);
    expect(screen.getByText(/nothing on this platform can quote a premium/)).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: 'Base rate table' })).not.toBeInTheDocument();

    // An empty benefit schedule is a different fact from an absent one.
    expect(screen.getByText('No benefits on this version.')).toBeInTheDocument();
  });

  it('does not offer a rating basis when no version is active today', () => {
    // The key is OMITTED, not set to undefined: `exactOptionalPropertyTypes`
    // treats those as different types, and the wire shape is an absent field.
    const { productVersionId: _absent, ...noVersion } = snapshot;

    useProductStore.setState({
      list: success([product]),
      // A product whose snapshot resolves no version -- the rating endpoint is
      // addressed by versionId, so there is nothing to request.
      snapshots: { [PRODUCT_ID]: success(noVersion) },
      ratings: {},
      loadList: vi.fn(async () => {}),
      loadSnapshot: vi.fn(async () => {}),
      loadRating: vi.fn(async () => {}),
    });

    render(
      <MemoryRouter initialEntries={[`/staff/products/${PRODUCT_ID}`]}>
        <Routes>
          <Route path="/staff/products/:productId" element={<ProductDetailPage />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByText(/No version is active today/)).toBeInTheDocument();
  });
});
