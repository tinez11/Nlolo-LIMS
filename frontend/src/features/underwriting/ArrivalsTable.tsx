import { useEffect, useState } from 'react';
import { getArrivals, type Arrival } from '@/api/proposalAmendments';
import type { Page } from '@/api/types';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { PartyName } from '@/components/PartyName';
import { ProductName } from '@/components/ProductName';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Badge } from '@/components/ui/badge';
import { toApiError, type ApiError } from '@/lib/apiError';
import { formatInstant } from '@/lib/dates';

const PAGE_SIZE = 20;

/**
 * The underwriter's arrivals (2026-10-20): open and in-review cases, most recently arrived first. A proposal an agent
 * has corrected and sent back counts as arriving again, so it is at the top -- not wherever it was first opened -- and
 * is marked with its round. "From" is the agent who introduced the client, or Direct.
 */
export function ArrivalsTable({
  resubmittedOnly,
  page,
  onPage,
  previewing,
  onPreview,
  onTotal,
}: {
  resubmittedOnly: boolean;
  page: number;
  onPage: (page: number) => void;
  previewing: string | null;
  onPreview: (caseId: string) => void;
  /** The total, for the page's count line. */
  onTotal: (total: number | null) => void;
}) {
  const [loaded, setLoaded] = useState<{ key: string; data?: Page<Arrival>; error?: ApiError } | null>(null);
  const [tick, setTick] = useState(0);
  const key = `${resubmittedOnly}|${page}|${tick}`;

  useEffect(() => {
    let live = true;
    getArrivals({ resubmittedOnly, page, pageSize: PAGE_SIZE }).then(
      (data) => {
        if (!live) return;
        setLoaded({ key, data });
        onTotal(data.page.totalElements);
      },
      (e: unknown) => {
        if (!live) return;
        setLoaded({ key, error: toApiError(e) });
        onTotal(null);
      },
    );
    return () => { live = false; };
  }, [key, resubmittedOnly, page, onTotal]);

  const columns: Column<Arrival>[] = [
    {
      key: 'proposal',
      header: 'Proposal',
      nowrap: true,
      render: (a) => (
        <span className="inline-flex items-center gap-1.5">
          <span className="font-mono text-xs font-medium">{a.caseView.proposalNumber ?? a.caseView.caseId ?? '—'}</span>
          {a.resubmitted && <Badge>Resubmitted · round {a.round}</Badge>}
        </span>
      ),
    },
    { key: 'received', header: 'Received', nowrap: true, render: (a) => formatInstant(a.lastSubmittedAt) },
    {
      key: 'from',
      header: 'From',
      render: (a) => (a.introducedBy ? `Agent · ${a.introducedBy}` : <span className="text-muted-foreground">Direct</span>),
    },
    {
      key: 'applicant',
      header: 'Applicant',
      render: (a) => (a.caseView.applicantPartyId ? <PartyName partyId={a.caseView.applicantPartyId} /> : '—'),
    },
    {
      key: 'product',
      header: 'Product',
      secondary: true,
      render: (a) =>
        a.caseView.productId ? (
          <ProductName productId={a.caseView.productId} withCode={false} className="text-muted-foreground" />
        ) : '—',
    },
    {
      key: 'status',
      header: 'Status',
      secondary: true,
      render: (a) => (a.caseView.status ? <StatusBadge kind="underwritingCase" value={a.caseView.status} /> : '—'),
    },
  ];

  const current = loaded?.key === key ? loaded : null;
  if (!current) return <TableSkeleton columns={columns.length} />;
  if (current.error) return <ErrorPanel error={current.error} onRetry={() => setTick((t) => t + 1)} />;
  const rows = current.data?.items ?? [];
  if (rows.length === 0) {
    return (
      <EmptyState
        title={resubmittedOnly ? 'Nothing back from agents' : 'Nothing waiting'}
        description={resubmittedOnly
          ? 'When an agent corrects a returned or postponed proposal and sends it back, it appears here.'
          : 'Open and in-review cases appear here, the most recently arrived first.'}
      />
    );
  }
  return (
    <>
      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(a) => a.caseView.caseId ?? ''}
        onRowActivate={(a) => { if (a.caseView.caseId) onPreview(a.caseView.caseId); }}
        isRowSelected={(a) => a.caseView.caseId === previewing}
        caption={resubmittedOnly ? 'Proposals back from agents' : 'Cases waiting, most recently arrived first'}
      />
      {current.data && <Pager page={current.data.page} onPageChange={onPage} />}
    </>
  );
}
