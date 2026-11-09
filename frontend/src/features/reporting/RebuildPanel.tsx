import { useState } from 'react';
import { getRebuildStatus, startRebuild, type RebuildStatus } from '@/api/reporting';
import { Button } from '@/components/ui/button';

/**
 * ADMIN only, matching POST /reports/rebuild. Needed once after the module is first deployed (the read model
 * starts empty) and whenever the failure metric shows events were missed. Status is fetched on request --
 * no polling timer for a job an administrator runs a few times a year.
 */
export function RebuildPanel() {
  const [status, setStatus] = useState<RebuildStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function act(action: () => Promise<RebuildStatus>) {
    setError(null);
    try {
      setStatus(await action());
    } catch {
      setError('The request failed. Try again in a moment.');
    }
  }

  return (
    <section className="space-y-2 rounded-xl border border-border bg-surface shadow-card p-3">
      <h2 className="text-sm font-semibold">Reporting data</h2>
      <p className="text-sm text-muted-foreground">
        Rebuild clears this organisation's reporting data and replays every recorded event. Reports read partial
        figures until it finishes.
      </p>
      <div className="flex gap-2">
        <Button size="sm" variant="secondary" onClick={() => void act(startRebuild)}>
          Rebuild reporting data
        </Button>
        <Button size="sm" variant="secondary" onClick={() => void act(getRebuildStatus)}>
          Check status
        </Button>
      </div>
      {status && (
        <p className="text-sm" role="status">
          {status.state} — {status.eventsReplayed} events replayed, {status.eventsFailed} failed
          {status.finishedAt ? `, finished ${status.finishedAt}` : ''}
        </p>
      )}
      {error && (
        <p role="alert" className="rounded-md bg-status-danger-bg px-3 py-2 text-sm text-status-danger-fg">
          {error}
        </p>
      )}
    </section>
  );
}
