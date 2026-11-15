import { useEffect, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { downloadActivity, type ActivityFilters, type ActivityKind, type ActivityOutcome } from '@/api/activity';
import { DatePicker } from '@/components/DatePicker';
import { FilterChip } from '@/components/FilterChip';
import { FormField } from '@/components/FormField';
import { Pager } from '@/components/DataTable';
import { PageHeader } from '@/components/PageHeader';
import { CountLine, type Stat } from '@/components/StatCards';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { formatDate, todayIso } from '@/lib/dates';
import { saveBlob } from '@/lib/download';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useActivityStore } from '@/store/activityStore';
import { ActivityDays } from './ActivityDays';

const KINDS: { value: ActivityKind; label: string }[] = [
  { value: 'claims', label: 'Claims' },
  { value: 'money', label: 'Money' },
  { value: 'policies', label: 'Policies' },
  { value: 'people', label: 'People & access' },
  { value: 'products', label: 'Products & prices' },
  { value: 'settings', label: 'Settings' },
];

const OUTCOMES: { value: ActivityOutcome; label: string }[] = [
  { value: 'DONE', label: 'Done' },
  { value: 'REFUSED', label: 'Refused' },
  { value: 'FAILED', label: 'Failed' },
];

/** The spans an investigator reaches for, as days back from today (today counts as one). */
const RANGES = [
  { days: 0, label: 'Today' },
  { days: 6, label: 'Last 7 days' },
  { days: 29, label: 'Last 30 days' },
] as const;

const SELECT = 'h-8 rounded-md border border-border-strong bg-surface px-2 text-xs';

/** Midnight of a `YYYY-MM-DD` day, local, for the date picker's bounds. */
const asDate = (day: string) => new Date(`${day}T00:00:00`);

/** "2026-11-14" moved by whole days, on the calendar rather than the clock. */
function shiftDay(day: string, days: number): string {
  const d = new Date(`${day}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** A day in Dar es Salaam, as the instants the server filters on. */
const startOf = (day: string) => `${day}T00:00:00+03:00`;
const endOf = (day: string) => `${day}T23:59:59.999+03:00`;

/**
 * The activity log (2026-11-14), replacing the Event journal: who did what, when, to what, and how it ended -- one line
 * per action, the events it raised listed when it is opened, and what the platform did by itself as System lines.
 * Filters live in the address, so a filtered view can be shared.
 */
export function ActivityLogPage() {
  const [params, setParams] = useSearchParams();
  const today = todayIso();
  const from = params.get('from') ?? shiftDay(today, -6);
  const to = params.get('to') ?? today;
  const person = params.get('person') ?? '';
  const q = params.get('q') ?? '';
  const kind = (KINDS.find((k) => k.value === params.get('kind'))?.value ?? undefined) as ActivityKind | undefined;
  const outcome = (OUTCOMES.find((o) => o.value === params.get('outcome'))?.value ?? undefined) as
    | ActivityOutcome
    | undefined;
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  const filters: ActivityFilters = {
    ...(person ? { person } : {}),
    ...(q ? { q } : {}),
    ...(kind ? { kind } : {}),
    ...(outcome ? { outcome } : {}),
    from: startOf(from),
    to: endOf(to),
  };

  const list = useActivityStore((s) => s.list);
  const people = useActivityStore((s) => s.people);
  const loadList = useActivityStore((s) => s.loadList);
  const loadPeople = useActivityStore((s) => s.loadPeople);

  const preset = RANGES.find((r) => to === today && from === shiftDay(today, -r.days));
  /** Own dates chosen: shown when no preset matches the address, or when asked for by "Choose dates". */
  const [choosing, setChoosing] = useState(false);
  const custom = choosing || !preset;

  const [openId, setOpenId] = useState<string | null>(null);
  const [search, setSearch] = useState(q);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  useEffect(() => {
    void loadPeople();
  }, [loadPeople]);

  useEffect(() => {
    void loadList(filters, page);
    // `filters` is rebuilt each render; the primitives it derives from are the deps.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loadList, person, q, kind, outcome, from, to, page]);

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params);
    for (const [key, value] of Object.entries(next)) {
      if (value === '') merged.delete(key);
      else merged.set(key, value);
    }
    if (!('page' in next)) merged.delete('page');
    setOpenId(null);
    setParams(merged);
  }

  function find(event: FormEvent) {
    event.preventDefault();
    update({ q: search.trim() });
  }

  async function download() {
    setDownloadError(null);
    setDownloading(true);
    try {
      saveBlob(await downloadActivity(filters), `activity-${from}-to-${to}.csv`);
    } catch {
      setDownloadError('The file could not be made. Try again in a moment.');
    } finally {
      setDownloading(false);
    }
  }

  const filtered = !!(person || q || kind || outcome || params.get('from') || params.get('to'));
  const total = list.data?.page.totalElements ?? null;
  const count: Stat = {
    label: 'actions and events',
    value: total,
    pending: isInitialLoad(list),
    hint:
      list.status === 'error' && total === null
        ? 'could not load'
        : from === to
          ? formatDate(from)
          : `${formatDate(from)} to ${formatDate(to)}`,
  };

  function pickRange(days: number) {
    setChoosing(false);
    // The default (last 7 days) is the bare address, so a shared link without dates stays "the last week".
    update(days === 6 ? { from: '', to: '' } : { from: shiftDay(today, -days), to: today });
  }

  function body() {
    if (isInitialLoad(list)) return <TableSkeleton columns={5} />;
    if (list.status === 'error' && list.error && list.data === null) {
      return <ErrorPanel error={list.error} onRetry={() => void loadList(filters, page)} />;
    }
    const lines = list.data?.items ?? [];
    if (lines.length === 0) {
      return (
        <EmptyState
          title={
            person || q || kind || outcome
              ? 'Nothing matches these filters'
              : `Nothing was done ${from === to ? `on ${formatDate(from)}` : `between ${formatDate(from)} and ${formatDate(to)}`}`
          }
          description={person || q || kind || outcome ? 'Try other dates, or clear the filters.' : 'Try a longer range.'}
          {...(filtered
            ? {
                action: (
                  <Button size="sm" onClick={() => { setSearch(''); setParams(new URLSearchParams()); }}>
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
        <ActivityDays
          lines={lines}
          openId={openId}
          onToggle={(id) => setOpenId(openId === id ? null : id)}
          onPerson={(subject) => update({ person: subject })}
          stickyDays
        />
        {list.data && (
          <Pager page={list.data.page} busy={list.status === 'loading'} onPageChange={(next) => update({ page: String(next) })} />
        )}
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Activity log"
        count={<CountLine {...count} />}
        actions={
          <Button size="sm" variant="outline" disabled={downloading} onClick={() => void download()}>
            {downloading ? 'Making the file…' : 'Download CSV'}
          </Button>
        }
      />
      {downloadError && <p role="alert" className="px-6 pb-2 text-sm text-status-danger-fg">{downloadError}</p>}

      <div className="px-6 pb-6">
        <div className="rounded-xl border border-border bg-surface shadow-card">
          <div className="flex flex-wrap items-end gap-3 border-b border-border px-3 py-3">
            <label className="flex flex-col gap-1 text-xs text-label" htmlFor="activity-person">
              Who
              <select id="activity-person" className={SELECT} value={person} onChange={(e) => update({ person: e.target.value })}>
                <option value="">Everyone</option>
                <option value="SYSTEM">System</option>
                {(people.data ?? []).map((p) => (
                  <option key={p.subject} value={p.subject}>{p.name ?? 'Someone without a name'}</option>
                ))}
              </select>
            </label>
            <form onSubmit={find} className="flex flex-col gap-1 text-xs text-label">
              <label htmlFor="activity-q">Record</label>
              <div className="flex gap-1">
                <Input id="activity-q" inputSize="sm" value={search} placeholder="Policy, claim or client"
                  onChange={(e) => setSearch(e.target.value)} />
                <Button size="sm" type="submit" variant="outline">Find</Button>
              </div>
            </form>
            <div role="group" aria-labelledby="activity-when" className="flex flex-col gap-1">
              <span id="activity-when" className="text-xs text-label">When</span>
              <div className="flex flex-wrap items-center gap-1.5">
                {RANGES.map((r) => (
                  <FilterChip key={r.days} label={r.label} active={!custom && preset === r} onClick={() => pickRange(r.days)} />
                ))}
                <FilterChip label="Choose dates" active={custom} onClick={() => setChoosing(true)} />
              </div>
            </div>
            {custom && (
              <>
                <FormField label="From">
                  <DatePicker
                    value={from}
                    disabled={{ after: asDate(to) }}
                    onChange={(iso) => iso && update({ from: iso, to })}
                  />
                </FormField>
                <FormField label="To">
                  <DatePicker
                    value={to}
                    disabled={{ before: asDate(from), after: asDate(today) }}
                    onChange={(iso) => iso && update({ from, to: iso })}
                  />
                </FormField>
              </>
            )}
          </div>
          <div className="flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-2.5">
            <FilterChip label="All kinds" active={!kind} onClick={() => update({ kind: '' })} />
            {KINDS.map((k) => (
              <FilterChip key={k.value} label={k.label} active={kind === k.value} onClick={() => update({ kind: k.value })} />
            ))}
            <span className="mx-1 h-4 w-px bg-border" aria-hidden />
            <FilterChip label="Any outcome" active={!outcome} onClick={() => update({ outcome: '' })} />
            {OUTCOMES.map((o) => (
              <FilterChip key={o.value} label={o.label} active={outcome === o.value} onClick={() => update({ outcome: o.value })} />
            ))}
          </div>
          {body()}
        </div>
      </div>
    </>
  );
}
