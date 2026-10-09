import { useEffect } from 'react';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectCessions, useReinsuranceStore } from '@/store/reinsuranceStore';

/**
 * `GET /policies/{policyNumber}/cessions` -- read-only. Cessions are entirely
 * event-derived (`CessionCalculator` runs off `policy.PolicyIssued` against
 * whatever treaty was ACTIVE at issuance), so there is no action to offer
 * here, only a record of what a treaty already ceded.
 */
export function CessionsPanel({ policyNumber }: { policyNumber: string }) {
  const loadCessions = useReinsuranceStore((s) => s.loadCessions);
  const cessions = useReinsuranceStore(selectCessions(policyNumber));

  useEffect(() => {
    void loadCessions(policyNumber);
  }, [policyNumber, loadCessions]);

  if (isInitialLoad(cessions)) return <LoadingBlock label="Loading cessions" />;
  if (cessions.status === 'error' && cessions.error && cessions.data === null) {
    return <ErrorPanel error={cessions.error} onRetry={() => void loadCessions(policyNumber)} />;
  }
  const rows = cessions.data ?? [];
  if (rows.length === 0) {
    return (
      <p className="px-4 pb-4 text-xs text-muted-foreground">
        No reinsurance cession on this policy.
      </p>
    );
  }

  return (
    <ul className="space-y-2 px-4 pb-4">
      {rows.map((c) => (
        <li key={c.cessionId} className="rounded-md border border-border p-2.5 text-xs">
          <div className="flex items-center justify-between">
            <span className="font-mono text-xs text-muted-foreground">
              treaty {c.treatyId.slice(0, 8)}
            </span>
            <span>
              <span className="text-muted-foreground">Cover ceded </span>
              <span className="font-medium">{formatMoney(c.cededAmount)}</span>
            </span>
          </div>
          {c.cededPremium && (
            <p className="mt-1 text-xs text-muted-foreground">
              Premium ceded: {formatMoney(c.cededPremium)}
            </p>
          )}
        </li>
      ))}
    </ul>
  );
}
