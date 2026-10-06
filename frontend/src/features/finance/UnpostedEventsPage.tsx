import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import type { UnpostedEventView } from '@/api/types';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { PageHeader } from '@/components/PageHeader';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { formatInstant } from '@/lib/dates';
import { isInitialLoad } from '@/store/createResourceSlice';
import { useLedgerControlsStore } from '@/store/ledgerControlsStore';
import { dismissSchema, type DismissValues } from './postingQueueForms';

const REASON_LABEL: Record<string, string> = {
  UNMAPPED: 'No rule posts it',
  REFUSED: 'The ledger refused it',
  ERROR: 'Failed',
};

/**
 * The events the posting rules could not post (IFRS 17 I3a) -- never dropped, kept with the facts the rules read.
 * Finance retries one once the cause is fixed (a rule added, a policy classified, a period reopened) or dismisses it
 * with a reason. An open one holds its period open: the period cannot lock until it is posted or dismissed.
 */
export function UnpostedEventsPage() {
  const unposted = useLedgerControlsStore((s) => s.unposted);
  const loadUnposted = useLedgerControlsStore((s) => s.loadUnposted);

  useEffect(() => {
    void loadUnposted();
  }, [loadUnposted]);

  function renderBody() {
    if (isInitialLoad(unposted)) return <LoadingBlock />;
    if (unposted.status === 'error' && unposted.error && unposted.data === null) {
      return <ErrorPanel error={unposted.error} onRetry={() => void loadUnposted()} />;
    }
    const rows = unposted.data ?? [];
    const open = rows.filter((e) => e.resolution == null);
    const resolved = rows.filter((e) => e.resolution != null);
    return (
      <>
        <section className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label="Waiting to post">
          <p className="text-xs font-medium">Waiting to post</p>
          {open.length === 0 ? (
            <EmptyState title="Nothing waiting" description="Every event the ledger received has posted." />
          ) : (
            <div className="divide-y divide-border rounded-md border border-border" role="list" aria-label="Unposted events">
              {open.map((e) => (
                <OpenRow key={e.id} event={e} />
              ))}
            </div>
          )}
        </section>
        {resolved.length > 0 && (
          <section className="space-y-2 rounded-lg border border-border bg-surface p-4" aria-label="Resolved">
            <p className="text-xs font-medium">Resolved</p>
            <div className="overflow-x-auto">
              <table className="w-full text-sm" aria-label="Resolved events">
                <thead>
                  <tr className="text-left text-xs text-muted-foreground">
                    <th className="py-1 pr-3 font-normal">Event</th>
                    <th className="py-1 pr-3 font-normal">Source</th>
                    <th className="w-24 py-1 pr-3 font-normal">Outcome</th>
                    <th className="py-1 pr-3 font-normal">By</th>
                    <th className="py-1 font-normal">Why</th>
                  </tr>
                </thead>
                <tbody>
                  {resolved.map((e) => (
                    <tr key={e.id} className="border-t border-border align-top">
                      <td className="py-1 pr-3 font-mono text-xs">{e.eventType}</td>
                      <td className="py-1 pr-3 font-mono text-xs">{e.sourceRef}</td>
                      <td className="py-1 pr-3">{e.resolution === 'POSTED' ? 'Posted' : 'Dismissed'}</td>
                      <td className="py-1 pr-3 text-xs">
                        {e.resolvedBy} {e.resolvedAt ? formatInstant(e.resolvedAt) : ''}
                      </td>
                      <td className="py-1 text-xs text-muted-foreground">{e.resolutionReason ?? ''}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        )}
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Unposted events"
        description="Events the posting rules could not post. Retry once the cause is fixed, or dismiss with a reason. A period cannot lock while one of its events waits here."
      />
      <div className="space-y-4 px-6 pb-6">{renderBody()}</div>
    </>
  );
}

function OpenRow({ event }: { event: UnpostedEventView }) {
  const acting = useLedgerControlsStore((s) => s.acting[`unposted.${event.id}`]);
  const retry = useLedgerControlsStore((s) => s.retryUnposted);
  const busy = acting?.status === 'loading';
  const amounts = Object.entries(event.amounts)
    .filter(([, v]) => Number(v) !== 0)
    .map(([k, v]) => `${k} ${Number(v).toLocaleString('en-US', { minimumFractionDigits: 2 })}`)
    .join(' · ');
  const name = `${event.eventType} ${event.sourceRef}`;

  return (
    <div role="listitem" aria-label={name} className="space-y-2 px-4 py-2.5">
      <div>
        <span className="font-mono text-sm font-medium">{event.eventType}</span>{' '}
        <span className="text-xs font-medium">{REASON_LABEL[event.reason] ?? event.reason}</span>
        <p className="text-xs text-muted-foreground">
          {event.policyNumber ? `Policy ${event.policyNumber} · ` : ''}
          Source {event.sourceRef} · period {event.period} · {amounts ? `${amounts} ${event.currency ?? ''} · ` : ''}
          {event.attempts} {event.attempts === 1 ? 'attempt' : 'attempts'}, first {formatInstant(event.createdAt)}
        </p>
        {event.detail && <p className="text-xs">{event.detail}</p>}
      </div>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      <div className="flex flex-wrap items-end gap-4">
        <Button type="button" size="sm" disabled={busy} onClick={() => void retry(event.id)}>
          Retry
        </Button>
        <DismissForm id={event.id} disabled={busy} />
      </div>
    </div>
  );
}

function DismissForm({ id, disabled }: { id: string; disabled: boolean }) {
  const dismiss = useLedgerControlsStore((s) => s.dismissUnposted);
  const form = useForm<DismissValues>({ resolver: zodResolver(dismissSchema), defaultValues: { reason: '' } });
  return (
    <form
      className="flex items-end gap-2"
      aria-label="Dismiss event"
      onSubmit={form.handleSubmit((v) => void dismiss(id, v.reason))}
    >
      <FormField label="Reason to dismiss" error={form.formState.errors.reason?.message}>
        <Input inputSize="sm" disabled={disabled} {...form.register('reason')} />
      </FormField>
      <Button type="submit" size="sm" variant="ghost" disabled={disabled}>
        Dismiss
      </Button>
    </form>
  );
}
