import type { AccountChargeView, ChargeWhen } from '@/api/accountCharges';

/** When a charge is taken, in words (2026-10-09, product V32). */
export const CHARGE_WHEN: Record<ChargeWhen, string> = {
  DEPOSIT: 'On each deposit',
  WITHDRAWAL: 'On each withdrawal',
  MONTHLY: 'Monthly',
  YEARLY: 'Yearly, on the policy anniversary',
  OPENING: 'Once, at opening',
  MATURITY: 'At maturity',
};

/** What a percentage is taken of, for the form's hint. */
export const PERCENT_OF: Record<ChargeWhen, string> = {
  DEPOSIT: 'of each deposit',
  WITHDRAWAL: 'of the amount withdrawn',
  MONTHLY: 'of the balance',
  YEARLY: 'of the balance',
  OPENING: 'of the first deposit',
  MATURITY: 'of the payout',
};

const MONEY = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** "1.5% of the amount withdrawn" or "TZS 1,000.00". */
export function chargeSize(charge: Pick<AccountChargeView, 'amountType' | 'amount' | 'currency' | 'when'>): string {
  return charge.amountType === 'PERCENT'
    ? `${Number(charge.amount)}% ${PERCENT_OF[charge.when]}`
    : `${charge.currency} ${MONEY.format(Number(charge.amount))}`;
}
