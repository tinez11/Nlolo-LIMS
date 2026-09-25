import type { ReactNode } from 'react';
import { Check } from 'lucide-react';

/**
 * What happened, left on screen where the form was.
 *
 * Before this, a completed mutation was acknowledged by the panel vanishing.
 * That is indistinguishable from a click that did nothing, and it leaves the
 * operator with nothing to quote when somebody later asks what was approved and
 * when.
 *
 * ## Persistent, not a toast
 *
 * A toast is gone in four seconds and cannot be re-read, copied or screenshotted
 * for a file note. On a screen where the consequence of being wrong is money or
 * compliance, the confirmation of what was done should outlive the moment — it
 * stays until the operator navigates away or starts the next action.
 *
 * ## Only facts the server returned
 *
 * The critique asked this to carry a "journal reference". **No endpoint on this
 * platform returns one**, and `POST .../payout` returns 202 with no body at all,
 * so there is nothing to render. Printing the client's own idempotency key in a
 * slot labelled "reference" would look exactly like a server-issued receipt
 * number and be nothing of the kind — the same defect as a stat card with no
 * data behind it.
 *
 * So `lines` is supplied by the caller from the response it actually got, and
 * anything the CLIENT knows rather than the server goes in `note`, in words that
 * say which it is. If a receipt looks thin, that is the API surface being
 * reported honestly rather than dressed up.
 */

export interface ReceiptLine {
  label: string;
  /** A rendered value — money already formatted, a status already humanised. */
  value: ReactNode;
}

export function Receipt({
  heading,
  lines,
  note,
  onward,
}: {
  heading: string;
  lines: ReceiptLine[];
  /** Anything true but not server-issued, said plainly. */
  note?: string;
  /** Where to go next — the record just changed, usually. */
  onward?: ReactNode;
}) {
  return (
    <section
      // A completed action is announced once. `status`, not `alert`: this is a
      // result, not a problem, and alert's assertive interruption is for the
      // latter.
      role="status"
      className="rounded-md border border-status-success-fg/40 bg-status-success-bg p-3"
    >
      <p className="flex items-center gap-1.5 text-sm font-semibold text-status-success-fg">
        <Check className="size-4 shrink-0" aria-hidden />
        {heading}
      </p>

      {lines.length > 0 && (
        <dl className="mt-2 space-y-1">
          {lines.map((line) => (
            <div key={line.label} className="flex items-baseline justify-between gap-4 text-xs">
              <dt className="shrink-0 text-status-success-fg">{line.label}</dt>
              <dd className="min-w-0 text-right font-medium text-status-success-fg">
                {line.value}
              </dd>
            </div>
          ))}
        </dl>
      )}

      {note && <p className="mt-2 text-[11px] text-status-success-fg">{note}</p>}
      {onward && <div className="mt-2">{onward}</div>}
    </section>
  );
}
