import { useEffect, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { AuditEntryView } from '@/api/types';
import { DataTable, Pager, type Column } from '@/components/DataTable';
import { PageHeader } from '@/components/PageHeader';
import { StatCards, type Stat } from '@/components/StatCards';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import { formatInstant } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useAuditStore } from '@/store/auditStore';

const DEFAULT_PAGE_SIZE = 20;

/**
 * The modules that publish domain events, for the prefix filter. Taken from the
 * `module.EventName` convention rather than from a list the API exposes — there is
 * no endpoint that enumerates event types.
 */
const MODULES = [
  'policy',
  'claims',
  'billing',
  'payment',
  'policyloan',
  'distribution',
  'reinsurance',
  'underwriting',
] as const;

/**
 * `GET /audit-log` — the audit module's first read screen.
 *
 * THE PAGE SAYS WHAT IT IS, deliberately. `audit.audit_log` records event type,
 * when it happened, and the event's own JSON payload. It has NO actor column and
 * no before/after values and no reason, so this cannot answer "who did this and
 * why" — five of the six columns a compliance register wants exist nowhere on
 * this platform. Presenting it as a compliance trail would be presenting evidence
 * the data cannot support, which is why the description below is blunt about it.
 *
 * The payload is rendered raw. Its shape varies across forty-odd event types and
 * normalising it in the UI would be inventing a schema the platform never agreed.
 */
export function AuditLogPage() {
  const [params, setParams] = useSearchParams();

  const moduleParam = params.get('module');
  const module = moduleParam && (MODULES as readonly string[]).includes(moduleParam) ? moduleParam : undefined;
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0);

  const list = useAuditStore((s) => s.list);
  const loadList = useAuditStore((s) => s.loadList);

  const query = {
    ...(module ? { eventTypePrefix: `${module}.` } : {}),
    page,
    pageSize: DEFAULT_PAGE_SIZE,
  };

  useEffect(() => {
    void loadList(query);
    // `query` is rebuilt each render; the primitives it derives from are the deps.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loadList, module, page]);

  function update(next: { module?: string | undefined; page?: number }) {
    const merged = new URLSearchParams(params);
    if ('module' in next) {
      if (next.module) merged.set('module', next.module);
      else merged.delete('module');
      merged.delete('page');
    }
    if (next.page !== undefined) {
      if (next.page === 0) merged.delete('page');
      else merged.set('page', String(next.page));
    }
    setParams(merged);
  }

  const total = list.data?.page.totalElements ?? null;

  const stats: Stat[] = [
    {
      label: module ? `${module} events` : 'Recorded events',
      value: total,
      pending: isInitialLoad(list),
      hint:
        list.status === 'error' && total === null
          ? 'could not load'
          : module
            ? 'from this module'
            : 'in this tenant',
    },
  ];

  const columns: Column<AuditEntryView>[] = [
    {
      key: 'occurredAt',
      header: 'Occurred',
      render: (e) => <span className="tabular-nums">{formatInstant(e.occurredAt)}</span>,
    },
    {
      key: 'eventType',
      header: 'Event',
      render: (e) => <span className="font-mono text-xs font-medium">{e.eventType ?? '—'}</span>,
    },
    {
      key: 'payloadJson',
      header: 'Payload',
      secondary: true,
      // Raw and truncated. The shape varies per event type; normalising it here
      // would invent a schema the platform never agreed on.
      render: (e) => (
        <span className="block max-w-xl truncate font-mono text-xs text-muted-foreground" title={e.payloadJson}>
          {e.payloadJson ?? '—'}
        </span>
      ),
    },
  ];

  function renderBody() {
    if (isInitialLoad(list)) return <TableSkeleton columns={columns.length} />;

    if (list.status === 'error' && list.error && list.data === null) {
      return <ErrorPanel error={list.error} onRetry={() => void loadList(query)} />;
    }

    const rows = list.data?.items ?? [];
    if (rows.length === 0 && list.status === 'success') {
      return (
        <EmptyState
          title={module ? `No ${module} events` : 'No events recorded'}
          description={
            module
              ? 'Nothing from that module has been recorded in this tenant.'
              : 'Events appear here as the platform records them.'
          }
          {...(module
            ? {
                action: (
                  <Button size="sm" onClick={() => update({ module: undefined })}>
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
          rowKey={(e) => e.eventId ?? JSON.stringify(e)}
          caption="Recorded domain events"
        />
        {list.data && (
          <Pager
            page={list.data.page}
            busy={list.status === 'loading'}
            onPageChange={(next) => update({ page: next })}
          />
        )}
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Event journal"
        description="Every domain event this tenant has recorded, newest first."
      />

      {/* Not a disclaimer for its own sake: without it, a reader reasonably assumes
          this is the compliance register, and it cannot be one. */}
      <div className="px-6 pb-1">
        <p className="max-w-3xl rounded-md border border-border bg-surface-muted px-3 py-2 text-xs text-muted-foreground">
          This is a journal of what the platform <strong className="font-medium text-foreground">did</strong>, not of
          who did it. Each row is a domain event with its own payload — there is no actor, no before-and-after value
          and no stated reason recorded, so this cannot serve as a compliance audit trail.
        </p>
      </div>

      <StatCards stats={stats} />

      <div className="px-6 pb-6">
        <div className="rounded-lg border border-border bg-surface">
          <div className="flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-2.5">
            <FilterChip label="All modules" active={module === undefined} onClick={() => update({ module: undefined })} />
            {MODULES.map((value) => (
              <FilterChip
                key={value}
                label={value}
                active={module === value}
                onClick={() => update({ module: value })}
              />
            ))}
          </div>

          {renderBody()}
        </div>
      </div>
    </>
  );
}

function FilterChip({
  label,
  active,
  onClick,
}: {
  label: ReactNode;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        'rounded-full px-2 py-1 font-mono text-xs transition-colors',
        active ? 'bg-selected ring-1 ring-border-strong ring-inset' : 'hover:bg-hover',
      )}
    >
      {label}
    </button>
  );
}
