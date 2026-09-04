import { Plus } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { UNDERWRITING_CASE_STATUSES, type UnderwritingCaseStatus, type UnderwritingCaseView } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { PartyName } from '@/components/PartyName';
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
      render: (c) => (c.status ? <StatusBadge kind="underwritingCase" value={c.status} /> : '—'),
    },
    {
      key: 'applicantPartyId',
      header: 'Applicant',
      // The underwriter's whole job on this screen is deciding which case to
      // open next, and a column of raw ids cannot support that: on the live
      // queue, 17 of 20 rows rendered the same applicant uuid. `PartyName`
      // caches per id, so a queue of 20 rows costs one request per distinct
      // person, not one per row.
      render: (c) =>
        c.applicantPartyId ? <PartyName partyId={c.applicantPartyId} /> : '—',
    },
    {
      key: 'productId',
      header: 'Product',
      secondary: true,
      render: (c) => <span className="font-mono text-xs text-muted-foreground">{c.productId ?? '—'}</span>,
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

