import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { getNeedsAction, RETURN_CATEGORY_LABELS, type NeedsAction } from '@/api/proposalAmendments';
import { DataTable, type Column } from '@/components/DataTable';
import { PageHeader } from '@/components/PageHeader';
import { PartyName } from '@/components/PartyName';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatInstant } from '@/lib/dates';

/**
 * The agent's proposals waiting on them (2026-10-20): returned for corrections, postponed, and declined in the last
 * thirty days. Returned and postponed can be corrected and resubmitted; a decline is listed so the agent can tell the
 * customer why, and is final. Scoped by the server to the agent's own clients.
 */
export function NeedsActionPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<NeedsAction[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let live = true;
    getNeedsAction().then(
      (rows) => { if (live) setItems(rows); },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, [tick]);

  const columns: Column<NeedsAction>[] = [
    { key: 'proposal', header: 'Proposal', nowrap: true, render: (n) => <span className="font-mono text-xs">{n.caseView.proposalNumber ?? '—'}</span> },
    { key: 'customer', header: 'Customer', render: (n) => (n.caseView.applicantPartyId ? <PartyName partyId={n.caseView.applicantPartyId} /> : '—') },
    {
      key: 'state',
      header: 'Where it is',
      nowrap: true,
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
      nowrap: true,
      render: (n) => (n.caseView.decisionDecidedAt ? formatInstant(n.caseView.decisionDecidedAt) : '—'),
    },
  ];

  return (
    <>
      <PageHeader
        title="Needs your action"
        description="Proposals returned to you for corrections or postponed, and recent declines with their reasons."
      />
      <div className="px-6 pb-8">
        {error ? (
          <ErrorPanel error={error} onRetry={() => { setError(null); setTick((t) => t + 1); }} />
        ) : !items ? (
          <LoadingBlock label="Loading your proposals" />
        ) : items.length === 0 ? (
          <EmptyState
            title="Nothing needs your action"
            description="When an underwriter returns or postpones one of your proposals, it appears here with what to do."
          />
        ) : (
          <DataTable
            columns={columns}
            rows={items}
            rowKey={(n) => n.caseView.caseId ?? ''}
            onRowActivate={(n) => navigate(`../proposals/${n.caseView.caseId ?? ''}`, { relative: 'path' })}
            caption="Proposals needing your action"
          />
        )}
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
