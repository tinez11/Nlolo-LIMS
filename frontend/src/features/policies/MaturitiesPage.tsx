import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import type { PolicyView } from '@/api/types';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { DatePicker } from '@/components/DatePicker';
import { FormField } from '@/components/FormField';
import { PageHeader } from '@/components/PageHeader';
import { ProductName } from '@/components/ProductName';
import { CountLine, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatDate, todayIso } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { usePolicyStore } from '@/store/policyStore';

/** Today plus `months`, as `YYYY-MM-DD`. Date arithmetic through Date, never string surgery. */
function isoMonthsFromToday(months: number): string {
  const date = new Date();
  date.setMonth(date.getMonth() + months);
  const month = `${date.getMonth() + 1}`.padStart(2, '0');
  const day = `${date.getDate()}`.padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

/**
 * Which policies mature in a window — finance's cash planning list (guide §6).
 *
 * It LISTS; it does not total, and neither does this page. A sum across currencies is a number with
 * no meaning and this platform does not convert, so inventing one in the browser would be worse
 * than the server declining to.
 *
 * In-force policies only, which the server decides: a lapsed policy keeps its maturity date, and
 * counting those would overstate the cash to find by every contract that ended early.
 */
export function MaturitiesPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();

  const from = params.get('from') ?? todayIso();
  const to = params.get('to') ?? isoMonthsFromToday(3);
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  const [fromInput, setFromInput] = useState(from);
  const [toInput, setToInput] = useState(to);

  const maturing = usePolicyStore((s) => s.maturing);
  const loadMaturing = usePolicyStore((s) => s.loadMaturing);

  useEffect(() => {
    void loadMaturing({ from, to, page });
  }, [loadMaturing, from, to, page]);

  function applyWindow(event: React.FormEvent) {
    event.preventDefault();
    const merged = new URLSearchParams(params);
    merged.set('from', fromInput);
    merged.set('to', toInput);
    merged.delete('page');
    setParams(merged);
  }

  function updatePage(next: number) {
    const merged = new URLSearchParams(params);
    if (next === 0) merged.delete('page');
    else merged.set('page', String(next));
    setParams(merged);
  }

  const count: Stat = {
    label: 'policies maturing',
    value: maturing.data?.page.totalElements ?? null,
    pending: isInitialLoad(maturing),
    hint: maturing.status === 'error' && maturing.data === null ? 'could not load' : 'in this window',
  };

  const columns: Column<PolicyView>[] = [
    {
      key: 'maturityDate',
      header: 'Matures',
      render: (policy) => <span className="font-medium">{formatDate(policy.maturityDate)}</span>,
    },
    {
      key: 'policyNumber',
      header: 'Policy',
      render: (policy) => <span className="font-mono text-xs">{policy.policyNumber}</span>,
    },
    {
      key: 'productId',
      header: 'Product',
      secondary: true,
      render: (policy) => (policy.productId ? <ProductName productId={policy.productId} /> : '—'),
    },
    {
      key: 'sumAssured',
      header: 'Sum assured',
      align: 'right',
      render: (policy) => formatMoney(policy.sumAssured),
    },
    {
      key: 'status',
      header: 'Status',
      render: (policy) => (policy.status ? <StatusBadge kind="policy" value={policy.status} /> : '—'),
    },
  ];

  function renderBody() {
    if (isInitialLoad(maturing)) return <TableSkeleton columns={columns.length} />;

    if (maturing.status === 'error' && maturing.error && maturing.data === null) {
      return <ErrorPanel error={maturing.error} onRetry={() => void loadMaturing({ from, to, page })} />;
    }

    const rows = maturing.data?.items ?? [];
    if (rows.length === 0 && maturing.status === 'success') {
      return (
        <EmptyState
          title="Nothing matures in this window"
          description="No policy still in force reaches its maturity date between these two dates. Widen the window to look further ahead."
        />
      );
    }

    return (
      <>
        {maturing.status === 'error' && maturing.error && (
          <p className="border-b border-border bg-status-warning-bg px-4 py-2 text-xs text-status-warning-fg">
            Showing older data — could not refresh.
            {maturing.error.traceId && <span className="ml-1 font-mono">({maturing.error.traceId})</span>}
          </p>
        )}
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(policy) => policy.policyNumber ?? JSON.stringify(policy)}
          onRowActivate={(policy) => {
            if (policy.policyNumber) {
              navigate(`/staff/policies/${encodeURIComponent(policy.policyNumber)}`);
            }
          }}
          caption="Policies maturing"
        />
        {maturing.data && (
          <Pager page={maturing.data.page} busy={maturing.status === 'loading'} onPageChange={updatePage} />
        )}
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Maturities"
        description="Policies still in force that reach their maturity date in this window — what the insurer will have to pay, and when."
        count={<CountLine {...count} />}
      />

      <div className="px-6 pb-6">
        <div className="rounded-lg border border-border bg-surface">
          <form
            className="flex flex-wrap items-end gap-2 border-b border-border px-3 py-2.5"
            onSubmit={applyWindow}
          >
            {/* The picker reports null while a date is half-typed. The window needs two real
                dates, so a cleared field falls back to the default rather than sending nothing
                and getting a 400 the reader did not ask for. */}
            <FormField label="From">
              <DatePicker value={fromInput} onChange={(iso) => setFromInput(iso ?? todayIso())} />
            </FormField>
            <FormField label="To">
              <DatePicker value={toInput} onChange={(iso) => setToInput(iso ?? isoMonthsFromToday(3))} />
            </FormField>
            <Button type="submit" size="sm">
              Apply
            </Button>
          </form>
          {renderBody()}
        </div>
      </div>
    </>
  );
}
