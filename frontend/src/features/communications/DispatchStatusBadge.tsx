import { cn } from '@/lib/cn';
import { Tip } from '@/components/ui/tooltip';

/**
 * A dispatch's state, coloured by what it means for the customer.
 *
 * Not routed through the shared StatusBadge: that component's buckets are keyed per domain in
 * lib/status.ts and this is a four-value set with its own reading. SENT is deliberately not
 * "success" green — it means accepted by the transport, never delivered or read, and neither
 * SMTP nor an aggregator's 200 proves a human received anything. Colouring it as a completed
 * success would overstate what the platform actually knows.
 */
const TONES: Record<string, string> = {
  PENDING: 'bg-status-pending-bg text-status-pending-fg',
  CLAIMED: 'bg-status-pending-bg text-status-pending-fg',
  SENT: 'bg-status-active-bg text-status-active-fg',
  FAILED: 'bg-status-danger-bg text-status-danger-fg',
};

const TITLES: Record<string, string> = {
  PENDING: 'Queued, not yet sent',
  CLAIMED: 'A sender has taken this and not yet finished',
  SENT: 'Accepted by the network — not a delivery receipt',
  FAILED: 'Not sent. See the reason.',
};

export function DispatchStatusBadge({ status }: { status: string | undefined }) {
  if (!status) return <span className="text-subtle-foreground">—</span>;
  const meaning = TITLES[status] ?? `Unrecognised status: ${status}`;
  // One on every row of a message list, so not a tab stop each: the meaning is a hover tip for the
  // eye and screen-reader text for everyone else (2026-10-09; it was a mouse-only title).
  return (
    <Tip content={meaning}>
      <span
        className={cn(
          'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap',
          TONES[status] ?? 'bg-status-neutral-bg text-status-neutral-fg',
        )}
      >
        {status}
        <span className="sr-only"> — {meaning}</span>
      </span>
    </Tip>
  );
}
