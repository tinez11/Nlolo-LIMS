import { Plus } from 'lucide-react';
import { useEffect } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { LICENSE_STATUSES, type AgentView, type LicenseStatus } from '@/api/types';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { PageHeader } from '@/components/PageHeader';
import { PartyName } from '@/components/PartyName';
import { StatCards, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatDate } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useDistributionStore } from '@/store/distributionStore';
import { FilterChip } from '@/components/FilterChip';
import { Input } from '@/components/ui/input';

const DEFAULT_PAGE_SIZE = 20;

/**
 * `GET /agents` — the list this domain never had.
 *
 * Until M13, distribution served every per-agent read and no list, so the
 * console's "Agents" nav item pointed at the ONBOARDING FORM: the only entry
 * point onto the domain that existed server-side. PLAN.md §7 recorded that as a
 * deliberate exception. This retires it — the nav item is a real list now.
 *
 * An agent has no name in the distribution context -- the person's name lives in
 * `party`, reached through `partyId`. That used to mean the Party column showed
 * a raw id, on the grounds that resolving it would cost a request per row.
 * `PartyName` now caches per id across every instance, so the column shows the
 * person and the page costs one request per distinct party.
 *
 * Search still matches the LICENCE NUMBER, not the name: it is the agent's
 * human-facing identifier in this context, and `GET /agents` has no name filter
 * to offer.
 */
export function AgentsPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();

  const statusParam = params.get('status');
  const status: LicenseStatus | undefined =
    statusParam && (LICENSE_STATUSES as readonly string[]).includes(statusParam)
      ? (statusParam as LicenseStatus)
      : undefined;
  const q = params.get('q') ?? '';
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  const list = useDistributionStore((s) => s.list);
  const loadList = useDistributionStore((s) => s.loadList);

  useEffect(() => {
    void loadList({
      ...(q ? { q } : {}),
      ...(status ? { status } : {}),
      page,
      pageSize: DEFAULT_PAGE_SIZE,
    });
  }, [loadList, q, status, page]);

  function update(next: { status?: LicenseStatus | undefined; q?: string; page?: number }) {
    const merged = new URLSearchParams(params);
    if ('status' in next) {
      if (next.status) merged.set('status', next.status);
      else merged.delete('status');
      merged.delete('page');
    }
    if (next.q !== undefined) {
      if (next.q) merged.set('q', next.q);
      else merged.delete('q');
      merged.delete('page');
    }
    if (next.page !== undefined) {
      if (next.page === 0) merged.delete('page');
      else merged.set('page', String(next.page));
    }
    setParams(merged);
  }

  const total = list.data?.page.totalElements ?? null;

  const stats: Stat[] = [
    {
      label: status ? `${status[0]}${status.slice(1).toLowerCase()} agents` : 'All agents',
      value: total,
      pending: isInitialLoad(list),
      hint:
        list.status === 'error' && total === null
          ? 'could not load'
          : status || q
            ? 'matching this filter'
            : 'in this tenant',
    },
  ];

  const columns: Column<AgentView>[] = [
    {
      key: 'licenseNumber',
      header: 'Licence number',
      render: (a) => <span className="font-mono text-xs font-medium">{a.licenseNumber ?? '—'}</span>,
    },
    {
      key: 'licenseStatus',
      header: 'Licence',
      render: (a) => (a.licenseStatus ? <StatusBadge kind="agentLicense" value={a.licenseStatus} /> : '—'),
    },
    {
      key: 'licenseExpiryDate',
      header: 'Expires',
      render: (a) => <span className="tabular-nums">{formatDate(a.licenseExpiryDate)}</span>,
    },
    {
      key: 'partyId',
      header: 'Party',
      secondary: true,
      // The person's name lives in `party`, not in the distribution context. The
      // objection to resolving it here used to be a second request per row;
      // `PartyName` caches per id across every instance, so that cost is now one
      // request per distinct person for the whole page.
      render: (a) => (a.partyId ? <PartyName partyId={a.partyId} /> : '—'),
    },
    {
      key: 'hierarchyParentId',
      header: 'Reports to',
      secondary: true,
      render: (a) =>
        a.hierarchyParentId ? (
          <span className="font-mono text-xs text-muted-foreground">{a.hierarchyParentId}</span>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
  ];

  function retry() {
    void loadList({
      ...(q ? { q } : {}),
      ...(status ? { status } : {}),
      page,
      pageSize: DEFAULT_PAGE_SIZE,
    });
  }

  function renderBody() {
    if (isInitialLoad(list)) return <TableSkeleton columns={columns.length} />;

    if (list.status === 'error' && list.error && list.data === null) {
      return <ErrorPanel error={list.error} onRetry={retry} />;
    }

    const rows = list.data?.items ?? [];
    if (rows.length === 0 && list.status === 'success') {
      return (
        <EmptyState
          title={q || status ? 'No agents match' : 'No agents yet'}
          description={
            q || status
              ? 'Nothing in this tenant matches that licence number or status.'
              : 'Agents appear here once onboarded.'
          }
          {...(q || status
            ? {
                action: (
                  <Button size="sm" onClick={() => update({ q: '', status: undefined })}>
                    Clear filters
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
          rowKey={(a) => a.agentId ?? JSON.stringify(a)}
          onRowActivate={(a) => {
            if (a.agentId) navigate(a.agentId);
          }}
          caption="Agents"
        />
        {list.data && (
          <Pager
            page={list.data.page}
            busy={list.status === 'loading'}
            onPageChange={(next) => update({ page: next })}
          />
        )}
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Agents"
        description="Every agent in your tenant. Select one to open it."
        actions={
          <Button asChild size="sm" variant="primary">
            <Link to="new">
              <Plus />
              Onboard agent
            </Link>
          </Button>
        }
      />

      <StatCards stats={stats} />

      <div className="px-6 pb-6">
        <div className="rounded-lg border border-border bg-surface">
          <div className="flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-2.5">
            <FilterChip label="All" active={status === undefined} onClick={() => update({ status: undefined })} />
            {LICENSE_STATUSES.map((value) => (
              <FilterChip
                key={value}
                label={<StatusBadge kind="agentLicense" value={value} />}
                active={status === value}
                onClick={() => update({ status: value })}
              />
            ))}
            <form
              className="ml-auto"
              onSubmit={(e) => {
                e.preventDefault();
                const value = new FormData(e.currentTarget).get('q');
                update({ q: typeof value === 'string' ? value.trim() : '' });
              }}
            >
              <Input
                name="q"
                defaultValue={q}
                placeholder="Search by licence number"
                aria-label="Search by licence number"
                inputSize="sm" className="w-56 px-2.5 text-sm"
              />
            </form>
          </div>

          {renderBody()}
        </div>
      </div>
    </>
  );
}

