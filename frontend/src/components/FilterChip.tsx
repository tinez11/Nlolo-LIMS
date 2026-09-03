import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

/**
 * One toggle in a row of list filters.
 *
 * Was nine private copies. Seven were byte-identical; the two that differed were
 * checked rather than assumed, and only one difference was real:
 *
 * - `AuditLogPage` renders event types (`policy.PolicyIssued`), which are code
 *   and read as code. That is what `mono` preserves — flattening it would have
 *   been a silent visual regression dressed up as a refactor.
 * - `GroupSchemePage`'s copy was simply newer and inconsistent (a `children` API
 *   and a border instead of a ring). It was the outlier, not the improvement, so
 *   it conforms here.
 *
 * `aria-pressed` rather than a `role="tab"` or a checkbox: these are independent
 * toggles over the same list, and a pressed button is what a screen reader
 * should announce. It is on every chip, including the inactive ones — an
 * absent `aria-pressed` reads as "not a toggle at all" rather than "off".
 */
export function FilterChip({
  label,
  active,
  onClick,
  mono = false,
}: {
  label: ReactNode;
  active: boolean;
  onClick: () => void;
  /** Render the label as code — for filters whose values are identifiers. */
  mono?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        'rounded-full px-2 py-1 text-xs transition-colors',
        mono && 'font-mono',
        active ? 'bg-selected ring-1 ring-border-strong ring-inset' : 'hover:bg-hover',
      )}
    >
      {label}
    </button>
  );
}
