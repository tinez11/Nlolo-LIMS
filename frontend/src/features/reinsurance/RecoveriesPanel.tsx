import { useEffect, useState } from 'react';
import type { ClaimRecoveryView } from '@/api/types';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatInstant } from '@/lib/dates';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectConfirmingRecovery, selectRecoveries, useReinsuranceStore } from '@/store/reinsuranceStore';

/**
 * `GET /claims/{claimId}/recoveries` -- read-only line items, plus
 * `POST .../confirm` (staff FINANCE_OFFICER/ADMIN) for whichever are still
 * pending (`confirmedAt === null`). A recovery is entirely event-derived
 * (`RecoveryCalculator` runs off `claims.ClaimSettled`) -- confirming one only
 * records that the reinsurer actually paid; there is no un-confirm.
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
        <RecoveryRow key={r.recoveryId} claimId={claimId} recovery={r} />
      ))}
    </ul>
  );
}

function RecoveryRow({ claimId, recovery }: { claimId: string; recovery: ClaimRecoveryView }) {
  const confirmRecovery = useReinsuranceStore((s) => s.confirmRecovery);
  const resetConfirmRecovery = useReinsuranceStore((s) => s.resetConfirmRecovery);
  const confirming = useReinsuranceStore(selectConfirmingRecovery(recovery.recoveryId));
  const [attempt] = useState<MutationAttempt>(() => startMutation());

  useEffect(() => {
    resetConfirmRecovery(recovery.recoveryId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recovery.recoveryId]);

  return (
    <li className="rounded-md border border-border p-2.5 text-xs">
      <div className="flex items-center justify-between">
        <span className="font-mono text-xs text-muted-foreground">
          treaty {recovery.treatyId.slice(0, 8)}
        </span>
        <span className="font-medium">{formatMoney(recovery.recoverableAmount)}</span>
      </div>

      {recovery.confirmedAt ? (
        <p className="mt-1 text-xs text-status-success-fg">
          Confirmed {formatInstant(recovery.confirmedAt)}
        </p>
      ) : (
        <div className="mt-1.5">
          <Button
            size="sm"
            variant="outline"
            disabled={confirming.status === 'loading'}
            onClick={() => void confirmRecovery(claimId, recovery.recoveryId, attempt)}
          >
            {confirming.status === 'loading' ? 'Confirming…' : 'Confirm recovery'}
          </Button>
          {confirming.status === 'error' && confirming.error && (
            <p role="alert" className="mt-1 text-xs text-status-danger-fg">
              {confirming.error.detail ?? confirming.error.title}
            </p>
          )}
        </div>
      )}
    </li>
  );
}
