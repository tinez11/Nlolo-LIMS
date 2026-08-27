import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { JournalEntryView } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { StatCards, type Stat } from '@/components/StatCards';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatInstant } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useFinaccountingStore } from '@/store/finaccountingStore';
import { GlPostingDrawer } from './GlPostingDrawer';

/**
 * `GET /gl-postings` is one of only four paged endpoints on the platform
 * (added by M9's own final review after it used to answer with a bare
 * unbounded array). Filters are free-text (`period` as YYYY-MM,
 * `policyNumber`), not an enum, so this uses a small apply-on-submit form
 * rather than the chip pattern Policies/Claims/Products/Treaties use for
 * their fixed status sets.
 *
 * Read-only by platform design -- there is no "New posting" action anywhere
 * on this page, because there is no such endpoint: every entry is derived
 * from a domain event, never hand-entered.
 */
export function GlPostingsPage() {
  const [params, setParams] = useSearchParams();
  const [previewing, setPreviewing] = useState<string | null>(null);

  const period = params.get('period') ?? '';
  const policyNumber = params.get('policyNumber') ?? '';
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  const [periodInput, setPeriodInput] = useState(period);
  const [policyNumberInput, setPolicyNumberInput] = useState(policyNumber);

  const list = useFinaccountingStore((s) => s.list);
  const loadList = useFinaccountingStore((s) => s.loadList);

  useEffect(() => {
    void loadList({
      ...(period ? { period } : {}),
      ...(policyNumber ? { policyNumber } : {}),
      page,
    });
  }, [loadList, period, policyNumber, page]);

  function applyFilters(e: React.FormEvent) {
    e.preventDefault();
    const merged = new URLSearchParams(params);
    if (periodInput) merged.set('period', periodInput);
    else merged.delete('period');
    if (policyNumberInput) merged.set('policyNumber', policyNumberInput);
    else merged.delete('policyNumber');
    merged.delete('page');
    setParams(merged);
  }

  function clearFilters() {
    setPeriodInput('');
    setPolicyNumberInput('');
    setParams(new URLSearchParams());
  }

  function updatePage(next: number) {
    const merged = new URLSearchParams(params);
    if (next === 0) merged.delete('page');
    else merged.set('page', String(next));
    setParams(merged);
  }

  const total = list.data?.page.totalElements ?? null;
  const busy = list.status === 'loading';
  const filtered = Boolean(period || policyNumber);

  const stats: Stat[] = [
    {
      label: filtered ? 'Matching journal entries' : 'All journal entries',
      value: total,
      pending: isInitialLoad(list),
      hint:
        list.status === 'error' && total === null
          ? 'could not load'
          : filtered
            ? 'matching this filter'
            : 'in this tenant',
    },
  ];

  const columns: Column<JournalEntryView>[] = [
    {
      key: 'sourceEvent',
      header: 'Source event',
      render: (e) => <span className="font-mono text-xs">{e.sourceEvent}</span>,
    },
    {
      key: 'period',
      header: 'Period',
      secondary: true,
      render: (e) => <span className="text-muted-foreground">{e.period}</span>,
    },
    {
      key: 'policyNumber',
      header: 'Policy',
      secondary: true,
      render: (e) => <span className="font-mono text-xs">{e.policyNumber ?? '—'}</span>,
    },
    {
      key: 'postedAt',
      header: 'Posted',
      align: 'right',
      render: (e) => <span className="text-muted-foreground">{formatInstant(e.postedAt)}</span>,
    },
  ];

  function renderBody() {
    if (isInitialLoad(list)) return <TableSkeleton columns={columns.length} />;

    if (list.status === 'error' && list.error && list.data === null) {
      return (
        <ErrorPanel
          error={list.error}
          onRetry={() =>
            void loadList({
              ...(period ? { period } : {}),
              ...(policyNumber ? { policyNumber } : {}),
              page,
            })
          }
        />
      );
    }

    const rows = list.data?.items ?? [];
    if (rows.length === 0 && list.status === 'success') {
      return (
        <EmptyState
          title={filtered ? 'No matching journal entries' : 'No journal entries yet'}
          description={
            filtered
              ? 'Nothing in this tenant currently matches that filter.'
              : 'Entries appear here as money-movement events are posted.'
          }
          {...(filtered ? { action: <Button size="sm" onClick={clearFilters}>Clear filter</Button> } : {})}
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
          rowKey={(e) => e.journalEntryId ?? JSON.stringify(e)}
          onRowActivate={(e) => {
            if (e.journalEntryId) setPreviewing(e.journalEntryId);
          }}
          isRowSelected={(e) => e.journalEntryId === previewing}
          caption="GL postings"
        />
        {list.data && <Pager page={list.data.page} busy={busy} onPageChange={updatePage} />}
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="GL postings"
        description="Every journal entry is derived from a domain event. Select one to preview it."
      />

      <StatCards stats={stats} />

      <div className="px-6 pb-6">
        <div className="rounded-lg border border-border bg-surface">
          <form
            className="flex flex-wrap items-end gap-2 border-b border-border px-3 py-2.5"
            onSubmit={applyFilters}
          >
            <label className="block">
              <span className="mb-1 block text-[11px] font-medium text-muted-foreground">Period</span>
              <input
                className="h-8 w-28 rounded-md border border-input bg-surface px-2 text-xs"
                placeholder="YYYY-MM"
                value={periodInput}
                onChange={(e) => setPeriodInput(e.target.value)}
              />
            </label>
            <label className="block">
              <span className="mb-1 block text-[11px] font-medium text-muted-foreground">Policy number</span>
              <input
                className="h-8 w-40 rounded-md border border-input bg-surface px-2 font-mono text-xs"
                placeholder="POL-XXXXXXXX"
                value={policyNumberInput}
                onChange={(e) => setPolicyNumberInput(e.target.value)}
              />
            </label>
            <Button type="submit" size="sm">
              Apply
            </Button>
            {filtered && (
              <Button type="button" size="sm" variant="ghost" onClick={clearFilters}>
                Clear
              </Button>
            )}
          </form>

          {renderBody()}
        </div>
      </div>

      <GlPostingDrawer journalEntryId={previewing} onClose={() => setPreviewing(null)} />
    </>
  );
}
