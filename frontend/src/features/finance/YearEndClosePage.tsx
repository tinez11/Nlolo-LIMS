import { useEffect, useState } from 'react';
import { useAuth } from 'react-oidc-context';
import { Link, useParams } from 'react-router-dom';
import type { YearEndCloseView } from '@/api/types';
import { canApproveJournals, readIdentity } from '@/auth/claims';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { formatInstant } from '@/lib/dates';
import { useYearEndStore } from '@/store/yearEndStore';
import { CLOSE_STATUS_LABEL, canDecideClose, money, resultLabel } from './yearEnd';
import { YearEndFigures } from './YearEndFigures';

/**
 * One year-end close (IFRS 17 I6): its figures -- computed now while it awaits a decision, as posted once decided -- and
 * the decision by a finance approver who did not prepare it. A stale close (classes 4-8 posted in the year since) needs a
 * new close before December can lock.
 */
export function YearEndClosePage() {
  const { closeId = '' } = useParams();
  const current = useYearEndStore((s) => s.current);
  const load = useYearEndStore((s) => s.load);

  useEffect(() => {
    void load(closeId);
  }, [closeId, load]);

  if (current.status === 'error' && current.error && current.data?.closeId !== closeId) {
    return <ErrorPanel error={current.error} onRetry={() => void load(closeId)} />;
  }
  if (!current.data || current.data.closeId !== closeId) return <LoadingBlock />;
  return <Close close={current.data} />;
}

function Close({ close: c }: { close: YearEndCloseView }) {
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const approver = canApproveJournals(identity);
  return (
    <>
      <PageHeader
        title={`Close of ${c.year}`}
        description={`${CLOSE_STATUS_LABEL[c.status ?? ''] ?? c.status} · ${resultLabel(c.profit)} · dividends ${money(c.dividends)}`}
      />
      <div className="space-y-4 px-6 pb-6">
        <section className="grid grid-cols-1 gap-2 rounded-lg border border-border bg-surface p-4 text-sm sm:grid-cols-2"
          aria-label="About this close">
          <p><span className="text-muted-foreground">Prepared:</span> {c.preparedAt ? formatInstant(c.preparedAt) : ''} by {c.preparedBy}</p>
          {c.decidedAt && (
            <p>
              <span className="text-muted-foreground">{c.status === 'REJECTED' ? 'Rejected' : 'Approved'}:</span>{' '}
              {formatInstant(c.decidedAt)} by {c.decidedBy}{c.decisionReason ? ` — ${c.decisionReason}` : ''}
            </p>
          )}
          {c.replacesId && (
            <p>
              <span className="text-muted-foreground">Replaces:</span>{' '}
              <Link className="underline" to={`../${c.replacesId}`} relative="path">an earlier close</Link>
            </p>
          )}
        </section>
        {c.stale && (
          <p role="status" className="text-sm text-status-danger-fg">
            Postings to classes 4–8 since this close was approved: prepare a new close before December can lock.
          </p>
        )}
        <YearEndFigures close={c} />
        {canDecideClose(c, identity.subject, approver) && c.closeId && <Decision id={c.closeId} />}
        {c.status === 'PREPARED' && identity.subject === c.preparedBy && (
          <p className="text-sm text-muted-foreground">A finance approver other than you will approve or reject it.</p>
        )}
      </div>
    </>
  );
}

function Decision({ id }: { id: string }) {
  const approve = useYearEndStore((s) => s.approve);
  const reject = useYearEndStore((s) => s.reject);
  const acting = useYearEndStore((s) => s.acting[`decide.${id}`]);
  const [reason, setReason] = useState('');
  const busy = acting?.status === 'loading';
  return (
    <section className="space-y-3 rounded-lg border border-border bg-surface p-4" aria-label="Decision">
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <Button type="button" size="sm" variant="primary" disabled={busy} onClick={() => void approve(id)}>
        Approve and post
      </Button>
      <form className="flex items-end gap-2" aria-label="Reject close"
        onSubmit={(e) => {
          e.preventDefault();
          void reject(id, reason.trim());
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
