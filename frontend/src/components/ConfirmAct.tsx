import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { Button } from './ui/button';

/**
 * The second, deliberate click in front of an action that cannot be taken back.
 *
 * Zero confirmations existed anywhere in this console: approving a payout,
 * repudiating a claim and waiving an invoice were each one unguarded click,
 * acknowledged only by a panel disappearing. `PRODUCT.md` principle 2 says the
 * consequence of being wrong here is money or compliance.
 *
 * ## Inline, not a modal
 *
 * A dialog that closes on a backdrop click is the wrong shape for "move money".
 * This replaces the submit row in place, so the values being committed stay on
 * screen above it — the operator re-reads what they typed rather than a summary
 * of it — and there is no focus trap, no scroll lock, and no way to dismiss it
 * by missing. It also matches the console's own rule that mutating actions never
 * live on a surface you can dismiss by accident (PLAN.md §6).
 *
 * ## The consequence has to be true
 *
 * `src/gates` holds the doctrine that a gate asserts only what the platform can
 * prove, because a gate that overstates teaches staff to ignore it. A
 * confirmation is the same promise pointed the other way: if it says "this
 * cannot be undone", that must be a fact about the backend, and where something
 * IS recoverable the copy says how. Overstating here is not a harmless caution —
 * it is how a screen stops being read.
 *
 * That is why `reversal` is required rather than optional. Writing the
 * confirmation forces someone to have answered "and what if this is wrong?".
 */

export interface ConfirmActProps {
  /** What is about to happen, as a question. "Approve this settlement?" */
  heading: string;
  /**
   * The consequence in real words with the real values — "Pay TZS 1,240,000.00
   * to Juma Senior" — not a generic "this action is permanent".
   */
  consequence: ReactNode;
  /**
   * What happens if this turns out to be wrong. Required, and the honest answer
   * is often "nothing can": say so. Where there IS a route back, name it, so the
   * warning keeps its meaning on the actions that genuinely have none.
   */
  reversal: ReactNode;
  /** The confirming button's label. A verb, matching the heading. */
  confirmLabel: string;
  /** `danger` for money leaving or a benefit refused; `primary` otherwise. */
  tone?: 'danger' | 'primary';
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmAct({
  heading,
  consequence,
  reversal,
  confirmLabel,
  tone = 'primary',
  busy = false,
  onConfirm,
  onCancel,
}: ConfirmActProps) {
  return (
    <section
      // role="group" with a name, not role="alertdialog": nothing here steals
      // focus or blocks the page, and announcing a dialog that is neither modal
      // nor focus-trapped describes an interaction the user is not actually in.
      role="group"
      aria-label={heading}
      className={cn(
        'rounded-md border p-3',
        tone === 'danger'
          ? 'border-status-danger-fg/40 bg-status-danger-bg'
          : 'border-border-strong bg-surface-muted',
      )}
    >
      <p className="text-sm font-semibold">{heading}</p>
      <p className="mt-1 text-xs">{consequence}</p>
      <p
        className={cn(
          'mt-1.5 text-xs',
          tone === 'danger' ? 'text-status-danger-fg' : 'text-muted-foreground',
        )}
      >
        {reversal}
      </p>

      <div className="mt-3 flex items-center gap-1.5">
        {/* The confirming button carries the VERB, never "Confirm" or "Yes".
            Two identical-looking buttons a click apart is how a second click
            becomes as automatic as the first. */}
        <Button
          type="button"
          size="sm"
          variant={tone === 'danger' ? 'danger' : 'primary'}
          disabled={busy}
          onClick={onConfirm}
        >
          {busy ? 'Working…' : confirmLabel}
        </Button>
        <Button type="button" size="sm" variant="ghost" disabled={busy} onClick={onCancel}>
          Cancel
        </Button>
      </div>
    </section>
  );
}
