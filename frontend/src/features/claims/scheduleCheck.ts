import type { ClaimCoverView } from '@/api/types';
import { compareAmounts } from '@/lib/money';

/**
 * True when this amount pays more than the repayment schedule's balance on a credit-life claim (2026-11-02): the
 * lender's statement may honestly exceed the schedule, so it is allowed -- with a reason the manager gives.
 */
export function aboveSchedule(cover: ClaimCoverView | null | undefined, amount: string | undefined): boolean {
  const scheduled = cover?.scheduledBalance;
  if (!scheduled || typeof amount !== 'string') return false;
  return compareAmounts(amount, scheduled.amount) > 0;
}
