import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import type { PaymentRunView } from '@/api/types';
import { DataTable, type Column } from '@/components/DataTable';
import { PageHeader } from '@/components/PageHeader';
import { CountLine, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useBenefitPayoutStore } from '@/store/benefitPayoutStore';

/**
 * The daily batches of income instalments.
 *
 * An income plan pays twelve times a year for twenty years, so each instalment earning two
 * signatures is a queue nobody clears. After a stream's FIRST instalment goes through the ordinary
 * two-person route, the rest are batched here and one person releases the batch — with the stream's
 * proof-of-life interval standing in for the second signature.
 *
 * No pager: `GET /payment-runs` returns a bare array because there is one run per day.
 */
export function PaymentRunsPage() {
  const navigate = useNavigate();
  const runs = useBenefitPayoutStore((s) => s.runs);
  const loadRuns = useBenefitPayoutStore((s) => s.loadRuns);

  useEffect(() => {
    void loadRuns();
  }, [loadRuns]);

  const count: Stat = {
    label: 'payment runs',
    value: runs.data?.length ?? null,
    pending: isInitialLoad(runs),
    hint: runs.status === 'error' && runs.data === null ? 'could not load' : 'in this tenant',
  };

  const columns: Column<PaymentRunView>[] = [
    {
      key: 'runDate',
      header: 'Run date',
      render: (run) => <span className="font-medium">{formatDate(run.runDate)}</span>,
    },
    {
      key: 'instalmentCount',
      header: 'Instalments',
      align: 'right',
      secondary: true,
      render: (run) => <span className="text-muted-foreground">{run.instalmentCount}</span>,
    },
    {
      key: 'total',
      header: 'Total',
      align: 'right',
      // The SERVER's total. Summing the instalments here would give a person a second figure to
      // approve, and two independent additions of the same list are two chances to disagree.
      render: (run) => formatMoney(run.total),
    },
    {
      key: 'status',
      header: 'Status',
      render: (run) => <StatusBadge kind="paymentRun" value={run.status} />,
    },
  ];

  function renderBody() {
    if (isInitialLoad(runs)) return <TableSkeleton columns={columns.length} />;

    if (runs.status === 'error' && runs.error && runs.data === null) {
      return <ErrorPanel error={runs.error} onRetry={() => void loadRuns()} />;
    }

    const rows = runs.data ?? [];
    if (rows.length === 0) {
      return (
        <EmptyState
          title="No payment runs yet"
          description="A run is prepared each day income instalments fall due. Only an income plan produces one — a maturity or survival benefit is approved on its own page."
        />
      );
    }

    return (
      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(run) => run.paymentRunId}
        onRowActivate={(run) => navigate(`/staff/payment-runs/${encodeURIComponent(run.paymentRunId)}`)}
        caption="Payment runs"
      />
    );
  }

  return (
    <>
      <PageHeader
        title="Payment runs"
        description="One batch of income instalments a day. The system assembles it; one person releases it."
        count={<CountLine {...count} />}
      />
      <div className="px-6 pb-6">
        <div className="rounded-lg border border-border bg-surface">{renderBody()}</div>
      </div>
    </>
  );
}
