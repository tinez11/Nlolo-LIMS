import { useEffect, useState } from 'react';
import { useAuth } from 'react-oidc-context';
import { Link, useParams } from 'react-router-dom';
import type { ExpenseAllocationView } from '@/api/types';
import { canApproveJournals, readIdentity } from '@/auth/claims';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { CheckboxField } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { formatInstant } from '@/lib/dates';
import { useExpenseAllocationStore } from '@/store/expenseAllocationStore';
import {
  ALLOCATION_STATUS_LABEL,
  CATEGORY_LABEL,
  DRIVER_LABEL,
  canDecideAllocation,
  money,
} from './expenseAllocation';

/**
 * One month's expense allocation (IFRS 17 I5b, P-19): the totals and the study they come from, the month's pool, and the
 * split per group and category -- computed now while it awaits a decision, as posted once decided. A finance approver
 * who did not prepare it approves it (acknowledging a total above the pool) or rejects it with a reason.
 */
export function ExpenseAllocationPage() {
  const { allocationId = '' } = useParams();
  const current = useExpenseAllocationStore((s) => s.current);
  const load = useExpenseAllocationStore((s) => s.load);

  useEffect(() => {
    void load(allocationId);
  }, [allocationId, load]);

  if (current.status === 'error' && current.error && current.data?.allocationId !== allocationId) {
    return <ErrorPanel error={current.error} onRetry={() => void load(allocationId)} />;
  }
  if (!current.data || current.data.allocationId !== allocationId) return <LoadingBlock />;
  return <Allocation allocation={current.data} />;
}

function Allocation({ allocation: a }: { allocation: ExpenseAllocationView }) {
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const approver = canApproveJournals(identity);
  return (
    <>
      <PageHeader
        title={`Expense allocation ${a.period}`}
        description={`${ALLOCATION_STATUS_LABEL[a.status] ?? a.status} · total ${money(a.total)} · pool ${money(a.pool)}`}
      />
      <div className="space-y-4 px-6 pb-6">
        <section className="grid grid-cols-1 gap-2 rounded-lg border border-border bg-surface p-4 text-sm sm:grid-cols-2"
          aria-label="About this allocation">
          {a.nilReason ? (
            <p><span className="text-muted-foreground">No allocation this month:</span> {a.nilReason}</p>
          ) : (
            <p>
              <span className="text-muted-foreground">Totals:</span> maintenance {money(a.maintenance)} · claims handling{' '}
              {money(a.claimsHandling)} · acquisition {money(a.acquisition)}
            </p>
          )}
          {a.studyReference && <p><span className="text-muted-foreground">Study:</span> {a.studyReference}</p>}
          {a.note && <p><span className="text-muted-foreground">Note:</span> {a.note}</p>}
          <p><span className="text-muted-foreground">Prepared:</span> {formatInstant(a.preparedAt)} by {a.preparedBy}</p>
          {a.decidedAt && (
            <p>
              <span className="text-muted-foreground">{a.status === 'REJECTED' ? 'Rejected' : 'Approved'}:</span>{' '}
              {formatInstant(a.decidedAt)} by {a.decidedBy}{a.decisionReason ? ` — ${a.decisionReason}` : ''}
            </p>
          )}
          {a.replacesId && (
            <p>
              <span className="text-muted-foreground">Replaces:</span>{' '}
              <Link className="underline" to={`../${a.replacesId}`} relative="path">an earlier allocation</Link>
            </p>
          )}
        </section>

        {a.staleExtract != null && (
          <p role="status" className="text-sm text-status-danger-fg">
            Extract #{a.staleExtract} predates this allocation; create a new extract.
          </p>
        )}
        {a.overPool && (
          <p role="status" className="text-sm text-status-danger-fg">
            The total {money(a.total)} is above the month's pool of {money(a.pool)}.
          </p>
        )}

        {a.lines.length > 0 && (
          <section className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label="Lines">
            <p className="text-xs font-medium">
              {a.status === 'PREPARED' ? 'The split as it would post now' : 'As posted'} (Dr per group, Cr 8490)
            </p>
            <table className="w-full text-sm" aria-label="Allocation lines">
              <thead>
                <tr className="text-left text-xs text-muted-foreground">
                  <th className="py-1 pr-3 font-normal">Group</th>
                  <th className="py-1 pr-3 font-normal">Category</th>
                  <th className="py-1 pr-3 font-normal">Account</th>
                  <th className="py-1 pr-3 font-normal">Shared by</th>
                  <th className="py-1 text-right font-normal">Amount</th>
                </tr>
              </thead>
              <tbody>
                {a.lines.map((l) => (
                  <tr key={`${l.group}:${l.category}`} className="border-t border-border">
                    <td className="py-1 pr-3 font-mono text-xs">{l.group}</td>
                    <td className="py-1 pr-3">{CATEGORY_LABEL[l.category] ?? l.category}</td>
                    <td className="py-1 pr-3 font-mono">{l.account}</td>
                    <td className="py-1 pr-3 text-xs text-muted-foreground">
                      {l.driverCount} {DRIVER_LABEL[l.driver] ?? l.driver}
                    </td>
                    <td className="py-1 text-right tabular-nums">{money(l.amount)}</td>
                  </tr>
                ))}
                <tr className="border-t border-border font-medium">
                  <td className="py-1 pr-3" colSpan={2}>Credit</td>
                  <td className="py-1 pr-3 font-mono">8490</td>
                  <td />
                  <td className="py-1 text-right tabular-nums">{money(a.total)}</td>
                </tr>
              </tbody>
            </table>
          </section>
        )}

        {canDecideAllocation(a, identity.subject, approver) && <Decision allocation={a} />}
        {a.status === 'PREPARED' && identity.subject === a.preparedBy && (
          <p className="text-sm text-muted-foreground">A finance approver other than you will approve or reject it.</p>
        )}
      </div>
    </>
  );
}

function Decision({ allocation: a }: { allocation: ExpenseAllocationView }) {
  const approve = useExpenseAllocationStore((s) => s.approve);
  const reject = useExpenseAllocationStore((s) => s.reject);
  const acting = useExpenseAllocationStore((s) => s.acting[`decide.${a.allocationId}`]);
  const [abovePool, setAbovePool] = useState(false);
  const [reason, setReason] = useState('');
  const busy = acting?.status === 'loading';
  return (
    <section className="space-y-3 rounded-lg border border-border bg-surface p-4" aria-label="Decision">
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <form className="flex flex-wrap items-center gap-3" aria-label="Approve allocation"
        onSubmit={(e) => {
          e.preventDefault();
          void approve(a.allocationId, abovePool);
        }}>
        {a.overPool && (
          <CheckboxField label="Approve above the pool" checked={abovePool} onChange={(e) => setAbovePool(e.target.checked)} />
        )}
        <Button type="submit" size="sm" variant="primary" disabled={busy || (a.overPool && !abovePool)}>
          Approve and post
        </Button>
      </form>
      <form className="flex items-end gap-2" aria-label="Reject allocation"
        onSubmit={(e) => {
          e.preventDefault();
          void reject(a.allocationId, reason.trim());
        }}>
        <FormField label="Reason to reject">
          <Input inputSize="sm" value={reason} onChange={(e) => setReason(e.target.value)} />
        </FormField>
        <Button type="submit" size="sm" variant="ghost" disabled={busy || reason.trim() === ''}>
          Reject
        </Button>
      </form>
    </section>
  );
}
