import type { VersionValueTermsView } from '@/api/types';
import { KIND_LABEL, type ProductKind } from './productKind';

/**
 * A version's value terms in plain words (2026-10-29): what its policies build up and pay back. Each was published with
 * the version and then shown nowhere, so an endowment's bonus method or surrender table could be read only in the
 * database.
 */

type Payout = NonNullable<VersionValueTermsView['payouts']>['rows'][number];
type BonusRow = NonNullable<VersionValueTermsView['bonus']>['surrenderRows'][number];
type DepositRate = VersionValueTermsView['depositRates'][number];

const AMOUNT = new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 });
export const amount = (n: number | null | undefined) => (n == null ? '—' : AMOUNT.format(n));

/** What kind of product a version makes this, read from what it carries -- the category alone cannot say. */
export function valueKind(terms: VersionValueTermsView): ProductKind | null {
  if (terms.depositRates.length > 0) return 'DEPOSIT';
  if (terms.account) return terms.account.minRatePercent != null ? 'TERM_ACCOUNT' : 'SAVINGS_ACCOUNT';
  if (terms.cashValue) return 'CASH_VALUE';
  return null;
}

/** "Savings account", "Cash-value plan, with profits" -- or null for a version that builds no value. */
export function valueKindLabel(terms: VersionValueTermsView, category?: string | null): string | null {
  const kind = valueKind(terms);
  // A deferred annuity saves in an account until it vests; calling it a savings account hid the annuity.
  if (category === 'ANNUITY' && kind === 'SAVINGS_ACCOUNT') return 'Deferred — builds an account before it pays';
  if (kind == null) return terms.bonus ? 'With profits' : null;
  return terms.bonus ? `${KIND_LABEL[kind]}, with profits` : KIND_LABEL[kind];
}

/** Whether any version shares in profits: only then does a bonus declaration reach a policy. */
export function sharesInProfits(versions: readonly VersionValueTermsView[]): boolean {
  return versions.some((v) => v.bonus != null);
}

/**
 * Whether any version credits the product's declared rate. A term account earns the rate its policy locked at sale and
 * a deposit its grid's, so neither is touched by a declaration.
 */
export function creditsDeclaredRates(versions: readonly VersionValueTermsView[]): boolean {
  return versions.some((v) => valueKind(v) === 'SAVINGS_ACCOUNT');
}

export const BONUS_METHOD: Record<string, { label: string; note: string }> = {
  SIMPLE: { label: 'Simple', note: 'on the sum assured only' },
  COMPOUND: { label: 'Compound', note: 'bonuses earn bonus too' },
};

export const BONUS_ON_SURRENDER: Record<string, string> = {
  NONE: 'Nothing',
  SUM_ASSURED_SCALE: 'The cash-value rate',
  OWN_SCALE: 'The bonus scale below',
};

/** Each bonus scale row runs from its start to the year before the next one's. */
export function bonusScaleRows(rows: readonly BonusRow[]): string[][] {
  const sorted = [...rows].sort((a, b) => a.fromCompletedYears - b.fromCompletedYears);
  return sorted.map((row, i) => {
    const next = sorted[i + 1];
    const span = next == null
      ? `${row.fromCompletedYears} or more`
      : next.fromCompletedYears - 1 === row.fromCompletedYears
        ? String(row.fromCompletedYears)
        : `${row.fromCompletedYears}–${next.fromCompletedYears - 1}`;
    return [span, amount(row.perMille), `${amount(row.perMille / 10)}%`];
  });
}

export const PAID_UP_BASIS: Record<string, { label: string; note: string }> = {
  PROPORTIONATE: { label: 'In proportion', note: 'by months paid of months due' },
  TABLE: { label: 'From the table', note: 'paid-up column below' },
};

const KIND: Record<string, string> = {
  SURVIVAL: 'Survival benefit',
  MATURITY: 'Maturity benefit',
  INCOME: 'Income',
  RETURN_OF_PREMIUM: 'Premiums returned',
  ANNUITY: 'Annuity',
  COMMUTATION: 'Lump sum at vesting',
};

const FREQUENCY: Record<string, string> = {
  ANNUAL: 'Once a year', SEMI_ANNUAL: 'Twice a year', QUARTERLY: 'Every quarter', MONTHLY: 'Every month',
};

/** Paid once on the maturity date, so it has no years and no frequency. */
const AT_MATURITY = new Set(['MATURITY', 'RETURN_OF_PREMIUM']);

/** One payout row: what it is, when, how much, how often. */
export function payoutRow(row: Payout): string[] {
  const perYear = !AT_MATURITY.has(row.kind) && row.amountBasis !== 'ACCOUNT_VALUE' ? ' a year' : '';
  return [
    KIND[row.kind] ?? row.kind,
    AT_MATURITY.has(row.kind) ? 'At maturity' : payoutYears(row.fromPolicyYear, row.toPolicyYear),
    payoutAmount(row) + perYear,
    AT_MATURITY.has(row.kind) || row.frequency == null ? 'Once' : FREQUENCY[row.frequency] ?? row.frequency,
  ];
}

function payoutYears(from: number | null | undefined, to: number | null | undefined): string {
  if (from == null) return '—';
  if (to == null) return `From year ${from}`;
  return from === to ? `Year ${from}` : `Years ${from}–${to}`;
}

function payoutAmount(row: Payout): string {
  switch (row.amountBasis) {
    case 'PERCENT_OF_SA': return `${amount(row.amountValue)}% of the sum assured`;
    case 'PERCENT_OF_PREMIUMS': return `${amount(row.amountValue)}% of premiums paid`;
    case 'ACCOUNT_VALUE': return 'The whole account';
    default: return amount(row.amountValue);
  }
}

/** A deposit's grid as a table: a row per amount band, a column per term. */
export function depositGrid(rates: readonly DepositRate[]): { columns: string[]; rows: string[][] } {
  const terms = [...new Set(rates.map((r) => r.termMonths))].sort((a, b) => a - b);
  const starts = [...new Set(rates.map((r) => r.minAmount))].sort((a, b) => a - b);
  return {
    columns: ['Deposit of', ...terms.map((t) => (t % 12 === 0 ? `${t / 12} year${t === 12 ? '' : 's'}` : `${t} month${t === 1 ? '' : 's'}`))],
    rows: starts.map((start, i) => {
      const next = starts[i + 1];
      const band = next == null ? `${amount(start)} or more` : `${amount(start)} to under ${amount(next)}`;
      return [band, ...terms.map((t) => {
        const rate = rates.find((r) => r.minAmount === start && r.termMonths === t)?.ratePercent;
        return rate == null ? '—' : `${amount(rate)}%`;
      })];
    }),
  };
}
