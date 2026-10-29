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
import { formatMoney } from '@/lib/money';
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

  /*
   * Read as a ledger (2026-10-27): what the entry records in words, which account it debited and which it credited,
   * and how much. The list used to show only the source event -- "billing.PremiumInvoiceGenerated" -- and a finance
   * officer could not tell from 15,000 rows what money had moved where. The event is still on the entry's own page.
   */
  const columns: Column<JournalEntryView>[] = [
    {
      key: 'postedAt',
      header: 'Posted',
      className: 'w-40',
      secondary: true,
      render: (e) => (
        <span className="flex flex-col gap-0.5 whitespace-nowrap text-muted-foreground">
          <span>{formatInstant(e.postedAt)}</span>
          <span className="text-xs">period {e.period}</span>
        </span>
      ),
    },
    {
      key: 'description',
      header: 'What',
      className: 'w-56',
      render: (e) => (
        <span className="flex flex-col gap-0.5">
          <span className="font-medium">{e.description}</span>
          {e.policyNumber && <span className="whitespace-nowrap text-xs text-muted-foreground">{e.policyNumber}</span>}
        </span>
      ),
    },
    { key: 'debit', header: 'Debit', render: (e) => <LegAccounts entry={e} side="DR" /> },
    { key: 'credit', header: 'Credit', render: (e) => <LegAccounts entry={e} side="CR" /> },
    {
      key: 'amount',
      header: 'Amount',
      align: 'right',
      className: 'w-40',
      render: (e) => <span className="whitespace-nowrap font-medium">{formatMoney(e.amount)}</span>,
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
        description="Every journal entry: what it records, the account it debited and the one it credited, and how much. Select one to see every line."
        count={<CountLine {...count} />}
      />

      <div className="px-6 pb-6">
        <div className="rounded-xl border border-border bg-surface shadow-card">
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

/**
 * The accounts one side of an entry touched: the first by code and name, and how many more. A premium with a levy
 * debits two accounts; the entry's own page lists every line.
 */
function LegAccounts({ entry, side }: { entry: JournalEntryView; side: 'DR' | 'CR' }) {
  const legs = (entry.postings ?? []).filter((p) => p.direction === side);
  const codes = [...new Set(legs.map((p) => p.accountCode))];
  const first = legs.find((p) => p.accountCode === codes[0]);
  if (!first) return <span className="text-subtle-foreground">—</span>;
  return (
    <span className="flex flex-col gap-0.5">
      <span className="line-clamp-2" title={`${first.accountCode} ${first.accountName ?? ''}`}>
        <span className="font-mono text-xs text-muted-foreground">{first.accountCode}</span> {first.accountName ?? ''}
      </span>
      {codes.length > 1 && <span className="text-xs text-muted-foreground">and {codes.length - 1} more</span>}
    </span>
  );
}
