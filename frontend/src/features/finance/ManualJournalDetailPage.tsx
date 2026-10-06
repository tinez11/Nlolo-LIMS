import { useEffect, useRef, useState } from 'react';
import { useAuth } from 'react-oidc-context';
import { Link, useNavigate, useParams } from 'react-router-dom';
import type { ManualJournalView } from '@/api/types';
import { canApproveJournals, readIdentity } from '@/auth/claims';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { formatInstant } from '@/lib/dates';
import { useManualJournalsStore } from '@/store/manualJournalsStore';
import { STATUS_LABEL } from './ManualJournalsPage';

const money = (n: number) => n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/**
 * One manual journal (IFRS 17 I4): its lines and documents, and what can be done with it now -- the preparer edits,
 * attaches, uploads lines, submits or withdraws; a finance approver who did not prepare it approves (which posts it)
 * or rejects it; a posted journal is corrected by a reversal, itself approved.
 */
export function ManualJournalDetailPage() {
  const { id = '' } = useParams();
  const current = useManualJournalsStore((s) => s.current);
  const load = useManualJournalsStore((s) => s.load);

  useEffect(() => {
    void load(id);
  }, [id, load]);

  if (current.status === 'error' && current.error && current.data?.id !== id) {
    return <ErrorPanel error={current.error} onRetry={() => void load(id)} />;
  }
  if (!current.data || current.data.id !== id) return <LoadingBlock />;
  return <Journal journal={current.data} />;
}

function Journal({ journal: j }: { journal: ManualJournalView }) {
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const isPreparer = identity.subject === j.preparer;
  const mayDecide = canApproveJournals(identity) && !isPreparer;

  return (
    <>
      <PageHeader title={j.title} description={`${STATUS_LABEL[j.status] ?? j.status} · period ${j.period}`} />
      <div className="space-y-4 px-6 pb-6">
        <section className="grid grid-cols-1 gap-2 rounded-lg border border-border bg-surface p-4 text-sm sm:grid-cols-2"
          aria-label="About this journal">
          <p><span className="text-muted-foreground">Reason:</span> {j.reason ?? '—'}</p>
          <p><span className="text-muted-foreground">Reason code:</span> {j.reasonCode ?? '—'}</p>
          <p><span className="text-muted-foreground">Prepared:</span> {formatInstant(j.preparedAt)}{isPreparer ? ' (by you)' : ''}</p>
          <p><span className="text-muted-foreground">Template:</span> {j.templateId ?? '—'}</p>
          {j.autoReverseOn && <p><span className="text-muted-foreground">Reverses automatically on:</span> {j.autoReverseOn}</p>}
          {j.reversesJournalId && (
            <p>
              <span className="text-muted-foreground">Reverses journal:</span>{' '}
              <Link className="underline" to={`../../gl-postings/${j.reversesJournalId}`} relative="path">{j.reversesJournalId}</Link>
            </p>
          )}
          {j.decidedAt && (
            <p>
              <span className="text-muted-foreground">{j.status === 'REJECTED' ? 'Rejected' : 'Approved'}:</span>{' '}
              {formatInstant(j.decidedAt)}{j.decisionReason ? ` — ${j.decisionReason}` : ''}
            </p>
          )}
          {j.journalEntryId && (
            <p>
              <span className="text-muted-foreground">Posted as:</span>{' '}
              <Link className="underline" to={`../../gl-postings/${j.journalEntryId}`} relative="path">journal entry</Link>
            </p>
          )}
        </section>

        <Lines journal={j} />
        <Documents journal={j} editable={j.status === 'DRAFT' && isPreparer} />
        <Actions journal={j} isPreparer={isPreparer} mayDecide={mayDecide} />
      </div>
    </>
  );
}

function Lines({ journal: j }: { journal: ManualJournalView }) {
  const upload = useManualJournalsStore((s) => s.uploadLines);
  const acting = useManualJournalsStore((s) => s.acting[`lines.${j.id}`]);
  const input = useRef<HTMLInputElement>(null);
  return (
    <section className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label="Lines">
      <p className="text-xs font-medium">Lines</p>
      <div className="overflow-x-auto">
        <table className="w-full text-sm" aria-label="Journal lines">
          <thead>
            <tr className="text-left text-xs text-muted-foreground">
              <th className="w-12 py-1 pr-3 font-normal">Side</th>
              <th className="py-1 pr-3 font-normal">Account</th>
              <th className="w-36 py-1 pr-3 text-right font-normal">Amount</th>
              <th className="py-1 pr-3 font-normal">Description</th>
              <th className="w-20 py-1 font-normal">Branch</th>
            </tr>
          </thead>
          <tbody>
            {j.lines.map((l) => (
              <tr key={l.lineNo} className="border-t border-border">
                <td className="py-1 pr-3 font-mono">{l.side === 'DR' ? 'Dr' : 'Cr'}</td>
                <td className="py-1 pr-3">
                  <span className="font-mono">{l.accountCode}</span>{' '}
                  <span className="text-muted-foreground">{l.accountName ?? 'unknown account'}</span>
                  {l.accountMode && l.accountMode !== 'MAN' && <span className="ml-1 text-xs">({l.accountMode})</span>}
                </td>
                <td className="py-1 pr-3 text-right tabular-nums">{money(l.amount)}</td>
                <td className="py-1 pr-3">{l.description ?? ''}</td>
                <td className="py-1">{l.branch ?? ''}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr className="border-t border-border text-xs">
              <td colSpan={2} className="py-1 pr-3 text-muted-foreground">Totals</td>
              <td className="py-1 pr-3 text-right tabular-nums" colSpan={3}>
                Dr {money(j.totalDebit)} · Cr {money(j.totalCredit)}
              </td>
            </tr>
          </tfoot>
        </table>
      </div>
      {j.status === 'DRAFT' && (
        <div className="flex flex-wrap items-center gap-2">
          <input ref={input} type="file" accept=".csv,.xlsx" className="sr-only" aria-label="Lines file"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) void upload(j.id, file);
              e.target.value = '';
            }} />
          <Button type="button" size="sm" variant="outline" onClick={() => input.current?.click()}>
            Replace lines from a file
          </Button>
          <span className="text-xs text-muted-foreground">CSV or Excel: account, side, amount, description, branch, fund, reference</span>
        </div>
      )}
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
    </section>
  );
}

function Documents({ journal: j, editable }: { journal: ManualJournalView; editable: boolean }) {
  const attach = useManualJournalsStore((s) => s.attach);
  const detach = useManualJournalsStore((s) => s.detach);
  const acting = useManualJournalsStore((s) => s.acting[`documents.${j.id}`]);
  const input = useRef<HTMLInputElement>(null);
  return (
    <section className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label="Documents">
      <p className="text-xs font-medium">Supporting documents</p>
      {j.documentRefs.length === 0 ? (
        <p className="text-sm text-muted-foreground">None yet — a journal needs at least one to be submitted.</p>
      ) : (
        <ul className="text-sm">
          {j.documentRefs.map((ref) => (
            <li key={ref} className="flex items-center gap-2">
              <span className="font-mono text-xs">{ref}</span>
              {editable && (
                <Button type="button" size="sm" variant="ghost" onClick={() => void detach(j.id, ref)}>
                  Remove
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {editable && (
        <>
          <input ref={input} type="file" accept=".pdf,.png,.jpg,.jpeg" className="sr-only" aria-label="Document file"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) void attach(j.id, file);
              e.target.value = '';
            }} />
          <Button type="button" size="sm" variant="outline" onClick={() => input.current?.click()}>
            Attach a document
          </Button>
        </>
      )}
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
    </section>
  );
}

function Actions({ journal: j, isPreparer, mayDecide }: { journal: ManualJournalView; isPreparer: boolean; mayDecide: boolean }) {
  const navigate = useNavigate();
  const act = useManualJournalsStore((s) => s.act);
  const reject = useManualJournalsStore((s) => s.reject);
  const reverse = useManualJournalsStore((s) => s.reverse);
  const acting = useManualJournalsStore((s) => s.acting[`journal.${j.id}`]);
  const [reason, setReason] = useState('');
  const busy = acting?.status === 'loading';

  return (
    <section className="space-y-3 rounded-lg border border-border bg-surface p-4" aria-label="Actions">
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <div className="flex flex-wrap items-end gap-2">
        {j.status === 'DRAFT' && isPreparer && (
          <>
            <Button asChild size="sm" variant="outline">
              <Link to="edit">Edit</Link>
            </Button>
            <Button type="button" size="sm" variant="primary" disabled={busy} onClick={() => void act(j.id, 'submission')}>
              Submit for approval
            </Button>
          </>
        )}
        {j.status === 'SUBMITTED' && isPreparer && (
          <Button type="button" size="sm" variant="outline" disabled={busy} onClick={() => void act(j.id, 'withdrawal')}>
            Withdraw
          </Button>
        )}
        {j.status === 'SUBMITTED' && mayDecide && (
          <>
            <Button type="button" size="sm" variant="primary" disabled={busy} onClick={() => void act(j.id, 'approval')}>
              Approve and post
            </Button>
            <form className="flex items-end gap-2" aria-label="Reject journal"
              onSubmit={(e) => {
                e.preventDefault();
                void reject(j.id, reason.trim());
              }}>
              <FormField label="Reason to reject">
                <Input inputSize="sm" value={reason} onChange={(e) => setReason(e.target.value)} />
              </FormField>
              <Button type="submit" size="sm" variant="ghost" disabled={busy || reason.trim() === ''}>
                Reject
              </Button>
            </form>
          </>
        )}
        {j.status === 'SUBMITTED' && !isPreparer && !mayDecide && (
          <p className="text-sm text-muted-foreground">Awaiting a finance approver.</p>
        )}
        {j.status === 'SUBMITTED' && isPreparer && (
          <p className="text-sm text-muted-foreground">A finance approver other than you will approve or reject it.</p>
        )}
        {j.status === 'APPROVED' && !j.reversesJournalId && (
          <Button type="button" size="sm" variant="outline" disabled={busy}
            onClick={async () => {
              const draft = await reverse(j.id);
              if (draft) navigate(`../${draft.id}`, { relative: 'path' });
            }}>
            Reverse
          </Button>
        )}
      </div>
    </section>
  );
}
