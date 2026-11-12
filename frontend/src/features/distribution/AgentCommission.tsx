import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { correctAccrual } from '@/api/distribution';
import type { CommissionAccrualView, CommissionStatementView } from '@/api/types';
import { ConfirmAct } from '@/components/ConfirmAct';
import { DataTable, type Column } from '@/components/DataTable';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import type { ApiError } from '@/lib/http';
import { Field } from '@/components/Field';
import { Panel } from '@/components/Panel';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { formatDate } from '@/lib/dates';
import { formatMoney, NO_VALUE } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectAccruals, selectStatements, useDistributionStore } from '@/store/distributionStore';
import {
  accrualTypeLabel,
  commissionTotals,
  darToday,
  monthLabel,
  statementMonths,
  type CommissionTotals,
  type StatementMonth,
} from './commissionView';
import { PayoutDecision } from './PayoutDecision';
import { PayoutForm } from './PayoutForm';

/**
 * What one agent has earned: headline totals, the months, and the lines of the chosen month with
 * their client, product, type, date and amount (spec 2026-10-12).
 *
 * Shared by the agent's "My commission" screen and staff's AgentDetailPage. Who may read which
 * agent is the server's decision (`enforceAgentReadAccess`); this renders whatever it is allowed
 * to load. The selected month is local state -- callers remount with `key={agentId}` when the
 * agent changes, rather than resetting state in an effect.
 */
export function AgentCommission({
  agentId,
  realm,
  canManage,
}: {
  agentId: string;
  realm: 'agents' | 'staff';
  canManage: boolean;
}) {
  // Read once per mount: "this month" must not shift under the user mid-session, and the figures
  // and the month list have to agree on which month is current.
  const [today] = useState(() => darToday());
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const loadStatements = useDistributionStore((s) => s.loadStatements);
  const statements = useDistributionStore(selectStatements(agentId));

  useEffect(() => {
    void loadStatements(agentId);
  }, [agentId, loadStatements]);

  if (isInitialLoad(statements)) return <LoadingBlock label="Loading commission" />;
  if (statements.status === 'error' && statements.error && statements.data === null) {
    return <ErrorPanel error={statements.error} onRetry={() => void loadStatements(agentId)} />;
  }

  const all = statements.data ?? [];
  const currentPeriod = today.slice(0, 7);
  const months = statementMonths(all, currentPeriod);
  const selected =
    months.find((m) => m.key === selectedKey) ?? months.find((m) => m.period === currentPeriod) ?? months[0];

  const monthColumns: Column<StatementMonth>[] = [
    { key: 'month', header: 'Month', render: (m) => monthLabel(m.period) },
    {
      key: 'status',
      header: 'Status',
      render: (m) => (m.statement ? <StatusBadge kind="commissionStatement" value={m.statement.status} /> : NO_VALUE),
    },
    {
      key: 'total',
      header: 'Total',
      align: 'right',
      render: (m) => (m.statement ? formatMoney(m.statement.totalAmount) : NO_VALUE),
    },
  ];

  return (
    <>
      <Panel title="Totals" subtitle="Net of clawbacks. Paid when finance releases a closed month.">
        <Totals totals={commissionTotals(all, today)} />
      </Panel>

      <Panel title="Statements">
        <DataTable
          caption="Commission statements by month"
          columns={monthColumns}
          rows={months}
          rowKey={(m) => m.key}
          onRowActivate={(m) => setSelectedKey(m.key)}
          isRowSelected={(m) => m.key === selected.key}
        />
      </Panel>

      {selected.statement ? (
        <StatementLines
          key={selected.key}
          agentId={agentId}
          realm={realm}
          statement={selected.statement}
          canManage={canManage}
        />
      ) : (
        <Panel title={monthLabel(selected.period)}>
          <EmptyState title={`No commission earned in ${monthLabel(selected.period)} yet.`} />
        </Panel>
      )}
    </>
  );
}

function Totals({ totals }: { totals: CommissionTotals[] }) {
  if (totals.length === 0) {
    return <p className="px-4 pb-4 text-xs text-muted-foreground">Nothing earned yet.</p>;
  }
  // The currency joins the label only when there is more than one, so the usual case reads plainly.
  const label = (text: string, code: string) => (totals.length > 1 ? `${text} (${code})` : text);
  return (
    <dl className="px-4 pb-2">
      {totals.map((t) => (
        <div key={t.currencyCode}>
          <Field label={label('Earned this month', t.currencyCode)} value={formatMoney(t.earnedThisMonth)} />
          <Field label={label('Earned this year', t.currencyCode)} value={formatMoney(t.earnedThisYear)} />
          <Field label={label('Paid out', t.currencyCode)} value={formatMoney(t.paidOut)} />
          <Field label={label('Awaiting payout', t.currencyCode)} value={formatMoney(t.awaitingPayout)} />
        </div>
      ))}
    </dl>
  );
}

function StatementLines({
  agentId,
  realm,
  statement,
  canManage,
}: {
  agentId: string;
  realm: 'agents' | 'staff';
  statement: CommissionStatementView;
  canManage: boolean;
}) {
  const loadAccruals = useDistributionStore((s) => s.loadAccruals);
  const loadStatements = useDistributionStore((s) => s.loadStatements);
  const accruals = useDistributionStore(selectAccruals(agentId, statement.statementId));

  useEffect(() => {
    void loadAccruals(agentId, statement.statementId);
  }, [agentId, statement.statementId, loadAccruals]);

  const month = monthLabel(statement.period);
  const rows = accruals.data ?? [];
  const [correcting, setCorrecting] = useState<CommissionAccrualView | null>(null);
  // A line already corrected has a reversal booked against it by a correction.
  const corrected = new Set(rows.filter((r) => r.sourceRef?.startsWith('correction:')).map((r) => r.reversesAccrualId));
  const title = accruals.data ? `${month} — ${rows.length} ${rows.length === 1 ? 'line' : 'lines'}` : month;

  const columns: Column<CommissionAccrualView>[] = [
    { key: 'client', header: 'Client', render: (a) => a.clientName ?? NO_VALUE },
    { key: 'product', header: 'Product', secondary: true, render: (a) => a.productName ?? NO_VALUE },
    {
      key: 'policy',
      header: 'Policy',
      nowrap: true,
      render: (a) => (
        <Link
          className="font-mono text-xs underline"
          to={`/${realm}/policies/${encodeURIComponent(a.policyNumber)}`}
        >
          {a.policyNumber}
        </Link>
      ),
    },
    { key: 'type', header: 'Type', render: (a) => accrualTypeLabel(a) },
    { key: 'accrued', header: 'Accrued', secondary: true, nowrap: true, render: (a) => formatDate(a.accruedOn) },
    {
      key: 'amount',
      header: 'Amount',
      align: 'right',
      render: (a) => (
        <span className={a.reversesAccrualId ? 'text-status-danger-fg' : undefined}>{formatMoney(a.amount)}</span>
      ),
    },
    // Correct a line booked at the wrong amount (finding 187 / D8): staff who manage agents, while the month is open,
    // on a line that is not itself a reversal and has not been corrected already.
    ...(realm === 'staff' && canManage && statement.status === 'OPEN'
      ? [{
          key: 'correct',
          header: '',
          align: 'right' as const,
          render: (a: CommissionAccrualView) =>
            !a.reversesAccrualId && !corrected.has(a.accrualId) && Number(a.amount.amount) > 0 ? (
              <Button size="sm" variant="outline" onClick={() => setCorrecting(a)}>Correct</Button>
            ) : null,
        }]
      : []),
  ];

  return (
    <Panel title={title}>
      {isInitialLoad(accruals) && <LoadingBlock label="Loading lines" />}
      {accruals.status === 'error' && accruals.error && accruals.data === null && (
        <div className="p-4">
          <ErrorPanel error={accruals.error} onRetry={() => void loadAccruals(agentId, statement.statementId)} />
        </div>
      )}
      {accruals.data && rows.length === 0 && <EmptyState title={`No commission earned in ${month} yet.`} />}
      {rows.length > 0 && (
        <DataTable caption={`Commission lines for ${month}`} columns={columns} rows={rows} rowKey={(a) => a.accrualId} />
      )}
      {correcting && (
        <CorrectLine
          line={correcting}
          onDone={() => {
            setCorrecting(null);
            void loadAccruals(agentId, statement.statementId);
            void loadStatements(agentId);
          }}
          onCancel={() => setCorrecting(null)}
        />
      )}
      {/* The whole statement, not just its id: a confirmation that cannot name the amount it is
          about to pay is not a confirmation. */}
      {statement.status === 'AWAITING_PAYOUT_APPROVAL' ? (
        <div className="px-4 pb-4">
          <PayoutDecision agentId={agentId} statement={statement} />
        </div>
      ) : (
        canManage && (
          <div className="px-4 pb-4">
            {statement.status === 'CLOSED' && statement.payoutRefusalReason && (
              <p className="text-xs text-muted-foreground">Last payout refused: {statement.payoutRefusalReason}</p>
            )}
            <PayoutForm agentId={agentId} statement={statement} />
          </div>
        )
      )}
    </Panel>
  );
}

/**
 * Correct one commission line booked at the wrong amount (2026-11-12, finance finding 187 / D8: a 5% plan stored as
 * 500% credited TZS 10,000,000 on a 2,000,000 deposit). The server reverses it in full and books the right amount in
 * this month's statement and the ledger, with the reason; the original line stays as it was.
 */
function CorrectLine({ line, onDone, onCancel }: { line: CommissionAccrualView; onDone: () => void; onCancel: () => void }) {
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const valid = /^\d+(\.\d{1,2})?$/.test(amount.trim()) && reason.trim().length > 0
    && Number(amount) !== Number(line.amount.amount);
  const right = valid ? formatMoney({ amount: Number(amount).toFixed(2), currencyCode: line.amount.currencyCode }) : null;

  async function correct() {
    setBusy(true);
    setError(null);
    try {
      await correctAccrual(line.accrualId, Number(amount).toFixed(2), reason.trim());
      onDone();
    } catch (e) {
      setError(e as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-3 border-t border-border px-4 py-3">
      <ConfirmAct
        heading="Correct this commission?"
        consequence={
          <>
            {line.clientName ?? line.policyNumber}: <strong>{formatMoney(line.amount)}</strong> is reversed in full
            {right ? <> and <strong>{right}</strong> is booked in its place</> : null}, in this month&apos;s statement and
            the ledger. The original line stays as it was, with the reason beside the correction.
          </>
        }
        reversal="A commission can be corrected once. A mistake in the correction is put right by a manual journal."
        confirmLabel="Correct commission"
        tone="danger"
        busy={busy}
        confirmDisabled={!valid}
        onConfirm={() => void correct()}
        onCancel={onCancel}
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <FormField required label="The right amount (TZS)">
            <Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="100000.00" />
          </FormField>
          <FormField required label="Why it is corrected">
            <Input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Rate typed as 500%, agreed 5%" />
          </FormField>
        </div>
      </ConfirmAct>
      {error && <InlineError lead="The commission could not be corrected" error={error} />}
    </div>
  );
}
