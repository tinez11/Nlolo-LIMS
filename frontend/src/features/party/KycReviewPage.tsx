import { useEffect, type ReactNode } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { DEFAULT_PAGE_SIZE } from '@/api/party';
import { KYC_STATUSES, type KycStatus, type PartyView } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { StatCards, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectPartyList, usePartyStore } from '@/store/partyStore';

/**
 * The staff KYC review queue -- there was genuinely no way to find a party at
 * all before `GET /parties` existed (staff portal review, 2026-08-25): a
 * fresh self-service or agent-assisted registration had nothing else
 * referencing it (no policy/claim/case/agent yet), so it was invisible.
 *
 * Defaults to the PENDING filter, not "All" -- unlike Policies/Claims, this
 * screen's whole reason to exist is "what needs my attention right now", and
 * a firehose of every VERIFIED/REJECTED party ever seen would bury that. No
 * drawer preview: `PartyDetailPage` (upload evidence + verify/reject) IS the
 * only thing to do with a row, so a row click goes straight there.
 */
export function KycReviewPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();

  // Unlike Policies/Claims (whose "no param" state IS "no filter"), a bare
  // visit here defaults to PENDING -- so "no param" can't also mean "the user
  // explicitly cleared the filter", or clicking "All" would be indistinguishable
  // from a fresh visit and immediately snap back to PENDING. `ALL` is an
  // explicit sentinel in the URL for that case.
  const statusParam = params.get('kycStatus');
  const kycStatus: KycStatus | undefined =
    statusParam === 'ALL'
      ? undefined
      : statusParam && (KYC_STATUSES as readonly string[]).includes(statusParam)
        ? (statusParam as KycStatus)
        : 'PENDING';
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  const list = usePartyStore(selectPartyList);
  const loadList = usePartyStore((s) => s.loadList);

  useEffect(() => {
    void loadList({ ...(kycStatus ? { kycStatus } : {}), page, pageSize: DEFAULT_PAGE_SIZE });
  }, [loadList, kycStatus, page]);

  function update(next: { kycStatus?: KycStatus | undefined; page?: number }) {
    const merged = new URLSearchParams(params);
    if ('kycStatus' in next) {
      merged.set('kycStatus', next.kycStatus ?? 'ALL');
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

  const stats: Stat[] = [
    {
      label: kycStatus ? `${kycStatus[0]}${kycStatus.slice(1).toLowerCase()} parties` : 'All parties',
      value: total,
      pending: isInitialLoad(list),
      hint:
        list.status === 'error' && total === null
          ? 'could not load'
          : kycStatus
            ? 'matching this filter'
            : 'in this tenant',
    },
  ];

  const columns: Column<PartyView>[] = [
    {
      key: 'displayName',
      header: 'Name',
      render: (p) => <span className="font-medium">{p.displayName ?? '—'}</span>,
    },
    {
      key: 'partyType',
      header: 'Type',
      secondary: true,
      render: (p) => <span className="text-muted-foreground">{p.partyType ?? '—'}</span>,
    },
    {
      key: 'kycStatus',
      header: 'KYC status',
      render: (p) => (p.kycStatus ? <StatusBadge kind="kyc" value={p.kycStatus} /> : '—'),
    },
    {
      key: 'partyId',
      header: 'Party id',
      secondary: true,
      render: (p) => <span className="font-mono text-xs">{p.partyId ?? '—'}</span>,
    },
  ];

  function renderBody() {
    if (isInitialLoad(list)) return <TableSkeleton columns={columns.length} />;

    if (list.status === 'error' && list.error && list.data === null) {
      return (
        <ErrorPanel
          error={list.error}
          onRetry={() => void loadList({ ...(kycStatus ? { kycStatus } : {}), page })}
        />
      );
    }

    const rows = list.data?.items ?? [];
    if (rows.length === 0 && list.status === 'success') {
      return (
        <EmptyState
          title={kycStatus ? `No ${kycStatus.toLowerCase()} parties` : 'No parties yet'}
          description={
            kycStatus === 'PENDING'
              ? 'Nothing is currently waiting on a KYC decision.'
              : 'Nothing in this tenant currently has that status.'
          }
          {...(kycStatus
            ? {
                action: (
                  <Button size="sm" onClick={() => update({ kycStatus: undefined })}>
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
          rowKey={(p) => p.partyId ?? JSON.stringify(p)}
          onRowActivate={(p) => {
            if (p.partyId) navigate(`../parties/${p.partyId}`, { relative: 'path' });
          }}
          caption="Parties"
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
        title="KYC review"
        description="Every party in your tenant, filterable by KYC status. Select one to verify or reject it."
      />

      <StatCards stats={stats} />

      <div className="px-6 pb-6">
        <div className="rounded-lg border border-border bg-surface">
          <div className="flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-2.5">
            <FilterChip
              label="All"
              active={kycStatus === undefined}
              onClick={() => update({ kycStatus: undefined })}
            />
            {KYC_STATUSES.map((value) => (
              <FilterChip
                key={value}
                label={<StatusBadge kind="kyc" value={value} />}
                active={kycStatus === value}
                onClick={() => update({ kycStatus: value })}
              />
            ))}
          </div>

          {renderBody()}
        </div>
      </div>
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
