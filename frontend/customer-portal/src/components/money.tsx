import { formatMoney } from '@/lib/money';

/**
 * Renders a Money object exactly as the API returned it. Cash value is TZS 0.00 platform-wide
 * today (PolicyAccount.cashValueAmount is never credited); per the approved decision that renders
 * as-is, with no caveat and no special case — the display path is already correct for the day the
 * backend starts crediting real values.
 */
export function MoneyText({ amount, currencyCode }: { amount: string; currencyCode: string }) {
  return <span className="tabular-nums">{formatMoney(amount, currencyCode)}</span>;
}
