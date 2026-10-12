import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import type { CommissionAccrualView, CommissionStatementView } from '@/api/types';
import { DataTable, type Column } from '@/components/DataTable';
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
  const accruals = useDistributionStore(selectAccruals(agentId, statement.statementId));

  useEffect(() => {
    void loadAccruals(agentId, statement.statementId);
  }, [agentId, statement.statementId, loadAccruals]);

  const month = monthLabel(statement.period);
  const rows = accruals.data ?? [];
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
      {/* The whole statement, not just its id: a confirmation that cannot name the amount it is
          about to pay is not a confirmation. */}
      {canManage && (
        <div className="px-4 pb-4">
          <PayoutForm agentId={agentId} statement={statement} />
        </div>
      )}
    </Panel>
  );
}
