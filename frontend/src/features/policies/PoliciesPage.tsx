import { useEffect, useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import { POLICY_STATUSES, type PolicyStatus, type PolicyView } from '@/api/types';
import { DEFAULT_PAGE_SIZE } from '@/api/policies';
import { PageHeader } from '@/components/AppShell';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { StatCards, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { cn } from '@/lib/cn';
import { isInitialLoad } from '@/store/createResourceSlice';
import { usePolicyStore } from '@/store/policyStore';
import { PolicyDrawer } from './PolicyDrawer';

/**
 * `GET /policies` is one of only four paged endpoints, so this screen gets the
 * pager. Filter and page live in the URL, which makes a filtered view shareable and
 * the back button correct -- the one thing React Router does not hand us for free.
 */
export function PoliciesPage() {
  const [params, setParams] = useSearchParams();
  const [previewing, setPreviewing] = useState<string | null>(null);

  const statusParam = params.get('status');
  const status: PolicyStatus | undefined =
    statusParam && (POLICY_STATUSES as readonly string[]).includes(statusParam)
      ? (statusParam as PolicyStatus)
      : undefined;
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  const list = usePolicyStore((s) => s.list);
  const loadList = usePolicyStore((s) => s.loadList);

  useEffect(() => {
    void loadList({ ...(status ? { status } : {}), page, pageSize: DEFAULT_PAGE_SIZE });
  }, [loadList, status, page]);

  function update(next: { status?: PolicyStatus | undefined; page?: number }) {
    const merged = new URLSearchParams(params);
    if ('status' in next) {
      if (next.status) merged.set('status', next.status);
      else merged.delete('status');
      // A new filter invalidates the current page offset.
      merged.delete('page');
    }
    if (next.page !== undefined) {
      if (next.page === 0) merged.delete('page');
      else merged.set('page', String(next.page));
    }
    setParams(merged);
  }

  const total = list.data?.page.totalElements ?? null;
  const busy = list.status === 'loading';

  // Counts only -- no trend arrows, because no analytics endpoint exists. The card
  // reports the total for the CURRENT query, which is the one figure genuinely
  // available, and says so rather than implying it is a global metric.
  const stats: Stat[] = [
    {
      label: status ? `${status[0]}${status.slice(1).toLowerCase()} policies` : 'All policies',
      value: total,
      // isInitialLoad, not `busy` alone: if the load has already FAILED with no
      // data, spinning forever is worse than a dash -- the user is looking at an
      // error panel that already told them the load finished, unsuccessfully.
      pending: isInitialLoad(list),
      hint:
        list.status === 'error' && total === null
          ? 'could not load'
          : status
            ? 'matching this filter'
            : 'in this tenant',
    },
  ];

  const columns: Column<PolicyView>[] = [
    {
      key: 'policyNumber',
      header: 'Policy number',
      render: (p) => <span className="font-medium">{p.policyNumber ?? '—'}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      render: (p) => <StatusBadge kind="policy" value={p.status} />,
    },
    {
      key: 'sumAssured',
      header: 'Sum assured',
      align: 'right',
      render: (p) => formatMoney(p.sumAssured),
    },
    {
      key: 'premium',
      header: 'Premium',
      align: 'right',
      secondary: true,
      render: (p) => (
        <span>
          {formatMoney(p.premium)}
          {p.premiumFrequency && (
            <span className="ml-1 text-xs text-subtle-foreground">
              /{p.premiumFrequency.replace(/LY$/, '').toLowerCase()}
            </span>
          )}
        </span>
      ),
    },
    {
      key: 'issueDate',
      header: 'Issued',
      secondary: true,
      render: (p) => <span className="text-muted-foreground">{formatDate(p.issueDate)}</span>,
    },
  ];

  function renderBody() {
    // Initial load shows skeleton rows; a refetch keeps the old rows so the table
    // the user is reading does not blank out. isInitialLoad, not `busy` alone: the
    // fetch is kicked off from an effect, which runs AFTER the first render, so
    // there is a real frame where status is still 'idle' rather than 'loading' --
    // checking only 'loading' let that frame fall through to the empty state and
    // flash "No policies yet" before the request had even started.
    if (isInitialLoad(list)) return <TableSkeleton columns={columns.length} />;

    if (list.status === 'error' && list.error && list.data === null) {
      return (
        <ErrorPanel
          error={list.error}
          onRetry={() => void loadList({ ...(status ? { status } : {}), page })}
        />
      );
    }

    const rows = list.data?.items ?? [];
    if (rows.length === 0 && list.status === 'success') {
      return (
        <EmptyState
          title={status ? `No ${status.toLowerCase()} policies` : 'No policies yet'}
          description={
            status
              ? 'Nothing in this tenant currently has that status.'
              : 'Policies appear here once they are issued.'
          }
          {...(status
            ? {
                action: (
                  <Button size="sm" onClick={() => update({ status: undefined })}>
                    Clear filter
                  </Button>
                ),
              }
            : {})}
        />
      );
    }

    return (
      <>
        {list.status === 'error' && list.error && (
          // A failed refresh keeps the stale rows and says so, rather than replacing
          // real data with an error page.
          <p className="border-b border-border bg-status-warning-bg px-4 py-2 text-xs text-status-warning-fg">
            Showing older data — could not refresh.
            {list.error.traceId && <span className="ml-1 font-mono">({list.error.traceId})</span>}
          </p>
        )}
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(p) => p.policyNumber ?? JSON.stringify(p)}
          onRowActivate={(p) => {
            if (p.policyNumber) setPreviewing(p.policyNumber);
          }}
          isRowSelected={(p) => p.policyNumber === previewing}
          caption="Policies"
        />
        {list.data && (
          <Pager
            page={list.data.page}
            busy={busy}
            onPageChange={(next) => update({ page: next })}
          />
        )}
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Policies"
        description="Every policy in your tenant. Select one to preview it."
      />

      <StatCards stats={stats} />

      <div className="px-6 pb-6">
        <div className="rounded-lg border border-border bg-surface">
          <div className="flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-2.5">
            <FilterChip
              label="All"
              active={status === undefined}
              onClick={() => update({ status: undefined })}
            />
            {POLICY_STATUSES.map((value) => (
              <FilterChip
                key={value}
                label={<StatusBadge kind="policy" value={value} />}
                active={status === value}
                onClick={() => update({ status: value })}
              />
            ))}
          </div>

          {renderBody()}
        </div>
      </div>

      <PolicyDrawer policyNumber={previewing} onClose={() => setPreviewing(null)} />
    </>
  );
}

function FilterChip({
  label,
  active,
  onClick,
}: {
  label: ReactNode;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        'rounded-full px-2 py-1 text-xs transition-colors',
        active ? 'bg-selected ring-1 ring-border-strong ring-inset' : 'hover:bg-hover',
      )}
    >
      {label}
    </button>
  );
}
