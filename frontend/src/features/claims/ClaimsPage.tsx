import { Plus } from 'lucide-react';
import { useEffect, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { CLAIM_STATUSES, type ClaimStatus, type ClaimView } from '@/api/types';
import { DEFAULT_PAGE_SIZE } from '@/api/policies';
import { PageHeader } from '@/components/AppShell';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { StatCards, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import { formatDate } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useClaimStore } from '@/store/claimStore';
import { ClaimDrawer } from './ClaimDrawer';

/**
 * `GET /claims` is one of only four paged endpoints on the platform, so this
 * screen gets the real pager -- same pattern as PoliciesPage. Filter and page
 * live in the URL for the same reason: a shareable filtered view and a correct
 * back button, which React Router does not give for free.
 */
export function ClaimsPage() {
  const [params, setParams] = useSearchParams();
  const [previewing, setPreviewing] = useState<string | null>(null);

  const statusParam = params.get('status');
  const status: ClaimStatus | undefined =
    statusParam && (CLAIM_STATUSES as readonly string[]).includes(statusParam)
      ? (statusParam as ClaimStatus)
      : undefined;
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  const list = useClaimStore((s) => s.list);
  const loadList = useClaimStore((s) => s.loadList);

  useEffect(() => {
    void loadList({ ...(status ? { status } : {}), page, pageSize: DEFAULT_PAGE_SIZE });
  }, [loadList, status, page]);

  function update(next: { status?: ClaimStatus | undefined; page?: number }) {
    const merged = new URLSearchParams(params);
    if ('status' in next) {
      if (next.status) merged.set('status', next.status);
      else merged.delete('status');
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

  // Counts only -- no trend arrows, matching PoliciesPage: no analytics endpoint
  // exists anywhere on this platform.
  const stats: Stat[] = [
    {
      label: status ? `${status[0]}${status.slice(1).toLowerCase().replace(/_/g, ' ')} claims` : 'All claims',
      value: total,
      pending: isInitialLoad(list),
      hint:
        list.status === 'error' && total === null
          ? 'could not load'
          : status
            ? 'matching this filter'
            : 'in this tenant',
    },
  ];

  const columns: Column<ClaimView>[] = [
    {
      key: 'claimType',
      header: 'Type',
      render: (c) => <span className="font-medium">{c.claimType}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      render: (c) => (
        <span className="flex items-center gap-1.5">
          <StatusBadge kind="claim" value={c.status} />
          {c.requiresContestabilityReview && (
            <span
              className="text-[11px] text-status-warning-fg"
              title="Falls inside the policy's contestability window"
            >
              CR
            </span>
          )}
        </span>
      ),
    },
    {
      key: 'policyNumber',
      header: 'Policy',
      secondary: true,
      render: (c) => <span className="font-mono text-xs">{c.policyNumber ?? '—'}</span>,
    },
    {
      key: 'dateOfEvent',
      header: 'Date of event',
      secondary: true,
      render: (c) => <span className="text-muted-foreground">{formatDate(c.dateOfEvent)}</span>,
    },
    {
      key: 'approvedAmount',
      header: 'Approved',
      align: 'right',
      render: (c) => (c.approvedAmount ? formatMoney(c.approvedAmount) : '—'),
    },
  ];

  function renderBody() {
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
          title={status ? `No ${status.toLowerCase().replace(/_/g, ' ')} claims` : 'No claims yet'}
          description={
            status
              ? 'Nothing in this tenant currently has that status.'
              : 'Claims appear here once one is registered.'
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
          <p className="border-b border-border bg-status-warning-bg px-4 py-2 text-xs text-status-warning-fg">
            Showing older data — could not refresh.
            {list.error.traceId && <span className="ml-1 font-mono">({list.error.traceId})</span>}
          </p>
        )}
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(c) => c.claimId ?? JSON.stringify(c)}
          onRowActivate={(c) => {
            if (c.claimId) setPreviewing(c.claimId);
          }}
          isRowSelected={(c) => c.claimId === previewing}
          caption="Claims"
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
        title="Claims"
        description="Every claim in your tenant. Select one to preview it."
        actions={
          <Button asChild size="sm" variant="primary">
            <Link to="new">
              <Plus />
              New claim
            </Link>
          </Button>
        }
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
            {CLAIM_STATUSES.map((value) => (
              <FilterChip
                key={value}
                label={<StatusBadge kind="claim" value={value} />}
                active={status === value}
                onClick={() => update({ status: value })}
              />
            ))}
          </div>

          {renderBody()}
        </div>
      </div>

      <ClaimDrawer claimId={previewing} onClose={() => setPreviewing(null)} />
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
