import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Download, Phone, Search } from 'lucide-react';
import { can } from '@/auth/access';
import {
  exportFollowUp,
  LATE_BANDS,
  SOON_DAYS,
  type FollowUpQuery,
  type FollowUpRow,
  type FollowUpView,
} from '@/api/followUp';
import type { ExportFormat } from '@/api/reporting';
import { AgentPicker } from '@/components/AgentPicker';
import { BranchSelect } from '@/components/BranchSelect';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { DatePicker } from '@/components/DatePicker';
import { FilterChip } from '@/components/FilterChip';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { KpiRow, type Kpi } from '@/components/KpiCard';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { ProductFilter } from '@/components/ProductFilter';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input, Select, Textarea } from '@/components/ui/input';
import { cn } from '@/lib/cn';
import { formatDate, todayIso } from '@/lib/dates';
import { saveBlob } from '@/lib/download';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useFollowUpStore } from '@/store/followUpStore';
import { useProductStore } from '@/store/productStore';
import {
  actionInWords,
  blankCall,
  CALL_OUTCOMES,
  callProblem,
  NOTE_MAX,
  toBody,
  type CallFormValues,
} from './recordCallForm';

const PAGE_SIZE = 50;

const VIEWS: { value: FollowUpView; label: string }[] = [
  { value: 'DUE_SOON', label: 'Due soon' },
  { value: 'DUE_TODAY', label: 'Due today' },
  { value: 'OVERDUE', label: 'Overdue' },
];

/** The server's own figure, written as money. Formatting only -- nothing here is added up. */
function money(amount: number | null | undefined, currency: string): string {
  return amount == null ? '—' : formatMoney({ amount: amount.toFixed(2), currencyCode: currency });
}

/** The first day of this month and today, as the server reads dates. */
function thisMonth(): { from: string; to: string } {
  const today = todayIso();
  return { from: `${today.slice(0, 8)}01`, to: today };
}

/**
 * Premiums to follow up (2026-11-11, analytics plan 1): who must pay, how much, how late, and what has already been
 * done -- the collections desk's working list. It opens on the overdue, worst first, because that is the work.
 *
 * Staff with collections.view read it; only collections.manage acts on a row (ask to pay, send a reminder, record a
 * call). A promise to pay is a note only: nothing holds back reminders or letters on it (the user, 2026-11-11).
 */
export function FollowUpPage() {
  const [params, setParams] = useSearchParams();
  const view: FollowUpView = VIEWS.some((v) => v.value === params.get('view'))
    ? (params.get('view') as FollowUpView)
    : 'OVERDUE';
  const soonParam = Number(params.get('soonDays'));
  const soonDays = (SOON_DAYS as readonly number[]).includes(soonParam) ? soonParam : 7;
  const lateBand = view === 'OVERDUE' && (LATE_BANDS as readonly string[]).includes(params.get('late') ?? '')
    ? (params.get('late') as string)
    : '';
  const agentId = params.get('agentId') ?? '';
  const branchCode = params.get('branch') ?? '';
  const productId = params.get('productId') ?? '';
  const category = params.get('category') ?? '';
  const q = params.get('q') ?? '';
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  const list = useFollowUpStore((s) => s.list);
  const figures = useFollowUpStore((s) => s.figures);
  const acting = useFollowUpStore((s) => s.acting);
  const load = useFollowUpStore((s) => s.load);
  const loadFigures = useFollowUpStore((s) => s.loadFigures);
  const sendReminder = useFollowUpStore((s) => s.sendReminder);
  const recordCall = useFollowUpStore((s) => s.recordCall);
  const clearActing = useFollowUpStore((s) => s.clearActing);
  const products = useProductStore((s) => s.list);
  const loadProducts = useProductStore((s) => s.loadList);
  const manage = can('collections.manage');

  const [search, setSearch] = useState(q);
  const [calling, setCalling] = useState<FollowUpRow | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState<ExportFormat | null>(null);

  const query: FollowUpQuery = {
    view,
    soonDays,
    ...(lateBand ? { lateBand } : {}),
    ...(agentId ? { agentId } : {}),
    ...(branchCode ? { branchCode } : {}),
    ...(productId ? { productId } : {}),
    ...(q ? { search: q } : {}),
    page,
    pageSize: PAGE_SIZE,
  };
  const queryKey = JSON.stringify(query);

  useEffect(() => {
    void load(JSON.parse(queryKey) as FollowUpQuery, false);
  }, [load, queryKey]);

  useEffect(() => {
    const { from, to } = thisMonth();
    void loadFigures(from, to);
    void loadProducts();
  }, [loadFigures, loadProducts]);

  // The search box sends after a pause, not on every key.
  useEffect(() => {
    if (search === q) return;
    const timer = window.setTimeout(() => update({ q: search }), 350);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search]);

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params);
    for (const [key, value] of Object.entries(next)) {
      if (value === '') merged.delete(key);
      else merged.set(key, value);
    }
    if (!('page' in next)) merged.delete('page');
    setParams(merged);
  }

  async function download(format: ExportFormat) {
    setDownloadError(null);
    setDownloading(format);
    try {
      const blob = await exportFollowUp(query, format);
      saveBlob(blob, `premiums-to-follow-up-${todayIso()}.${format}`);
    } catch {
      setDownloadError('The file could not be made. Try again in a moment.');
    } finally {
      setDownloading(null);
    }
  }

  const data = list.data;
  const month = figures.data;
  const kpis: Kpi[] = [
    {
      label: 'Due today',
      value: data ? data.dueToday.toLocaleString() : null,
      hint: month ? `${money(month.dueTodayOwed, month.currency)} to collect` : 'premiums falling due today',
    },
    {
      label: `Due in the next ${soonDays} days`,
      value: data ? data.dueSoon.toLocaleString() : null,
      hint: 'premiums not yet due',
    },
    {
      label: 'Overdue',
      value: data ? data.overdue.toLocaleString() : null,
      hint: data ? `${money(data.overdueOwed, 'TZS')} owed` : '',
    },
    {
      label: 'Paid this month',
      value: month ? money(month.paid, month.currency) : null,
      hint: month ? `of ${money(month.due, month.currency)} due this month` : '',
    },
    {
      label: 'Collection rate',
      value: month?.ratePercent ? `${month.ratePercent}%` : null,
      hint: "Of this month's premiums due, how much is paid",
    },
  ];

  const columns: Column<FollowUpRow>[] = [
    {
      key: 'client',
      header: 'Client',
      render: (r) => (
        <div className="flex flex-col py-1.5">
          <span className="font-medium">{r.clientName ?? '—'}</span>
          {r.clientPhone ? (
            <a
              href={`tel:${r.clientPhone}`}
              className="inline-flex w-fit items-center gap-1 text-xs text-muted-foreground underline-offset-2 hover:text-foreground hover:underline"
            >
              <Phone className="size-3" aria-hidden />
              {r.clientPhone}
            </a>
          ) : (
            <span className="text-xs text-subtle-foreground">No phone recorded</span>
          )}
        </div>
      ),
    },
    {
      key: 'policy',
      header: 'Policy',
      render: (r) => (
        <div className="flex flex-col py-1.5">
          <Link
            to={`../policies/${encodeURIComponent(r.policyNumber)}`}
            className="w-fit whitespace-nowrap font-mono font-medium underline-offset-2 hover:underline"
          >
            {r.policyNumber}
          </Link>
          <span className="text-xs text-muted-foreground">{r.productName ?? '—'}</span>
        </div>
      ),
    },
    {
      key: 'agent',
      header: 'Agent',
      secondary: true,
      render: (r) => (r.agentName ? r.agentName.replace(/ \([^)]*\)$/, '') : <span className="text-subtle-foreground">Direct</span>),
    },
    {
      key: 'owed',
      header: 'Owed',
      align: 'right',
      render: (r) => <span className="font-medium">{money(r.owed, r.currency)}</span>,
    },
    {
      key: 'late',
      header: <span className="whitespace-nowrap">How late</span>,
      render: (r) => (
        <div className="flex flex-col items-start gap-1 py-1.5">
          <Lateness row={r} />
          {r.lapseExpectedOn && (
            <span className="whitespace-nowrap text-xs text-status-danger-fg">Will lapse on {formatDate(r.lapseExpectedOn)}</span>
          )}
          {r.agentCashHeld != null && (
            <span className="text-xs text-muted-foreground">
              Agent cash with {r.agentCashWith?.replace(/ \([^)]*\)$/, '') ?? 'an agent'} ({money(r.agentCashHeld, r.currency)})
            </span>
          )}
        </div>
      ),
    },
    {
      key: 'done',
      header: <span className="whitespace-nowrap">What was done</span>,
      render: (r) =>
        r.actions.length === 0 ? (
          <span className="text-subtle-foreground">Nothing yet</span>
        ) : (
          <ul className="flex flex-col gap-0.5 py-1.5 text-xs">
            {r.actions.slice(0, 2).map((a) => (
              <li key={a.recordedAt + a.kind} title={`By ${a.recordedBy}${a.note ? ` — ${a.note}` : ''}`}>
                {actionInWords(a)}
              </li>
            ))}
          </ul>
        ),
    },
  ];
  if (manage) {
    columns.push({
      key: 'act',
      header: <span className="sr-only">Act</span>,
      nowrap: true,
      render: (r) => {
        const state = acting[r.invoiceId];
        const busy = state?.status === 'loading';
        return (
          <div className="flex flex-col items-end gap-1 py-1.5">
            <div className="flex items-center gap-1.5">
              <Button asChild size="sm" variant="ghost">
                <Link to={`/staff/receipts/new?policy=${encodeURIComponent(r.policyNumber)}&method=MOBILE_MONEY`}>
                  Ask to pay
                </Link>
              </Button>
              <Button
                size="sm"
                variant="ghost"
                pending={busy && calling?.invoiceId !== r.invoiceId}
                onClick={() => {
                  if (busy) return;
                  void sendReminder(r.invoiceId);
                }}
              >
                Send reminder
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={() => {
                  clearActing(r.invoiceId);
                  setCalling(r);
                }}
              >
                Record a call
              </Button>
            </div>
            {state?.status === 'error' && calling?.invoiceId !== r.invoiceId && state.error && (
              <InlineError error={state.error} className="max-w-72" />
            )}
          </div>
        );
      },
    });
  }

  function renderBody() {
    if (isInitialLoad(list)) return <TableSkeleton columns={columns.length} />;
    if (list.status === 'error' && list.error && !data) {
      return <ErrorPanel error={list.error} onRetry={() => void load(query, false)} />;
    }
    const rows = data?.rows ?? [];
    if (rows.length === 0) {
      const filtered = Boolean(lateBand || agentId || branchCode || productId || q);
      return (
        <EmptyState
          title={
            filtered
              ? 'No premium matches these filters'
              : view === 'DUE_TODAY'
                ? 'Nobody owes a premium due today'
                : view === 'DUE_SOON'
                  ? `No premium falls due in the next ${soonDays} days`
                  : 'Nobody is behind on a premium'
          }
          {...(filtered
            ? {
                action: (
                  <Button size="sm" onClick={() => update({ late: '', agentId: '', branch: '', productId: '', category: '', q: '' })}>
                    Clear the filters
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
          </p>
        )}
        <DataTable columns={columns} rows={rows} rowKey={(r) => r.invoiceId} caption="Premiums to follow up" />
        {data && (
          <Pager
            page={{ page: data.page, pageSize: data.pageSize, totalElements: data.total }}
            busy={list.status === 'loading'}
            onPageChange={(next) => update({ page: next === 0 ? '' : String(next) })}
          />
        )}
      </>
    );
  }

  const counts: Record<FollowUpView, number | undefined> = {
    DUE_SOON: data?.dueSoon,
    DUE_TODAY: data?.dueToday,
    OVERDUE: data?.overdue,
  };

  return (
    <>
      <PageHeader
        title="Premiums to follow up"
        description="Who must pay, how much, and what has been done. Premiums already covered by money on the policy or by an agent's cash are left off."
        actions={
          <>
            <Button size="sm" variant="outline" pending={downloading === 'xlsx'} onClick={() => void download('xlsx')}>
              <Download />
              Download Excel
            </Button>
            <Button size="sm" variant="outline" pending={downloading === 'pdf'} onClick={() => void download('pdf')}>
              <Download />
              Download PDF
            </Button>
          </>
        }
      />

      <div className="space-y-4 px-6 pb-6">
        {downloadError && (
          <p role="alert" className="rounded-md bg-status-danger-bg px-3 py-2 text-sm text-status-danger-fg">
            {downloadError}
          </p>
        )}
        {/* Not drawn until the first read lands: a card saying "Not available" while it loads would be untrue. */}
        {!isInitialLoad(list) && !isInitialLoad(figures) && <KpiRow kpis={kpis} />}

        <div className="rounded-xl border border-border bg-surface shadow-card">
          <div className="flex flex-wrap items-center gap-2 gap-y-2 border-b border-border px-3 py-2.5">
            <div role="group" aria-label="Which premiums" className="inline-flex max-w-full overflow-x-auto rounded-xl bg-control p-1">
              {VIEWS.map((v) => (
                <button
                  key={v.value}
                  type="button"
                  aria-pressed={view === v.value}
                  onClick={() => update({ view: v.value === 'OVERDUE' ? '' : v.value, late: '' })}
                  className={cn(
                    'h-8 shrink-0 whitespace-nowrap rounded-lg px-3 text-sm font-medium text-muted-foreground transition-colors pointer-coarse:h-11',
                    view === v.value ? 'bg-surface text-foreground shadow-raise' : 'hover:text-foreground',
                  )}
                >
                  {v.label}
                  {counts[v.value] !== undefined && <span className="tabular-nums"> · {counts[v.value]}</span>}
                </button>
              ))}
            </div>
            {view === 'DUE_SOON' && (
              <>
                <label htmlFor="follow-up-soon" className="sr-only">
                  Due within
                </label>
                <Select
                  id="follow-up-soon"
                  inputSize="sm"
                  className="w-auto"
                  value={String(soonDays)}
                  onChange={(e) => update({ soonDays: e.target.value === '7' ? '' : e.target.value })}
                >
                  {SOON_DAYS.map((d) => (
                    <option key={d} value={d}>
                      Next {d} days
                    </option>
                  ))}
                </Select>
              </>
            )}
            <div className="relative ml-auto w-full max-w-64">
              <Search className="pointer-events-none absolute left-2 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
              <Input
                inputSize="sm"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                data-list-filter
                placeholder="Name, phone or policy"
                aria-label="Search by name, phone or policy"
                className="pl-7 pr-2.5"
              />
            </div>
          </div>

          {view === 'OVERDUE' && (
            <div className="flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-2" role="group" aria-label="How late">
              <FilterChip label="Any lateness" active={lateBand === ''} onClick={() => update({ late: '' })} />
              {LATE_BANDS.map((band) => (
                <FilterChip
                  key={band}
                  label={band === 'More than 90 days' ? 'Over 90 days' : band}
                  active={lateBand === band}
                  onClick={() => update({ late: band })}
                />
              ))}
            </div>
          )}

          <div className="flex flex-wrap items-center gap-2 border-b border-border px-3 py-2">
            <div className="w-64">
              <AgentPicker
                value={agentId || null}
                onChange={(id) => update({ agentId: id ?? '' })}
                placeholder="All agents"
              />
            </div>
            <div className="w-48">
              <label htmlFor="follow-up-branch" className="sr-only">
                Branch
              </label>
              <BranchSelect
                id="follow-up-branch"
                allowNone
                noneLabel="All branches"
                value={branchCode}
                onChange={(code) => update({ branch: code })}
              />
            </div>
            <ProductFilter
              compact
              products={products.data ?? []}
              category={category}
              productId={productId}
              onChange={(next) => update({ category: next.category, productId: next.productId })}
            />
          </div>

          {renderBody()}
        </div>

        {calling && manage && (
          <RecordCall
            key={calling.invoiceId}
            row={calling}
            onCancel={() => setCalling(null)}
            onSave={async (values) => {
              const ok = await recordCall(toBody(calling.invoiceId, values));
              if (ok) setCalling(null);
            }}
          />
        )}
      </div>
    </>
  );
}

/** Due, due today, or N days late -- the last a pill, because it reports a state. */
function Lateness({ row }: { row: FollowUpRow }) {
  if (row.daysLate > 0) {
    return (
      <span
        className={cn(
          'inline-flex whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium',
          row.daysLate > 30 ? 'bg-status-danger-bg text-status-danger-fg' : 'bg-status-warning-bg text-status-warning-fg',
        )}
      >
        {row.daysLate === 1 ? '1 day late' : `${row.daysLate} days late`}
      </span>
    );
  }
  if (row.dueDate === todayIso()) {
    return (
      <span className="inline-flex whitespace-nowrap rounded-full bg-status-pending-bg px-2 py-0.5 text-xs font-medium text-status-pending-fg">
        Due today
      </span>
    );
  }
  return <span className="whitespace-nowrap text-sm">Due {formatDate(row.dueDate)}</span>;
}

/** The call form, under the list: what happened, a promised day if they will pay, and a note. */
function RecordCall({
  row,
  onCancel,
  onSave,
}: {
  row: FollowUpRow;
  onCancel: () => void;
  onSave: (values: CallFormValues) => Promise<void>;
}) {
  const [values, setValues] = useState<CallFormValues>(blankCall);
  const [problem, setProblem] = useState<string | null>(null);
  const state = useFollowUpStore((s) => s.acting[row.invoiceId]);
  const saving = state?.status === 'loading';
  const panel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    panel.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    panel.current?.querySelector('select')?.focus();
  }, []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const why = callProblem(values);
    setProblem(why);
    if (why || saving) return;
    await onSave(values);
  }

  return (
    <div ref={panel}>
      <Panel emphasis title={`Record a call — ${row.clientName ?? 'client'}, ${row.policyNumber}`}>
        <form onSubmit={(e) => void submit(e)} className="grid max-w-2xl gap-4 p-5 sm:grid-cols-2" noValidate>
          <FormField label="What happened" required error={problem && !values.outcome ? problem : undefined}>
            <Select
              value={values.outcome}
              onChange={(e) => setValues((v) => ({ ...v, outcome: e.target.value as CallFormValues['outcome'] }))}
            >
              <option value="">Choose…</option>
              {CALL_OUTCOMES.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </Select>
          </FormField>
          {values.outcome === 'WILL_PAY' && (
            <FormField label="Promised to pay on" hint="Optional">
              <DatePicker
                value={values.promisedOn}
                onChange={(day) => setValues((v) => ({ ...v, promisedOn: day }))}
                disabled={{ before: new Date(`${todayIso()}T00:00:00`) }}
              />
            </FormField>
          )}
          <FormField
            label="Note"
            className="sm:col-span-2"
            hint={`${values.note.length} of ${NOTE_MAX} characters`}
            error={problem && values.outcome ? problem : undefined}
          >
            <Textarea
              rows={2}
              maxLength={NOTE_MAX}
              value={values.note}
              onChange={(e) => setValues((v) => ({ ...v, note: e.target.value }))}
            />
          </FormField>
          <p className="text-xs text-muted-foreground sm:col-span-2">
            A promise is a note only: reminders and letters still go.
          </p>
          {state?.status === 'error' && state.error && (
            <div className="sm:col-span-2">
              <InlineError error={state.error} />
            </div>
          )}
          <div className="flex gap-2 sm:col-span-2">
            <Button type="submit" variant="primary" pending={saving}>
              Save call
            </Button>
            <Button type="button" variant="ghost" onClick={onCancel}>
              Cancel
            </Button>
          </div>
        </form>
      </Panel>
    </div>
  );
}
