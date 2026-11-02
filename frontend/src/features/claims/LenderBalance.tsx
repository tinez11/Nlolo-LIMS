import type { ClaimCoverView as ClaimCover } from '@/api/types';
import { compareAmounts, formatMoney, subtractAmounts } from '@/lib/money';

/**
 * A credit-life claim's balance, three ways (2026-11-02): what the lender's statement says was owed, what the repayment
 * schedule says, and what was lent. The statement is what the claim pays; the schedule is the insurer's estimate, so a
 * statement above it is allowed but said out loud -- a 50,000,000 statement on a 10,400,000 loan once went through
 * capped and unremarked.
 *
 * Renders nothing on any other claim.
 */
export function LenderBalance({ cover }: { cover: ClaimCover | null | undefined }) {
  const scheduled = cover?.scheduledBalance ?? null;
  const owed = cover?.statementOwed ?? null;
  if (!scheduled) return null;
  const above = owed && compareAmounts(owed.amount, scheduled.amount) > 0
    ? subtractAmounts(owed.amount, scheduled.amount)
    : null;

  return (
    <div className="space-y-1 rounded-md bg-surface-muted px-3 py-2 text-xs">
      <p className="flex flex-wrap gap-x-4 gap-y-0.5">
        <span>
          <span className="text-muted-foreground">Lender&rsquo;s statement</span>{' '}
          <span className="font-medium tabular-nums">{owed ? formatMoney(owed) : '—'}</span>
        </span>
        <span>
          <span className="text-muted-foreground">Repayment schedule</span>{' '}
          <span className="font-medium tabular-nums">{formatMoney(scheduled)}</span>
        </span>
        {cover?.loanAmount && (
          <span>
            <span className="text-muted-foreground">Lent</span>{' '}
            <span className="font-medium tabular-nums">{formatMoney(cover.loanAmount)}</span>
          </span>
        )}
      </p>
      {above && (
        <p className="text-status-warning-fg">
          The statement is {formatMoney({ amount: above, currencyCode: scheduled.currencyCode })} above the schedule.
          Check it before paying more than the schedule.
        </p>
      )}
    </div>
  );
}
