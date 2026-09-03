import { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { DEFAULT_PAGE_SIZE } from '@/api/party';
import { KYC_STATUSES, type KycStatus, type PartyView } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { StatCards, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { isInitialLoad } from '@/store/createResourceSlice';
import { selectPartyList, usePartyStore } from '@/store/partyStore';
import { FilterChip } from '@/components/FilterChip';
import { Input } from '@/components/ui/input';

/**
 * The client register. Everyone this tenant has registered, and the way into one
 * person's full record.
 *
 * There was genuinely no way to find a party at all before `GET /parties` existed
 * (staff portal review, 2026-08-25): a fresh self-service or agent-assisted
 * registration had nothing else referencing it -- no policy, claim, case or agent
 * -- so it was invisible to staff.
 *
 * This began as the KYC review queue and defaulted to PENDING. It is now a register
 * and shows everyone; see the default-filter comment below for why that change was
 * not cosmetic. The queue did not disappear: the sidebar badge still counts parties
 * awaiting KYC and links straight to the PENDING filter, so "what needs me today"
 * is one click away rather than being the only thing the screen can say.
 *
 * The same component serves the agents realm as "My clients": `GET /parties`
 * force-scopes an agents-realm token to parties IT registered, so the scoping is
 * the server's, not a prop this screen could get wrong.
 *
 * No drawer preview: the detail page is where a client's policies, claims,
 * beneficiary exposure, documents and KYC actually live, so a row click goes
 * straight there rather than to a summary of what the row already showed.
 */
export interface ClientsPageProps {
  /** Overridden by the agents realm, where the same list means "clients I registered". */
  title?: string;
  description?: string;
}

export function ClientsPage({
  title = 'Clients',
  description = 'Everyone this tenant has registered. Open one for their policies, claims, documents and KYC.',
}: ClientsPageProps = {}) {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();

  // A bare visit shows EVERY client, like Policies and Claims -- "no param" means
  // "no filter" here now.
  //
  // This screen was the KYC review queue and defaulted to PENDING, with `ALL` as an
  // explicit URL sentinel so clicking "All" could be told apart from a fresh visit.
  // As a client register that default was a trap: a screen called Clients that opens
  // showing only the few dozen awaiting KYC hides every verified client until the
  // reader notices a filter they had no reason to look for. The queue is not lost --
  // the sidebar badge still carries the pending count and links here.
  //
  // The `ALL` sentinel is still honoured so old bookmarks and the badge's own links
  // keep working; it simply now means the same thing as no parameter at all.
  const statusParam = params.get('kycStatus');
  const kycStatus: KycStatus | undefined =
    statusParam && statusParam !== 'ALL' && (KYC_STATUSES as readonly string[]).includes(statusParam)
      ? (statusParam as KycStatus)
      : undefined;
  // A queue of everything awaiting KYC is browsable; a register of every client the
  // tenant has ever registered is not, so searching by name is how anyone actually
  // finds a person here. `GET /parties` has supported `q` (case-insensitive substring
  // on displayName) since the list endpoint existed -- this screen simply never sent
  // it, which was survivable while it was a queue and is not now.
  const q = params.get('q') ?? '';
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  const list = usePartyStore(selectPartyList);
  const loadList = usePartyStore((s) => s.loadList);

  useEffect(() => {
    void loadList({
      ...(kycStatus ? { kycStatus } : {}),
      ...(q ? { q } : {}),
      page,
      pageSize: DEFAULT_PAGE_SIZE,
    });
  }, [loadList, kycStatus, q, page]);

  function update(next: { kycStatus?: KycStatus | undefined; q?: string; page?: number }) {
    const merged = new URLSearchParams(params);
    if ('q' in next) {
      if (next.q) merged.set('q', next.q);
      else merged.delete('q');
      // A new search starts at the first page: keeping page 3 of the previous
      // result would show an empty table for a search that actually matched.
      merged.delete('page');
    }
    if ('kycStatus' in next) {
      // No parameter IS "no filter" now, so clearing removes it rather than writing
      // the `ALL` sentinel the PENDING default used to need.
      if (next.kycStatus) merged.set('kycStatus', next.kycStatus);
      else merged.delete('kycStatus');
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
      label: kycStatus ? `${kycStatus[0]}${kycStatus.slice(1).toLowerCase()} clients` : 'All clients',
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
          onRetry={() =>
            void loadList({ ...(kycStatus ? { kycStatus } : {}), ...(q ? { q } : {}), page })
          }
        />
      );
    }

    const rows = list.data?.items ?? [];
    if (rows.length === 0 && list.status === 'success') {
      // The message must name the reason the table is empty. "No clients yet" in
      // front of a tenant with hundreds of them, because a search matched nothing,
      // would be flatly untrue -- and a search is now the most likely reason to be
      // looking at an empty table.
      return (
        <EmptyState
          title={q ? `No clients matching "${q}"` : kycStatus ? `No ${kycStatus.toLowerCase()} clients` : 'No clients yet'}
          description={
            q
              ? 'The search matches a client’s name, not a policy number or an id.'
              : kycStatus === 'PENDING'
                ? 'Nothing is currently waiting on a KYC decision.'
                : 'Nothing in this tenant currently has that status.'
          }
          {...(q || kycStatus
            ? {
                action: (
                  <Button
                    size="sm"
                    onClick={() => update({ q: '', kycStatus: undefined })}
                  >
                    Clear {q && kycStatus ? 'filters' : 'filter'}
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
          caption="Clients"
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
      <PageHeader title={title} description={description} />

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
                placeholder="Search by name"
                aria-label="Search by name"
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

