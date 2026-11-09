import { ChevronDown, ChevronRight, Search } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import type { ApprovalGroup, ApprovalRow } from '@/api/approvals';
import { DataTable, type Column } from '@/components/DataTable';
import { FilterChip } from '@/components/FilterChip';
import { PageHeader } from '@/components/PageHeader';
import { CountLine, type Stat } from '@/components/StatCards';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/cn';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useApprovalsStore } from '@/store/approvalsStore';
import { approvalLink } from './approvalLinks';
import { AccountApprovalReview } from './AccountApprovalReview';
import { ApprovalDetails } from './ApprovalDetails';
import { matches, waiting } from './approvalWords';

/**
 * Everything waiting for the viewer's signature, across every policy (2026-10-27; reworked 2026-11-09 after the user
 * found it "not user friendly" and short of detail).
 *
 * Each row says whose money it is and the second fact a signer needs -- who is paid and how, or the reason given --
 * and how long it has waited, flagged once it is overdue. The groups the viewer can act on come first; groups made
 * only of their own requests fold away under "Waiting on someone else". A search and a kind filter narrow the queue.
 * Details opens every fact in a drawer; the act itself stays on the page that owns it, so its checks stay the only
 * ones (the inbox never becomes a second way to approve).
 */
export function ApprovalsPage() {
  const resource = useApprovalsStore((s) => s.approvals);
  const loadApprovals = useApprovalsStore((s) => s.loadApprovals);
  const [params, setParams] = useSearchParams();
  const showAll = params.get('show') === 'all';
  const [query, setQuery] = useState('');
  const [kind, setKind] = useState('');
  const [othersOpen, setOthersOpen] = useState(false);

  useEffect(() => {
    void loadApprovals();
  }, [loadApprovals]);

  const view = resource.data;
  const count: Stat = {
    label: 'waiting for you',
    value: view ? view.actionable : null,
    pending: isInitialLoad(resource),
    hint: resource.status === 'error' && !view ? 'could not load' : 'your own requests wait for someone else',
  };

  const allGroups = view?.groups ?? [];
  const groups = useMemo(() => {
    const narrowed = (view?.groups ?? [])
      .filter((g) => showAll || g.total > 0 || g.unreadable)
      .filter((g) => !kind || g.kind === kind)
      .map((g) => ({ ...g, rows: g.rows.filter((r) => matches(r, query)) }));
    return query.trim() ? narrowed.filter((g) => g.rows.length > 0) : narrowed;
  }, [view, showAll, kind, query]);
  // What the viewer can act on leads; a group made only of their own requests is someone else's to sign, and folds
  // away -- on the waiting view. "Everything you approve" means everything, in one list.
  const othersOnly = (g: ApprovalGroup) => !g.unreadable && g.total > 0 && g.actionable === 0;
  const toAct = showAll ? groups : groups.filter((g) => !othersOnly(g));
  const others = showAll ? [] : groups.filter(othersOnly);

  function renderBody() {
    if (isInitialLoad(resource)) return <TableSkeleton columns={5} />;
    if (resource.status === 'error' && resource.error && !view) {
      return <ErrorPanel error={resource.error} onRetry={() => void loadApprovals()} />;
    }
    if (view && view.groups.length === 0) {
      return (
        <EmptyState
          title="Nothing here is yours to sign"
          description="Your role approves none of the kinds this screen lists. Ask an administrator if your work needs it."
        />
      );
    }
    if (groups.length === 0) {
      return query.trim() || kind ? (
        <EmptyState title="No request matches" description="Clear the search or choose every kind to see the whole queue." />
      ) : (
        <EmptyState
          title="Nothing is waiting for your signature"
          description="Every withdrawal, payout, price and journal you approve has been dealt with. New requests appear here the moment they are made."
        />
      );
    }
    return (
      <>
        {toAct.map((g) => <GroupCard key={g.kind} group={g} />)}
        {others.length > 0 && (
          <section aria-labelledby="waiting-on-others" className="space-y-3">
            <button
              type="button"
              id="waiting-on-others"
              aria-expanded={othersOpen}
              onClick={() => setOthersOpen((o) => !o)}
              className="flex items-center gap-1.5 rounded-md text-sm font-semibold text-muted-foreground hover:text-foreground"
            >
              {othersOpen ? <ChevronDown className="size-4" aria-hidden /> : <ChevronRight className="size-4" aria-hidden />}
              Waiting on someone else
              <span className="font-normal tabular-nums">
                · {others.reduce((n, g) => n + g.total, 0).toLocaleString('en-US')} you asked for
              </span>
            </button>
            {othersOpen && others.map((g) => <GroupCard key={g.kind} group={g} />)}
          </section>
        )}
      </>
    );
  }

  return (
    <>
      <PageHeader title="Approvals" count={<CountLine {...count} />} />
      <div className="space-y-4 px-6 pb-6">
        <div className="flex flex-wrap items-center gap-2">
          <label className="relative block w-full sm:w-72">
            <span className="sr-only">Search approvals</span>
            <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
            <Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Policy, client or what it is" className="pl-9" />
          </label>
          <label className="sr-only" htmlFor="approvals-kind">Kind</label>
          <select
            id="approvals-kind"
            value={kind}
            onChange={(e) => setKind(e.target.value)}
            className="h-9 rounded-md border border-border-strong bg-surface px-2 text-sm"
          >
            <option value="">Every kind</option>
            {allGroups.filter((g) => showAll || g.total > 0).map((g) => (
              <option key={g.kind} value={g.kind}>{g.label} ({g.total})</option>
            ))}
          </select>
          <div className="flex flex-wrap gap-1.5">
            <FilterChip label="Waiting" active={!showAll} onClick={() => setParams({}, { replace: true })} />
            <FilterChip
              label="Everything you approve"
              active={showAll}
              onClick={() => setParams({ show: 'all' }, { replace: true })}
            />
          </div>
        </div>
        {resource.status === 'error' && resource.error && view && (
          <p className="rounded-md bg-status-warning-bg px-4 py-2 text-xs text-status-warning-fg">
            Showing older figures — could not refresh.
          </p>
        )}
        {renderBody()}
      </div>
    </>
  );
}

/** Rows a kind shows before "Show all": enough to see what is oldest without one long queue burying the rest. */
const FIRST_ROWS = 5;

/**
 * Kinds signed right here (2026-10-29, the user: finance should not have to open each policy). Review opens the
 * policy's facts and the request above the table; everything else still opens the page that owns the act.
 */
const REVIEWED_HERE = new Set(['account-withdrawal', 'account-adjustment']);

function GroupCard({ group }: { group: ApprovalGroup }) {
  const [expanded, setExpanded] = useState(false);
  const [reviewing, setReviewing] = useState<ApprovalRow | null>(null);
  const [details, setDetails] = useState<ApprovalRow | null>(null);
  const shown = expanded ? group.rows : group.rows.slice(0, FIRST_ROWS);
  const columns: Column<ApprovalRow>[] = [
    {
      key: 'summary',
      header: 'What',
      // Lean on purpose (2026-11-09, the user: "many info there"): what it is and whose money. Everything else -- the
      // payee, the reason, who asked, since when -- opens from the request's name, in the drawer.
      render: (r) => {
        const age = waiting(group.kind, r.requestedAt);
        return (
          <span className="flex min-w-0 flex-col gap-0.5">
            <button
              type="button"
              onClick={() => setDetails(r)}
              aria-label={`Details of ${r.summary}${r.policyNumber ? ` on ${r.policyNumber}` : ''}`}
              className="w-fit text-left font-medium hover:underline focus-visible:underline"
            >
              {r.summary}
            </button>
            <span className="text-xs text-muted-foreground">
              {r.clientName ?? r.policyNumber ?? ''}
              {r.yours && <span>{r.clientName || r.policyNumber ? ' · ' : ''}yours — someone else signs</span>}
            </span>
            {/* On a phone the amount and the wait have no column of their own: they ride under the request. */}
            <span className="flex flex-wrap items-baseline gap-x-2 sm:hidden">
              {r.amount && <span className="whitespace-nowrap font-semibold">{formatMoney(r.amount)}</span>}
              {age && (
                <span className={cn('text-xs', age.overdue ? 'font-semibold text-status-danger-fg' : 'text-muted-foreground')}>
                  {age.words}
                </span>
              )}
            </span>
          </span>
        );
      },
    },
    {
      key: 'amount',
      header: 'Amount',
      align: 'right',
      className: 'sm:w-40',
      secondary: true,
      render: (r) =>
        r.amount ? (
          <span className="whitespace-nowrap font-medium">{formatMoney(r.amount)}</span>
        ) : (
          <span className="text-subtle-foreground">—</span>
        ),
    },
    {
      key: 'requestedAt',
      header: 'Waiting',
      className: 'w-28',
      secondary: true,
      render: (r) => {
        const age = waiting(group.kind, r.requestedAt);
        if (!age) return <span className="text-subtle-foreground">—</span>;
        return (
          <span
            data-overdue={age.overdue ? 'true' : 'false'}
            className={cn(
              'inline-block whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium',
              age.overdue ? 'bg-status-danger-bg text-status-danger-fg' : 'bg-control text-muted-foreground',
            )}
          >
            {age.words}
          </span>
        );
      },
    },
    {
      key: 'act',
      header: <span className="sr-only">Act</span>,
      align: 'right',
      className: 'sm:w-32',
      render: (r) => <ActButton group={group} row={r} reviewing={reviewing} onReview={setReviewing} />,
    },
  ];

  return (
    <section aria-label={group.label} className="overflow-hidden rounded-xl border border-border bg-surface shadow-card">
      <div className="flex flex-wrap items-baseline justify-between gap-3 border-b border-border px-4 py-3">
        <h2 className="text-title">{group.label}</h2>
        <p className="text-xs text-muted-foreground tabular-nums">
          {group.unreadable
            ? 'could not be read'
            : group.total === 0
              ? 'nothing waiting'
              : `${group.total.toLocaleString()} waiting${
                  group.actionable !== group.total ? ` · ${group.actionable.toLocaleString()} for you` : ''
                }`}
        </p>
      </div>
      {group.unreadable ? (
        <p className="bg-status-warning-bg px-4 py-2.5 text-xs text-status-warning-fg">
          This list could not be read just now, so how many wait here is unknown — not none. Try again shortly.
        </p>
      ) : group.rows.length > 0 ? (
        <>
          {reviewing?.policyNumber && (
            <AccountApprovalReview
              key={reviewing.reference}
              kind={group.kind as 'account-withdrawal' | 'account-adjustment'}
              policyNumber={reviewing.policyNumber}
              reference={reviewing.reference}
              onClose={() => setReviewing(null)}
            />
          )}
          <DataTable columns={columns} rows={shown} rowKey={(r) => r.reference} caption={group.label} />
          {(group.rows.length > FIRST_ROWS || group.total > group.rows.length) && (
            <div className="flex flex-wrap items-center justify-between gap-2 border-t border-border px-4 py-2">
              <p className="text-xs text-muted-foreground">
                Showing the oldest {shown.length} of {group.total.toLocaleString()}.
              </p>
              {group.rows.length > FIRST_ROWS && (
                <Button size="sm" variant="ghost" onClick={() => setExpanded((e) => !e)}>
                  {expanded ? 'Show fewer' : `Show ${group.rows.length === group.total ? 'all' : 'the oldest'} ${group.rows.length}`}
                </Button>
              )}
            </div>
          )}
        </>
      ) : null}
      <ApprovalDetails group={group} row={details} onClose={() => setDetails(null)} />
    </section>
  );
}

/** The row's act: Review in place for the kinds signed here, else the page that owns it. */
function ActButton({ group, row, reviewing, onReview }: {
  group: ApprovalGroup;
  row: ApprovalRow;
  reviewing: ApprovalRow | null;
  onReview: (r: ApprovalRow | null) => void;
}) {
  if (REVIEWED_HERE.has(group.kind) && !row.yours && row.policyNumber) {
    const open = reviewing?.reference === row.reference;
    return (
      <Button size="sm" variant={open ? 'secondary' : 'outline'} aria-expanded={open} onClick={() => onReview(open ? null : row)}>
        Review
      </Button>
    );
  }
  const to = approvalLink(group.kind, row);
  if (!to) return null;
  return (
    <Button asChild size="sm" variant={row.yours ? 'ghost' : 'outline'}>
      <Link to={to}>{row.yours ? 'Open' : group.act}</Link>
    </Button>
  );
}
