import type { FuneralTermsView } from '@/api/types';

/** Plain words about a product version, shared by the product page's components (2026-10-08). */

const AMOUNT = new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 });
const amount = (n: number | null | undefined) => (n == null ? '—' : AMOUNT.format(n));

/**
 * What prices a version that base rates do not (2026-10-08): a funeral plan's premium table, a unit-linked
 * version's mortality table, an annuity's purchase price. This panel told a staff member such a version was
 * "unpriced -- nothing on this platform can quote a premium for it", on a funeral product quoting families daily.
 */
export function pricedOtherwise(category: string | null | undefined): string | null {
  switch (category) {
    case 'FUNERAL': return 'Priced by its premium table, shown with its plans — not by base rates or rating multipliers.';
    case 'UNIT_LINKED': return 'Charged from its mortality table and policy fee, shown with its terms — not by base rates.';
    case 'ANNUITY': return 'Its premium is the purchase price the annuitant pays; the annuity it buys comes from the version’s rate rows.';
    default: return null;
  }
}

/** "01 nuruI: main member 4,000,000 for 100,000 a year" -- a funeral version in one line, for its row in the list. */
export function funeralSummary(terms: FuneralTermsView): string {
  return terms.plans.map((plan) => {
    const main = terms.benefits.find((b) => b.planCode === plan.planCode && b.role === 'MAIN_MEMBER');
    const prices = terms.premiums.filter((p) => p.planCode === plan.planCode && p.role === 'MAIN_MEMBER').map((p) => p.yearlyPremium);
    const individual = prices.length === 0 ? null
      : `${AMOUNT.format(Math.min(...prices))}${Math.max(...prices) !== Math.min(...prices) ? `–${AMOUNT.format(Math.max(...prices))}` : ''} a year`;
    const group = plan.groupMonthlyRate == null ? null
      : `${AMOUNT.format(plan.groupMonthlyRate)} per member ${plan.groupRatePeriod === 'YEARLY' ? 'a year' : 'a month'}`;
    return `${plan.planCode} ${plan.name}: main member ${amount(main?.benefit)} for ${[individual, group].filter(Boolean).join(' or ') || '—'}`;
  }).join(' · ');
}

