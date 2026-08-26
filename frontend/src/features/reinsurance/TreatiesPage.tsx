import { Plus } from 'lucide-react';
import { useEffect, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { TREATY_STATUSES, type TreatyStatus, type TreatyView } from '@/api/types';
import { PageHeader } from '@/components/AppShell';
import { DataTable, type Column } from '@/components/DataTable';
import { StatCards, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad, isEmpty } from '@/store/createResourceSlice';
import { useReinsuranceStore } from '@/store/reinsuranceStore';
import { TreatyDrawer } from './TreatyDrawer';

/**
 * `GET /treaties` is a bare unpaged array (like products' catalog), so this
 * gets the no-pager table variant. Unlike agents (still create-only, see
 * AppShell.tsx's STAFF_NAV comment), a treaty IS listable -- so this follows
 * Policies/Claims/Products/Underwriting's full drawer-previews-page-acts
 * shape rather than the create-only exception.
 */
export function TreatiesPage() {
  const [params, setParams] = useSearchParams();
  const [previewing, setPreviewing] = useState<string | null>(null);

  const statusParam = params.get('status');
  const status: TreatyStatus | undefined =
    statusParam && (TREATY_STATUSES as readonly string[]).includes(statusParam)
      ? (statusParam as TreatyStatus)
      : undefined;

  const list = useReinsuranceStore((s) => s.list);
  const loadList = useReinsuranceStore((s) => s.loadList);

  useEffect(() => {
    void loadList(status);
  }, [loadList, status]);

  function update(next: TreatyStatus | undefined) {
    const merged = new URLSearchParams(params);
    if (next) merged.set('status', next);
    else merged.delete('status');
    setParams(merged);
  }

  const total = list.data?.length ?? null;
  const stats: Stat[] = [
    {
      label: status ? `${status.toLowerCase()} treaties` : 'All treaties',
      value: total,
      pending: isInitialLoad(list),
      hint: list.status === 'error' && total === null ? 'could not load' : 'in your tenant',
    },
  ];

  const columns: Column<TreatyView>[] = [
    {
      key: 'reinsurerName',
      header: 'Reinsurer',
      render: (t) => <span className="font-medium">{t.reinsurerName}</span>,
    },
    {
      key: 'treatyType',
      header: 'Type',
      secondary: true,
      render: (t) => <span className="text-muted-foreground">{t.treatyType.replace(/_/g, ' ')}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      render: (t) => <StatusBadge kind="treaty" value={t.status} />,
    },
    {
      key: 'retentionLimit',
      header: 'Retention limit',
      align: 'right',
      render: (t) => formatMoney(t.retentionLimit),
    },
    {
      key: 'effectiveFrom',
      header: 'Effective from',
      secondary: true,
      render: (t) => <span className="text-muted-foreground">{formatDate(t.effectiveFrom)}</span>,
    },
  ];

  function renderBody() {
    if (isInitialLoad(list)) return <TableSkeleton columns={columns.length} />;

    if (list.status === 'error' && list.error && list.data === null) {
      return <ErrorPanel error={list.error} onRetry={() => void loadList(status)} />;
    }

    if (isEmpty(list)) {
      return (
        <EmptyState
          title={status ? `No ${status.toLowerCase()} treaties` : 'No treaties yet'}
          description="A treaty must be authored before any policy can cede risk against it."
        />
      );
    }

    return (
      <>
        {list.status === 'error' && list.error && (
          <p className="border-b border-border bg-status-warning-bg px-4 py-2 text-xs text-status-warning-fg">
            Showing older data — could not refresh.
            {list.error.traceId && <span className="ml-1 font-mono">({list.error.traceId})</span>}
          </p>
        )}
        <DataTable
          columns={columns}
          rows={list.data ?? []}
          rowKey={(t) => t.treatyId ?? JSON.stringify(t)}
          onRowActivate={(t) => {
            if (t.treatyId) setPreviewing(t.treatyId);
          }}
          isRowSelected={(t) => t.treatyId === previewing}
          caption="Treaties"
        />
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Treaties"
        description="Reinsurance treaties in your tenant. Select one to preview it."
        actions={
          <Button asChild size="sm" variant="primary">
            <Link to="new">
              <Plus />
              New treaty
            </Link>
          </Button>
        }
      />

      <StatCards stats={stats} />

      <div className="px-6 pb-6">
        <div className="rounded-lg border border-border bg-surface">
          <div className="flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-2.5">
            <FilterChip label="All" active={status === undefined} onClick={() => update(undefined)} />
            {TREATY_STATUSES.map((value) => (
              <FilterChip
                key={value}
                label={<StatusBadge kind="treaty" value={value} />}
                active={status === value}
                onClick={() => update(value)}
              />
            ))}
          </div>

          {renderBody()}
        </div>
      </div>

      <TreatyDrawer treatyId={previewing} onClose={() => setPreviewing(null)} />
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
