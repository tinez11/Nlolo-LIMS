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
  bare = false,
}: {
  label: ReactNode;
  active: boolean;
  onClick: () => void;
  /** Render the label as code — for filters whose values are identifiers. */
  mono?: boolean;
  /**
   * The label already draws its own pill — a `StatusBadge`, on the seven screens whose
   * status filters are the statuses themselves. The chip then draws NO ground of its own
   * and marks selection with a ring, because a tinted badge sitting inside a filled chip is
   * two pills for one control, and the badge's hue is the thing being read.
   */
  bare?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        // Ink-filled when active, not a faint ring: three inactive chips beside one active
        // one have to be distinguishable at a glance from across a desk. `aria-pressed`
        // still carries the state for assistive tech, as it always did.
        'inline-flex min-h-8 items-center rounded-full transition-colors pointer-coarse:min-h-11',
        mono && 'font-mono',
        bare
          ? // The ring sits outside the badge, so the status hue underneath it is untouched.
            cn('p-0.5', active ? 'ring-2 ring-accent' : 'hover:bg-hover')
          : cn(
              'px-3 text-[13px]',
              active ? 'bg-accent text-accent-foreground' : 'bg-control hover:bg-control-hover',
            ),
      )}
    >
      {label}
    </button>
  );
}
