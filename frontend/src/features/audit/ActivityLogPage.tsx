import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  downloadActivity,
  type ActivityDetail,
  type ActivityFilters,
  type ActivityKind,
  type ActivityOutcome,
} from '@/api/activity';
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
import { isInitialLoad, type Resource } from '@/store/createResourceSlice';
import { useActivityStore } from '@/store/activityStore';
import { ActivityDays } from './ActivityDays';
import { ActivityLine } from './ActivityLine';

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

  /** The action a link was sent for (`?line=`), pinned above the list whatever the filters and page. */
  const linked = params.get('line');
  const linkedDetail = useActivityStore((s) => (linked ? s.details[linked] : undefined));
  const loadDetail = useActivityStore((s) => s.loadDetail);

  /** Several lines open at once, so two actions can be read side by side; kept across a change of filter. */
  const [openIds, setOpenIds] = useState<ReadonlySet<string>>(() => new Set());
  const toggle = (id: string) =>
    setOpenIds((now) => {
      const next = new Set(now);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  /**
   * What is typed in Record, tied to the `q` it was typed against: Back or Forward changes `q`, and the box then
   * shows the address's words again rather than stale ones (no effect needed -- derived on render).
   */
  const [draft, setDraft] = useState({ q, text: q });
  const searchText = draft.q === q ? draft.text : q;
  const typing = useRef<ReturnType<typeof setTimeout> | null>(null);

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

  useEffect(() => {
    if (linked) void loadDetail(linked);
  }, [linked, loadDetail]);

  useEffect(() => () => {
    if (typing.current) clearTimeout(typing.current);
  }, []);

  /** Set or clear address keys; any filter change goes back to the first page. Built on the latest address, so a
   *  search applied after a pause never undoes a chip pressed meanwhile. */
  function update(next: Record<string, string>) {
    setParams((current) => {
      const merged = new URLSearchParams(current);
      for (const [key, value] of Object.entries(next)) {
        if (value === '') merged.delete(key);
        else merged.set(key, value);
      }
      if (!('page' in next) && !('line' in next)) merged.delete('page');
      return merged;
    });
  }

  /** Record applies as it is typed, after a short pause -- like every other filter here, with no Find to press. */
  function search(text: string) {
    setDraft({ q, text });
    if (typing.current) clearTimeout(typing.current);
    typing.current = setTimeout(() => update({ q: text.trim() }), 400);
  }

  function searchNow(event: FormEvent) {
    event.preventDefault();
    if (typing.current) clearTimeout(typing.current);
    update({ q: searchText.trim() });
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
                  <Button size="sm" onClick={() => { setDraft({ q: '', text: '' }); setParams(new URLSearchParams()); }}>
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
          openIds={openIds}
          onToggle={toggle}
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
            <form onSubmit={searchNow} role="search" className="flex flex-col gap-1 text-xs text-label">
              <label htmlFor="activity-q">Record</label>
              <Input id="activity-q" type="search" inputSize="sm" value={searchText} placeholder="Policy, claim or client"
                onChange={(e) => search(e.target.value)} />
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
          {/* Two named groups; a chip pressed again lets go, so no "All kinds" / "Any outcome" chip is needed. */}
          <div className="flex flex-wrap items-center gap-x-5 gap-y-2 border-b border-border px-3 py-2.5">
            <div role="group" aria-labelledby="activity-kind" className="flex flex-wrap items-center gap-1.5">
              <span id="activity-kind" className="mr-1 text-xs text-label">Kind</span>
              {KINDS.map((k) => (
                <FilterChip key={k.value} label={k.label} active={kind === k.value}
                  onClick={() => update({ kind: kind === k.value ? '' : k.value })} />
              ))}
            </div>
            <div role="group" aria-labelledby="activity-outcome" className="flex flex-wrap items-center gap-1.5">
              <span id="activity-outcome" className="mr-1 text-xs text-label">Outcome</span>
              {OUTCOMES.map((o) => (
                <FilterChip key={o.value} label={o.label} active={outcome === o.value}
                  onClick={() => update({ outcome: outcome === o.value ? '' : o.value })} />
              ))}
            </div>
          </div>
          {linked && (
            <LinkedLine
              detail={linkedDetail}
              onRetry={() => void loadDetail(linked)}
              onClose={() => update({ line: '' })}
              onPerson={(subject) => update({ person: subject, line: '' })}
            />
          )}
          {body()}
        </div>
      </div>
    </>
  );
}

/**
 * The one action a shared link names (`?line=<id>`, 2026-11-15 critique: an audit finding is something people send
 * each other). Pinned above the list and opened, whatever the filters and page -- the line may be on neither.
 */
function LinkedLine({
  detail,
  onRetry,
  onClose,
  onPerson,
}: {
  detail: Resource<ActivityDetail> | undefined;
  onRetry: () => void;
  onClose: () => void;
  onPerson: (subject: string) => void;
}) {
  const [open, setOpen] = useState(true);
  const line = detail?.data?.line;
  return (
    <section aria-labelledby="activity-linked" className="border-b-2 border-accent">
      <div className="flex items-center justify-between gap-3 bg-surface-muted px-4 py-1.5">
        <h2 id="activity-linked" className="text-xs font-semibold text-label">The action this link points to</h2>
        <Button size="sm" variant="ghost" onClick={onClose}>Close</Button>
      </div>
      {detail?.status === 'error' && !line && detail.error && <ErrorPanel error={detail.error} onRetry={onRetry} />}
      {!line && detail?.status !== 'error' && <p className="px-4 py-3 text-sm text-muted-foreground">Loading…</p>}
      {line && (
        <ul aria-label="Linked action">
          <ActivityLine line={line} open={open} onToggle={() => setOpen(!open)} onPerson={onPerson} withDate />
        </ul>
      )}
    </section>
  );
}
