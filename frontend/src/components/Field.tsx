import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

/**
 * One label/value row in a detail panel.
 *
 * `note` exists for a specific reason: several fields on this platform are
 * structurally present but not yet meaningful (cash value is hardcoded to zero
 * backend-side; a party id cannot be resolved to a name because no party search
 * endpoint exists). Annotating them is more honest than rendering a figure that
 * looks authoritative.
 */
export function Field({
  label,
  value,
  note,
  emphasis = false,
}: {
  label: string;
  value: ReactNode;
  note?: string;
  emphasis?: boolean;
}) {
  return (
    <div className="flex items-baseline justify-between gap-4 border-b border-border py-2.5 last:border-0">
      <dt className="shrink-0 text-xs text-muted-foreground">{label}</dt>
      <dd className="min-w-0 text-right">
        <span className={cn('text-sm', emphasis && 'text-base font-semibold')}>{value}</span>
        {note && <p className="mt-0.5 text-[11px] text-subtle-foreground">{note}</p>}
      </dd>
    </div>
  );
}
