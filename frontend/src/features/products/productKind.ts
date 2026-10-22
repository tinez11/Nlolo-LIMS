import type { ProductCategory } from '@/api/types';
import { ACCOUNT_CATEGORIES, CASH_VALUE_CATEGORIES, SCHEDULED_CATEGORIES, WITH_PROFITS_CATEGORIES } from './publishVersionSchema';

/**
 * What a product IS, which is what decides what its version form asks (2026-10-22).
 *
 * The form used to decide by category alone, so a fixed deposit -- an ENDOWMENT by category -- was asked for premium
 * rate bands, a rating table, instalment loadings, a with-profits switch and a cash-value table, none of which a
 * deposit has. A savings category's value basis is what separates a cash-value endowment from a savings account, a
 * deposit or a term account; every other category is one kind.
 */
export type ValueBasis = 'SCALE' | 'ACCOUNT' | 'DEPOSIT' | 'TERM_ACCOUNT';

export type ProductKind =
  | 'PROTECTION'
  | 'CASH_VALUE'
  | 'SAVINGS_ACCOUNT'
  | 'DEPOSIT'
  | 'TERM_ACCOUNT'
  | 'FUNERAL'
  | 'ANNUITY'
  | 'UNIT_LINKED';

export const KIND_LABEL: Record<ProductKind, string> = {
  PROTECTION: 'Life cover',
  CASH_VALUE: 'Cash-value plan',
  SAVINGS_ACCOUNT: 'Savings account',
  DEPOSIT: 'Fixed deposit',
  TERM_ACCOUNT: 'Term account',
  FUNERAL: 'Funeral plan',
  ANNUITY: 'Annuity',
  UNIT_LINKED: 'Unit-linked plan',
};

/** One line each, for the kind chooser. */
export const KIND_SUMMARY: Record<ProductKind, string> = {
  PROTECTION: 'Pays on death, priced from a rate table.',
  CASH_VALUE: 'Premiums build a surrender value from a table.',
  SAVINGS_ACCOUNT: 'Contributions earn interest; withdrawals allowed.',
  DEPOSIT: 'One deposit, fixed rate, paid out at maturity.',
  TERM_ACCOUNT: 'Regular contributions, locked until the term ends.',
  FUNERAL: 'Family cover from plans and a premium grid.',
  ANNUITY: 'Buys an income.',
  UNIT_LINKED: 'Premiums buy units in funds.',
};

/** The savings categories may be any of these four; the choice is the version's value basis. */
export const SAVINGS_KINDS: readonly { basis: ValueBasis; kind: ProductKind }[] = [
  { basis: 'SCALE', kind: 'CASH_VALUE' },
  { basis: 'ACCOUNT', kind: 'SAVINGS_ACCOUNT' },
  { basis: 'DEPOSIT', kind: 'DEPOSIT' },
  { basis: 'TERM_ACCOUNT', kind: 'TERM_ACCOUNT' },
];

/** Whether the version form must ask what the product is: only a savings category can be more than one kind. */
export function asksForKind(category: ProductCategory): boolean {
  return ACCOUNT_CATEGORIES.includes(category);
}

/**
 * Where a savings product's form should start. A product already filed under the deposit or savings portfolio has
 * told us what it is; the form used to open every one of them on the cash-value table regardless.
 */
export function defaultValueBasis(category: ProductCategory, portfolioCode: string | null | undefined): ValueBasis {
  if (!asksForKind(category)) return 'SCALE';
  if (portfolioCode === 'DEP') return 'DEPOSIT';
  if (portfolioCode === 'SAV' || portfolioCode === 'PEN') return 'ACCOUNT';
  return 'SCALE';
}

export function kindOf(category: ProductCategory, valueBasis: string): ProductKind {
  switch (category) {
    case 'FUNERAL':
      return 'FUNERAL';
    case 'ANNUITY':
      return 'ANNUITY';
    case 'UNIT_LINKED':
      return 'UNIT_LINKED';
    default:
      if (!asksForKind(category)) return 'PROTECTION';
      return SAVINGS_KINDS.find((k) => k.basis === valueBasis)?.kind ?? 'CASH_VALUE';
  }
}

/** Priced off a premium rate table: the kinds that have base rates, rating adjustments and instalment loadings. */
export function isRatedKind(kind: ProductKind): boolean {
  return kind === 'PROTECTION' || kind === 'CASH_VALUE';
}

/** Kinds whose value lives in an account; they carry account charges. */
export function isAccountKind(kind: ProductKind): boolean {
  return kind === 'SAVINGS_ACCOUNT' || kind === 'TERM_ACCOUNT';
}

export type StepId = 'kind' | 'basics' | 'eligibility' | 'pricing' | 'pays' | 'charges' | 'review';

export interface Step {
  id: StepId;
  label: string;
}

/**
 * The steps a kind needs, in order. A step with nothing for the kind to fill in is not shown at all. `hasAccount` and
 * not the kind decides Charges, because a deferred annuity keeps an account too.
 */
export function stepsFor(category: ProductCategory, kind: ProductKind, hasAccount: boolean): Step[] {
  const steps: Step[] = [];
  if (asksForKind(category)) steps.push({ id: 'kind', label: 'Product type' });
  steps.push({ id: 'basics', label: 'Basics' });
  steps.push({ id: 'eligibility', label: 'Who can buy' });
  steps.push({ id: 'pricing', label: kind === 'DEPOSIT' || isAccountKind(kind) ? 'Rates' : 'Pricing' });
  steps.push({ id: 'pays', label: 'What it pays' });
  if (hasAccount) steps.push({ id: 'charges', label: 'Charges' });
  steps.push({ id: 'review', label: 'Review' });
  return steps;
}

/** The sections inside "What it pays", by kind. */
export function paysSections(category: ProductCategory, kind: ProductKind) {
  return {
    cashValue: kind === 'CASH_VALUE' && CASH_VALUE_CATEGORIES.includes(category),
    withProfits: kind === 'CASH_VALUE' && WITH_PROFITS_CATEGORIES.includes(category),
    payouts: SCHEDULED_CATEGORIES.includes(category) && kind !== 'DEPOSIT' && kind !== 'TERM_ACCOUNT',
  };
}
