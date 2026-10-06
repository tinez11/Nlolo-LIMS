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
 * the only coverage the priced rendering has.
 *
 * ## Rates are padded to four decimals now, reversing an earlier decision here
 *
 * This file used to assert the opposite -- "shown as sent", on the reasoning that
 * `2.5000` claims precision the actuary did not give. That reasoning was tested
 * against a two-row fixture, where nothing is out of line because there is
 * nothing to line up with.
 *
 * Rendered against a real twenty-cell grid it fails badly. The column came out
 * `0.62 / 1.488 / 0.837 / 2.0088 / 0.95 / 2.28 / 1.2825`: one, two, three and
 * four decimals alternating, so no two adjacent decimal points aligned and the
 * table could not be read down a column -- which is the only reason a rate table
 * is a table at all. CSS cannot align on a decimal point; padding is the only
 * fix available.
 *
 * And the precision objection does not hold against the schema:
 * `product.base_rate_table.rate_per_mille` is `numeric(10,4)`. The stored value
 * IS four decimals, so `2.5000` reports the column's real precision rather than
 * inventing any -- whereas `2.5` leaves a reader unable to tell whether the
 * remaining digits were zero or rounded away.
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
  portfolioCode: 'TERM',
  profitabilityBucket: 'REMAINING',
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

  it('renders a real rate table, with an inclusive upper age and rates padded to the column precision', () => {
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
    // Four decimals, always, so the decimal points line up down the column. The
    // two fixtures deliberately differ in supplied precision (2.5 and 4.125) --
    // if either rendered unpadded the column would go ragged again.
    expect(screen.getByText('2.5000')).toBeInTheDocument();
    expect(screen.getByText('4.1250')).toBeInTheDocument();
    expect(screen.queryByText('2.5')).not.toBeInTheDocument();

    // Multipliers get three, for the same reason at their own precision.
    expect(screen.getByText('× 1.100')).toBeInTheDocument();

    // The amount, not the mechanism. This read "Sum assured" before -- the name of a
    // calculation method rather than what the benefit pays, which is the one thing a
    // reviewer opens this panel for. Never the raw wire literal either.
    expect(screen.getByText('Full sum assured')).toBeInTheDocument();
    expect(screen.queryByText('SUM_ASSURED')).not.toBeInTheDocument();

    // Sex was printed raw while the column beside it was lowercased, so the table
    // read "FEMALE" next to "non smoker". One humaniser for both.
    expect(screen.getByText('Male')).toBeInTheDocument();
    expect(screen.getByText('Non smoker')).toBeInTheDocument();
    expect(screen.queryByText('MALE')).not.toBeInTheDocument();

    // The unpriced warning must NOT appear next to a real rate table.
    expect(screen.queryByText(/unpriced/)).not.toBeInTheDocument();
  });

  it('shows what each benefit pays, not the name of its calculation method', () => {
    // This is the number a claim against the benefit is settled at -- `claimableCover`
    // resolves the coverage row this schedule produces -- so a reviewer checking a rider
    // has to be able to read it here. The panel printed "Percentage of sum assured" and
    // stopped, leaving the one figure that matters on the wire and off the screen.
    renderPage({
      productId: PRODUCT_ID,
      productVersionId: VERSION_ID,
      effectiveDate: '2026-01-01',
      baseRates: [],
      ratingFactors: [],
      benefitSchedule: [
        { benefitType: 'DEATH', calculationMethod: 'SUM_ASSURED' },
        { benefitType: 'CRITICAL_ILLNESS', calculationMethod: 'PERCENTAGE_OF_SUM_ASSURED', percent: 25 },
        { benefitType: 'DISABILITY', calculationMethod: 'FLAT_AMOUNT', flatAmount: 500000 },
      ],
    });

    expect(screen.getByText('Full sum assured')).toBeInTheDocument();
    expect(screen.getByText('25% of sum assured')).toBeInTheDocument();
    // Grouped, for the reason the sum assured bounds are: 500000 is a number a reader
    // has to count digits on.
    expect(screen.getByText('Flat 500,000')).toBeInTheDocument();
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
        // null, NOT omitted. openapi-product.yaml declares these as
        // `type: [integer, "null"]`, so null is what the wire actually sends for a
        // factor with no bounds -- see the regression test below for why that
        // distinction cost a visible bug.
        { factorType: 'SUM_ASSURED_BAND', band: 'LOW', multiplier: 1.1, ageFrom: null, ageTo: null },
      ],
      benefitSchedule: [],
    });

    // Factor type is the label; the band and the bounds are a note beneath it,
    // rather than all three crushed into one string.
    expect(screen.getByText('Age')).toBeInTheDocument();
    expect(screen.getByText('18-30 · ages 18–30')).toBeInTheDocument();

    // AGE bounds belong to AGE rows and nowhere else. A sum assured band with no age
    // range must never be described by one -- that is not a fact about that row.
    expect(screen.getByText('Sum assured band')).toBeInTheDocument();
    expect(screen.queryByText(/ages/i)).not.toBeNull();
    expect(screen.queryByText(/LOW · ages/)).not.toBeInTheDocument();
  });

  /**
   * The same for SUM_ASSURED_BAND, which had the identical defect one factor type over.
   *
   * Its band text was matched by exact string against LOW, MEDIUM or HIGH -- hardcoded in
   * underwriting on thresholds of two and ten million, and shown on no screen anywhere. A real
   * product published with the band '5000000' matched none of them, so its multiplier reached
   * no premium at all. Product V9 gave the row real amount bounds and they, not the label, are
   * what it resolves on -- so a screen an actuary reviews their own table on has to show them.
   */
  it('shows the amount bounds a sum assured factor resolves against, and says when it has none', () => {
    renderPage({
      productId: PRODUCT_ID,
      productVersionId: VERSION_ID,
      effectiveDate: '2026-01-01',
      baseRates: [],
      ratingFactors: [
        {
          factorType: 'SUM_ASSURED_BAND',
          band: 'Up to 5m',
          multiplier: 1.25,
          sumAssuredFrom: 0,
          sumAssuredTo: 5000000,
        },
        // null, NOT omitted -- the shape the wire actually sends for a row published
        // before V9, and the shape whose guard cost a visible bug on the AGE side.
        {
          factorType: 'SUM_ASSURED_BAND',
          band: 'LOW',
          multiplier: 1,
          sumAssuredFrom: null,
          sumAssuredTo: null,
        },
      ],
      benefitSchedule: [],
    });

    // Grouped, because a reader should not have to count the digits in 5000000.
    expect(screen.getByText(/Up to 5m · 0–5,000,000/)).toBeInTheDocument();

    // And the pre-V9 row says what it is rather than showing a label with nothing behind
    // it: with no bounds it resolves for no sum assured at all, which is worth knowing.
    expect(screen.getByText('LOW · no amount bounds')).toBeInTheDocument();
  });

  /**
   * The regression this file could not catch before.
   *
   * The guard read `factor.ageFrom === undefined`, and every fixture here OMITTED
   * the field, which makes it undefined -- so the tests passed. The wire sends
   * `null` (`type: [integer, "null"]`), which that guard does not catch, so the
   * live console rendered "(ages null–null)" and CSS `capitalize` dressed it up as
   * "(Ages Null–Null)" -- indistinguishable from a deliberate label.
   *
   * Both shapes are asserted here, because a fixture that only tests the one the
   * wire does not send is how this got shipped.
   */
  it('never prints a null age bound, whether the field is null or absent', () => {
    renderPage({
      productId: PRODUCT_ID,
      productVersionId: VERSION_ID,
      effectiveDate: '2026-01-01',
      baseRates: [],
      ratingFactors: [
        { factorType: 'AGE', band: 'ALL', multiplier: 1, ageFrom: null, ageTo: null },
        { factorType: 'OCCUPATION_CLASS', band: 'CLASS_2', multiplier: 1.25 },
      ],
      benefitSchedule: [],
    });

    expect(screen.queryByText(/null/i)).not.toBeInTheDocument();

    // An AGE factor with no bounds is the state that let age go unrated before
    // product V5, so it is named rather than silently omitted.
    expect(screen.getByText('ALL · no age bounds')).toBeInTheDocument();

    // A non-AGE factor gets no age note at all, bounds absent or not.
    expect(screen.getByText('Occupation class')).toBeInTheDocument();
    expect(screen.getByText('CLASS_2')).toBeInTheDocument();
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
