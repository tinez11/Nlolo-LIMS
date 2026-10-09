import { useEffect } from 'react';
import type { ClaimRecoveryView } from '@/api/types';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { formatInstant } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectRecoveries, useReinsuranceStore } from '@/store/reinsuranceStore';

/**
 * `GET /claims/{claimId}/recoveries` -- read-only. Since IFRS 17 I3c a recovery is calculated and posted when the
 * claim is APPROVED (guide B-05: Dr 1420 / Cr 6120) on the insured part of the claim, and the amount is agreed with
 * the reinsurer on its statement -- there is no Confirm. `confirmedAt` is set only on recoveries from before, when
 * staff confirmed them, and is shown for that history.
 */
export function RecoveriesPanel({ claimId }: { claimId: string }) {
  const loadRecoveries = useReinsuranceStore((s) => s.loadRecoveries);
  const recoveries = useReinsuranceStore(selectRecoveries(claimId));

  useEffect(() => {
    void loadRecoveries(claimId);
  }, [claimId, loadRecoveries]);

  if (isInitialLoad(recoveries)) return <LoadingBlock label="Loading recoveries" />;
  if (recoveries.status === 'error' && recoveries.error && recoveries.data === null) {
    return <ErrorPanel error={recoveries.error} onRetry={() => void loadRecoveries(claimId)} />;
  }
  const rows = recoveries.data ?? [];
  if (rows.length === 0) {
    return (
      <p className="px-4 pb-4 text-xs text-muted-foreground">
        No reinsurance recovery on this claim.
      </p>
    );
  }

  return (
    <ul className="space-y-2 px-4 pb-4">
      {rows.map((r) => (
        <RecoveryRow key={r.recoveryId} recovery={r} />
      ))}
    </ul>
  );
}

function RecoveryRow({ recovery }: { recovery: ClaimRecoveryView }) {
  return (
    <li className="rounded-md border border-border p-2.5 text-xs">
      <div className="flex items-center justify-between">
        <span className="font-mono text-xs text-muted-foreground">
          treaty {recovery.treatyId.slice(0, 8)}
        </span>
        <span className="font-medium">{formatMoney(recovery.recoverableAmount)}</span>
      </div>
      <p className="mt-1 text-xs text-muted-foreground">
        {recovery.confirmedAt
          ? `Confirmed ${formatInstant(recovery.confirmedAt)}`
          : 'Posted at approval — agreed with the reinsurer on its statement'}
      </p>
    </li>
  );
}
