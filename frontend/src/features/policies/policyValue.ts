import type { CashValueTermsView, PolicyUnitsView, PolicyView } from '@/api/types';
import { formatMoney } from '@/lib/money';

/** Portfolios whose value is a savings account's balance (product step 3; deposits; pensions before vesting). */
const ACCOUNT_PORTFOLIOS: readonly string[] = ['SAV', 'DEP', 'PEN', 'DANN'];

/** Categories that MAY carry a cash-value table -- whether this version does is the server's answer. */
const TABLE_CATEGORIES: readonly string[] = ['ENDOWMENT', 'WHOLE_LIFE', 'EDUCATION_SAVINGS'];

export interface ValueLine {
  label: string;
  value: string;
  note: string;
}

/** Whether the page needs the version's cash-value terms to say what the value is. */
export function needsCashValueTerms(policy: Pick<PolicyView, 'productCategory' | 'portfolioCode'>): boolean {
  return TABLE_CATEGORIES.includes(policy.productCategory ?? '') && !ACCOUNT_PORTFOLIOS.includes(policy.portfolioCode ?? '');
}

/**
 * The policy summary's value line, for every kind of policy and scheme (2026-10-19).
 *
 * <p>It used to choose by category alone and fall back to "pure protection": a unit-linked policy, an immediate
 * annuity and a vested pension all said so; an endowment with no cash-value table said "nothing until the first two
 * or three years" when it would never have one; and an agent, who cannot read the account, was told a savings
 * account's balance came from a cash-value table. It now chooses by what the value IS -- units, an account, a table,
 * an income, or nothing -- from the policy's portfolio and, for a table, the server's word on whether one exists.
 *
 * <p>Every figure is the server's; this only chooses the words around it.
 */
export function policyValueLine(input: {
  policy: Pick<PolicyView, 'productCategory' | 'portfolioCode' | 'cashValue'>;
  isStaff: boolean;
  units?: PolicyUnitsView | null | undefined;
  cashValueTerms?: CashValueTermsView | null | undefined;
  /** A pension or deferred annuity whose account has bought its income. */
  vested?: boolean | undefined;
}): ValueLine {
  const { policy, isStaff, units, cashValueTerms, vested } = input;
  const cash = formatMoney(policy.cashValue);
  const portfolio = policy.portfolioCode ?? '';
  const category = policy.productCategory ?? '';

  if (category === 'UNIT_LINKED') {
    if (!units) {
      return { label: 'Fund value', value: '—',
        note: isStaff ? "The units held in the funds, at each fund's latest approved price. See the Units tab."
          : "The units held in the funds, at each fund's latest approved price." };
    }
    if (units.totalValue == null) {
      const waiting = units.pending.length;
      return {
        label: 'Fund value',
        value: 'Not invested yet',
        note: waiting > 0
          // A count, not a sum: the amounts are on the Units tab, each as the server holds it.
          ? `${waiting} purchase${waiting === 1 ? '' : 's'} waiting for the funds' next approved price. Nothing is invested until then.`
          : 'No units are held yet.',
      };
    }
    return {
      label: 'Fund value',
      value: formatMoney({ amount: units.totalValue, currencyCode: units.currency ?? policy.cashValue?.currencyCode ?? 'TZS' }),
      note: "The units held in the funds, at each fund's latest approved price. A premium buys units at the first price approved after it arrives."
        + (isStaff ? ' See the Units tab.' : ''),
    };
  }

  if (ACCOUNT_PORTFOLIOS.includes(portfolio)) {
    if (vested) {
      return { label: 'Account value', value: 'Paid into the pension',
        note: 'At vesting the account bought the pension, which now pays an income. See the Annuity tab.' };
    }
    return {
      label: 'Account value',
      value: cash,
      note: portfolio === 'DEP'
        ? 'The deposit, with interest added at maturity.' + (isStaff ? ' See the Account tab.' : '')
        : 'The savings account balance, after every charge and interest posted.' + (isStaff ? ' See the Account tab.' : ''),
    };
  }

  if (portfolio === 'IANN') {
    return { label: 'Cash value', value: 'None',
      note: 'An annuity in payment has no cash value: the purchase price bought an income. See the Annuity tab.' };
  }

  if (TABLE_CATEGORIES.includes(category)) {
    if (cashValueTerms == null) return { label: 'Cash value', value: cash, note: '' };
    if (!cashValueTerms.hasTable) {
      return { label: 'Cash value', value: 'None',
        note: 'This version has no cash-value table, so the policy has no surrender value. It pays its benefits on death or at maturity.' };
    }
    return {
      label: 'Cash value',
      value: cash,
      note: "From the product's cash-value table, restated as premiums are paid."
        + (cashValueTerms.minYearsForValue != null
          ? ` Nothing before policy year ${cashValueTerms.minYearsForValue}.` : ''),
    };
  }

  // Term, credit life, group life and funeral -- individual or scheme: cover only.
  return { label: 'Cash value', value: cash, note: 'This product carries no cash value — it is pure protection.' };
}
