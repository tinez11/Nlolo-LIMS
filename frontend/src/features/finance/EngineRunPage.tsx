import { useEffect, useRef, useState } from 'react';
import { useAuth } from 'react-oidc-context';
import { Link, useParams } from 'react-router-dom';
import type { EngineReconciliation, EngineRunView } from '@/api/types';
import { canApproveJournals, readIdentity } from '@/auth/claims';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { formatInstant } from '@/lib/dates';
import { useEngineStore } from '@/store/engineStore';
import { FIGURE_LABEL, RUN_STATUS_LABEL, canAccept, canDecide, differenceLabel } from './enginePeriod';

const money = (n: number | null | undefined) =>
  n == null ? '—' : n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/**
 * One IFRS 17 engine run (IFRS 17 I5a, step 7): what the engine returned, group by group; every error of a rejected
 * upload; approval by a finance approver who did not upload it, with the appointed actuary's sign-off reference and
 * report; and, once posted, the ledger reconciled to the engine -- each difference explained by one person and
 * accepted by another before the period can lock.
 */
export function EngineRunPage() {
  const { runId = '' } = useParams();
  const current = useEngineStore((s) => s.current);
  const load = useEngineStore((s) => s.load);

  useEffect(() => {
    void load(runId);
  }, [runId, load]);

  if (current.status === 'error' && current.error && current.data?.runId !== runId) {
    return <ErrorPanel error={current.error} onRetry={() => void load(runId)} />;
  }
  if (!current.data || current.data.runId !== runId) return <LoadingBlock />;
  return <Run run={current.data} />;
}

function Run({ run: r }: { run: EngineRunView }) {
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const approver = canApproveJournals(identity);
  return (
    <>
      <PageHeader
        title={`Engine run ${r.engineReference ?? ''}`.trim()}
        description={`${RUN_STATUS_LABEL[r.status] ?? r.status} · period ${r.period ?? '—'} · extract #${r.extractNumber ?? '—'}`}
      />
      <div className="space-y-4 px-6 pb-6">
        <section className="grid grid-cols-1 gap-2 rounded-lg border border-border bg-surface p-4 text-sm sm:grid-cols-2"
          aria-label="About this run">
          <p><span className="text-muted-foreground">Engine:</span> {r.engineName ?? '—'} · measured {r.measurementDate ?? '—'}</p>
          <p><span className="text-muted-foreground">Uploaded:</span> {formatInstant(r.uploadedAt)} by {r.uploadedBy}</p>
          {r.decidedAt && (
            <p>
              <span className="text-muted-foreground">{r.status === 'REJECTED' ? 'Rejected' : 'Approved'}:</span>{' '}
              {formatInstant(r.decidedAt)} by {r.decidedBy}{r.decisionReason ? ` — ${r.decisionReason}` : ''}
            </p>
          )}
          {r.signOffReference && <p><span className="text-muted-foreground">Actuary's sign-off:</span> {r.signOffReference}</p>}
          {r.replacesRunId && (
            <p><span className="text-muted-foreground">Replaces:</span> <Link className="underline" to={`../${r.replacesRunId}`} relative="path">an earlier run</Link></p>
          )}
          {r.replacedByRunId && (
            <p><span className="text-muted-foreground">Replaced by:</span> <Link className="underline" to={`../${r.replacedByRunId}`} relative="path">a later run</Link></p>
          )}
        </section>

        {r.errors.length > 0 && (
          <section className="space-y-1 rounded-lg border border-border bg-surface p-4" aria-label="Errors">
            <p className="text-xs font-medium text-status-danger-fg">Why the file was rejected ({r.errors.length})</p>
            <ul className="list-disc pl-5 text-sm">
              {r.errors.map((e) => <li key={e}>{e}</li>)}
            </ul>
          </section>
        )}

        {r.groups.map((g) => (
          <section key={g.group} className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label={`Group ${g.group}`}>
            <p className="text-xs font-medium">{g.group}{g.reinsurance ? ' (reinsurance held)' : ''}</p>
            <table className="w-full text-sm" aria-label={`Lines of ${g.group}`}>
              <tbody>
                {g.lines.map((l) => (
                  <tr key={l.row} className="border-t border-border">
                    <td className="w-16 py-1 pr-3">{l.entry}</td>
                    <td className="w-12 py-1 pr-3 font-mono">{l.side === 'DR' ? 'Dr' : 'Cr'}</td>
                    <td className="w-16 py-1 pr-3 font-mono">{l.account}</td>
                    <td className="py-1 pr-3 text-right tabular-nums">{money(l.amount)}</td>
                    <td className="py-1 text-xs text-muted-foreground">{l.movement ?? ''} {l.note ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {g.closing && (
              <p className="text-xs text-muted-foreground">
                Engine closing (net Dr − Cr):{' '}
                {g.reinsurance
                  ? `ARC ${money(g.closing.arc)} · AIC ${money(g.closing.aic)} · RI CSM ${money(g.closing.riCsm)}`
                  : `LRC ${money(g.closing.lrc)} · LIC ${money(g.closing.lic)} · CSM ${money(g.closing.csm)}`}
              </p>
            )}
          </section>
        ))}

        {canDecide(r, identity.subject, approver) && <Decision run={r} />}
        {r.status === 'VALIDATED' && identity.subject === r.uploadedBy && (
          <p className="text-sm text-muted-foreground">A finance approver other than you will approve or reject it.</p>
        )}
        {r.reconciliation.length > 0 && <Reconciliation run={r} viewer={identity.subject} approver={approver} />}
      </div>
    </>
  );
}

function Decision({ run: r }: { run: EngineRunView }) {
  const approve = useEngineStore((s) => s.approve);
  const reject = useEngineStore((s) => s.reject);
  const acting = useEngineStore((s) => s.acting[`run.${r.runId}`]);
  const [signOff, setSignOff] = useState('');
  const [reason, setReason] = useState('');
  const [report, setReport] = useState<File | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const busy = acting?.status === 'loading';
  return (
    <section className="space-y-3 rounded-lg border border-border bg-surface p-4" aria-label="Decision">
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <form className="flex flex-wrap items-end gap-2" aria-label="Approve run"
        onSubmit={(e) => {
          e.preventDefault();
          if (report) void approve(r.runId, signOff.trim(), report);
        }}>
        <div className="w-72">
          <FormField label="Sign-off reference">
            <Input inputSize="sm" value={signOff} onChange={(e) => setSignOff(e.target.value)} />
          </FormField>
        </div>
        <input ref={input} type="file" accept=".pdf,.png,.jpg,.jpeg" className="sr-only" aria-label="Actuary's report"
          onChange={(e) => setReport(e.target.files?.[0] ?? null)} />
        <Button type="button" size="sm" variant="outline" onClick={() => input.current?.click()}>
          {report ? report.name : "Attach the actuary's report"}
        </Button>
        <Button type="submit" size="sm" variant="primary" disabled={busy || signOff.trim() === '' || report === null}>
          Approve and post
        </Button>
      </form>
      <form className="flex items-end gap-2" aria-label="Reject run"
        onSubmit={(e) => {
          e.preventDefault();
          void reject(r.runId, reason.trim());
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

function Reconciliation({ run: r, viewer, approver }: { run: EngineRunView; viewer: string | null | undefined; approver: boolean }) {
  return (
    <section className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label="Reconciliation section">
      <p className="text-xs font-medium">The ledger against the engine (net Dr − Cr)</p>
      <table className="w-full text-sm" aria-label="Reconciliation">
        <thead>
          <tr className="text-left text-xs text-muted-foreground">
            <th className="py-1 pr-3 font-normal">Group</th>
            <th className="py-1 pr-3 font-normal">Figure</th>
            <th className="py-1 pr-3 text-right font-normal">Ledger</th>
            <th className="py-1 pr-3 text-right font-normal">Engine</th>
            <th className="py-1 font-normal">Status</th>
          </tr>
        </thead>
        <tbody>
          {r.reconciliation.map((row) => (
            <ReconciliationRow key={`${row.group}:${row.figure}`} runId={r.runId} row={row} viewer={viewer} approver={approver} />
          ))}
        </tbody>
      </table>
    </section>
  );
}

function ReconciliationRow({ runId, row, viewer, approver }: {
  runId: string; row: EngineReconciliation; viewer: string | null | undefined; approver: boolean;
}) {
  const explain = useEngineStore((s) => s.explain);
  const accept = useEngineStore((s) => s.accept);
  const acting = useEngineStore((s) => s.acting[`exception.${runId}.${row.group}.${row.figure}`]);
  const [text, setText] = useState('');
  return (
    <tr className="border-t border-border align-top">
      <td className="py-1 pr-3">{row.group}</td>
      <td className="py-1 pr-3">{FIGURE_LABEL[row.figure] ?? row.figure}</td>
      <td className="py-1 pr-3 text-right tabular-nums">{money(row.ledger)}</td>
      <td className="py-1 pr-3 text-right tabular-nums">{money(row.engine)}</td>
      <td className="py-1">
        <p className={row.status === 'EXCEPTION' ? 'text-status-danger-fg' : undefined}>{differenceLabel(row)}</p>
        {row.explanation && <p className="text-xs text-muted-foreground">“{row.explanation}” — {row.explainedBy}</p>}
        {(row.status === 'EXCEPTION' || row.status === 'EXPLAINED') && (
          <form className="mt-1 flex items-end gap-2" aria-label={`Explain ${row.group} ${row.figure}`}
            onSubmit={(e) => {
              e.preventDefault();
              void explain(runId, row.group, row.figure, text.trim());
            }}>
            <FormField label={`Explanation for ${row.group} ${row.figure}`}>
              <Input inputSize="sm" value={text} onChange={(e) => setText(e.target.value)} />
            </FormField>
            <Button type="submit" size="sm" variant="outline" disabled={text.trim() === ''}>Explain</Button>
          </form>
        )}
        {canAccept(row, viewer, approver) && (
          <Button type="button" size="sm" variant="primary" className="mt-1"
            onClick={() => void accept(runId, row.group, row.figure)}>
            Accept explanation
          </Button>
        )}
        {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      </td>
    </tr>
  );
}
