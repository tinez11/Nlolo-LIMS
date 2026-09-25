import type { DisbursementView } from '@/api/types';
import { formatInstant } from '@/lib/dates';

/**
 * One sentence on whether anybody has to act on a claim's payout, and who.
 *
 * @param superseded true for an older row -- an earlier attempt a newer payment replaced
 */
export function whatHappensNext(payout: DisbursementView, superseded: boolean): string {
  if (superseded && payout.status === 'FAILED') {
    return 'An earlier attempt that failed. The newer payment above replaced it.';
  }
  switch (payout.status) {
    case 'AWAITING_EXECUTION':
      return 'Waiting for Finance to make this bank transfer and record it under Finance → Bank transfers. The claim settles, and the life leaves cover, when they do.';
    case 'PENDING':
      return 'Sent to the mobile-money gateway. The claim settles when the gateway confirms it.';
    case 'IN_DOUBT':
      return 'The gateway has not said whether this was paid. Finance is alerted; the claim stays here until the outcome is known.';
    case 'COMPLETED':
      return payout.executedAt
        ? `Paid. Finance recorded the transfer ${formatInstant(payout.executedAt)}.`
        : 'Paid. The gateway confirmed it.';
    case 'FAILED':
      return 'The payment failed and the claim returned to Approved, so it can be paid again.';
    default:
      return '';
  }
}
