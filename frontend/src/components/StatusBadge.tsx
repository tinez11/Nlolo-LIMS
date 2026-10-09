import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/cn';
import { humanizeStatus, resolveStatus, type StatusKind } from '@/lib/status';
import { Tip } from './ui/tooltip';

const badge = cva(
  'inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap',
  {
    variants: {
      bucket: {
        neutral: 'bg-status-neutral-bg text-status-neutral-fg',
        pending: 'bg-status-pending-bg text-status-pending-fg',
        active: 'bg-status-active-bg text-status-active-fg',
        success: 'bg-status-success-bg text-status-success-fg',
        warning: 'bg-status-warning-bg text-status-warning-fg',
        danger: 'bg-status-danger-bg text-status-danger-fg',
      },
    },
    defaultVariants: { bucket: 'neutral' },
  },
);

export type StatusBadgeProps = VariantProps<typeof badge> & {
  kind: StatusKind;
  /** Raw backend literal, e.g. `SETTLEMENT_REQUESTED`. */
  value: string | null | undefined;
  /**
   * The words to show, when the backend has already worded the status -- the payment schedule
   * sends "Partly paid" and "Upcoming", which `humanizeStatus` cannot rebuild from the literal.
   * The colour still comes from `value`.
   */
  label?: string;
  className?: string;
};

/**
 * The single badge for every status on the platform.
 *
 * Colour carries meaning here: staff scanning a table read hue before text, so the
 * same bucket must look identical whichever domain it came from. The mapping lives
 * in lib/status.ts, keyed by domain because the same literal genuinely differs
 * between them.
 */
export function StatusBadge({ kind, value, label, className }: StatusBadgeProps) {
  // An absent status is not a status. Rendering a grey "Unknown" pill would imply
  // the backend said something when it said nothing.
  if (!value) return <span className="text-subtle-foreground">—</span>;

  const { bucket, known } = resolveStatus(kind, value);

  const pill = (
    <span
      className={cn(badge({ bucket }), !known && 'ring-1 ring-border-strong ring-inset', className)}
    >
      {label ?? humanizeStatus(value)}
      {!known && <span aria-hidden>?</span>}
    </span>
  );
  // A literal this build has never heard of is flagged rather than dressed up as a deliberate
  // neutral, so a newly-added backend enum is visible on screen -- and the flag explains itself
  // to a keyboard too (2026-10-09; it was a mouse-only title). Rare by design, so the tab stop
  // costs little.
  return known ? pill : (
    <Tip content={`Unrecognised ${kind} status: ${value}`} focusable>
      {pill}
    </Tip>
  );
}
