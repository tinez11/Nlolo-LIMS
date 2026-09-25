import { useEffect } from 'react';
import type { ClaimStatus } from '@/api/types';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { formatInstant } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectPayouts, useClaimStore } from '@/store/claimStore';
import { whatHappensNext } from './payoutNextStep';

/**
 * What became of the money once the claim was approved.
 *
 * ## Why this exists
 *
 * Approval moves a claim to SETTLEMENT_REQUESTED, and the page used to say nothing more. On
 * individual business that is invisible because it lasts seconds -- the mobile-money gateway
 * answers and the claim settles itself. On credit life it can last days: the payout is an EFT to
 * the lender, and nothing moves it but a finance officer making the transfer in the bank and
 * recording it under Finance → Bank transfers -- a screen a claims manager cannot open. So the
 * claim sat there with no sign of who it was waiting on.
 *
 * Reloads when the claim's status changes, so an approval shows its payout without a refresh.
 */
export function ClaimPaymentPanel({ claimId, claimStatus }: { claimId: string; claimStatus: ClaimStatus }) {
  const loadPayouts = useClaimStore((s) => s.loadPayouts);
  const payouts = useClaimStore(selectPayouts(claimId));

  useEffect(() => {
    void loadPayouts(claimId);
  }, [claimId, claimStatus, loadPayouts]);

  if (isInitialLoad(payouts)) {
    return <LoadingBlock label="Loading payment" />;
  }
  if (payouts.data === null && payouts.status === 'error' && payouts.error) {
    return <ErrorPanel error={payouts.error} onRetry={() => void loadPayouts(claimId)} />;
  }

  const rows = payouts.data ?? [];
  if (rows.length === 0) {
    return (
      <EmptyState
        title="No payment yet"
        description="A payment is instructed when the claim is approved."
      />
    );
  }

  // Newest first. Older rows are earlier attempts -- a failed payout returns the claim to
  // APPROVED, and approving again instructs a new one.
  return (
    <ul className="divide-y divide-border">
      {rows.map((payout, index) => (
        <li key={payout.disbursementId} className="space-y-1 px-4 py-3">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <span className="text-sm font-medium">
              {formatMoney(payout.amount)}{' '}
              <span className="font-normal text-muted-foreground">
                by {payout.method === 'EFT' ? 'bank transfer' : 'mobile money'}
              </span>
            </span>
            <StatusBadge kind="disbursement" value={payout.status} />
          </div>
          <p className="text-xs text-muted-foreground">
            To <span className="font-medium">{payout.payeeRef}</span> · instructed{' '}
            {formatInstant(payout.createdAt)}
            {payout.reference && <> · reference {payout.reference}</>}
          </p>
          <p className="text-xs text-muted-foreground">{whatHappensNext(payout, index > 0)}</p>
        </li>
      ))}
    </ul>
  );
}
