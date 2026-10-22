import { useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { NOTIFICATION_STATUSES, type NotificationDispatchView } from '@/api/types';
import { DataTable, type Column } from '@/components/DataTable';
import { FilterChip } from '@/components/FilterChip';
import { PageHeader } from '@/components/PageHeader';
import { PartyName } from '@/components/PartyName';
import { CountLine, type Stat } from '@/components/StatCards';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatInstant } from '@/lib/dates';
import { humanizeStatus } from '@/lib/status';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useCommunicationsStore } from '@/store/communicationsStore';
import { DispatchStatusBadge } from './DispatchStatusBadge';
import { humanizeTemplateKey } from './templateKeys';

/** How many of the newest messages the register asks for. */
const MESSAGE_LIMIT = 200;

/**
 * The outbox: what this platform has actually said to customers.
 *
 * <p>It exists to answer one question the desk gets constantly — "was this customer told, and did
 * it arrive?" — and to make the answer "no, and here is why" possible. Nothing retries a failed
 * notification on this platform, deliberately, so a FAILED row is the entire trace that somebody
 * was owed a message and did not get one. That is why the reason is a column here and not a log
 * line: an unreachable aggregator and a customer with no phone number on file are different
 * problems with different fixes, and a bare status cannot tell them apart.
 */
export function MessagesPage() {
  const [params, setParams] = useSearchParams();

  const statusParam = params.get('status');
  const status =
    statusParam && (NOTIFICATION_STATUSES as readonly string[]).includes(statusParam) ? statusParam : undefined;

  const dispatches = useCommunicationsStore((s) => s.dispatches);
  const loadDispatches = useCommunicationsStore((s) => s.loadDispatches);

  // The newest MESSAGE_LIMIT only. Unbounded, this read the whole outbox -- 4,949 rows, 1.67 MB on the dev
  // tenant -- and drew every one, each resolving its customer's name: about 50 seconds before the page settled.
  const query = status ? { status, limit: MESSAGE_LIMIT } : { limit: MESSAGE_LIMIT };
  useEffect(() => {
    void loadDispatches(status ? { status, limit: MESSAGE_LIMIT } : { limit: MESSAGE_LIMIT });
  }, [loadDispatches, status]);

  function update(next: string | undefined) {
    const merged = new URLSearchParams(params);
    if (next) merged.set('status', next);
    else merged.delete('status');
    setParams(merged);
  }

  const rows = dispatches.data ?? [];

  // A full page means there are older ones too, so the count says "latest" rather than claiming a total
  // it no longer knows.
  const capped = rows.length >= MESSAGE_LIMIT;
  const kind = status ? `${status.toLowerCase()} messages` : 'messages';
  const count: Stat = {
    label: capped ? `latest ${kind}` : status ? kind : 'messages sent',
    value: isInitialLoad(dispatches) ? null : rows.length,
    pending: isInitialLoad(dispatches),
    hint:
      dispatches.status === 'error' && dispatches.data === null
        ? 'could not load'
        : capped
          ? 'newest first; older ones are on each policy'
          : 'in this tenant',
  };

  const columns: Column<NotificationDispatchView>[] = [
    {
      key: 'createdAt',
      header: 'When',
      render: (d) => <span className="tabular-nums">{formatInstant(d.createdAt)}</span>,
    },
    {
      key: 'partyId',
      header: 'Customer',
      render: (d) => (d.partyId ? <PartyName partyId={d.partyId} /> : '—'),
    },
    {
      key: 'templateKey',
      header: 'Message',
      render: (d) => <span className="font-medium">{humanizeTemplateKey(d.templateKey)}</span>,
    },
    { key: 'channel', header: 'Channel', render: (d) => d.channel ?? '—' },
    {
      key: 'policyNumber',
      header: 'About',
      secondary: true,
      render: (d) => <span className="font-mono text-xs">{d.policyNumber ?? '—'}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      render: (d) => <DispatchStatusBadge status={d.status} />,
    },
    {
      key: 'failureReason',
      header: 'Why not',
      secondary: true,
      // Only meaningful on a FAILED row, and the reason this column exists at all. A dash on a
      // SENT row is correct rather than missing data.
      render: (d) => (
        <span className="block max-w-md truncate text-xs text-muted-foreground" title={d.failureReason ?? ''}>
          {d.failureReason ?? '—'}
        </span>
      ),
    },
  ];

  function renderBody() {
    if (isInitialLoad(dispatches)) return <TableSkeleton columns={columns.length} />;

    if (dispatches.status === 'error' && dispatches.error && dispatches.data === null) {
      return <ErrorPanel error={dispatches.error} onRetry={() => void loadDispatches(query)} />;
    }

    if (rows.length === 0 && dispatches.status === 'success') {
      return (
        <EmptyState
          title={status ? `No ${status.toLowerCase()} messages` : 'Nothing sent yet'}
          description={
            status
              ? 'No message in this tenant is in that state.'
              : 'Messages appear here as the platform sends them — an offer made, cover starting, an offer closing or closed.'
          }
          {...(status
            ? { action: <Button size="sm" onClick={() => update(undefined)}>Clear filter</Button> }
            : {})}
        />
      );
    }

    return (
      <>
        {dispatches.status === 'error' && dispatches.error && (
          <p className="border-b border-border bg-status-warning-bg px-4 py-2 text-xs text-status-warning-fg">
            Showing older data — could not refresh.
            {dispatches.error.traceId && <span className="ml-1 font-mono">({dispatches.error.traceId})</span>}
          </p>
        )}
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(d) => d.dispatchId ?? JSON.stringify(d)}
          caption="Messages sent to customers"
        />
      </>
    );
  }

  return (
    <>
      {/* The count goes in the header and the list in a bordered panel, like every other register. Out here
          the count sat flush against the menu at a larger size, and the table ran edge to edge. */}
      <PageHeader
        title="Messages sent"
        description="Every message this platform has sent a customer, and every one it could not."
        count={<CountLine {...count} />}
      />
      <div className="px-6 pb-6">
        <div className="rounded-xl border border-border bg-surface shadow-card">
          <div className="flex flex-wrap items-center gap-1.5 gap-y-2 border-b border-border px-3 py-2.5">
            <FilterChip label="All" active={!status} onClick={() => update(undefined)} />
            {NOTIFICATION_STATUSES.map((s) => (
              <FilterChip key={s} label={humanizeStatus(s)} active={status === s} onClick={() => update(s)} />
            ))}
          </div>
          {renderBody()}
        </div>
      </div>
    </>
  );
}
