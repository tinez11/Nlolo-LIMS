import { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  INSTALMENT_STATUSES,
  type InstalmentStatus,
  type PayoutInstalmentView,
  type PayoutKind,
} from '@/api/types';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { FilterChip } from '@/components/FilterChip';
import { PageHeader } from '@/components/PageHeader';
import { CountLine, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useAccumulationStore } from '@/store/accumulationStore';
import { useBenefitPayoutStore } from '@/store/benefitPayoutStore';

const KIND_LABEL: Record<PayoutKind, string> = {
  SURVIVAL: 'Survival benefit',
  MATURITY: 'Maturity',
  INCOME: 'Income',
  RETURN_OF_PREMIUM: 'Premium return',
  ANNUITY: 'Annuity income',
};

/**
 * The payouts register — finance's queue of money owed to living policyholders.
 *
 * Ordered by due date then id server-side, which matters more here than on most registers: a drain
 * brings many instalments due in the same instant, and an unordered page would show one row twice
 * and never show another. On a payment queue, that is a customer nobody pays.
 *
 * One status filter at a time, like every other register on this platform.
 */
export function PayoutsQueuePage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();

  const statusParam = params.get('status');
  const status =
    statusParam && (INSTALMENT_STATUSES as readonly string[]).includes(statusParam)
      ? (statusParam as InstalmentStatus)
      : undefined;
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  const queue = useBenefitPayoutStore((s) => s.queue);
  const loadQueue = useBenefitPayoutStore((s) => s.loadQueue);

  useEffect(() => {
    void loadQueue({ ...(status ? { status } : {}), page });
  }, [loadQueue, status, page]);

  function update(next: { status?: InstalmentStatus | undefined; page?: number }) {
    const merged = new URLSearchParams(params);
    if ('status' in next) {
      if (next.status) merged.set('status', next.status);
      else merged.delete('status');
      // A filter change starts a new list; keeping the old page number would land the reader on
      // page 4 of a two-page result and show them nothing.
      merged.delete('page');
    }
    if (next.page !== undefined) {
      if (next.page === 0) merged.delete('page');
      else merged.set('page', String(next.page));
    }
    setParams(merged);
  }

  const total = queue.data?.page.totalElements ?? null;
  const count: Stat = {
    label: status ? `${status.toLowerCase().replace(/_/g, ' ')} payouts` : 'payouts',
    value: total,
    pending: isInitialLoad(queue),
    hint:
      queue.status === 'error' && total === null
        ? 'could not load'
        : status
          ? 'matching this filter'
          : 'in this tenant',
  };

  const columns: Column<PayoutInstalmentView>[] = [
    {
      key: 'policyNumber',
      header: 'Policy',
      render: (p) => <span className="font-mono text-xs font-medium">{p.policyNumber}</span>,
    },
    { key: 'kind', header: 'Benefit', render: (p) => KIND_LABEL[p.kind] },
    {
      key: 'dueDate',
      header: 'Due',
      secondary: true,
      render: (p) => <span className="text-muted-foreground">{formatDate(p.dueDate)}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      render: (p) => <StatusBadge kind="payoutInstalment" value={p.status} />,
    },
    {
      key: 'currentAmount',
      header: 'Amount',
      align: 'right',
      // A premium return carries no amount until it falls due -- it is a percentage of what was
      // actually collected, which is not settled before then.
      render: (p) => (p.currentAmount ? formatMoney(p.currentAmount) : '—'),
    },
  ];

  function renderBody() {
    if (isInitialLoad(queue)) return <TableSkeleton columns={columns.length} />;

    if (queue.status === 'error' && queue.error && queue.data === null) {
      return (
        <ErrorPanel
          error={queue.error}
          onRetry={() => void loadQueue({ ...(status ? { status } : {}), page })}
        />
      );
    }

    const rows = queue.data?.items ?? [];
    if (rows.length === 0 && queue.status === 'success') {
      return (
        <EmptyState
          title={status ? 'No matching payouts' : 'No payouts yet'}
          description={
            status
              ? 'Nothing in this tenant currently has that status.'
              : 'Payouts appear here as policies reach the dates their contracts promised.'
          }
          {...(status
            ? { action: <Button size="sm" onClick={() => update({ status: undefined })}>Clear filter</Button> }
            : {})}
        />
      );
    }

    return (
      <>
        {queue.status === 'error' && queue.error && (
          <p className="border-b border-border bg-status-warning-bg px-4 py-2 text-xs text-status-warning-fg">
            Showing older data — could not refresh.
            {queue.error.traceId && <span className="ml-1 font-mono">({queue.error.traceId})</span>}
          </p>
        )}
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(p) => p.instalmentId}
          onRowActivate={(p) => navigate(`/staff/payouts/${encodeURIComponent(p.instalmentId)}`)}
          caption="Payouts"
        />
        {queue.data && (
          <Pager
            page={queue.data.page}
            busy={queue.status === 'loading'}
            onPageChange={(next) => update({ page: next })}
          />
        )}
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Payouts"
        description="Money owed to living policyholders: maturities, survival benefits, income and premium returns. Each one is reviewed by one person and approved by another."
        count={<CountLine {...count} />}
      />

      <div className="px-6 pb-6">
        <div className="rounded-lg border border-border bg-surface">
          <div className="flex flex-wrap items-center gap-1.5 gap-y-2 border-b border-border px-3 py-2.5">
            <FilterChip label="All" active={status === undefined} onClick={() => update({ status: undefined })} />
            {INSTALMENT_STATUSES.map((value) => (
              <FilterChip
                key={value}
                label={<StatusBadge kind="payoutInstalment" value={value} />}
                bare
                active={status === value}
                onClick={() => update({ status: value })}
              />
            ))}
          </div>
          {renderBody()}
        </div>
        <AwaitingDeposits />
      </div>
    </>
  );
}

/**
 * Matured fixed-term deposits whose money waits for a payee: no number came with the deposit, or
 * the payment to it failed. Finance records the payee on the policy's Account tab. Rendered only
 * when there is something waiting -- an empty second list under the register would be noise.
 */
function AwaitingDeposits() {
  const awaiting = useAccumulationStore((s) => s.awaiting);
  const loadAwaiting = useAccumulationStore((s) => s.loadAwaiting);
  const navigate = useNavigate();

  useEffect(() => {
    void loadAwaiting();
  }, [loadAwaiting]);

  const rows = awaiting.data ?? [];
  if (rows.length === 0) return null;
  return (
    <section className="mt-6" aria-label="Matured deposits waiting for a payee">
      <h2 className="mb-2 text-sm font-medium">Matured deposits waiting for a payee</h2>
      <div className="divide-y divide-border rounded-lg border border-border bg-surface" role="list">
        {rows.map((row) => (
          <div key={row.policyNumber} role="listitem" className="flex items-center justify-between gap-3 px-4 py-2.5">
            <span className="text-sm">
              {row.policyNumber} · matured {formatDate(row.maturedOn)} · {formatMoney(row.balance)}
            </span>
            <Button size="sm" variant="outline" onClick={() => navigate(`/staff/policies/${encodeURIComponent(row.policyNumber)}`)}>
              Record payee
            </Button>
          </div>
        ))}
      </div>
    </section>
  );
}
