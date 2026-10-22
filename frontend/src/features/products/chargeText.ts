import type { AccountChargeView, ChargeWhen } from '@/api/accountCharges';

/** When a charge is taken, in words (2026-10-09, product V32; premium and transfer in, V34). */
export const CHARGE_WHEN: Record<ChargeWhen, string> = {
  PREMIUM: 'On each premium or top-up',
  DEPOSIT: 'On each deposit',
  TRANSFER_IN: 'On each transfer in',
  WITHDRAWAL: 'On each withdrawal',
  MONTHLY: 'Monthly',
  YEARLY: 'Yearly, on the policy anniversary',
  OPENING: 'Once, at opening',
  MATURITY: 'At maturity',
};

/** What a percentage is taken of, for the form's hint. */
export const PERCENT_OF: Record<ChargeWhen, string> = {
  PREMIUM: 'of each premium',
  DEPOSIT: 'of each deposit',
  TRANSFER_IN: 'of each transfer in',
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

/** "Years 1–3", "From year 2", "Year 1"; nothing for every year. */
export function chargeYears(charge: Pick<AccountChargeView, 'fromPolicyYear' | 'toPolicyYear'>): string {
  const { fromPolicyYear: from, toPolicyYear: to } = charge;
  if (to == null) return from <= 1 ? '' : `From year ${from}`;
  return from === to ? `Year ${from}` : `Years ${from}–${to}`;
}

/** "Monthly · TZS 1,000.00 · Years 1–3": when, how much, and over which years. */
export function chargeLine(charge: AccountChargeView): string {
  return [CHARGE_WHEN[charge.when], chargeSize(charge), chargeYears(charge)].filter(Boolean).join(' · ');
}
