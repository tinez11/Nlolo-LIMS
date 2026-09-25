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
 *
 * The note takes a line of its OWN, at the full width of the row. It used to sit
 * inside the value cell, which the label's own width had already narrowed to
 * about 170px of a 320px record rail -- so every note of more than four words
 * wrapped two or three times into a ragged right-aligned block, and the policy
 * panel showed three of them stacked. Given the whole row, most notes are one
 * line. A second `<dd>` after one `<dt>` is valid, and it is also true: the note
 * qualifies the value, so a screen reader should reach it with the value rather
 * than skip it.
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
    <div className="flex flex-wrap items-baseline justify-between gap-x-4 border-b border-border py-2.5 last:border-0">
      <dt className="shrink-0 text-xs text-muted-foreground">{label}</dt>
      <dd className="min-w-0 text-right">
        <span className={cn('text-sm', emphasis && 'text-base font-semibold')}>{value}</span>
      </dd>
      {note && (
        <dd className="mt-0.5 w-full text-right text-xs text-subtle-foreground">{note}</dd>
      )}
    </div>
  );
}
