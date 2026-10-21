import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { listCases } from '@/api/underwriting';
import { getNeedsAction, RETURN_CATEGORY_LABELS, type NeedsAction } from '@/api/proposalAmendments';
import { UNDERWRITING_CASE_STATUSES, type UnderwritingCaseStatus, type UnderwritingCaseView } from '@/api/types';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { FilterChip } from '@/components/FilterChip';
import { PageHeader } from '@/components/PageHeader';
import { PartyName } from '@/components/PartyName';
import { ProductName } from '@/components/ProductName';
import { CountLine, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatInstant } from '@/lib/dates';

const PAGE_SIZE = 20;
/** How often the list asks again while the page is open and the tab is visible (2026-10-21): close enough to "as it
 * happens" for an agent watching one proposal move through review, without a request every few seconds. */
const REFRESH_MS = 30_000;

/**
 * Every proposal an agent has submitted, newest first, with its current status (2026-10-21) -- open, in review,
 * returned, or decided. "Needs your action" (returned, postponed, and recent declines) is a view of this same list
 * rather than a page of its own: it used to be the only place an agent could see a proposal at all, so one that was
 * accepted or still being reviewed was invisible until a policy, or nothing, eventually turned up.
 *
 * <p>Scoped entirely by the server: `GET /underwriting/cases` resolves an agents-realm caller to their own
 * registered clients (`partyApi.partyIdsRegisteredBy`), the same way the staff queue resolves nothing and sees every
 * case in the tenant.
 */
export function AgentProposalsPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();

  const needsAction = params.get('view') === 'needs-action';
  const statusParam = params.get('status');
  const status: UnderwritingCaseStatus | undefined =
    !needsAction && statusParam && (UNDERWRITING_CASE_STATUSES as readonly string[]).includes(statusParam)
      ? (statusParam as UnderwritingCaseStatus)
      : undefined;
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  const [rows, setRows] = useState<UnderwritingCaseView[] | null>(null);
  const [needsActionRows, setNeedsActionRows] = useState<NeedsAction[] | null>(null);
  const [total, setTotal] = useState<number | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let live = true;
    if (needsAction) {
      getNeedsAction().then(
        (r) => { if (live) { setNeedsActionRows(r); setTotal(r.length); setError(null); } },
        (e: unknown) => { if (live) setError(toApiError(e)); },
      );
    } else {
      listCases({ ...(status ? { status } : {}), page, pageSize: PAGE_SIZE }).then(
        (p) => { if (live) { setRows(p.items); setTotal(p.page.totalElements); setError(null); } },
        (e: unknown) => { if (live) setError(toApiError(e)); },
      );
    }
    return () => { live = false; };
  }, [needsAction, status, page, tick]);

  // Asked again every REFRESH_MS while the tab is visible, same convention as the sidebar counts
  // (navBadges.ts) -- not while it is in the background, where nobody is watching it move.
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') setTick((t) => t + 1);
    }, REFRESH_MS);
    const onVisible = () => {
      if (document.visibilityState === 'visible') setTick((t) => t + 1);
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, []);

  function update(next: { view?: 'needs-action' | null; status?: UnderwritingCaseStatus | undefined; page?: number }) {
    const merged = new URLSearchParams(params);
    if ('view' in next) {
      if (next.view) merged.set('view', next.view);
      else merged.delete('view');
      merged.delete('status');
      merged.delete('page');
    }
    if ('status' in next) {
      if (next.status) merged.set('status', next.status);
      else merged.delete('status');
      merged.delete('view');
      merged.delete('page');
    }
    if (next.page !== undefined) {
      if (next.page === 0) merged.delete('page');
      else merged.set('page', String(next.page));
    }
    setParams(merged);
  }

  const count: Stat = {
    label: needsAction ? 'needing your action' : status ? `${status.toLowerCase().replace('_', ' ')} proposals` : 'proposals',
    value: total,
    pending: total === null,
    hint: needsAction ? 'returned, postponed, or recently declined' : 'most recently submitted first',
  };

  const columns: Column<UnderwritingCaseView>[] = [
    {
      key: 'proposalNumber',
      header: 'Proposal',
      render: (c) => <span className="font-mono text-xs font-medium">{c.proposalNumber ?? c.caseId ?? '—'}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      render: (c) =>
        c.status === 'DECIDED' ? (
          <StatusBadge kind="underwritingDecision" value={c.decisionOutcome} />
        ) : (
          <StatusBadge kind="underwritingCase" value={c.status} />
        ),
    },
    {
      key: 'applicantPartyId',
      header: 'Customer',
      render: (c) => (c.applicantPartyId ? <PartyName partyId={c.applicantPartyId} /> : '—'),
    },
    {
      key: 'productId',
      header: 'Product',
      secondary: true,
      render: (c) => (c.productId ? <ProductName productId={c.productId} withCode={false} className="text-muted-foreground" /> : '—'),
    },
  ];

  const needsActionColumns: Column<NeedsAction>[] = [
    { key: 'proposal', header: 'Proposal', render: (n) => <span className="font-mono text-xs font-medium">{n.caseView.proposalNumber ?? '—'}</span> },
    { key: 'customer', header: 'Customer', render: (n) => (n.caseView.applicantPartyId ? <PartyName partyId={n.caseView.applicantPartyId} /> : '—') },
    {
      key: 'state',
      header: 'Where it is',
      render: (n) =>
        n.caseView.status === 'RETURNED' ? (
          <StatusBadge kind="underwritingCase" value="RETURNED" />
        ) : (
          <StatusBadge kind="underwritingDecision" value={n.caseView.decisionOutcome} />
        ),
    },
    { key: 'asked', header: 'What is needed', render: (n) => <Asked item={n} /> },
    {
      key: 'when',
      header: 'Decided',
      secondary: true,
      render: (n) => (n.caseView.decisionDecidedAt ? formatInstant(n.caseView.decisionDecidedAt) : '—'),
    },
  ];

  function renderBody() {
    if (error && ((needsAction && needsActionRows === null) || (!needsAction && rows === null))) {
      return <ErrorPanel error={error} onRetry={() => setTick((t) => t + 1)} />;
    }
    if (needsAction) {
      if (needsActionRows === null) return <TableSkeleton columns={needsActionColumns.length} />;
      if (needsActionRows.length === 0) {
        return (
          <EmptyState
            title="Nothing needs your action"
            description="When an underwriter returns or postpones one of your proposals, it appears here with what to do."
          />
        );
      }
      return (
        <DataTable
          columns={needsActionColumns}
          rows={needsActionRows}
          rowKey={(n) => n.caseView.caseId ?? ''}
          onRowActivate={(n) => n.caseView.caseId && navigate(`../proposals/${n.caseView.caseId}`, { relative: 'path' })}
          caption="Proposals needing your action"
        />
      );
    }
    if (rows === null) return <TableSkeleton columns={columns.length} />;
    if (rows.length === 0) {
      return (
        <EmptyState
          title={status ? `No ${status.toLowerCase().replace('_', ' ')} proposals` : 'No proposals yet'}
          description={status ? 'Nothing of yours currently has that status.' : 'A proposal appears here once you submit it.'}
        />
      );
    }
    return (
      <>
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(c) => c.caseId ?? ''}
          onRowActivate={(c) => c.caseId && navigate(`../proposals/${c.caseId}`, { relative: 'path' })}
          caption="Your proposals"
        />
        {total !== null && (
          <Pager page={{ page, pageSize: PAGE_SIZE, totalElements: total }} onPageChange={(next) => update({ page: next })} />
        )}
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Proposals"
        description="Every proposal you have submitted, most recently submitted first."
        count={<CountLine {...count} />}
      />
      <div className="px-6 pb-6">
        <div className="rounded-lg border border-border bg-surface">
          <div className="flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-2.5">
            <FilterChip label="Needs your action" active={needsAction} onClick={() => update({ view: 'needs-action' })} />
            <span className="mx-1 h-4 w-px bg-border" aria-hidden />
            <FilterChip label="All" active={!needsAction && status === undefined} onClick={() => update({ status: undefined })} />
            {UNDERWRITING_CASE_STATUSES.map((value) => (
              <FilterChip
                key={value}
                label={<StatusBadge kind="underwritingCase" value={value} />}
                bare
                active={!needsAction && status === value}
                onClick={() => update({ status: value })}
              />
            ))}
          </div>
          {renderBody()}
        </div>
      </div>
    </>
  );
}

/** The first thing the underwriter asked, or the decision's reason -- the rest is on the proposal page. */
function Asked({ item }: { item: NeedsAction }) {
  if (item.caseView.status === 'RETURNED') {
    const first = item.reasons[0];
    if (!first) return <span className="text-xs">Corrections asked</span>;
    return (
      <span className="text-xs">
        <span className="font-medium">{RETURN_CATEGORY_LABELS[first.category]}:</span> {first.change}
        {item.reasons.length > 1 && <span className="text-muted-foreground"> · and {item.reasons.length - 1} more</span>}
      </span>
    );
  }
  return (
    <span className="text-xs">
      {item.note ?? '—'}
      {!item.actionable && <span className="text-muted-foreground"> · final</span>}
    </span>
  );
}
