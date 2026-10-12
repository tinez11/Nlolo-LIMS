import { Plus, Search } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import type { TreatyView } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { DataTable, type Column } from '@/components/DataTable';
import { CountLine, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { formatDate } from '@/lib/dates';
import { humanizeStatus } from '@/lib/status';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useReinsuranceStore } from '@/store/reinsuranceStore';
import { TreatyDrawer } from './TreatyDrawer';
import { FilterChip } from '@/components/FilterChip';
import { matchesReinsurer, TREATY_STANDINGS, treatyCedes, treatyTypeLabel, type TreatyStanding } from './treatyTerms';

/**
 * `GET /treaties` is a bare unpaged array (like products' catalog), so this
 * gets the no-pager table variant. Unlike agents (still create-only, see
 * AppShell.tsx's STAFF_NAV comment), a treaty IS listable -- so this follows
 * Policies/Claims/Products/Underwriting's full drawer-previews-page-acts
 * shape rather than the create-only exception.
 *
 * <p>Filtering and search run on the downloaded list, never the network. The chips filter by
 * STANDING -- in force, not yet started, ended -- read from each treaty's dates by the server. They
 * used to filter on the stored ACTIVE/EXPIRED flag, which nothing ever changes: "Expired" was always
 * empty and a treaty starting next week sat under "Active". The search is the only way to find a
 * reinsurer by name; the "Go to" palette searches screens, not records.
 */
export function TreatiesPage() {
  const [params, setParams] = useSearchParams();
  const [previewing, setPreviewing] = useState<string | null>(null);

  const standingParam = params.get('standing');
  const standing: TreatyStanding | undefined =
    standingParam && (TREATY_STANDINGS as readonly string[]).includes(standingParam)
      ? (standingParam as TreatyStanding)
      : undefined;
  const query = params.get('q') ?? '';

  const list = useReinsuranceStore((s) => s.list);
  const loadList = useReinsuranceStore((s) => s.loadList);

  useEffect(() => {
    void loadList();
  }, [loadList]);

  function setParam(key: string, value: string | undefined) {
    const merged = new URLSearchParams(params);
    if (value) merged.set(key, value);
    else merged.delete(key);
    // Typing a name should not push a history entry per keystroke.
    setParams(merged, { replace: key === 'q' });
  }

  const shown = useMemo(
    () => (list.data ?? []).filter((t) => (!standing || t.standing === standing) && matchesReinsurer(t, query)),
    [list.data, standing, query],
  );
  const filtering = standing !== undefined || query.trim() !== '';

  const total = list.data ? shown.length : null;
  const count: Stat = {
    label: standing ? `${humanizeStatus(standing).toLowerCase()} treaties` : 'treaties',
    value: total,
    pending: isInitialLoad(list),
    hint: list.status === 'error' && total === null ? 'could not load' : filtering ? 'matching this filter' : 'in your tenant',
  };

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
      render: (t) => <span className="text-muted-foreground">{treatyTypeLabel(t.treatyType)}</span>,
    },
    {
      key: 'standing',
      header: 'Status',
      render: (t) => <StatusBadge kind="treatyStanding" value={t.standing} />,
    },
    {
      key: 'cedes',
      header: 'Cedes',
      align: 'right',
      render: (t) => treatyCedes(t),
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
      return <ErrorPanel error={list.error} onRetry={() => void loadList()} />;
    }

    if ((list.data ?? []).length === 0) {
      return (
        <EmptyState
          title="No treaties yet"
          description="A treaty must be authored before any policy can cede risk against it."
        />
      );
    }

    if (shown.length === 0) {
      return (
        <EmptyState
          title="No treaties match"
          description={query.trim() ? `Nothing for “${query.trim()}”${standing ? ' with this status' : ''}.` : 'None with this status.'}
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
          rows={shown}
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
        count={<CountLine {...count} />}
      />

      <div className="px-6 pb-6">
        <div className="rounded-lg border border-border bg-surface">
          <div className="flex flex-wrap items-center gap-1.5 gap-y-2 border-b border-border px-3 py-2.5">
            <FilterChip label="All" active={standing === undefined} onClick={() => setParam('standing', undefined)} />
            {TREATY_STANDINGS.map((value) => (
              <FilterChip
                key={value}
                label={<StatusBadge kind="treatyStanding" value={value} />}
                bare
                active={standing === value}
                onClick={() => setParam('standing', value)}
              />
            ))}

            <div className="relative ml-auto">
              <Search
                className="pointer-events-none absolute top-1/2 left-2 h-3.5 w-3.5 -translate-y-1/2 text-subtle-foreground"
                aria-hidden="true"
              />
              <Input
                inputSize="sm"
                className="w-56 pl-7"
                aria-label="Search reinsurers"
                data-list-filter
                placeholder="Search reinsurer"
                value={query}
                onChange={(e) => setParam('q', e.target.value || undefined)}
              />
            </div>
          </div>

          {renderBody()}
        </div>
      </div>

      <TreatyDrawer treatyId={previewing} onClose={() => setPreviewing(null)} />
    </>
  );
}
