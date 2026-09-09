import { useEffect } from 'react';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { formatInstant } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useCommunicationsStore } from '@/store/communicationsStore';
import { DispatchStatusBadge } from './DispatchStatusBadge';
import { humanizeTemplateKey } from './templateKeys';

/**
 * What this customer has been told about this policy.
 *
 * <p>The reason this panel exists rather than sending people to the global outbox: standing on a
 * policy, the question is never "what went out today". It is "has this person been told, and did
 * it arrive" — and answering that from a tenant-wide list means knowing a party id and filtering
 * by hand while somebody waits on the phone.
 *
 * <p>It matters most on an offer. A customer who says nobody warned them their cover never
 * started is either right, in which case there is a FAILED row here saying why, or mistaken, in
 * which case there is a SENT row with a timestamp.
 */
export function MessagesPanel({ policyNumber }: { policyNumber: string }) {
  const byPolicy = useCommunicationsStore((s) => s.byPolicy);
  const loadForPolicy = useCommunicationsStore((s) => s.loadForPolicy);

  useEffect(() => {
    void loadForPolicy(policyNumber);
  }, [loadForPolicy, policyNumber]);

  if (isInitialLoad(byPolicy)) return <LoadingBlock label="Loading messages…" />;

  if (byPolicy.status === 'error' && byPolicy.error && byPolicy.data === null) {
    return <ErrorPanel error={byPolicy.error} onRetry={() => void loadForPolicy(policyNumber)} />;
  }

  const rows = byPolicy.data ?? [];
  if (rows.length === 0) {
    return (
      <EmptyState
        title="Nothing sent about this policy yet"
        description="Messages appear here as the platform sends them — when an offer is made, when cover starts, and if an offer closes unpaid."
      />
    );
  }

  return (
    <ul className="divide-y divide-border px-4 pb-4">
      {rows.map((dispatch) => (
        <li key={dispatch.dispatchId} className="flex items-start justify-between gap-3 py-2">
          <div className="min-w-0">
            <p className="text-sm font-medium">{humanizeTemplateKey(dispatch.templateKey)}</p>
            <p className="text-xs text-muted-foreground">
              {dispatch.channel} · <span className="tabular-nums">{formatInstant(dispatch.createdAt)}</span>
            </p>
            {/* Only rendered when there is one, and only a FAILED row has one. This is the line
                that turns "we think we told them" into something checkable. */}
            {dispatch.failureReason && (
              <p className="mt-0.5 text-xs text-status-danger-fg">{dispatch.failureReason}</p>
            )}
          </div>
          <DispatchStatusBadge status={dispatch.status} />
        </li>
      ))}
    </ul>
  );
}
