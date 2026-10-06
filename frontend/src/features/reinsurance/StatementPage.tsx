import { useEffect, useRef, useState } from 'react';
import { useAuth } from 'react-oidc-context';
import { Link, useParams } from 'react-router-dom';
import type { ReinsuranceStatementView } from '@/api/types';
import { canApproveJournals, readIdentity } from '@/auth/claims';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { formatInstant } from '@/lib/dates';
import { useReinsuranceStatementsStore } from '@/store/reinsuranceStatementsStore';
import { ENTRY_LABEL, STATEMENT_STATUS_LABEL, settlementSide } from './statementForm';

const money = (amount: string) =>
  Number(amount).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/**
 * One quarter's reinsurance statement (IFRS 17 I3d): the platform's figures and what they settle, what the reinsurer's
 * statement adds (funds withheld, profit commission), the journal approval will post, and what can be done now -- the
 * preparer completes, attaches and submits; a finance approver who did not prepare it approves (which posts R-04, R-01
 * and R-03 as one journal) or rejects it.
 */
export function StatementPage() {
  const { statementId = '' } = useParams();
  const current = useReinsuranceStatementsStore((s) => s.current);
  const load = useReinsuranceStatementsStore((s) => s.load);

  useEffect(() => {
    void load(statementId);
  }, [statementId, load]);

  if (current.status === 'error' && current.error && current.data?.statementId !== statementId) {
    return <ErrorPanel error={current.error} onRetry={() => void load(statementId)} />;
  }
  if (!current.data || current.data.statementId !== statementId) return <LoadingBlock />;
  return <Statement statement={current.data} />;
}

function Statement({ statement: s }: { statement: ReinsuranceStatementView }) {
  const auth = useAuth();
  const identity = readIdentity(auth.user?.access_token);
  const isPreparer = identity.subject === s.preparer;
  const mayDecide = canApproveJournals(identity) && !isPreparer;
  const editable = s.status === 'DRAFT' && isPreparer;

  return (
    <>
      <PageHeader
        title={`${s.reinsurerName ?? 'Treaty'} · ${s.quarter}`}
        description={`Reinsurance statement · ${STATEMENT_STATUS_LABEL[s.status] ?? s.status}`}
      />
      <div className="space-y-4 px-6 pb-6">
        <section className="grid grid-cols-1 gap-2 rounded-lg border border-border bg-surface p-4 text-sm sm:grid-cols-2"
          aria-label="Statement">
          <p><span className="text-muted-foreground">Status:</span> {STATEMENT_STATUS_LABEL[s.status] ?? s.status}</p>
          <p><span className="text-muted-foreground">Prepared:</span> {formatInstant(s.preparedAt)}{isPreparer ? ' (by you)' : ''}</p>
          <p><span className="text-muted-foreground">Ceded premium:</span> {money(s.premium)} {s.currency}</p>
          <p><span className="text-muted-foreground">Commission:</span> {money(s.commission)} {s.currency}</p>
          <p><span className="text-muted-foreground">Recoveries:</span> {money(s.recoveries)} {s.currency}</p>
          <p><span className="text-muted-foreground">Funds withheld:</span> {money(s.fundsWithheld)} {s.currency}</p>
          <p><span className="text-muted-foreground">Profit commission:</span> {money(s.profitCommission)} {s.currency}</p>
          <p className="font-medium">{settlementSide(s)}</p>
          <p className="sm:col-span-2"><span className="text-muted-foreground">Reason:</span> {s.reason ?? '—'}</p>
          {s.decidedAt && (
            <p className="sm:col-span-2">
              <span className="text-muted-foreground">{s.status === 'REJECTED' ? 'Rejected' : 'Approved'}:</span>{' '}
              {formatInstant(s.decidedAt)} by {s.decidedBy}{s.decisionReason ? ` — ${s.decisionReason}` : ''}
            </p>
          )}
        </section>

        {editable && <Figures key={`${s.statementId}:${s.fundsWithheld}:${s.profitCommission}:${s.reason ?? ''}`} statement={s} />}
        <Items statement={s} />
        <Journal statement={s} />
        <Documents statement={s} editable={editable} />
        <Actions statement={s} isPreparer={isPreparer} mayDecide={mayDecide} />
      </div>
    </>
  );
}

/** What the reinsurer's statement states: funds withheld (R-04) and profit commission (R-03), and why. */
function Figures({ statement: s }: { statement: ReinsuranceStatementView }) {
  const update = useReinsuranceStatementsStore((st) => st.update);
  const acting = useReinsuranceStatementsStore((st) => st.acting[`save.${s.statementId}`]);
  const [withheld, setWithheld] = useState(s.fundsWithheld);
  const [profitCommission, setProfitCommission] = useState(s.profitCommission);
  const [reason, setReason] = useState(s.reason ?? '');
  return (
    <form className="space-y-3 rounded-lg border border-border bg-surface p-4" aria-label="From the reinsurer's statement"
      onSubmit={(e) => {
        e.preventDefault();
        void update(s.statementId, { fundsWithheld: withheld.trim() || '0', profitCommission: profitCommission.trim() || '0',
          reason: reason.trim() });
      }}>
      <p className="text-xs font-medium">From the reinsurer's statement</p>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <FormField label="Funds withheld">
          <Input inputSize="sm" inputMode="decimal" value={withheld} onChange={(e) => setWithheld(e.target.value)} />
        </FormField>
        <FormField label="Profit commission">
          <Input inputSize="sm" inputMode="decimal" value={profitCommission} onChange={(e) => setProfitCommission(e.target.value)} />
        </FormField>
        <FormField label="Reason">
          <Input inputSize="sm" value={reason} onChange={(e) => setReason(e.target.value)} />
        </FormField>
      </div>
      <Button type="submit" size="sm" variant="outline" disabled={acting?.status === 'loading'}>
        Save
      </Button>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
    </form>
  );
}

function Items({ statement: s }: { statement: ReinsuranceStatementView }) {
  return (
    <section className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label="Items">
      <p className="text-xs font-medium">What it settles</p>
      {s.items.length === 0 ? (
        <p className="text-sm text-muted-foreground">Nothing: no bordereau charged and no recovery recorded this quarter.</p>
      ) : (
        <table className="w-full text-sm" aria-label="Settled items">
          <tbody>
            {s.items.map((i) => (
              <tr key={`${i.type}:${i.id}`} className="border-t border-border">
                <td className="py-1 pr-3">
                  {i.type === 'BORDEREAU' ? (
                    <Link className="underline" to={`../../bordereaux/${i.id}`} relative="path">{i.label}</Link>
                  ) : (
                    i.label
                  )}
                </td>
                <td className="py-1 text-right tabular-nums">{money(i.amount)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

/** The journal approval posts -- the approver sees exactly what will reach the ledger. */
function Journal({ statement: s }: { statement: ReinsuranceStatementView }) {
  return (
    <section className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label="Journal preview">
      <p className="text-xs font-medium">{s.status === 'APPROVED' ? 'Journal posted' : 'Journal on approval'}</p>
      {s.journal.length === 0 ? (
        <p className="text-sm text-muted-foreground">Nothing to post: every figure is zero.</p>
      ) : (
        <table className="w-full text-sm" aria-label="Journal">
          <thead>
            <tr className="text-left text-xs text-muted-foreground">
              <th className="py-1 pr-3 font-normal">Entry</th>
              <th className="w-12 py-1 pr-3 font-normal">Side</th>
              <th className="w-20 py-1 pr-3 font-normal">Account</th>
              <th className="w-40 py-1 text-right font-normal">Amount</th>
            </tr>
          </thead>
          <tbody>
            {s.journal.map((l, n) => (
              <tr key={n} className="border-t border-border">
                <td className="py-1 pr-3">{ENTRY_LABEL[l.entry] ?? l.entry}</td>
                <td className="py-1 pr-3 font-mono">{l.side === 'DR' ? 'Dr' : 'Cr'}</td>
                <td className="py-1 pr-3 font-mono">{l.account}</td>
                <td className="py-1 text-right tabular-nums">{money(l.amount)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function Documents({ statement: s, editable }: { statement: ReinsuranceStatementView; editable: boolean }) {
  const attach = useReinsuranceStatementsStore((st) => st.attach);
  const acting = useReinsuranceStatementsStore((st) => st.acting[`documents.${s.statementId}`]);
  const input = useRef<HTMLInputElement>(null);
  return (
    <section className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label="Documents">
      <p className="text-xs font-medium">The reinsurer's statement</p>
      {s.documentRefs.length === 0 ? (
        <p className="text-sm text-muted-foreground">None yet — a statement needs the reinsurer's to be submitted.</p>
      ) : (
        <ul className="text-sm">
          {s.documentRefs.map((ref) => (
            <li key={ref} className="font-mono text-xs">{ref}</li>
          ))}
        </ul>
      )}
      {editable && (
        <>
          <input ref={input} type="file" accept=".pdf,.png,.jpg,.jpeg,.xlsx" className="sr-only" aria-label="Statement file"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) void attach(s.statementId, file);
              e.target.value = '';
            }} />
          <Button type="button" size="sm" variant="outline" onClick={() => input.current?.click()}>
            Attach the reinsurer's statement
          </Button>
        </>
      )}
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
    </section>
  );
}

function Actions({ statement: s, isPreparer, mayDecide }: { statement: ReinsuranceStatementView; isPreparer: boolean; mayDecide: boolean }) {
  const act = useReinsuranceStatementsStore((st) => st.act);
  const reject = useReinsuranceStatementsStore((st) => st.reject);
  const acting = useReinsuranceStatementsStore((st) => st.acting[`statement.${s.statementId}`]);
  const [reason, setReason] = useState('');
  const busy = acting?.status === 'loading';

  return (
    <section className="space-y-3 rounded-lg border border-border bg-surface p-4" aria-label="Actions">
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <div className="flex flex-wrap items-end gap-2">
        {s.status === 'DRAFT' && isPreparer && (
          <Button type="button" size="sm" variant="primary" disabled={busy} onClick={() => void act(s.statementId, 'submission')}>
            Submit for approval
          </Button>
        )}
        {s.status === 'SUBMITTED' && isPreparer && (
          <>
            <Button type="button" size="sm" variant="outline" disabled={busy} onClick={() => void act(s.statementId, 'withdrawal')}>
              Withdraw
            </Button>
            <p className="text-sm text-muted-foreground">Waiting for a finance approver other than you.</p>
          </>
        )}
        {s.status === 'SUBMITTED' && mayDecide && (
          <>
            <Button type="button" size="sm" variant="primary" disabled={busy} onClick={() => void act(s.statementId, 'approval')}>
              Approve and post
            </Button>
            <form className="flex items-end gap-2" aria-label="Reject statement"
              onSubmit={(e) => {
                e.preventDefault();
                void reject(s.statementId, reason.trim());
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
        {s.status === 'SUBMITTED' && !isPreparer && !mayDecide && (
          <p className="text-sm text-muted-foreground">Awaiting a finance approver.</p>
        )}
        {s.status === 'APPROVED' && <p className="text-sm">Posted</p>}
      </div>
    </section>
  );
}
