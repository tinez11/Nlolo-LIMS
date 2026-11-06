import type { ReactNode } from 'react';

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
  stacked = false,
  className,
}: {
  label: string;
  value: ReactNode;
  note?: string;
  emphasis?: boolean;
  /**
   * The label above the value rather than across from it, for a headline figure in a figures
   * band -- the value then takes the Display tier.
   */
  stacked?: boolean;
  /**
   * An escape hatch for spacing only -- a group's first field takes `mt-3` so a long flat list
   * reads as clusters (who / contact / record) instead of one undifferentiated run. Never a rule:
   * the gap between rows is already the system's way of grouping without lined paper.
   */
  className?: string;
}) {
  if (stacked) {
    return (
      <div className={`flex min-w-0 flex-col gap-1${className ? ` ${className}` : ''}`}>
        <dt className="text-sm text-muted-foreground">{label}</dt>
        {/* The body face, as every figure in the console: Sora is for heading words, never for numbers. */}
        <dd className="min-w-0 text-display">{value}</dd>
        {note && <dd className="text-xs text-subtle-foreground">{note}</dd>}
      </div>
    );
  }

  // An absent value is an em dash in Subtle Ink (DESIGN.md), never navy: in navy it carried the same
  // weight as real data, so a card of dashes read as a card of facts.
  const absent = value === '—' || value === null || value === undefined || value === '';

  return (
    // Two columns, the value set beside its label (2026-11-06). The row used to push the label to the
    // left edge and the value to the right edge of the card -- 486px apart on a 1440px screen -- so the
    // eye had to cross a gap to pair them and there was no column of values to read down. No rule
    // between rows (2026-10-09): ruled under every field, a record rail read as lined paper.
    <div className={`grid grid-cols-[8rem_minmax(0,1fr)] items-baseline gap-x-4 py-1.5${className ? ` ${className}` : ''}`}>
      <dt className="text-xs text-label">{label}</dt>
      <dd className="min-w-0 break-words">
        {absent
          ? <span className="text-sm text-subtle-foreground">—</span>
          : <span className={emphasis ? 'text-title' : 'text-sm'}>{value}</span>}
      </dd>
      {/* Under the value it qualifies, in the value's column. */}
      {note && <dd className="col-start-2 mt-0.5 text-xs text-subtle-foreground">{note}</dd>}
    </div>
  );
}
