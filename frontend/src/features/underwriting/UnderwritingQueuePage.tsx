import { Plus } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { UNDERWRITING_CASE_STATUSES, type UnderwritingCaseStatus, type UnderwritingCaseView } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { PartyName } from '@/components/PartyName';
import { ProductName } from '@/components/ProductName';
import { CountLine, type Stat } from '@/components/StatCards';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useUnderwritingStore } from '@/store/underwritingStore';
import { UnderwritingCaseDrawer } from './UnderwritingCaseDrawer';
import { FilterChip } from '@/components/FilterChip';

const DEFAULT_PAGE_SIZE = 20;

/**
 * `GET /underwriting/cases` -- staff console only for now (see AppShell.tsx's
 * STAFF_NAV comment). No free-text search: a case has no human-facing
 * identifier, only a raw UUID caseId, unlike policies/claims.
 */
export function UnderwritingQueuePage() {
  const [params, setParams] = useSearchParams();
  const [previewing, setPreviewing] = useState<string | null>(null);

  const statusParam = params.get('status');
  const status: UnderwritingCaseStatus | undefined =
    statusParam && (UNDERWRITING_CASE_STATUSES as readonly string[]).includes(statusParam)
      ? (statusParam as UnderwritingCaseStatus)
      : undefined;
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  const list = useUnderwritingStore((s) => s.list);
  const loadList = useUnderwritingStore((s) => s.loadList);

  useEffect(() => {
    void loadList({ ...(status ? { status } : {}), page, pageSize: DEFAULT_PAGE_SIZE });
  }, [loadList, status, page]);

  function update(next: { status?: UnderwritingCaseStatus | undefined; page?: number }) {
    const merged = new URLSearchParams(params);
    if ('status' in next) {
      if (next.status) merged.set('status', next.status);
      else merged.delete('status');
      merged.delete('page');
    }
    if (next.page !== undefined) {
      if (next.page === 0) merged.delete('page');
      else merged.set('page', String(next.page));
    }
    setParams(merged);
  }

  const total = list.data?.page.totalElements ?? null;
  const busy = list.status === 'loading';

  const count: Stat = {
    label: status ? `${status.toLowerCase().replace('_', ' ')} cases` : 'cases',
    value: total,
    pending: isInitialLoad(list),
    hint:
      list.status === 'error' && total === null
        ? 'could not load'
        : status
          ? 'matching this filter'
          : 'in this tenant',
  };

  const columns: Column<UnderwritingCaseView>[] = [
    {
      key: 'proposalNumber',
      header: 'Proposal',
      // The identifying column was the raw caseId, which nobody can quote over the
      // phone and which reads as noise down a queue. Cases opened before the column
      // existed have no proposal number, so those still fall back to the id rather
      // than showing an em dash where the row's identity should be.
      render: (c) =>
        c.proposalNumber ? (
          <span className="font-mono text-xs font-medium">{c.proposalNumber}</span>
        ) : (
          <span className="font-mono text-xs text-muted-foreground" title="Opened before proposal numbers existed">
            {c.caseId ?? '—'}
          </span>
        ),
    },
    {
      key: 'status',
      header: 'Status',
      // A DECIDED case that issued nothing is flagged HERE, beside the status it contradicts,
      // rather than only on the case itself. The whole failure mode is that nobody had a reason
      // to open the case again -- it was decided, and decided looks finished. Somebody scanning
      // this queue for work is exactly who needs to see it.
      render: (c) => (
        <span className="inline-flex items-center gap-1.5">
          {c.status ? <StatusBadge kind="underwritingCase" value={c.status} /> : '—'}
          {c.issuanceFailureReason && (
            <span
              className="rounded bg-status-danger-bg px-1.5 py-0.5 text-[10px] font-medium text-status-danger-fg"
              title={c.issuanceFailureReason}
            >
              No policy
            </span>
          )}
        </span>
      ),
    },
    {
      key: 'applicantPartyId',
      header: 'Applicant',
      // The underwriter's whole job on this screen is deciding which case to
      // open next, and a column of raw ids cannot support that: on the live
      // queue, 17 of 20 rows rendered the same applicant uuid. `PartyName`
      // caches per id, so a queue of 20 rows costs one request per distinct
      // person, not one per row.
      // A GROUP case is marked here rather than in a column of its own. A scheme's
      // applicant is a company, and a company name in this column reads exactly like a
      // person's -- so without the tag an underwriter picking work off the queue cannot
      // tell a 500-life scheme from one proposal. There is no sum-assured column to give
      // it away either, and a group case would have nothing to put in one: the figure is
      // derived from the schedule when policy issues the scheme.
      render: (c) => (
        <span className="inline-flex items-center gap-1.5">
          {c.applicantPartyId ? <PartyName partyId={c.applicantPartyId} /> : '—'}
          {c.groupScheme && (
            <span className="rounded bg-muted px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground">
              Group scheme
            </span>
          )}
        </span>
      ),
    },
    {
      key: 'productId',
      header: 'Product',
      secondary: true,
      // The same argument as the Applicant column above, for the same reason: a
      // term life case and a unit-linked case are not assessed alike, and a uuid
      // does not say which this is. `ProductName` caches per id, and a queue is
      // typically twenty rows over three products, so it costs three requests.
      // No code beside the name -- the name alone is what this column is scanned
      // for, and the column is `secondary`.
      render: (c) =>
        c.productId ? (
          <ProductName productId={c.productId} withCode={false} className="text-muted-foreground" />
        ) : (
          '—'
        ),
    },
    {
      key: 'decisionOutcome',
      header: 'Decision',
      secondary: true,
      render: (c) => <span className="text-muted-foreground">{c.decisionOutcome ?? '—'}</span>,
    },
  ];

  function renderBody() {
    if (isInitialLoad(list)) return <TableSkeleton columns={columns.length} />;

    if (list.status === 'error' && list.error && list.data === null) {
      return (
        <ErrorPanel
          error={list.error}
          onRetry={() => void loadList({ ...(status ? { status } : {}), page, pageSize: DEFAULT_PAGE_SIZE })}
        />
      );
    }

    const rows = list.data?.items ?? [];
    if (rows.length === 0 && list.status === 'success') {
      return (
        <EmptyState
          title={status ? `No ${status.toLowerCase().replace('_', ' ')} cases` : 'No cases yet'}
          description={
            status
              ? 'Nothing in this tenant currently has that status.'
              : 'Cases appear here once opened.'
          }
          {...(status
            ? {
                action: (
                  <Button size="sm" onClick={() => update({ status: undefined })}>
                    Clear filter
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
            {list.error.traceId && <span className="ml-1 font-mono">({list.error.traceId})</span>}
          </p>
        )}
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(c) => c.caseId ?? JSON.stringify(c)}
          onRowActivate={(c) => {
            if (c.caseId) setPreviewing(c.caseId);
          }}
          isRowSelected={(c) => c.caseId === previewing}
          caption="Underwriting cases"
        />
        {list.data && (
          <Pager page={list.data.page} busy={busy} onPageChange={(next) => update({ page: next })} />
        )}
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Underwriting"
        description="Every underwriting case in your tenant. Select one to preview it."
        actions={
          <Button asChild size="sm" variant="primary">
            <Link to="new">
              <Plus />
              New case
            </Link>
          </Button>
        }
        count={<CountLine {...count} />}
      />

      <div className="px-6 pb-6">
        <div className="rounded-lg border border-border bg-surface">
          <div className="flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-2.5">
            <FilterChip label="All" active={status === undefined} onClick={() => update({ status: undefined })} />
            {UNDERWRITING_CASE_STATUSES.map((value) => (
              <FilterChip
                key={value}
                label={<StatusBadge kind="underwritingCase" value={value} />}
                active={status === value}
                onClick={() => update({ status: value })}
              />
            ))}
          </div>

          {renderBody()}
        </div>
      </div>

      <UnderwritingCaseDrawer caseId={previewing} onClose={() => setPreviewing(null)} />
    </>
  );
}

