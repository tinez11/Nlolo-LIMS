import { X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { JournalEntryView } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { CountLine, type Stat } from '@/components/StatCards';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { FormField } from '@/components/FormField';
import { Button } from '@/components/ui/button';
import { formatInstant } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useFinaccountingStore } from '@/store/finaccountingStore';
import { GlPostingDrawer } from './GlPostingDrawer';
import { Input } from '@/components/ui/input';

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
  // Arrives from the chart of accounts: "show me what made up this balance". Deliberately NOT
  // an input on this form -- an account code is chosen from the chart, where its name is, not
  // typed as four digits into a box. It is shown as a removable chip instead.
  const accountCode = params.get('accountCode') ?? '';
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  const [periodInput, setPeriodInput] = useState(period);
  const [policyNumberInput, setPolicyNumberInput] = useState(policyNumber);

  const list = useFinaccountingStore((s) => s.list);
  const loadList = useFinaccountingStore((s) => s.loadList);

  useEffect(() => {
    void loadList({
      ...(period ? { period } : {}),
      ...(policyNumber ? { policyNumber } : {}),
      ...(accountCode ? { accountCode } : {}),
      page,
    });
  }, [loadList, period, policyNumber, accountCode, page]);

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
  const filtered = Boolean(period || policyNumber || accountCode);

  function clearAccountFilter() {
    const merged = new URLSearchParams(params);
    merged.delete('accountCode');
    merged.delete('page');
    setParams(merged);
  }

  const count: Stat = {
    label: filtered ? 'matching journal entries' : 'journal entries',
    value: total,
    pending: isInitialLoad(list),
    hint:
      list.status === 'error' && total === null
        ? 'could not load'
        : filtered
          ? 'matching this filter'
          : 'in this tenant',
  };

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
              // Retrying must reissue the SAME query. Dropping the account filter here would
              // silently widen it and show the whole ledger under an "Account 1210" chip.
              ...(accountCode ? { accountCode } : {}),
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
        count={<CountLine {...count} />}
      />

      <div className="px-6 pb-6">
        <div className="rounded-lg border border-border bg-surface">
          <form
            className="flex flex-wrap items-end gap-2 border-b border-border px-3 py-2.5"
            onSubmit={applyFilters}
          >
            <FormField label="Period">
              <Input
                inputSize="sm"
                className="w-28"
                placeholder="YYYY-MM"
                value={periodInput}
                onChange={(e) => setPeriodInput(e.target.value)}
              />
            </FormField>
            <FormField label="Policy number">
              <Input
                inputSize="sm"
                className="w-40 font-mono"
                placeholder="POL-XXXXXXXX"
                value={policyNumberInput}
                onChange={(e) => setPolicyNumberInput(e.target.value)}
              />
            </FormField>
            <Button type="submit" size="sm">
              Apply
            </Button>
            {filtered && (
              <Button type="button" size="sm" variant="ghost" onClick={clearFilters}>
                Clear
              </Button>
            )}
            {/*
              A chip, not a text input. An account code is picked from the chart of accounts --
              where it has a name beside it -- and arrives here on the URL. Offering a box for
              four digits would invite somebody to type one nobody can read back.
            */}
            {accountCode && (
              <span className="ml-auto inline-flex items-center gap-1.5 rounded-md border border-border bg-surface-muted px-2 py-1 text-xs">
                <span className="text-muted-foreground">Account</span>
                <span className="font-mono font-medium">{accountCode}</span>
                <button
                  type="button"
                  aria-label="Clear account filter"
                  className="rounded p-0.5 text-muted-foreground hover:bg-hover hover:text-foreground"
                  onClick={clearAccountFilter}
                >
                  <X className="size-3.5" />
                </button>
              </span>
            )}
          </form>

          {renderBody()}
        </div>
      </div>

      <GlPostingDrawer journalEntryId={previewing} onClose={() => setPreviewing(null)} />
    </>
  );
}
