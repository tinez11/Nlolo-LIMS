import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import type { ApprovalGroup, ApprovalRow } from '@/api/approvals';
import { DataTable, type Column } from '@/components/DataTable';
import { FilterChip } from '@/components/FilterChip';
import { PageHeader } from '@/components/PageHeader';
import { CountLine, type Stat } from '@/components/StatCards';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatInstantDay } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useApprovalsStore } from '@/store/approvalsStore';
import { approvalLink } from './approvalLinks';
import { AccountApprovalReview } from './AccountApprovalReview';

/**
 * Everything waiting for the viewer's signature, across every policy (2026-10-27).
 *
 * Finance signed in some twenty places, and most could be found only by opening the right policy: a withdrawal, a
 * surrender, a free-look refund. This lists them by kind, oldest first, each linking to the page that already does the
 * act -- the inbox never becomes a second way to approve anything, so the checks each page makes stay the only ones.
 *
 * The viewer's own requests are listed and not counted: on a two-person act somebody else signs them.
 */
export function ApprovalsPage() {
  const resource = useApprovalsStore((s) => s.approvals);
  const loadApprovals = useApprovalsStore((s) => s.loadApprovals);
  const [params, setParams] = useSearchParams();
  const showAll = params.get('show') === 'all';

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

  const groups = (view?.groups ?? []).filter((g) => showAll || g.total > 0 || g.unreadable);

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
      return (
        <EmptyState
          title="Nothing is waiting for your signature"
          description="Every withdrawal, payout, price and journal you approve has been dealt with. New requests appear here the moment they are made."
        />
      );
    }
    return groups.map((g) => <GroupCard key={g.kind} group={g} />);
  }

  return (
    <>
      <PageHeader
        title="Approvals"
        description="Everything waiting for your signature, across every policy, oldest first. Each opens the page where it is done."
        count={<CountLine {...count} />}
      />
      <div className="space-y-4 px-6 pb-6">
        <div className="flex flex-wrap gap-1.5">
          <FilterChip label="Waiting" active={!showAll} onClick={() => setParams({}, { replace: true })} />
          <FilterChip
            label="Everything you approve"
            active={showAll}
            onClick={() => setParams({ show: 'all' }, { replace: true })}
          />
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
  const shown = expanded ? group.rows : group.rows.slice(0, FIRST_ROWS);
  const columns: Column<ApprovalRow>[] = [
    {
      key: 'summary',
      header: 'What',
      render: (r) => (
        <span className="flex flex-col gap-0.5">
          <span className="font-medium">{r.summary}</span>
          {r.policyNumber && <span className="text-xs text-muted-foreground">{r.policyNumber}</span>}
        </span>
      ),
    },
    {
      key: 'amount',
      header: 'Amount',
      align: 'right',
      className: 'w-40',
      render: (r) =>
        r.amount ? (
          <span className="whitespace-nowrap font-medium">{formatMoney(r.amount)}</span>
        ) : (
          <span className="text-subtle-foreground">—</span>
        ),
    },
    {
      key: 'requestedBy',
      header: 'Asked by',
      className: 'w-48',
      secondary: true,
      render: (r) =>
        r.yours ? (
          <span className="text-muted-foreground">You — someone else signs</span>
        ) : (
          <span>{r.requestedByName ?? r.requestedBy ?? <span className="text-subtle-foreground">—</span>}</span>
        ),
    },
    {
      key: 'requestedAt',
      header: 'Waiting since',
      className: 'w-44',
      secondary: true,
      render: (r) => <span className="whitespace-nowrap text-muted-foreground">{formatInstantDay(r.requestedAt)}</span>,
    },
    {
      key: 'act',
      header: <span className="sr-only">Act</span>,
      align: 'right',
      className: 'w-40',
      render: (r) => {
        if (REVIEWED_HERE.has(group.kind) && !r.yours && r.policyNumber) {
          const open = reviewing?.reference === r.reference;
          return (
            <Button size="sm" variant={open ? 'secondary' : 'outline'} aria-expanded={open}
              onClick={() => setReviewing(open ? null : r)}>
              Review
            </Button>
          );
        }
        const to = approvalLink(group.kind, r);
        if (!to) return null;
        return (
          <Button asChild size="sm" variant={r.yours ? 'ghost' : 'outline'}>
            <Link to={to}>{r.yours ? 'Open' : group.act}</Link>
          </Button>
        );
      },
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
    </section>
  );
}
